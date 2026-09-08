//! A loopback relay: `<audio>` plays from here, and Rust does the talking.
//!
//! Why this exists: a media element cannot choose its own request headers, and
//! a number of broadcasters decide whether to answer on the strength of them.
//! SomaFM returns `403 text/html` to the webview's User-Agent and
//! `200 audio/mpeg` to an ordinary browser's - measured by sending both down
//! the same proxy and changing nothing else, which is also how the guess that
//! this was a codec problem was ruled out. `MEDIA_ERR_SRC_NOT_SUPPORTED` is
//! what the element reports for a 403 error page, for a refused connection and
//! for a codec it cannot decode alike, so "cannot decode" was never what it
//! actually meant.
//!
//! Relaying also puts playlist resolution, redirects and Shoutcast v1 on the
//! side that already knows how to do all three.
//!
//! Loopback only, registered tokens only, GET only. The webview is the sole
//! intended client and none of this should be reachable from off the machine.

use std::collections::{HashMap, VecDeque};
use std::sync::{Arc, Mutex};

use futures_util::StreamExt;
use rand::Rng;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::{TcpListener, TcpStream};

use crate::stream::{open_for_relay, RelayBody};

/// A request head is a few hundred bytes and the webview sends nothing like
/// this much. Anything still writing one after this is not a browser.
const MAX_HEAD_BYTES: usize = 8 * 1024;
/// Stations addressable at once. Browsing can register a great many in one
/// session, and the table must not grow for as long as the app is open.
const MAX_ROUTES: usize = 128;

pub struct Relay {
    pub port: u16,
    routes: Mutex<Routes>,
}

#[derive(Default)]
struct Routes {
    by_token: HashMap<String, String>,
    by_url: HashMap<String, String>,
    order: VecDeque<String>,
}

fn token() -> String {
    let bytes: [u8; 16] = rand::thread_rng().gen();
    bytes.iter().map(|b| format!("{b:02x}")).collect()
}

impl Relay {
    /// Bind the loopback listener and start accepting. Port 0: the OS picks
    /// one, so nothing is assumed about what happens to be free.
    pub async fn start() -> Result<Arc<Self>, String> {
        let listener = TcpListener::bind(("127.0.0.1", 0))
            .await
            .map_err(|e| format!("could not open the local relay: {e}"))?;
        let port = listener
            .local_addr()
            .map_err(|e| format!("the local relay has no address: {e}"))?
            .port();
        let relay = Arc::new(Relay {
            port,
            routes: Mutex::new(Routes::default()),
        });
        let accepting = relay.clone();
        tokio::spawn(async move {
            loop {
                let Ok((socket, peer)) = listener.accept().await else {
                    break;
                };
                // Bound to 127.0.0.1 already; this is the belt to those braces.
                if !peer.ip().is_loopback() {
                    continue;
                }
                let relay = accepting.clone();
                tokio::spawn(async move {
                    let _ = relay.serve(socket).await;
                });
            }
        });
        Ok(relay)
    }

    /// Give a station URL a local address. The same URL keeps the same token,
    /// so replaying a station does not fill the table.
    pub fn route(&self, url: &str) -> String {
        let url = url.trim().to_string();
        let token = {
            let mut routes = self.routes.lock().unwrap();
            match routes.by_url.get(&url) {
                Some(existing) => existing.clone(),
                None => {
                    let fresh = token();
                    routes.by_token.insert(fresh.clone(), url.clone());
                    routes.by_url.insert(url, fresh.clone());
                    routes.order.push_back(fresh.clone());
                    while routes.order.len() > MAX_ROUTES {
                        let Some(old) = routes.order.pop_front() else {
                            break;
                        };
                        if let Some(stale) = routes.by_token.remove(&old) {
                            routes.by_url.remove(&stale);
                        }
                    }
                    fresh
                }
            }
        };
        format!("http://127.0.0.1:{}/s/{token}", self.port)
    }

    fn upstream_for(&self, token: &str) -> Option<String> {
        self.routes.lock().unwrap().by_token.get(token).cloned()
    }

    async fn serve(&self, mut socket: TcpStream) -> std::io::Result<()> {
        let Some(token) = read_request(&mut socket).await? else {
            return reply(&mut socket, "400 Bad Request").await;
        };
        let Some(url) = self.upstream_for(&token) else {
            return reply(&mut socket, "404 Not Found").await;
        };
        let source = match open_for_relay(&url).await {
            Ok(source) => source,
            // The player only ever sees a status code, so the reason goes to
            // the log. `probe_stream` is what puts words on the screen.
            Err(e) => {
                eprintln!("aerowave relay: {url}: {e}");
                return reply(&mut socket, "502 Bad Gateway").await;
            }
        };
        let head = format!(
            "HTTP/1.1 200 OK\r\nContent-Type: {}\r\nCache-Control: no-store\r\n\
             Connection: close\r\n\r\n",
            source.content_type
        );
        socket.write_all(head.as_bytes()).await?;
        match source.body {
            RelayBody::Http(resp) => {
                // reqwest has already undone the chunked framing. Forwarding
                // the upstream socket raw instead would splice the chunk
                // sizes into the audio, which is silence with extra steps.
                let mut body = resp.bytes_stream();
                while let Some(chunk) = body.next().await {
                    let Ok(chunk) = chunk else { break };
                    socket.write_all(&chunk).await?;
                }
            }
            RelayBody::Icy {
                socket: mut upstream,
                primed,
            } => {
                if !primed.is_empty() {
                    socket.write_all(&primed).await?;
                }
                let mut chunk = [0u8; 16384];
                loop {
                    let Ok(read) = upstream.read(&mut chunk).await else {
                        break;
                    };
                    if read == 0 {
                        break;
                    }
                    socket.write_all(&chunk[..read]).await?;
                }
            }
        }
        Ok(())
    }
}

/// Read the request head and return the token out of `GET /s/<token>`.
///
/// Deliberately strict: one method, one shape of path. A Host header that is
/// not loopback is refused, so a name pointed at 127.0.0.1 cannot be used to
/// reach this from a page the user never opened.
async fn read_request(socket: &mut TcpStream) -> std::io::Result<Option<String>> {
    let mut buf: Vec<u8> = Vec::with_capacity(1024);
    while !buf.windows(4).any(|w| w == b"\r\n\r\n") {
        if buf.len() > MAX_HEAD_BYTES {
            return Ok(None);
        }
        let mut chunk = [0u8; 1024];
        let read = socket.read(&mut chunk).await?;
        if read == 0 {
            return Ok(None);
        }
        buf.extend_from_slice(&chunk[..read]);
    }
    let head = String::from_utf8_lossy(&buf);
    let mut lines = head.lines();
    let Some(request_line) = lines.next() else {
        return Ok(None);
    };
    let mut parts = request_line.split_whitespace();
    if parts.next() != Some("GET") {
        return Ok(None);
    }
    let Some(path) = parts.next() else {
        return Ok(None);
    };
    let host_ok = lines
        .take_while(|line| !line.is_empty())
        .filter_map(|line| line.split_once(':'))
        .find(|(key, _)| key.eq_ignore_ascii_case("host"))
        .map(|(_, value)| {
            let value = value.trim();
            let host = value.rsplit_once(':').map(|(h, _)| h).unwrap_or(value);
            host == "127.0.0.1" || host == "localhost" || host == "[::1]"
        })
        .unwrap_or(false);
    if !host_ok {
        return Ok(None);
    }
    let token = path.strip_prefix("/s/").unwrap_or("");
    if token.is_empty() || !token.chars().all(|c| c.is_ascii_hexdigit()) {
        return Ok(None);
    }
    Ok(Some(token.to_string()))
}

async fn reply(socket: &mut TcpStream, status: &str) -> std::io::Result<()> {
    let head = format!("HTTP/1.1 {status}\r\nContent-Length: 0\r\nConnection: close\r\n\r\n");
    socket.write_all(head.as_bytes()).await
}
