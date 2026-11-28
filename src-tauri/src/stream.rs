//! Talking to Icecast/Shoutcast servers: turning a playlist link into a
//! playable stream URL, and reading the "now playing" title out of the
//! ICY metadata the server interleaves with the audio.
//!
//! The <audio> element cannot see ICY metadata, so the title has to be read
//! here on a separate, short-lived connection. One connection, not two:
//! `resolve` hands back the live response it already opened rather than just
//! its URL, so a probe does not fetch the same stream twice.

use std::collections::HashSet;
use std::io;
use std::net::SocketAddr;
use std::sync::{Arc, OnceLock};
use std::time::Duration;

use aerowave_core::icy::{
    decode_text, head_end, is_hls, is_playable_type, looks_like_playlist, metadata_interval,
    parse_head, playlist_prefix, playlist_urls, socket_host, Head, MetaStrip, PlaylistPrefix,
};
use aerowave_core::network::{clear_userinfo, inherit_userinfo, StationNetwork};
use aerowave_core::stream_tags::StreamTags;
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
const MAX_PLAYLIST_PREFIX_BYTES: usize = 1024;
pub(crate) const RELAY_READ_TIMEOUT: Duration = Duration::from_secs(30);
const ICY_HEAD_TIMEOUT: Duration = Duration::from_secs(8);
const MAX_REDIRECTS: usize = 6;
const MAX_PLAYLIST_DEPTH: usize = 2;
const MAX_STATION_CANDIDATES: usize = 8;
const ALTERNATE_WAIT: Duration = Duration::from_secs(5);

static PROBE_CLIENTS: OnceLock<StationClients> = OnceLock::new();
static RELAY_CLIENTS: OnceLock<StationClients> = OnceLock::new();

struct StationClients {
    public: reqwest::Client,
    lan: reqwest::Client,
}

impl StationClients {
    fn get(&self, network: StationNetwork) -> &reqwest::Client {
        match network {
            StationNetwork::Lan => &self.lan,
            StationNetwork::Public => &self.public,
        }
    }
}

struct LanDns;

impl reqwest::dns::Resolve for LanDns {
    fn resolve(&self, name: reqwest::dns::Name) -> reqwest::dns::Resolving {
        Box::pin(async move {
            let addrs = tokio::time::timeout(
                Duration::from_secs(8),
                checked_addresses(name.as_str(), 0, StationNetwork::Lan),
            )
            .await??;
            Ok(Box::new(addrs.into_iter()) as reqwest::dns::Addrs)
        })
    }
}

async fn checked_addresses(
    host: &str,
    port: u16,
    network: StationNetwork,
) -> io::Result<Vec<SocketAddr>> {
    let addresses: Vec<_> = tokio::net::lookup_host((host, port)).await?.collect();
    if !network.addresses_allowed(&addresses) {
        return Err(io::Error::new(
            io::ErrorKind::PermissionDenied,
            "that station hostname resolves outside its permitted network",
        ));
    }
    Ok(addresses)
}

fn station_clients(
    cache: &'static OnceLock<StationClients>,
    read_timeout: Duration,
) -> Result<&'static StationClients, String> {
    if let Some(clients) = cache.get() {
        return Ok(clients);
    }
    // Follow redirects here so HTTP, playlist resolution and raw ICY share
    // the same policy. A chain that leaves the LAN cannot come back via ICY.
    let builder = || {
        crate::hls::client_builder()
            .read_timeout(read_timeout)
            .redirect(reqwest::redirect::Policy::none())
    };
    let public = builder().build().map_err(|e| e.to_string())?;
    let lan = builder()
        .dns_resolver(Arc::new(LanDns))
        .build()
        .map_err(|e| e.to_string())?;
    Ok(cache.get_or_init(|| StationClients { public, lan }))
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

fn without_userinfo(url: &reqwest::Url) -> String {
    let mut clean = url.clone();
    clear_userinfo(&mut clean);
    clean.into()
}

fn redirect_target(current: &reqwest::Url, location: &str) -> Result<reqwest::Url, String> {
    let mut target = current
        .join(location)
        .map_err(|error| format!("invalid stream redirect: {error}"))?;
    inherit_userinfo(current, &mut target);
    Ok(target)
}

fn is_redirect(status: reqwest::StatusCode) -> bool {
    use reqwest::StatusCode;

    matches!(
        status,
        StatusCode::MOVED_PERMANENTLY
            | StatusCode::FOUND
            | StatusCode::SEE_OTHER
            | StatusCode::TEMPORARY_REDIRECT
            | StatusCode::PERMANENT_REDIRECT
    )
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
    /// Only a successfully resolved ordinary LAN source may bypass the relay.
    pub direct_fallback_allowed: bool,
}

/// What `resolve` arrived at. The stream arm carries the open response, so the
/// caller can read headers and body from the connection already established.
enum Resolved {
    Stream {
        resp: reqwest::Response,
        primed: Vec<u8>,
        final_url: String,
        content_type: String,
    },
    Icy {
        socket: tokio::net::TcpStream,
        head: Head,
        primed: Vec<u8>,
        final_url: String,
    },
    Hls {
        final_url: String,
        content_type: String,
    },
}

impl Resolved {
    /// A playlist can name a mount that sends audio headers before it fails.
    /// Check for initial bytes while the resolver can still try another entry.
    async fn confirm_audio(&mut self) -> Result<(), String> {
        let primed = match self {
            Self::Stream { resp, primed, .. } => {
                metadata_interval(header(resp, "icy-metaint").as_deref())?;
                while primed.is_empty() {
                    let Some(chunk) = resp.chunk().await.map_err(|e| describe(&e.without_url()))?
                    else {
                        break;
                    };
                    primed.extend_from_slice(&chunk);
                }
                primed
            }
            Self::Icy {
                socket,
                head,
                primed,
                ..
            } => {
                metadata_interval(head.get("icy-metaint"))?;
                if primed.is_empty() {
                    let mut chunk = [0u8; 2048];
                    let read = socket.read(&mut chunk).await.map_err(|e| e.to_string())?;
                    primed.extend_from_slice(&chunk[..read]);
                }
                primed
            }
            Self::Hls { .. } => return Ok(()),
        };
        if primed.is_empty() {
            return Err("the station returned an empty audio response".into());
        }
        Ok(())
    }
}

enum Candidate {
    Source(Resolved),
    Playlist(Vec<String>),
}

/// An HTTP attempt that may still have reached a plaintext Shoutcast-v1
/// endpoint. `raw_url` is the last playlist candidate or redirect destination,
/// rather than necessarily the wrapper address the user supplied.
struct ResolveError {
    message: String,
    raw_url: Option<String>,
}

impl ResolveError {
    fn new(message: impl Into<String>, raw_url: impl Into<String>) -> Self {
        Self {
            message: message.into(),
            raw_url: Some(raw_url.into()),
        }
    }

    fn refused(message: impl Into<String>) -> Self {
        Self {
            message: message.into(),
            raw_url: None,
        }
    }
}

async fn station_response(
    clients: &StationClients,
    url: &str,
    want_metadata: bool,
    network: &mut StationNetwork,
) -> Result<(reqwest::Response, reqwest::Url), ResolveError> {
    let mut current = reqwest::Url::parse(url.trim())
        .map_err(|error| ResolveError::refused(format!("invalid station URL: {error}")))?;
    for redirects in 0..=MAX_REDIRECTS {
        network.admit(&current).map_err(ResolveError::refused)?;
        let mut request = clients.get(*network).get(current.clone());
        if want_metadata {
            request = request.header("Icy-MetaData", "1");
        }
        let response = request.send().await.map_err(|error| {
            ResolveError::new(
                format!(
                    "{}: {}",
                    without_userinfo(&current),
                    describe(&error.without_url())
                ),
                current.as_str(),
            )
        })?;
        let status = response.status();
        if status.is_success() {
            return Ok((response, current));
        }
        if !is_redirect(status) {
            return Err(ResolveError::new(
                format!("HTTP {status} from {}", without_userinfo(&current)),
                current.as_str(),
            ));
        }
        if redirects == MAX_REDIRECTS {
            return Err(ResolveError::refused("too many station redirects"));
        }
        let location = response
            .headers()
            .get(reqwest::header::LOCATION)
            .and_then(|location| location.to_str().ok())
            .ok_or_else(|| {
                ResolveError::refused("the stream redirect had no valid Location header")
            })?;
        current = redirect_target(&current, location).map_err(ResolveError::refused)?;
    }
    unreachable!("the redirect limit returns before another request")
}

/// Read a playlist body, refusing to buffer more than the cap.
async fn read_capped(resp: reqwest::Response, mut body: Vec<u8>) -> Result<String, String> {
    if let Some(len) = resp.content_length() {
        if len as usize > MAX_PLAYLIST_BYTES {
            return Err(format!(
                "that playlist claims to be {len} bytes; the limit is {MAX_PLAYLIST_BYTES}"
            ));
        }
    }
    if body.len() > MAX_PLAYLIST_BYTES {
        return Err(format!(
            "that playlist ran past the {MAX_PLAYLIST_BYTES}-byte limit"
        ));
    }
    let mut stream = resp.bytes_stream();
    while let Some(chunk) = stream.next().await {
        let chunk = chunk.map_err(|e| describe(&e.without_url()))?;
        if body.len() + chunk.len() > MAX_PLAYLIST_BYTES {
            return Err(format!(
                "that playlist ran past the {MAX_PLAYLIST_BYTES}-byte limit"
            ));
        }
        body.extend_from_slice(&chunk);
    }
    Ok(String::from_utf8_lossy(&body).into_owned())
}

/// A generic URL or MIME type can still lead to a playlist. Keep every byte
/// consumed while inspecting its prefix so an audio body can be replayed whole.
async fn sniff_playlist(resp: &mut reqwest::Response, primed: &mut Vec<u8>) -> Result<bool, String> {
    loop {
        let inspected = primed.len().min(MAX_PLAYLIST_PREFIX_BYTES);
        match playlist_prefix(&primed[..inspected], false) {
            PlaylistPrefix::Recognized => return Ok(true),
            PlaylistPrefix::Other => return Ok(false),
            PlaylistPrefix::Incomplete if inspected == MAX_PLAYLIST_PREFIX_BYTES => {
                return Ok(false);
            }
            PlaylistPrefix::Incomplete => {}
        }
        match resp.chunk().await.map_err(|e| describe(&e.without_url()))? {
            Some(chunk) => primed.extend_from_slice(&chunk),
            None => return Ok(playlist_prefix(primed, true) == PlaylistPrefix::Recognized),
        }
    }
}

fn content_type_of(resp: &reqwest::Response) -> String {
    resp.headers()
        .get(reqwest::header::CONTENT_TYPE)
        .and_then(|v| v.to_str().ok())
        .unwrap_or("")
        .to_ascii_lowercase()
}

async fn open_candidate(
    clients: &StationClients,
    url: &str,
    want_metadata: bool,
    network: &mut StationNetwork,
    skip_resolve: bool,
) -> Result<Candidate, ResolveError> {
    let (mut resp, final_url) = match station_response(clients, url, want_metadata, network).await {
        Ok(response) => response,
        Err(error) => {
            let Some(raw_url) = error.raw_url.as_deref() else {
                return Err(error);
            };
            // An HTTP client cannot parse Shoutcast v1's status line. Prefer
            // the raw server's reason only when it proves it speaks ICY.
            return match icy_connect(raw_url, want_metadata, network).await {
                Ok((socket, head, primed, final_url)) => Ok(Candidate::Source(Resolved::Icy {
                    socket,
                    head,
                    primed,
                    final_url,
                })),
                Err(icy) if icy.proven => Err(ResolveError::refused(icy.message)),
                Err(_) => Err(error),
            };
        }
    };
    let content_type = content_type_of(&resp);
    let mut primed = Vec::new();
    let mime = content_type.split(';').next().unwrap_or("").trim();
    let playlist = !skip_resolve
        && (looks_like_playlist(final_url.path(), &content_type)
            || (matches!(mime, "" | "application/octet-stream" | "text/plain")
                && sniff_playlist(&mut resp, &mut primed)
                    .await
                    .map_err(ResolveError::refused)?));
    if playlist {
        let body = read_capped(resp, primed)
            .await
            .map_err(ResolveError::refused)?;
        if is_hls(&body) {
            return Ok(Candidate::Source(Resolved::Hls {
                final_url: final_url.into(),
                content_type,
            }));
        }
        let candidates = playlist_urls(
            &body,
            final_url.as_str(),
            &content_type,
            MAX_STATION_CANDIDATES,
        );
        if candidates.is_empty() {
            return Err(ResolveError::refused(format!(
                "{} is a playlist with no stream URL in it",
                without_userinfo(&final_url)
            )));
        }
        return Ok(Candidate::Playlist(candidates));
    }

    if !is_playable_type(&content_type) {
        return Err(ResolveError::refused(format!(
            "that address answers with {content_type}, not audio - the station is probably down, full, or has moved"
        )));
    }
    if primed.is_empty() && resp.content_length() == Some(0) {
        return Err(ResolveError::refused(
            "the station returned an empty audio response",
        ));
    }
    Ok(Candidate::Source(Resolved::Stream {
        resp,
        primed,
        final_url: final_url.into(),
        content_type,
    }))
}

/// Try the playlist's addresses in order with bounded depth, attempts and
/// time for a candidate when another is waiting. Each branch inherits the
/// network policy of the playlist that named it.
async fn resolve(
    clients: &StationClients,
    url: &str,
    want_metadata: bool,
    network: &mut StationNetwork,
    skip_resolve: bool,
) -> Result<Resolved, ResolveError> {
    let mut pending = vec![(url.trim().to_string(), *network, 0)];
    let mut seen = HashSet::new();
    let mut first_error = None;
    let mut attempts = 0;
    while let Some((current, mut branch_network, depth)) = pending.pop() {
        if attempts == MAX_STATION_CANDIDATES {
            break;
        }
        if !seen.insert((
            current.clone(),
            branch_network == StationNetwork::Lan,
            depth,
        )) {
            continue;
        }
        attempts += 1;
        let attempt = open_candidate(
            clients,
            &current,
            want_metadata,
            &mut branch_network,
            skip_resolve,
        );
        let result = if pending.is_empty() {
            attempt.await
        } else {
            tokio::time::timeout(ALTERNATE_WAIT, async {
                let mut candidate = attempt.await?;
                if let Candidate::Source(source) = &mut candidate {
                    source.confirm_audio().await.map_err(ResolveError::refused)?;
                }
                Ok(candidate)
            })
            .await
            .unwrap_or_else(|_| {
                Err(ResolveError::refused(
                    "timed out talking to a playlist entry",
                ))
            })
        };

        let error = match result {
            Ok(Candidate::Source(source)) => {
                *network = branch_network;
                return Ok(source);
            }
            Ok(Candidate::Playlist(candidates)) if depth < MAX_PLAYLIST_DEPTH => {
                pending.extend(
                    candidates
                        .into_iter()
                        .rev()
                        .map(|url| (url, branch_network, depth + 1)),
                );
                continue;
            }
            Ok(Candidate::Playlist(_)) => {
                ResolveError::refused("too many nested station playlists")
            }
            Err(error) => error,
        };
        if first_error.is_none() {
            first_error = Some(error);
        }
    }
    Err(first_error
        .unwrap_or_else(|| ResolveError::refused("playlist links went round in circles")))
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
async fn probe_inner(
    url: &str,
    want_title: bool,
    skip_resolve: bool,
) -> Result<StreamInfo, String> {
    let parsed =
        reqwest::Url::parse(url.trim()).map_err(|e| format!("invalid station URL: {e}"))?;
    let mut network = StationNetwork::for_source(&parsed);
    let clients = station_clients(&PROBE_CLIENTS, Duration::from_secs(5))?;
    match resolve(clients, url, true, &mut network, skip_resolve)
        .await
        .map_err(|error| error.message)?
    {
        Resolved::Hls {
            final_url,
            content_type,
        } => Ok(StreamInfo {
            url: final_url,
            content_type: Some(content_type).filter(|c| !c.is_empty()),
            hls: true,
            ..Default::default()
        }),
        Resolved::Stream {
            resp,
            primed,
            final_url,
            content_type,
        } => http_probe(resp, primed, final_url, content_type, want_title, network).await,
        Resolved::Icy {
            socket,
            head,
            primed,
            final_url,
        } => Ok(icy_probe(socket, head, primed, final_url, want_title, network).await),
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
async fn icy_connect_once(
    url: &str,
    want_metadata: bool,
    network: &mut StationNetwork,
) -> Result<(tokio::net::TcpStream, Head, Vec<u8>), IcyFailure> {
    let parsed = reqwest::Url::parse(url.trim())
        .map_err(|e| unproven(format!("invalid station URL: {e}")))?;
    if parsed.scheme() != "http" {
        return Err(unproven("only a plaintext URL can be read this way"));
    }
    network.admit(&parsed).map_err(unproven)?;
    let host = parsed
        .host_str()
        .ok_or_else(|| unproven("that URL has no host in it"))?;
    let connect_host = socket_host(&parsed).ok_or_else(|| unproven("that URL has no host in it"))?;
    let port = parsed.port().unwrap_or(80);
    let mut target = parsed.path().to_string();
    if target.is_empty() {
        target.push('/');
    }
    if let Some(query) = parsed.query() {
        target.push('?');
        target.push_str(query);
    }

    let mut socket = tokio::time::timeout(Duration::from_secs(6), async {
        let addresses = checked_addresses(&connect_host, port, *network).await?;
        tokio::net::TcpStream::connect(addresses.as_slice()).await
    })
    .await
    .map_err(|_| unproven(format!("timed out connecting to {host}")))?
    .map_err(|e| unproven(format!("could not connect to {host}: {e}")))?;

    // HTTP/1.0 is what the players these servers were written for send, and
    // it settles the question of keeping the connection open afterwards.
    let metadata = if want_metadata { "Icy-MetaData: 1\r\n" } else { "" };
    // Build the header the same way as the HTTP path, including URL percent
    // decoding. Only the header is needed; no request is sent through reqwest.
    let authorization = if parsed.username().is_empty() && parsed.password().is_none() {
        String::new()
    } else {
        let clients = station_clients(&PROBE_CLIENTS, Duration::from_secs(5)).map_err(unproven)?;
        let request = clients
            .get(*network)
            .get(parsed.clone())
            .build()
            .map_err(|error| unproven(describe(&error.without_url())))?;
        request
            .headers()
            .get(reqwest::header::AUTHORIZATION)
            .and_then(|value| value.to_str().ok())
            .map(|value| format!("Authorization: {value}\r\n"))
            .unwrap_or_default()
    };
    let request = format!(
        "GET {target} HTTP/1.0\r\nHost: {host}:{port}\r\nUser-Agent: {UA}\r\n\
         {metadata}{authorization}Connection: close\r\n\r\n"
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
    // Whatever audio arrived alongside the head belongs to the caller.
    let body = buf.split_off(body_at);
    Ok((socket, head, body))
}

async fn icy_connect(
    url: &str,
    want_metadata: bool,
    network: &mut StationNetwork,
) -> Result<(tokio::net::TcpStream, Head, Vec<u8>, String), IcyFailure> {
    let mut current = url.trim().to_string();
    for _ in 0..=MAX_REDIRECTS {
        let (socket, head, body) = icy_connect_once(&current, want_metadata, network).await?;
        if head.icy {
            // Past this point the server has shown what it is, so what it says
            // about itself is worth reporting over the HTTP client's guess.
            if head.code != 200 {
                let host = reqwest::Url::parse(&current)
                    .ok()
                    .and_then(|url| url.host_str().map(str::to_string))
                    .unwrap_or_else(|| current.clone());
                return Err(IcyFailure {
                    proven: true,
                    message: format!("ICY {} from {host}", head.code),
                });
            }
            let content_type = head.get("content-type").unwrap_or("");
            if !is_playable_type(content_type) {
                return Err(IcyFailure {
                    proven: true,
                    message: format!(
                        "that address answers with {content_type}, not audio - \
                         the station is probably down, full, or has moved"
                    ),
                });
            }
            return Ok((socket, head, body, current));
        }

        // Usually reqwest has already followed this. Keeping redirects here
        // covers a plaintext wrapper when its final response is `ICY 200 OK`,
        // which prevents the HTTP stack from handing back a normal Response.
        if (300..400).contains(&head.code) {
            let Some(location) = head.get("location") else {
                return Err(unproven("the stream redirect had no Location header"));
            };
            let base = reqwest::Url::parse(&current)
                .map_err(|e| unproven(format!("invalid station URL: {e}")))?;
            let next = redirect_target(&base, location).map_err(unproven)?;
            if next.scheme() != "http" {
                return Err(unproven("the raw ICY redirect was not plaintext HTTP"));
            }
            current = next.to_string();
            continue;
        }

        let host = reqwest::Url::parse(&current)
            .ok()
            .and_then(|url| url.host_str().map(str::to_string))
            .unwrap_or(current);
        return Err(unproven(format!("{host} answered HTTP after all")));
    }
    Err(unproven("too many redirects while reaching the ICY stream"))
}

fn metadata_readers_done(strip: &Option<MetaStrip>, tags: &Option<StreamTags>) -> bool {
    let icy_done = match strip {
        Some(strip) => strip.blocks() >= 2,
        None => true,
    };
    let native_done = match tags {
        Some(tags) => tags.initial_done(),
        None => true,
    };
    icy_done && native_done
}

/// Observe one run of upstream bytes. ICY blocks are removed before native
/// container tags see the audio, matching the order used by the live relay.
fn metadata_updates(
    strip: &mut Option<MetaStrip>,
    tags: &mut Option<StreamTags>,
    chunk: &[u8],
    audio: &mut Vec<u8>,
) -> Vec<String> {
    audio.clear();
    let mut updates = match strip {
        Some(strip) => strip.push(chunk, audio),
        None => {
            audio.extend_from_slice(chunk);
            Vec::new()
        }
    };
    if let Some(tags) = tags {
        updates.extend(tags.push(audio));
    }
    updates
}

/// The Shoutcast v1 probe: connect as above, then read far enough into the
/// audio to catch either an ICY title or the container's initial native tags.
async fn icy_probe(
    mut socket: tokio::net::TcpStream,
    head: Head,
    mut body: Vec<u8>,
    final_url: String,
    want_title: bool,
    network: StationNetwork,
) -> StreamInfo {
    let content_type = head
        .get("content-type")
        .map(|content_type| content_type.to_ascii_lowercase());

    let mut info = StreamInfo {
        url: final_url.clone(),
        name: head.get("icy-name").map(str::to_string),
        genre: head.get("icy-genre").map(str::to_string),
        bitrate: head.get("icy-br").map(str::to_string),
        content_type: content_type.clone(),
        title: None,
        warning: None,
        hls: false,
        direct_fallback_allowed: network == StationNetwork::Lan,
    };

    if !want_title {
        return info;
    }
    let metaint = head
        .get("icy-metaint")
        .and_then(|value| value.trim().parse().ok())
        .filter(|&interval| interval > 0 && interval < MAX_META_BYTES)
        .unwrap_or(0);
    let mut strip = (metaint > 0).then(|| MetaStrip::new(metaint));
    let mut tags = StreamTags::for_stream(content_type.as_deref().unwrap_or(""), &final_url);
    if strip.is_none() && tags.is_none() {
        return info;
    }

    let mut total = 0usize;
    let mut audio = Vec::new();
    loop {
        if body.is_empty() {
            let mut chunk = [0u8; 8192];
            // The head is already in hand at this point, so a stream that
            // stops mid-metadata still hands back its static headers.
            let read = match tokio::time::timeout(Duration::from_secs(5), socket.read(&mut chunk))
                .await
            {
                Ok(Ok(read)) => read,
                _ => break,
            };
            if read == 0 {
                break;
            }
            body.extend_from_slice(&chunk[..read]);
        }
        let take = body.len().min(MAX_META_BYTES - total);
        let updates = metadata_updates(
            &mut strip,
            &mut tags,
            &body[..take],
            &mut audio,
        );
        total += take;
        body.clear();
        if let Some(title) = updates.into_iter().next() {
            info.title = Some(title);
            return info;
        }
        if total >= MAX_META_BYTES || metadata_readers_done(&strip, &tags) {
            break;
        }
    }
    info
}

/// Connect, read enough of the stream to catch one metadata block, disconnect.
///
async fn http_probe(
    resp: reqwest::Response,
    primed: Vec<u8>,
    direct: String,
    content_type: String,
    want_title: bool,
    network: StationNetwork,
) -> Result<StreamInfo, String> {
    let mut info = StreamInfo {
        url: direct.clone(),
        name: header(&resp, "icy-name"),
        genre: header(&resp, "icy-genre"),
        bitrate: header(&resp, "icy-br"),
        content_type: Some(content_type.clone()).filter(|c| !c.is_empty()),
        title: None,
        warning: None,
        hls: false,
        direct_fallback_allowed: network == StationNetwork::Lan,
    };

    if !want_title {
        return Ok(info);
    }
    let metaint = header(&resp, "icy-metaint")
        .and_then(|value| value.trim().parse().ok())
        .filter(|&interval| interval > 0 && interval < MAX_META_BYTES)
        .unwrap_or(0);
    let mut strip = (metaint > 0).then(|| MetaStrip::new(metaint));
    let mut tags = StreamTags::for_stream(&content_type, &direct);
    if strip.is_none() && tags.is_none() {
        return Ok(info);
    }

    let primed = (!primed.is_empty()).then(|| Ok(primed.into()));
    let mut body = futures_util::stream::iter(primed).chain(resp.bytes_stream());
    let mut total = 0usize;
    let mut audio = Vec::new();
    while total < MAX_META_BYTES && !metadata_readers_done(&strip, &tags) {
        match body.next().await {
            Some(Ok(chunk)) => {
                let take = chunk.len().min(MAX_META_BYTES - total);
                let updates = metadata_updates(
                    &mut strip,
                    &mut tags,
                    &chunk[..take],
                    &mut audio,
                );
                total += take;
                if let Some(title) = updates.into_iter().next() {
                    info.title = Some(title);
                    return Ok(info);
                }
            }
            Some(Err(e)) => {
                return Err(describe(&e.without_url()));
            }
            None => break,
        }
    }
    Ok(info)
}

/// An upstream the relay can pump from, however the server chose to answer.
pub enum RelayBody {
    Http {
        response: reqwest::Response,
        primed: Vec<u8>,
    },
    /// Shoutcast v1: the socket, plus the audio that arrived with the head.
    Icy {
        socket: tokio::net::TcpStream,
        primed: Vec<u8>,
    },
}

pub struct RelaySource {
    pub content_type: String,
    /// The final upstream address identifies a native Ogg/FLAC container even
    /// when the user supplied a playlist wrapper with no useful extension.
    pub metadata_url: String,
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
    let parsed =
        reqwest::Url::parse(url.trim()).map_err(|e| format!("invalid station URL: {e}"))?;
    let mut network = StationNetwork::for_source(&parsed);
    // An established radio stream gets longer to recover from a quiet stretch
    // than a short probe, but a dead connection still ends on its own.
    let clients = station_clients(&RELAY_CLIENTS, RELAY_READ_TIMEOUT)?;
    match tokio::time::timeout(
        RELAY_READ_TIMEOUT,
        resolve(clients, url, true, &mut network, false),
    )
    .await
    .map_err(|_| "timed out opening the station".to_string())?
    .map_err(|error| error.message)?
    {
        Resolved::Stream {
            resp,
            primed,
            final_url,
            content_type,
        } => {
            let content_type = if content_type.is_empty() {
                "audio/mpeg".to_string()
            } else {
                content_type
            };
            Ok(RelaySource {
                content_type,
                metadata_url: final_url,
                metaint: metadata_interval(header(&resp, "icy-metaint").as_deref())?,
                body: RelayBody::Http {
                    response: resp,
                    primed,
                },
            })
        }
        Resolved::Hls { .. } => Err("HLS - this plays through hls.rs, not the relay".into()),
        Resolved::Icy {
            socket,
            head,
            primed,
            final_url,
        } => Ok(RelaySource {
            content_type: head
                .get("content-type")
                .map(|c| c.to_ascii_lowercase())
                .unwrap_or_else(|| "audio/mpeg".to_string()),
            metadata_url: final_url,
            metaint: metadata_interval(head.get("icy-metaint"))?,
            body: RelayBody::Icy { socket, primed },
        }),
    }
}

/// Fetch one public HLS resource with a hard size cap and deadline. The
/// caller's client has automatic redirects disabled so this loop can check
/// the destination and scope authorization again for every hop.
pub async fn fetch_once(
    client: &reqwest::Client,
    url: &str,
    range: Option<(u64, u64)>,
    cap: usize,
) -> Result<crate::hls::Fetched, String> {
    let work = async {
        let mut url = reqwest::Url::parse(url).map_err(|e| format!("invalid HLS URL: {e}"))?;
        let mut redirects = 0;
        let resp = loop {
            if !aerowave_core::network::public_http_destination_allowed(
                url.scheme(),
                url.host_str().unwrap_or(""),
            ) {
                return Err("that HLS request does not lead to a public HTTP address".into());
            }
            let mut request = client.get(url.clone());
            if let Some((start, end)) = range {
                request = request.header("Range", format!("bytes={start}-{end}"));
            }
            let resp = request.send().await.map_err(|e| describe(&e.without_url()))?;
            if !is_redirect(resp.status()) {
                break resp;
            }
            if redirects == MAX_REDIRECTS {
                return Err("too many HLS redirects".into());
            }
            let location = resp
                .headers()
                .get(reqwest::header::LOCATION)
                .and_then(|value| value.to_str().ok())
                .ok_or("the HLS redirect had no valid Location header")?;
            url = redirect_target(&url, location)?;
            redirects += 1;
        };
        let status = resp.status().as_u16();
        let content_type = content_type_of(&resp);
        let final_url = without_userinfo(&url);

        if let Some(len) = resp.content_length() {
            if len as usize > cap {
                return Err(format!("that file claims to be {len} bytes; the limit is {cap}"));
            }
        }
        let mut body: Vec<u8> = Vec::new();
        let mut chunks = resp.bytes_stream();
        while let Some(chunk) = chunks.next().await {
            let chunk = chunk.map_err(|e| describe(&e.without_url()))?;
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
            effective_url: url,
            body,
        })
    };
    match tokio::time::timeout(Duration::from_secs(20), work).await {
        Ok(result) => result,
        Err(_) => Err("timed out fetching the HLS resource".into()),
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

#[cfg(test)]
mod tests {
    use super::*;
    use tokio::net::TcpListener;

    async fn public_wrapper_cannot_reach_loopback(redirect: bool) {
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let address = listener.local_addr().unwrap();
        let target = format!("http://{address}/private");
        let wrapper = if redirect {
            format!("HTTP/1.1 302 Found\r\nLocation: {target}\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
        } else {
            format!("HTTP/1.1 200 OK\r\nContent-Type: audio/x-mpegurl\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{target}", target.len())
        };
        let server = tokio::spawn(async move {
            let mut connections = 0;
            loop {
                let Ok(Ok((mut socket, _))) =
                    tokio::time::timeout(Duration::from_secs(1), listener.accept()).await
                else {
                    break;
                };
                let mut head = Vec::new();
                while head_end(&head).is_none() {
                    let mut buf = [0; 1024];
                    let read = socket.read(&mut buf).await.unwrap();
                    assert!(read > 0);
                    head.extend_from_slice(&buf[..read]);
                }
                connections += 1;
                let response = if head.starts_with(b"GET /private ") {
                    "HTTP/1.1 200 OK\r\nContent-Type: audio/mpeg\r\nContent-Length: 4\r\nConnection: close\r\n\r\nsong"
                } else {
                    &wrapper
                };
                socket.write_all(response.as_bytes()).await.unwrap();
            }
            connections
        });
        let client = reqwest::Client::builder()
            .no_proxy()
            .redirect(reqwest::redirect::Policy::none())
            .resolve("station.test", address)
            .build()
            .unwrap();
        let clients = StationClients {
            public: client.clone(),
            lan: client,
        };
        let url = format!("http://station.test:{}/stream", address.port());
        let mut network = StationNetwork::Public;
        let result = resolve(&clients, &url, false, &mut network, false).await;
        let error = result.err().expect("private hop was accepted");
        assert!(
            error.raw_url.is_none(),
            "denied hop was eligible for raw ICY"
        );
        assert_eq!(server.await.unwrap(), 1, "contacted a private destination");
    }

    #[tokio::test]
    async fn rejects_private_redirect_before_connecting() {
        public_wrapper_cannot_reach_loopback(true).await;
    }

    #[tokio::test]
    async fn rejects_private_playlist_entry_before_connecting() {
        public_wrapper_cannot_reach_loopback(false).await;
    }

    #[tokio::test]
    async fn raw_icy_rejects_a_private_target_and_private_dns_in_public_scope() {
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let port = listener.local_addr().unwrap().port();
        assert!(icy_connect_once(
            &format!("http://127.0.0.1:{port}/stream"),
            true,
            &mut StationNetwork::Public,
        )
        .await
        .is_err());
        assert!(checked_addresses("localhost", port, StationNetwork::Public)
            .await
            .is_err());
        assert!(
            tokio::time::timeout(Duration::from_millis(50), listener.accept())
                .await
                .is_err()
        );
    }

    #[tokio::test]
    async fn explicit_lan_probe_allows_a_direct_browser_fallback() {
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let address = listener.local_addr().unwrap();
        let server = tokio::spawn(async move {
            let (mut socket, _) = listener.accept().await.unwrap();
            let mut request = [0; 1024];
            assert!(socket.read(&mut request).await.unwrap() > 0);
            socket.write_all(b"HTTP/1.1 200 OK\r\nContent-Type: audio/mpeg\r\nContent-Length: 4\r\nConnection: close\r\n\r\nsong").await.unwrap();
        });
        let info = probe(&format!("http://{address}/stream"), false, false)
            .await.unwrap();
        assert!(info.direct_fallback_allowed);
        server.await.unwrap();
    }
}
