//! Fetching the pieces an HLS stream is made of, on behalf of the webview.
//!
//! Audio goes through the loopback relay in `relay.rs`, which suits it: one
//! endless body, pulled by a media element, which is a no-cors load and needs
//! no permission from anybody. HLS is a different shape. It is a playlist
//! refetched every few seconds and a run of finite segment files, and hls.js
//! fetches all of them over XHR - which is not a media load, so it needs CORS
//! and an origin the page is allowed to reach.
//!
//! Opening the loopback listener to webview script would have meant
//! `connect-src http://127.0.0.1:*` in the policy, and that authorises the
//! webview to reach every service on this machine that happens to be bound to
//! loopback - another dev server, a database console, an agent socket. So
//! these go over a Tauri custom protocol instead: one static origin, served
//! only to our own webview, and not reachable by any other process on the
//! machine the way a TCP port is.
//!
//! What is still true is that the webview names the URL. That is unavoidable -
//! hls.js discovers segment URLs by parsing a playlist - so the guard is not
//! "trust the caller" but "only fetch what this relay has itself seen": the
//! origins a session will serve are grown from the redirects it followed and
//! the playlist bodies it returned, never from what the caller asserts.

use std::collections::{HashMap, HashSet};
use std::net::IpAddr;
use std::sync::Mutex;
use std::time::{Duration, Instant};

use rand::Rng;

use crate::stream;

/// One segment. Radio segments run to a few hundred kB; this is slack.
const MAX_BODY: usize = 16 * 1024 * 1024;
/// A session's whole traffic budget. At 128 kbps a listener uses about 58 MB
/// an hour, so this is a long evening and then some.
const MAX_SESSION_BYTES: u64 = 2 * 1024 * 1024 * 1024;
const MAX_SESSIONS: usize = 8;
/// A session nobody has fetched from in this long is finished with.
const SESSION_IDLE: Duration = Duration::from_secs(600);

struct Session {
    /// Origins this session may fetch from. Seeded with the station's own,
    /// then grown only from what we have observed ourselves.
    origins: HashSet<String>,
    last_seen: Instant,
    bytes: u64,
    requests: u32,
}

#[derive(Default)]
pub struct Hls {
    sessions: Mutex<HashMap<String, Session>>,
}

pub struct Fetched {
    pub status: u16,
    pub content_type: String,
    /// Where the fetch actually ended up. hls.js resolves every relative URI
    /// in a playlist against this, so it has to be the broadcaster's URL and
    /// not ours - which is the whole reason playlist bodies go back verbatim
    /// rather than being rewritten here.
    pub final_url: String,
    pub body: Vec<u8>,
}

fn session_id() -> String {
    let bytes: [u8; 16] = rand::thread_rng().gen();
    bytes.iter().map(|b| format!("{b:02x}")).collect()
}

/// `https://host:443/a/b` -> `https://host:443`. Compared as a whole, so a
/// scheme or port change counts as a different origin.
fn origin_of(url: &reqwest::Url) -> Option<String> {
    let host = url.host_str()?;
    Some(match url.port() {
        Some(port) => format!("{}://{}:{}", url.scheme(), host, port),
        None => format!("{}://{}", url.scheme(), host),
    })
}

/// Is this address one the webview has no business reaching through us?
///
/// Defence in depth rather than a guarantee: reqwest resolves the name again
/// when it connects, so a name that answers differently the second time is
/// not caught here. It stops the obvious thing - a playlist pointing at
/// 127.0.0.1 or 192.168.x - which is what makes an SSRF worth having.
fn is_private(ip: &IpAddr) -> bool {
    match ip {
        IpAddr::V4(v4) => {
            v4.is_private()
                || v4.is_loopback()
                || v4.is_link_local()
                || v4.is_broadcast()
                || v4.is_documentation()
                || v4.is_unspecified()
                // 100.64.0.0/10, carrier NAT, and 169.254 is covered above.
                || (v4.octets()[0] == 100 && (v4.octets()[1] & 0xc0) == 64)
        }
        IpAddr::V6(v6) => {
            v6.is_loopback()
                || v6.is_unspecified()
                // fc00::/7 unique-local, fe80::/10 link-local.
                || (v6.segments()[0] & 0xfe00) == 0xfc00
                || (v6.segments()[0] & 0xffc0) == 0xfe80
                || v6.to_ipv4_mapped().map(|v4| is_private(&IpAddr::V4(v4))).unwrap_or(false)
        }
    }
}

async fn destination_is_private(url: &reqwest::Url) -> bool {
    let Some(host) = url.host_str() else {
        return true;
    };
    if let Ok(ip) = host.parse::<IpAddr>() {
        return is_private(&ip);
    }
    if host.eq_ignore_ascii_case("localhost") || host.to_ascii_lowercase().ends_with(".localhost") {
        return true;
    }
    let port = url.port_or_known_default().unwrap_or(443);
    match tokio::net::lookup_host((host, port)).await {
        Ok(addrs) => {
            let mut any = false;
            for addr in addrs {
                any = true;
                if is_private(&addr.ip()) {
                    return true;
                }
            }
            // A name that resolves to nothing is not worth connecting to.
            !any
        }
        Err(_) => true,
    }
}

/// Every URI a playlist mentions: the segment lines, and the `URI="..."` of
/// EXT-X-KEY, EXT-X-MAP, EXT-X-MEDIA and friends.
fn uris_in_playlist(body: &str) -> Vec<String> {
    let mut out = Vec::new();
    for line in body.lines() {
        let line = line.trim();
        if line.is_empty() {
            continue;
        }
        if !line.starts_with('#') {
            out.push(line.to_string());
            continue;
        }
        let mut rest = line;
        while let Some(at) = rest.find("URI=\"") {
            rest = &rest[at + 5..];
            match rest.find('"') {
                Some(end) => {
                    out.push(rest[..end].to_string());
                    rest = &rest[end + 1..];
                }
                None => break,
            }
        }
    }
    out
}

impl Hls {
    /// Begin a session for a station and return its id. The station's own
    /// origin is the only one trusted to start with.
    pub fn open(&self, url: &str) -> Option<String> {
        let parsed = reqwest::Url::parse(url.trim()).ok()?;
        let origin = origin_of(&parsed)?;
        let mut sessions = self.sessions.lock().unwrap();
        let now = Instant::now();
        sessions.retain(|_, s| now.duration_since(s.last_seen) < SESSION_IDLE);
        while sessions.len() >= MAX_SESSIONS {
            // Drop the least recently used rather than refusing to start.
            let Some(oldest) = sessions
                .iter()
                .min_by_key(|(_, s)| s.last_seen)
                .map(|(k, _)| k.clone())
            else {
                break;
            };
            sessions.remove(&oldest);
        }
        let id = session_id();
        sessions.insert(
            id.clone(),
            Session {
                origins: HashSet::from([origin]),
                last_seen: now,
                bytes: 0,
                requests: 0,
            },
        );
        Some(id)
    }

    pub fn close(&self, session: &str) {
        self.sessions.lock().unwrap().remove(session);
    }

    /// Is this session allowed to fetch this URL, and has it any budget left?
    fn admit(&self, session: &str, url: &reqwest::Url) -> Result<(), String> {
        let origin = origin_of(url).ok_or("that URL has no origin")?;
        let mut sessions = self.sessions.lock().unwrap();
        let Some(s) = sessions.get_mut(session) else {
            return Err("no such session".into());
        };
        if Instant::now().duration_since(s.last_seen) >= SESSION_IDLE {
            return Err("that session has expired".into());
        }
        if !s.origins.contains(&origin) {
            return Err(format!("{origin} is not an origin this session has seen"));
        }
        if s.bytes >= MAX_SESSION_BYTES {
            return Err("this session has used its whole budget".into());
        }
        s.requests += 1;
        s.last_seen = Instant::now();
        Ok(())
    }

    /// Record what a fetch taught us: where it ended up, and - if it handed
    /// back a playlist - which origins the next fetches will legitimately want.
    fn learn(&self, session: &str, base: &reqwest::Url, body: &[u8], read: usize) {
        let mut sessions = self.sessions.lock().unwrap();
        let Some(s) = sessions.get_mut(session) else {
            return;
        };
        s.bytes += read as u64;
        s.last_seen = Instant::now();
        if let Some(origin) = origin_of(base) {
            s.origins.insert(origin);
        }
        let head = &body[..body.len().min(8192)];
        if !head.starts_with(b"#EXTM3U") {
            return;
        }
        let text = String::from_utf8_lossy(body);
        for uri in uris_in_playlist(&text) {
            if let Ok(joined) = base.join(&uri) {
                if let Some(origin) = origin_of(&joined) {
                    s.origins.insert(origin);
                }
            }
        }
    }

    /// Fetch one playlist or segment for a session.
    pub async fn fetch(
        &self,
        session: &str,
        url: &str,
        range: Option<(u64, u64)>,
    ) -> Result<Fetched, String> {
        let parsed = reqwest::Url::parse(url.trim()).map_err(|e| format!("{url}: {e}"))?;
        if !matches!(parsed.scheme(), "http" | "https") {
            return Err("only http and https can be fetched".into());
        }
        self.admit(session, &parsed)?;
        if destination_is_private(&parsed).await {
            return Err("that address is not one to fetch on a page's say-so".into());
        }
        let fetched = stream::fetch_once(parsed.as_str(), range, MAX_BODY).await?;
        if let Ok(base) = reqwest::Url::parse(&fetched.final_url) {
            self.learn(session, &base, &fetched.body, fetched.body.len());
        }
        Ok(fetched)
    }
}
