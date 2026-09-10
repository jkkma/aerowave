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

use aerowave_core::icy::MetaStrip;
use aerowave_core::local_media;
use futures_util::StreamExt;
use rand::Rng;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::{TcpListener, TcpStream};

use crate::stream::{open_for_relay, RelayBody, RELAY_READ_TIMEOUT};

/// A request head is a few hundred bytes and the webview sends nothing like
/// this much. Anything still writing one after this is not a browser.
const MAX_HEAD_BYTES: usize = 8 * 1024;
/// Stations addressable at once. Browsing can register a great many in one
/// session, and the table must not grow for as long as the app is open.
const MAX_ROUTES: usize = 128;

pub struct Relay {
    pub port: u16,
    routes: Mutex<Routes>,
    on_title: TitleSink,
    #[cfg(target_os = "linux")]
    files: Mutex<FileRoutes>,
}

#[cfg(target_os = "linux")]
#[derive(Clone)]
struct FileRoute {
    file: Arc<std::fs::File>,
    length: u64,
    content_type: &'static str,
}

#[cfg(target_os = "linux")]
#[derive(Default)]
struct FileRoutes {
    by_token: HashMap<String, FileRoute>,
    order: VecDeque<String>,
}

/// What to do with a title the stream has just announced, given the station
/// URL it came from and the title itself.
///
/// A callback rather than an `AppHandle` on purpose: it is what keeps this
/// file free of Tauri, and that is the only reason `examples/relaycheck.rs`
/// can link the relay without WebView2 and the Win32 GUI stack behind it.
/// `lib.rs` passes one that emits `icy-title`; the example passes one that
/// prints.
pub type TitleSink = Arc<dyn Fn(&str, &str) + Send + Sync>;

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
    pub async fn start(on_title: TitleSink) -> Result<Arc<Self>, String> {
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
            on_title,
            #[cfg(target_os = "linux")]
            files: Mutex::new(FileRoutes::default()),
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

    /// The caller must check the asset scope before registering a file. Keep
    /// the opened handle so replacing its pathname cannot redirect a token.
    #[cfg(target_os = "linux")]
    pub fn route_file(&self, path: &std::path::Path) -> Result<String, String> {
        let content_type = path
            .extension()
            .and_then(|ext| ext.to_str())
            .and_then(local_media::content_type)
            .ok_or_else(|| "that file is not a supported audio format".to_string())?;
        let file =
            std::fs::File::open(path).map_err(|e| format!("could not open audio file: {e}"))?;
        let metadata = file.metadata().map_err(|e| e.to_string())?;
        if !metadata.is_file() {
            return Err("the audio source is not a regular file".to_string());
        }
        let token = token();
        let mut files = self.files.lock().unwrap();
        files.by_token.insert(
            token.clone(),
            FileRoute {
                file: Arc::new(file),
                length: metadata.len(),
                content_type,
            },
        );
        files.order.push_back(token.clone());
        // Each entry owns a descriptor. Recent retries and snoozes fit here,
        // while old folder picks do not keep files open for the whole run.
        while files.order.len() > 32 {
            if let Some(stale) = files.order.pop_front() {
                files.by_token.remove(&stale);
            }
        }
        Ok(format!("http://127.0.0.1:{}/f/{token}", self.port))
    }

    #[cfg(target_os = "linux")]
    async fn serve_file(
        &self,
        socket: &mut TcpStream,
        request: local_media::Request,
    ) -> std::io::Result<()> {
        use std::os::unix::fs::FileExt;
        let source = self
            .files
            .lock()
            .unwrap()
            .by_token
            .get(&request.token)
            .cloned();
        let Some(source) = source else {
            return reply(socket, "404 Not Found").await;
        };
        let (status, mut offset, length, content_range) = match local_media::byte_range(
            request.range.as_deref(),
            source.length,
        ) {
            local_media::ByteRange::Full => ("200 OK", 0, source.length, String::new()),
            local_media::ByteRange::Partial { start, end } => (
                "206 Partial Content",
                start,
                end - start + 1,
                format!("Content-Range: bytes {start}-{end}/{}\r\n", source.length),
            ),
            local_media::ByteRange::Unsatisfiable => {
                let head = format!("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */{}\r\nContent-Length: 0\r\nConnection: close\r\n\r\n", source.length);
                return socket.write_all(head.as_bytes()).await;
            }
        };
        let head = format!("HTTP/1.1 {status}\r\nContent-Type: {}\r\nContent-Length: {length}\r\nAccept-Ranges: bytes\r\n{content_range}Cache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nConnection: close\r\n\r\n", source.content_type);
        socket.write_all(head.as_bytes()).await?;
        let mut remaining = length;
        while remaining > 0 {
            let file = source.file.clone();
            let count = remaining.min(64 * 1024) as usize;
            // Positioned reads let simultaneous range requests share an open
            // descriptor without sharing a cursor. Disk I/O stays off Tokio's
            // worker threads and at most one small chunk is retained here.
            let chunk = tokio::task::spawn_blocking(move || {
                let mut chunk = vec![0; count];
                let read = file.read_at(&mut chunk, offset)?;
                chunk.truncate(read);
                Ok::<_, std::io::Error>(chunk)
            })
            .await
            .map_err(std::io::Error::other)??;
            if chunk.is_empty() {
                break;
            }
            tokio::time::timeout(RELAY_READ_TIMEOUT, socket.write_all(&chunk)).await??;
            offset += chunk.len() as u64;
            remaining -= chunk.len() as u64;
        }
        Ok(())
    }

    async fn serve(&self, mut socket: TcpStream) -> std::io::Result<()> {
        let Some(request) = read_request(&mut socket).await? else {
            return reply(&mut socket, "400 Bad Request").await;
        };
        if request.file {
            #[cfg(target_os = "linux")]
            return self.serve_file(&mut socket, request).await;
            #[cfg(not(target_os = "linux"))]
            return reply(&mut socket, "404 Not Found").await;
        }
        let Some(url) = self.upstream_for(&request.token) else {
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
        let mut pipe = Pipe::new(&self.on_title, &url, source.metaint);
        match source.body {
            RelayBody::Http(resp) => {
                // reqwest has already undone the chunked framing. Forwarding
                // the upstream socket raw instead would splice the chunk
                // sizes into the audio, which is silence with extra steps.
                let mut body = resp.bytes_stream();
                while let Some(chunk) = body.next().await {
                    let Ok(chunk) = chunk else { break };
                    pipe.write(&mut socket, &chunk).await?;
                }
            }
            RelayBody::Icy {
                socket: mut upstream,
                primed,
            } => {
                // `primed` is stream, not preamble: the metadata blocks are
                // counted from the first body byte, so it goes through the
                // same strip as everything after it.
                if !primed.is_empty() {
                    pipe.write(&mut socket, &primed).await?;
                }
                let mut chunk = [0u8; 16384];
                loop {
                    let Ok(Ok(read)) =
                        tokio::time::timeout(RELAY_READ_TIMEOUT, upstream.read(&mut chunk)).await
                    else {
                        break;
                    };
                    if read == 0 {
                        break;
                    }
                    pipe.write(&mut socket, &chunk[..read]).await?;
                }
            }
        }
        Ok(())
    }
}

/// Copies audio down to the player and titles out to the front end.
///
/// A station that interleaves nothing gets no stripper and its bytes go
/// straight through, which is what every relayed station did before titles
/// were read here.
struct Pipe<'a> {
    on_title: &'a TitleSink,
    url: &'a str,
    strip: Option<MetaStrip>,
    audio: Vec<u8>,
    last: Option<String>,
}

impl<'a> Pipe<'a> {
    fn new(on_title: &'a TitleSink, url: &'a str, metaint: usize) -> Self {
        Self {
            on_title,
            url,
            strip: (metaint > 0).then(|| MetaStrip::new(metaint)),
            audio: Vec::new(),
            last: None,
        }
    }

    async fn write(&mut self, socket: &mut TcpStream, chunk: &[u8]) -> std::io::Result<()> {
        let Some(strip) = self.strip.as_mut() else {
            return socket.write_all(chunk).await;
        };
        self.audio.clear();
        for title in strip.push(chunk, &mut self.audio) {
            // Not every server saves its breath between tracks; some repeat
            // the current title in every block. Only the changes are news.
            if self.last.as_deref() == Some(title.as_str()) {
                continue;
            }
            (self.on_title)(self.url, &title);
            self.last = Some(title);
        }
        socket.write_all(&self.audio).await
    }
}

/// Read the request head for an opaque station or local-file token.
///
/// Deliberately strict: one method, one shape of path. A Host header that is
/// not loopback is refused, so a name pointed at 127.0.0.1 cannot be used to
/// reach this from a page the user never opened.
async fn read_request(socket: &mut TcpStream) -> std::io::Result<Option<local_media::Request>> {
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
    if buf.len() > MAX_HEAD_BYTES {
        return Ok(None);
    }
    Ok(std::str::from_utf8(&buf)
        .ok()
        .and_then(local_media::request))
}

async fn reply(socket: &mut TcpStream, status: &str) -> std::io::Result<()> {
    let head = format!("HTTP/1.1 {status}\r\nContent-Length: 0\r\nConnection: close\r\n\r\n");
    socket.write_all(head.as_bytes()).await
}
