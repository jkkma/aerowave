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
    decode_text, first_url_in_playlist, head_end, is_hls, looks_like_playlist, metadata_interval,
    parse_head, scan_metadata, Head,
};
use futures_util::StreamExt;
use serde::Serialize;
use tokio::io::{AsyncReadExt, AsyncWriteExt};

/// What to tell a directory or a broadcaster we are. Both like to know, and
/// radio-browser asks outright. Carry the real version.
pub const UA: &str = concat!("Aerowave/", env!("CARGO_PKG_VERSION"));
/// 0.5.0 told broadcasters we were Edge, on the strength of SomaFM answering
/// `403` to one User-Agent and `200` to a browser's. That measurement was an
/// artefact: the agent SomaFM refused belonged to the browser the test ran in,
/// not to anything this app ever sends. SomaFM serves the name below quite
/// happily, and pretending to be a browser cost real stations - Shoutcast v1
/// sniffs the other way and hands a browser its admin page instead of the
/// stream, which is a `text/html` body the player then reports as an
/// undecodable one. Every Radio Caprice mount went silent that way.
///
/// Measured over 60 stations, one per host: this name got audio from 40, the
/// browser string from 38, and there was no station the browser string won.
const MAX_META_BYTES: usize = 512 * 1024;
/// A response head is a few hundred bytes. Anything still writing one after
/// this much is not going to stop on its own.
const MAX_HEAD_BYTES: usize = 8 * 1024;
/// A playlist is a few hundred bytes. Anything claiming to be one and running
/// to megabytes is a broken or hostile server, and buffering it whole would
/// let it decide how much memory this process uses.
const MAX_PLAYLIST_BYTES: usize = 256 * 1024;
pub(crate) const RELAY_READ_TIMEOUT: Duration = Duration::from_secs(30);
const ICY_HEAD_TIMEOUT: Duration = Duration::from_secs(8);

static CLIENT: OnceLock<reqwest::Client> = OnceLock::new();
static RELAY_CLIENT: OnceLock<reqwest::Client> = OnceLock::new();

fn build_client(read_timeout: Option<Duration>) -> Result<reqwest::Client, String> {
    let mut builder = reqwest::Client::builder()
        .user_agent(UA)
        .connect_timeout(Duration::from_secs(8))
        .redirect(reqwest::redirect::Policy::limited(6));
    if let Some(t) = read_timeout {
        builder = builder.read_timeout(t);
    }
    builder.build().map_err(|e| e.to_string())
}

pub fn client() -> Result<reqwest::Client, String> {
    if let Some(existing) = CLIENT.get() {
        return Ok(existing.clone());
    }
    // A body that simply stops arriving must not swallow the whole
    // twelve-second deadline while a caller waits on it.
    let built = build_client(Some(Duration::from_secs(5)))?;
    Ok(CLIENT.get_or_init(|| built).clone())
}

/// The relay holds one connection open for as long as the station plays, so
/// the probe's five-second read timeout would cut it off at the first quiet
/// stretch. Longer, but not infinite: a broadcaster that stops sending should
/// still end the connection so the player's reconnect can take over.
fn relay_client() -> Result<reqwest::Client, String> {
    if let Some(existing) = RELAY_CLIENT.get() {
        return Ok(existing.clone());
    }
    let built = build_client(Some(RELAY_READ_TIMEOUT))?;
    Ok(RELAY_CLIENT.get_or_init(|| built).clone())
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
    /// An HLS playlist. Not a warning any more - it is which player to use.
    pub hls: bool,
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

/// Is this a body worth handing to a player?
///
/// A station that is down, full, or pointed at the wrong path answers `200`
/// with a web page - Shoutcast's admin console, an error page, a consent
/// screen. Passing that to <audio> gets `MEDIA_ERR_SRC_NOT_SUPPORTED`, which
/// says "this codec is unsupported" when the truth is "this is not audio at
/// all". Better to notice here, where the content type is in hand.
fn is_playable_type(content_type: &str) -> bool {
    let t = content_type.trim().to_ascii_lowercase();
    if t.is_empty() {
        // Plenty of Shoutcast servers say nothing at all, and play fine.
        return true;
    }
    !(t.starts_with("text/")
        || t.starts_with("application/json")
        || t.starts_with("application/xml")
        || t.starts_with("application/xhtml"))
}

fn content_type_of(resp: &reqwest::Response) -> String {
    resp.headers()
        .get(reqwest::header::CONTENT_TYPE)
        .and_then(|v| v.to_str().ok())
        .unwrap_or("")
        .to_ascii_lowercase()
}

/// Follow playlist files until a direct stream turns up (max 3 hops).
///
/// `want_metadata` asks the server to interleave ICY metadata blocks with the
/// audio. Only a caller that will take them back out again may say yes - see
/// `open_for_relay`.
async fn resolve(
    client: &reqwest::Client,
    url: &str,
    want_metadata: bool,
) -> Result<Resolved, String> {
    let mut current = url.trim().to_string();

    for _ in 0..3 {
        let mut request = client.get(&current);
        if want_metadata {
            request = request.header("Icy-MetaData", "1");
        }
        let resp = request
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
            // Not a failure - a fork. The caller is told it is HLS so the
            // front end can hand it to hls.js instead of to <audio>.
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

/// One response header, decoded the way ICY text has to be.
///
/// Not `to_str`, which only succeeds for visible ASCII: `icy-name` and
/// `icy-genre` are free text, and plenty of stations put their own alphabet in
/// them. Refusing those left a station with a Cyrillic name showing no name at
/// all, which read as a server that had not sent one.
fn header(resp: &reqwest::Response, key: &str) -> Option<String> {
    resp.headers()
        .get(key)
        .map(|v| decode_text(v.as_bytes()).trim().to_string())
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
async fn icy_connect(
    url: &str,
    want_metadata: bool,
) -> Result<(tokio::net::TcpStream, Head, Vec<u8>), IcyFailure> {
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
    let metadata = if want_metadata { "Icy-MetaData: 1\r\n" } else { "" };
    let request = format!(
        "GET {target} HTTP/1.0\r\nHost: {host}:{port}\r\nUser-Agent: {UA}\r\n\
         {metadata}Connection: close\r\n\r\n"
    );
    // Bound the entire head, not each read: a server trickling a byte at a
    // time otherwise keeps the connection pending forever.
    let head_work = async {
        socket
            .write_all(request.as_bytes())
            .await
            .map_err(|e| unproven(format!("could not ask {host} for the stream: {e}")))?;
        let mut buf: Vec<u8> = Vec::with_capacity(2048);
        loop {
            if let Some((head_len, body_at)) = head_end(&buf) {
                if head_len > MAX_HEAD_BYTES {
                    return Err(unproven(format!("{host} sent too large a response head")));
                }
                return Ok((buf, head_len, body_at));
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
        }
    };
    let (mut buf, head_len, body_at) =
        tokio::time::timeout(ICY_HEAD_TIMEOUT, head_work)
            .await
            .map_err(|_| unproven(format!("{host} timed out answering the stream request")))??;

    let head = {
        let text = decode_text(&buf[..head_len]);
        parse_head(&text).ok_or_else(|| unproven(format!("{host} sent no status line")))?
    };
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

    // Whatever audio arrived alongside the head belongs to the caller.
    let body = buf.split_off(body_at);
    Ok((socket, head, body))
}

/// The Shoutcast v1 probe: connect as above, then read far enough into the
/// audio to catch a title.
async fn icy_probe(url: &str, want_title: bool) -> Result<StreamInfo, IcyFailure> {
    let (mut socket, head, mut body) = icy_connect(url, true).await?;

    let mut info = StreamInfo {
        url: url.trim().to_string(),
        name: head.get("icy-name").map(str::to_string),
        genre: head.get("icy-genre").map(str::to_string),
        bitrate: head.get("icy-br").map(str::to_string),
        content_type: head.get("content-type").map(|c| c.to_ascii_lowercase()),
        title: None,
        warning: None,
        hls: false,
    };

    let metaint: usize = match head.get("icy-metaint").and_then(|v| v.parse().ok()) {
        Some(n) if want_title && n > 0 && n < MAX_META_BYTES => n,
        _ => return Ok(info),
    };

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
        let read = match tokio::time::timeout(Duration::from_secs(5), socket.read(&mut chunk)).await
        {
            Ok(Ok(read)) => read,
            _ => break,
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
        match resolve(&client()?, url, true).await? {
            Resolved::Hls {
                final_url,
                content_type,
            } => {
                return Ok(StreamInfo {
                    url: final_url,
                    content_type: Some(content_type).filter(|c| !c.is_empty()),
                    hls: true,
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

    if !is_playable_type(&content_type) {
        return Err(format!(
            "that address answers with {content_type}, not audio - the station \
             is probably down, full, or has moved"
        ));
    }

    let mut info = StreamInfo {
        url: direct,
        name: header(&resp, "icy-name"),
        genre: header(&resp, "icy-genre"),
        bitrate: header(&resp, "icy-br"),
        content_type: Some(content_type).filter(|c| !c.is_empty()),
        title: None,
        warning: None,
        hls: false,
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

/// An upstream the relay can pump from, however the server chose to answer.
pub enum RelayBody {
    Http(reqwest::Response),
    /// Shoutcast v1: the socket, plus the audio that arrived with the head.
    Icy {
        socket: tokio::net::TcpStream,
        primed: Vec<u8>,
    },
}

pub struct RelaySource {
    pub content_type: String,
    pub body: RelayBody,
    /// What `icy-metaint` said, or 0 for a server interleaving nothing. The
    /// relay strips the blocks this far apart back out; see `MetaStrip`.
    pub metaint: usize,
}

/// Open a station on behalf of the relay: playlists followed, redirects
/// followed, Shoutcast v1 handled - the same three problems `probe` already
/// solves, and the reason relaying is worth doing in Rust rather than in the
/// webview.
///
/// HLS is refused here, and that is deliberate rather than a gap: it plays,
/// but not down this pipe. Splicing segments into one endless body is a
/// different job from copying bytes, and rewriting the playlist's origin
/// breaks hls.js's own URI resolution. HLS goes through `hls.rs` instead,
/// which hands back one file at a time.
pub async fn open_for_relay(url: &str) -> Result<RelaySource, String> {
    // `Icy-MetaData: 1`, and the relay takes the blocks out again on the way
    // past. Asking for metadata and *copying* it was what broke: the blocks
    // land every `icy-metaint` bytes, so a `StreamTitle='...'` ends up in the
    // middle of the MP3 and the decoder gives up - `MEDIA_ERR_DECODE`,
    // mid-song, on a full buffer. Radio Paradise (metaint 16000) died about
    // sixteen seconds in, every time, while FIP, which interleaves nothing,
    // played for as long as you left it.
    //
    // Stripping is what a player is supposed to do with them, and it is worth
    // the care: it is the connection the audio is already on, so a title
    // changes when the song does rather than whenever a poll next came round.
    let http = match resolve(&relay_client()?, url, true).await {
        Ok(Resolved::Stream {
            resp, content_type, ..
        }) => {
            if !is_playable_type(&content_type) {
                return Err(format!(
                    "that address answers with {content_type}, not audio - \
                     the station is probably down, full, or has moved"
                ));
            }
            let content_type = if content_type.is_empty() {
                "audio/mpeg".to_string()
            } else {
                content_type
            };
            return Ok(RelaySource {
                content_type,
                metaint: metadata_interval(header(&resp, "icy-metaint").as_deref())?,
                body: RelayBody::Http(resp),
            });
        }
        Ok(Resolved::Hls { .. }) => {
            return Err("HLS - this plays through hls.rs, not the relay".into())
        }
        Err(e) => e,
    };
    // Same order as `probe_inner`: HTTP first, and the hand-rolled ICY path
    // only once the HTTP client has failed.
    match icy_connect(url, true).await {
        Ok((socket, head, primed)) => Ok(RelaySource {
            content_type: head
                .get("content-type")
                .map(|c| c.to_ascii_lowercase())
                .unwrap_or_else(|| "audio/mpeg".to_string()),
            metaint: metadata_interval(head.get("icy-metaint"))?,
            body: RelayBody::Icy { socket, primed },
        }),
        Err(icy) if icy.proven => Err(icy.message),
        Err(_) => Err(http),
    }
}

/// One file, fetched once: no playlist following, no ICY metadata, a hard
/// size cap and a deadline. The HLS side wants exactly this - hls.js walks
/// the playlists itself and only needs each individual file handed back.
pub async fn fetch_once(
    client: &reqwest::Client,
    url: &str,
    range: Option<(u64, u64)>,
    cap: usize,
) -> Result<crate::hls::Fetched, String> {
    let work = async {
        let mut request = client.get(url);
        if let Some((start, end)) = range {
            request = request.header("Range", format!("bytes={start}-{end}"));
        }
        let resp = request.send().await.map_err(|e| describe(&e))?;
        let status = resp.status().as_u16();
        let content_type = content_type_of(&resp);
        let final_url = resp.url().to_string();

        if let Some(len) = resp.content_length() {
            if len as usize > cap {
                return Err(format!("that file claims to be {len} bytes; the limit is {cap}"));
            }
        }
        let mut body: Vec<u8> = Vec::new();
        let mut chunks = resp.bytes_stream();
        while let Some(chunk) = chunks.next().await {
            let chunk = chunk.map_err(|e| describe(&e))?;
            if body.len() + chunk.len() > cap {
                return Err(format!("that file ran past the {cap}-byte limit"));
            }
            body.extend_from_slice(&chunk);
        }
        Ok(crate::hls::Fetched {
            status,
            content_type: if content_type.is_empty() {
                "application/octet-stream".to_string()
            } else {
                content_type
            },
            final_url,
            body,
        })
    };
    match tokio::time::timeout(Duration::from_secs(20), work).await {
        Ok(result) => result,
        Err(_) => Err(format!("timed out fetching {url}")),
    }
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
