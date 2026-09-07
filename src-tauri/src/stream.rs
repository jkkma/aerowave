//! Talking to Icecast/Shoutcast servers: turning a playlist link into a
//! playable stream URL, and reading the "now playing" title out of the
//! ICY metadata the server interleaves with the audio.
//!
//! The <audio> element cannot see ICY metadata, so the title has to be read
//! here with a second, short-lived connection.

use std::time::Duration;

use aerowave_core::icy::{first_url_in_playlist, is_hls, looks_like_playlist, stream_title};
use futures_util::StreamExt;
use serde::Serialize;

const UA: &str = "Aerowave/0.1";
const MAX_META_BYTES: usize = 512 * 1024;

fn client() -> Result<reqwest::Client, String> {
    reqwest::Client::builder()
        .user_agent(UA)
        .connect_timeout(Duration::from_secs(8))
        .redirect(reqwest::redirect::Policy::limited(6))
        .build()
        .map_err(|e| e.to_string())
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

/// Follow playlist files until a direct stream URL turns up (max 3 hops).
/// Returns the direct URL, its content type, and any warning worth showing.
pub async fn resolve(url: &str) -> Result<(String, Option<String>, Option<String>), String> {
    let client = client()?;
    let mut current = url.trim().to_string();

    for _ in 0..3 {
        let resp = client
            .get(&current)
            .header("Icy-MetaData", "1")
            .send()
            .await
            .map_err(|e| format!("{current}: {e}"))?;
        if !resp.status().is_success() {
            return Err(format!("HTTP {} from {current}", resp.status()));
        }
        let content_type = resp
            .headers()
            .get(reqwest::header::CONTENT_TYPE)
            .and_then(|v| v.to_str().ok())
            .unwrap_or("")
            .to_ascii_lowercase();
        let final_url = resp.url().to_string();

        if !looks_like_playlist(&final_url, &content_type) {
            return Ok((final_url, Some(content_type), None));
        }

        let body = resp.text().await.map_err(|e| e.to_string())?;
        if is_hls(&body) {
            // An HLS playlist. WebView2 has no native HLS decoder, so say so
            // rather than handing back a URL that will play silence.
            return Ok((
                final_url,
                Some(content_type),
                Some("HLS playlist - WebView2 cannot play this natively".into()),
            ));
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
async fn probe_inner(url: &str, want_title: bool) -> Result<StreamInfo, String> {
    let (direct, content_type, warning) = resolve(url).await?;
    let client = client()?;
    let resp = client
        .get(&direct)
        .header("Icy-MetaData", "1")
        .send()
        .await
        .map_err(|e| e.to_string())?;
    if !resp.status().is_success() {
        return Err(format!("HTTP {}", resp.status()));
    }

    let mut info = StreamInfo {
        url: direct,
        name: header(&resp, "icy-name"),
        genre: header(&resp, "icy-genre"),
        bitrate: header(&resp, "icy-br"),
        content_type: content_type.filter(|c| !c.is_empty()),
        title: None,
        warning,
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
            Some(Err(e)) => return Err(e.to_string()),
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
pub async fn probe(url: &str, want_title: bool) -> Result<StreamInfo, String> {
    match tokio::time::timeout(Duration::from_secs(12), probe_inner(url, want_title)).await {
        Ok(result) => result,
        Err(_) => Err("timed out talking to the stream".into()),
    }
}
