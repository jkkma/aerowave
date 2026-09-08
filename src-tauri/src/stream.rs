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

use aerowave_core::icy::{first_url_in_playlist, is_hls, looks_like_playlist, stream_title};
use futures_util::StreamExt;
use serde::Serialize;

/// Directories and broadcasters both like to know who is calling, and
/// radio-browser asks for it outright. Carry the real version.
const UA: &str = concat!("Aerowave/", env!("CARGO_PKG_VERSION"));
const MAX_META_BYTES: usize = 512 * 1024;
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

/// Connect, read enough of the stream to catch one metadata block, disconnect.
///
/// `skip_resolve` is for the now-playing poll, which already holds the direct
/// URL from the first probe: following the playlist chain again every time
/// would ask the broadcaster for the same file over and over for nothing.
async fn probe_inner(url: &str, want_title: bool, skip_resolve: bool) -> Result<StreamInfo, String> {
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
        // Consume whole metadata blocks out of whatever has arrived so far.
        while buf.len() > cursor + metaint {
            let len_at = cursor + metaint;
            let block_len = buf[len_at] as usize * 16;
            let block_start = len_at + 1;
            if buf.len() < block_start + block_len {
                break;
            }
            if block_len > 0 {
                let text = String::from_utf8_lossy(&buf[block_start..block_start + block_len]);
                if let Some(t) = stream_title(&text) {
                    info.title = Some(t);
                    return Ok(info);
                }
            }
            cursor = block_start + block_len;
            blocks_read += 1;
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
