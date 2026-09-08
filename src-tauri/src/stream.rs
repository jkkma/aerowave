//! Talking to Icecast/Shoutcast servers: turning a playlist link into a
//! playable stream URL, and reading the "now playing" title out of the
//! ICY metadata the server interleaves with the audio.
//!
//! The <audio> element cannot see ICY metadata, so the title has to be read
//! here on a separate, short-lived connection. One connection, not two:
//! `resolve` hands back the live response it already opened rather than just
//! its URL, so a probe does not fetch the same stream twice.

use std::sync::OnceLock;
use std::time::Duration;

use aerowave_core::icy::{
    first_url_in_playlist, head_end, is_hls, looks_like_playlist, parse_head, scan_metadata,
};
use futures_util::StreamExt;
use serde::Serialize;
use tokio::io::{AsyncReadExt, AsyncWriteExt};

/// Directories and broadcasters both like to know who is calling, and
/// radio-browser asks for it outright. Carry the real version.
pub const UA: &str = concat!("Aerowave/", env!("CARGO_PKG_VERSION"));
const MAX_META_BYTES: usize = 512 * 1024;
/// A response head is a few hundred bytes. Anything still writing one after
/// this much is not going to stop on its own.
const MAX_HEAD_BYTES: usize = 8 * 1024;
/// A playlist is a few hundred bytes. Anything claiming to be one and running
/// to megabytes is a broken or hostile server, and buffering it whole would
/// let it decide how much memory this process uses.
const MAX_PLAYLIST_BYTES: usize = 256 * 1024;

static CLIENT: OnceLock<reqwest::Client> = OnceLock::new();

pub fn client() -> Result<reqwest::Client, String> {
    if let Some(existing) = CLIENT.get() {
        return Ok(existing.clone());
    }
    let built = reqwest::Client::builder()
        .user_agent(UA)
        .connect_timeout(Duration::from_secs(8))
        // A body that simply stops arriving must not swallow the whole
        // twelve-second deadline while a caller waits on it.
        .read_timeout(Duration::from_secs(5))
        .redirect(reqwest::redirect::Policy::limited(6))
        .build()
        .map_err(|e| e.to_string())?;
    Ok(CLIENT.get_or_init(|| built).clone())
}

/// reqwest's own Display is the outer wrapper - "error sending request for
/// url (...)" - and the cause underneath is the part that says what actually
/// went wrong. Walk the chain so the UI can show it.
pub fn describe(e: &reqwest::Error) -> String {
    let lead = if e.is_connect() {
        "could not connect"
    } else if e.is_timeout() {
        "timed out"
    } else if e.is_decode() {
        "could not decode the response"
    } else if e.is_body() {
        "the connection dropped"
    } else {
        "request failed"
    };
    let mut out = format!("{lead}: {e}");
    let mut source = std::error::Error::source(e);
    while let Some(cause) = source {
        out.push_str(&format!(": {cause}"));
        source = cause.source();
    }
    out
}

#[derive(Serialize, Clone, Debug, Default)]
#[serde(rename_all = "camelCase")]
pub struct StreamInfo {
    /// The URL that should actually be handed to <audio>.
    pub url: String,
    pub name: Option<String>,
    pub genre: Option<String>,
    pub bitrate: Option<String>,
    pub content_type: Option<String>,
    pub title: Option<String>,
    /// Set when the URL is a stream in principle but not one WebView2 can
    /// decode, so the UI can say why nothing is coming out.
    pub warning: Option<String>,
}

/// What `resolve` arrived at. The stream arm carries the open response, so the
/// caller can read headers and body from the connection already established.
enum Resolved {
    Stream {
        resp: reqwest::Response,
        final_url: String,
        content_type: String,
    },
    Hls {
        final_url: String,
        content_type: String,
    },
}

/// Read a playlist body, refusing to buffer more than the cap.
async fn read_capped(resp: reqwest::Response) -> Result<String, String> {
    if let Some(len) = resp.content_length() {
        if len as usize > MAX_PLAYLIST_BYTES {
            return Err(format!(
                "that playlist claims to be {len} bytes; the limit is {MAX_PLAYLIST_BYTES}"
            ));
        }
    }
    let mut body: Vec<u8> = Vec::new();
    let mut stream = resp.bytes_stream();
    while let Some(chunk) = stream.next().await {
        let chunk = chunk.map_err(|e| describe(&e))?;
        if body.len() + chunk.len() > MAX_PLAYLIST_BYTES {
            return Err(format!(
                "that playlist ran past the {MAX_PLAYLIST_BYTES}-byte limit"
            ));
        }
        body.extend_from_slice(&chunk);
    }
    Ok(String::from_utf8_lossy(&body).into_owned())
}

fn content_type_of(resp: &reqwest::Response) -> String {
    resp.headers()
        .get(reqwest::header::CONTENT_TYPE)
        .and_then(|v| v.to_str().ok())
        .unwrap_or("")
        .to_ascii_lowercase()
}

/// Follow playlist files until a direct stream turns up (max 3 hops).
async fn resolve(url: &str) -> Result<Resolved, String> {
    let client = client()?;
    let mut current = url.trim().to_string();

    for _ in 0..3 {
        let resp = client
            .get(&current)
            .header("Icy-MetaData", "1")
            .send()
            .await
            .map_err(|e| format!("{current}: {}", describe(&e)))?;
        if !resp.status().is_success() {
            return Err(format!("HTTP {} from {current}", resp.status()));
        }
        let content_type = content_type_of(&resp);
        let final_url = resp.url().to_string();

        if !looks_like_playlist(&final_url, &content_type) {
            return Ok(Resolved::Stream {
                resp,
                final_url,
                content_type,
            });
        }

        let body = read_capped(resp).await?;
        if is_hls(&body) {
            // WebView2 has no native HLS decoder, so say so rather than
            // handing back a URL that will play silence.
            return Ok(Resolved::Hls {
                final_url,
                content_type,
            });
        }
        match first_url_in_playlist(&body) {
            Some(next) => current = next,
            None => return Err(format!("{final_url} is a playlist with no stream URL in it")),
        }
    }
    Err("playlist links went round in circles".into())
}

fn header(resp: &reqwest::Response, key: &str) -> Option<String> {
    resp.headers()
        .get(key)
        .and_then(|v| v.to_str().ok())
        .map(|s| s.trim().to_string())
        .filter(|s| !s.is_empty())
}

/// Ask the stream about itself, over HTTP if that works and by hand if it
/// does not.
///
/// Shoutcast v1 answers `ICY 200 OK` instead of an HTTP status line. Hyper
/// throws that response away before a single header is seen, so a station
/// WebView2 plays quite happily would otherwise have no bitrate, no genre and
/// no now-playing title, and would fail its TEST for a reason that has
/// nothing to do with whether it plays.
async fn probe_inner(url: &str, want_title: bool, skip_resolve: bool) -> Result<StreamInfo, String> {
    match http_probe(url, want_title, skip_resolve).await {
        Ok(info) => Ok(info),
        Err(http_error) => match icy_probe(url, want_title).await {
            Ok(info) => Ok(info),
            // Past the head, the server has shown it really is speaking ICY
            // and has said something specific - "ICY 401", the station is
            // full. That beats hyper's complaint about a status line it could
            // not read, which is true of every one of these servers.
            Err(icy) if icy.proven => Err(icy.message),
            // Before that point it has proved nothing, so the HTTP error is
            // still the one worth showing.
            Err(_) => Err(http_error),
        },
    }
}

/// Why the hand-rolled attempt gave up, and whether it got far enough for its
/// own account of the failure to be worth more than the HTTP client's.
struct IcyFailure {
    proven: bool,
    message: String,
}

/// Gave up before the server proved anything.
fn unproven(message: impl Into<String>) -> IcyFailure {
    IcyFailure {
        proven: false,
        message: message.into(),
    }
}

/// The Shoutcast v1 path: send the request down a plain socket and read the
/// head ourselves, because no HTTP client will parse what comes back.
///
/// Plaintext only. ICY predates TLS by decades and the servers still speaking
/// it are `http://` to a one, so an `https://` failure is a real failure and
/// is left to stand.
async fn icy_probe(url: &str, want_title: bool) -> Result<StreamInfo, IcyFailure> {
    let parsed = reqwest::Url::parse(url.trim()).map_err(|e| unproven(format!("{url}: {e}")))?;
    if parsed.scheme() != "http" {
        return Err(unproven("only a plaintext URL can be read this way"));
    }
    let host = parsed
        .host_str()
        .ok_or_else(|| unproven("that URL has no host in it"))?;
    let port = parsed.port().unwrap_or(80);
    let mut target = parsed.path().to_string();
    if target.is_empty() {
        target.push('/');
    }
    if let Some(query) = parsed.query() {
        target.push('?');
        target.push_str(query);
    }

    let mut socket = tokio::time::timeout(
        Duration::from_secs(6),
        tokio::net::TcpStream::connect((host, port)),
    )
    .await
    .map_err(|_| unproven(format!("timed out connecting to {host}")))?
    .map_err(|e| unproven(format!("could not connect to {host}: {e}")))?;

    // HTTP/1.0 is what the players these servers were written for send, and
    // it settles the question of keeping the connection open afterwards.
    let request = format!(
        "GET {target} HTTP/1.0\r\nHost: {host}:{port}\r\nUser-Agent: {UA}\r\n\
         Icy-MetaData: 1\r\nConnection: close\r\n\r\n"
    );
    socket
        .write_all(request.as_bytes())
        .await
        .map_err(|e| unproven(format!("could not ask {host} for the stream: {e}")))?;

    let mut buf: Vec<u8> = Vec::with_capacity(2048);
    let (head_len, body_at) = loop {
        if let Some(found) = head_end(&buf) {
            break found;
        }
        if buf.len() > MAX_HEAD_BYTES {
            return Err(unproven(format!("{host} never finished its response head")));
        }
        let mut chunk = [0u8; 2048];
        let read = socket
            .read(&mut chunk)
            .await
            .map_err(|e| unproven(format!("{host} stopped talking: {e}")))?;
        if read == 0 {
            return Err(unproven(format!("{host} closed before answering")));
        }
        buf.extend_from_slice(&chunk[..read]);
    };

    let head = String::from_utf8_lossy(&buf[..head_len]);
    let head =
        parse_head(&head).ok_or_else(|| unproven(format!("{host} sent no status line")))?;
    // Only a station that really is speaking ICY. Anything answering HTTP was
    // reqwest's job, and it has already failed for a reason this cannot mend -
    // claiming it here would bury the real error under a worse one.
    if !head.icy {
        return Err(unproven(format!("{host} answered HTTP after all")));
    }
    // Past this point the server has shown what it is, so what it says about
    // itself is worth reporting in place of the HTTP client's guess.
    if head.code != 200 {
        return Err(IcyFailure {
            proven: true,
            message: format!("ICY {} from {host}", head.code),
        });
    }

    let mut info = StreamInfo {
        url: url.trim().to_string(),
        name: head.get("icy-name").map(str::to_string),
        genre: head.get("icy-genre").map(str::to_string),
        bitrate: head.get("icy-br").map(str::to_string),
        content_type: head.get("content-type").map(|c| c.to_ascii_lowercase()),
        title: None,
        warning: None,
    };

    let metaint: usize = match head.get("icy-metaint").and_then(|v| v.parse().ok()) {
        Some(n) if want_title && n > 0 && n < MAX_META_BYTES => n,
        _ => return Ok(info),
    };

    // Whatever audio arrived alongside the head counts towards the first block.
    let mut body = buf.split_off(body_at);
    let mut cursor = 0usize;
    let mut blocks_read = 0;
    while body.len() < MAX_META_BYTES && blocks_read < 2 {
        let scan = scan_metadata(&body, metaint, cursor);
        cursor = scan.cursor;
        blocks_read += scan.blocks;
        if scan.title.is_some() {
            info.title = scan.title;
            return Ok(info);
        }
        let mut chunk = [0u8; 8192];
        // The head is already in hand at this point, so a stream that stops
        // mid-metadata still hands back the bitrate and genre it gave us.
        let read = match socket.read(&mut chunk).await {
            Ok(read) => read,
            Err(_) => break,
        };
        if read == 0 {
            break;
        }
        body.extend_from_slice(&chunk[..read]);
    }
    Ok(info)
}

/// Connect, read enough of the stream to catch one metadata block, disconnect.
///
/// `skip_resolve` is for the now-playing poll, which already holds the direct
/// URL from the first probe: following the playlist chain again every time
/// would ask the broadcaster for the same file over and over for nothing.
async fn http_probe(url: &str, want_title: bool, skip_resolve: bool) -> Result<StreamInfo, String> {
    let (resp, direct, content_type) = if skip_resolve {
        let resp = client()?
            .get(url.trim())
            .header("Icy-MetaData", "1")
            .send()
            .await
            .map_err(|e| describe(&e))?;
        if !resp.status().is_success() {
            return Err(format!("HTTP {}", resp.status()));
        }
        let content_type = content_type_of(&resp);
        let final_url = resp.url().to_string();
        (resp, final_url, content_type)
    } else {
        match resolve(url).await? {
            Resolved::Hls {
                final_url,
                content_type,
            } => {
                return Ok(StreamInfo {
                    url: final_url,
                    content_type: Some(content_type).filter(|c| !c.is_empty()),
                    warning: Some("HLS playlist - WebView2 cannot play this natively".into()),
                    ..Default::default()
                })
            }
            Resolved::Stream {
                resp,
                final_url,
                content_type,
            } => (resp, final_url, content_type),
        }
    };

    let mut info = StreamInfo {
        url: direct,
        name: header(&resp, "icy-name"),
        genre: header(&resp, "icy-genre"),
        bitrate: header(&resp, "icy-br"),
        content_type: Some(content_type).filter(|c| !c.is_empty()),
        title: None,
        warning: None,
    };

    let metaint: usize = match header(&resp, "icy-metaint").and_then(|v| v.parse().ok()) {
        Some(n) if want_title && n > 0 && n < MAX_META_BYTES => n,
        _ => return Ok(info),
    };

    // Read up to two blocks: servers often send an empty first block and the
    // real title only on the next boundary.
    let mut buf: Vec<u8> = Vec::with_capacity(metaint * 2);
    let mut body = resp.bytes_stream();
    let mut cursor = 0usize;
    let mut blocks_read = 0;

    while buf.len() < MAX_META_BYTES && blocks_read < 2 {
        match body.next().await {
            Some(Ok(chunk)) => buf.extend_from_slice(&chunk),
            Some(Err(e)) => return Err(describe(&e)),
            None => break,
        }
        let scan = scan_metadata(&buf, metaint, cursor);
        cursor = scan.cursor;
        blocks_read += scan.blocks;
        if scan.title.is_some() {
            info.title = scan.title;
            return Ok(info);
        }
    }
    Ok(info)
}

/// Probe with a hard deadline - a stalled radio server must not leave the
/// command hanging forever.
pub async fn probe(url: &str, want_title: bool, skip_resolve: bool) -> Result<StreamInfo, String> {
    match tokio::time::timeout(
        Duration::from_secs(12),
        probe_inner(url, want_title, skip_resolve),
    )
    .await
    {
        Ok(result) => result,
        Err(_) => Err("timed out talking to the stream".into()),
    }
}
