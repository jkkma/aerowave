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
use std::sync::{Arc, Mutex, OnceLock};
use std::time::{Duration, Instant};

use aerowave_core::hls::{parse_playlist, playlist_identity, Variables};
use aerowave_core::network::{public_addresses, public_http_destination_allowed};
use rand::Rng;

use crate::stream;

/// One segment. Radio segments run to a few hundred kB; this is slack.
const MAX_BODY: usize = 16 * 1024 * 1024;
/// A session's whole traffic budget. At 128 kbps a listener uses about 58 MB
/// an hour, so this is a long evening and then some.
const MAX_SESSION_BYTES: u64 = 2 * 1024 * 1024 * 1024;
const MAX_SESSIONS: usize = 8;
const MAX_ORIGINS: usize = 4096;
const MAX_VARIANT_URLS: usize = 4096;
/// A session nobody has fetched from in this long is finished with.
const SESSION_IDLE: Duration = Duration::from_secs(600);
static CLIENT: OnceLock<reqwest::Client> = OnceLock::new();

struct Session {
    /// Origins this session may fetch from. Seeded with the station's own,
    /// then grown only from what we have observed ourselves.
    origins: HashSet<String>,
    manifest: reqwest::Url,
    variables: Variables,
    variants: HashSet<reqwest::Url>,
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

/// The connector receives exactly the addresses checked here. Checking with
/// a separate lookup before reqwest connects would permit DNS rebinding.
pub(crate) struct PublicDns;

impl reqwest::dns::Resolve for PublicDns {
    fn resolve(&self, name: reqwest::dns::Name) -> reqwest::dns::Resolving {
        Box::pin(async move {
            let addrs: Vec<_> = tokio::time::timeout(
                Duration::from_secs(8),
                tokio::net::lookup_host((name.as_str(), 0)),
            )
            .await??
            .collect();
            if !public_addresses(&addrs) {
                return Err("that hostname resolves to a non-public address".into());
            }
            Ok(Box::new(addrs.into_iter()) as reqwest::dns::Addrs)
        })
    }
}

pub(crate) fn client_builder() -> reqwest::ClientBuilder {
    reqwest::Client::builder()
        .user_agent(stream::UA)
        .connect_timeout(Duration::from_secs(8))
        .read_timeout(stream::RELAY_READ_TIMEOUT)
        // A proxy would resolve and connect on our behalf, bypassing the
        // checked DNS answers and potentially reaching its own local network.
        .no_proxy()
        .dns_resolver(Arc::new(PublicDns))
        .redirect(reqwest::redirect::Policy::custom(|attempt| {
            let url = attempt.url();
            if !public_http_destination_allowed(url.scheme(), url.host_str().unwrap_or("")) {
                attempt.error("that redirect does not lead to a public HTTP address")
            } else if attempt.previous().len() >= 6 {
                attempt.error("too many HLS redirects")
            } else {
                attempt.follow()
            }
        }))
}

fn client() -> Result<reqwest::Client, String> {
    if let Some(existing) = CLIENT.get() {
        return Ok(existing.clone());
    }
    let built = client_builder().build().map_err(|e| e.to_string())?;
    Ok(CLIENT.get_or_init(|| built).clone())
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
                manifest: playlist_identity(&parsed),
                variables: Variables::new(),
                variants: HashSet::new(),
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
    fn learn(
        &self,
        session: &str,
        requested: &reqwest::Url,
        base: &reqwest::Url,
        body: &[u8],
        successful: bool,
    ) {
        let mut sessions = self.sessions.lock().unwrap();
        let Some(s) = sessions.get_mut(session) else {
            return;
        };
        s.bytes += body.len() as u64;
        s.last_seen = Instant::now();
        if !successful {
            return;
        }
        s.learn_origin(base);
        if !body.starts_with(b"#EXTM3U") {
            return;
        }
        let Ok(text) = std::str::from_utf8(body) else {
            return;
        };
        let requested = playlist_identity(requested);
        let manifest = requested == s.manifest;
        let variant = !manifest && s.variants.contains(&requested);
        // Only declared variants can import the root's definitions. Query
        // values can name origins only for the root and its declared variants,
        // since the caller chooses the query on other admitted requests.
        let inherited = variant.then_some(&s.variables);
        let Some(playlist) = parse_playlist(text, base, inherited, manifest || variant) else {
            return;
        };
        if manifest {
            s.variables = playlist.variables;
            s.variants.clear();
        } else if variant && s.variants.len() < MAX_VARIANT_URLS {
            s.variants.insert(playlist_identity(base));
        }
        for resource in playlist.resources {
            s.learn_origin(&resource.url);
            if manifest && resource.variant && s.variants.len() < MAX_VARIANT_URLS {
                s.variants.insert(playlist_identity(&resource.url));
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
        if !public_http_destination_allowed(parsed.scheme(), parsed.host_str().unwrap_or("")) {
            return Err("that address is not one to fetch on a page's say-so".into());
        }
        let fetched = stream::fetch_once(&client()?, parsed.as_str(), range, MAX_BODY).await?;
        if let Ok(base) = reqwest::Url::parse(&fetched.final_url) {
            self.learn(
                session,
                &parsed,
                &base,
                &fetched.body,
                (200..300).contains(&fetched.status),
            );
        }
        Ok(fetched)
    }
}

impl Session {
    fn learn_origin(&mut self, url: &reqwest::Url) {
        if self.origins.len() < MAX_ORIGINS
            && public_http_destination_allowed(url.scheme(), url.host_str().unwrap_or(""))
        {
            if let Some(origin) = origin_of(url) {
                self.origins.insert(origin);
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn learns_only_the_expanded_variant_origin_from_a_playlist() {
        let hls = Hls::default();
        let base = reqwest::Url::parse("https://station.example/live.m3u8").unwrap();
        let session = hls.open(base.as_str()).unwrap();
        let expanded = reqwest::Url::parse("https://cdn.example/audio.m3u8").unwrap();
        let unrelated = reqwest::Url::parse("https://unrelated.example/audio.m3u8").unwrap();
        assert!(hls.admit(&session, &expanded).is_err());

        let playlist = b"#EXTM3U\n#EXT-X-DEFINE:NAME=\"cdn\",VALUE=\"https://cdn.example\"\n#EXT-X-STREAM-INF:BANDWIDTH=128000\n{$cdn}/audio.m3u8\n";
        hls.learn(&session, &base, &base, playlist, true);
        assert!(hls.admit(&session, &expanded).is_ok());
        assert!(hls.admit(&session, &unrelated).is_err());
    }

    #[test]
    fn imports_are_scoped_to_declared_variants_and_their_redirects() {
        let hls = Hls::default();
        let manifest = reqwest::Url::parse("https://station.example/live.m3u8").unwrap();
        let session = hls.open(manifest.as_str()).unwrap();
        let master = b"#EXTM3U\n#EXT-X-DEFINE:NAME=\"cdn\",VALUE=\"https://cdn.example\"\n#EXT-X-DEFINE:NAME=\"keys\",VALUE=\"https://keys.example\"\n#EXT-X-STREAM-INF:BANDWIDTH=128000\n/audio.m3u8?token=a%2Fb\n";
        hls.learn(&session, &manifest, &manifest, master, true);

        let unrelated = manifest.join("/audio.m3u8?token=other").unwrap();
        let child = b"#EXTM3U\n#EXT-X-DEFINE:IMPORT=\"cdn\"\n#EXTINF:4,\n{$cdn}/1.ts\n";
        let segment = reqwest::Url::parse("https://cdn.example/1.ts").unwrap();
        hls.learn(&session, &unrelated, &unrelated, child, true);
        assert!(hls.admit(&session, &segment).is_err());

        let variant = manifest.join("/audio.m3u8?token=a%2Fb&_HLS_msn=9").unwrap();
        let redirected = reqwest::Url::parse("https://edge.example/audio.m3u8").unwrap();
        hls.learn(&session, &variant, &redirected, child, false);
        assert!(hls.admit(&session, &segment).is_err());
        hls.learn(&session, &variant, &redirected, child, true);
        assert!(hls.admit(&session, &segment).is_ok());

        let reload = b"#EXTM3U\n#EXT-X-DEFINE:IMPORT=\"keys\"\n#EXT-X-KEY:METHOD=AES-128,URI=\"{$keys}/key\"\n";
        hls.learn(&session, &redirected, &redirected, reload, true);
        assert!(hls
            .admit(
                &session,
                &reqwest::Url::parse("https://keys.example/key").unwrap()
            )
            .is_ok());
    }

    #[test]
    fn query_variables_require_a_declared_playlist_url() {
        let hls = Hls::default();
        let manifest = reqwest::Url::parse(
            "https://station.example/live.m3u8?cdn=https%3A%2F%2Fexpected.example",
        )
        .unwrap();
        let session = hls.open(manifest.as_str()).unwrap();
        let body = b"#EXTM3U\n#EXT-X-DEFINE:QUERYPARAM=\"cdn\"\n#EXTINF:4,\n{$cdn}/part.ts\n";
        let unrelated = manifest
            .join("/other.m3u8?cdn=https%3A%2F%2Fcaller-chosen.example")
            .unwrap();
        assert!(hls.admit(&session, &unrelated).is_ok());
        hls.learn(&session, &unrelated, &unrelated, body, true);
        let arbitrary = reqwest::Url::parse("https://caller-chosen.example/part.ts").unwrap();
        assert!(hls.admit(&session, &arbitrary).is_err());

        hls.learn(&session, &manifest, &manifest, body, true);
        let expected = reqwest::Url::parse("https://expected.example/part.ts").unwrap();
        assert!(hls.admit(&session, &expected).is_ok());
    }

    #[test]
    fn reload_directives_cannot_supply_variable_origins() {
        let hls = Hls::default();
        let manifest = reqwest::Url::parse("https://station.example/live.m3u8").unwrap();
        let session = hls.open(manifest.as_str()).unwrap();
        let reload = manifest
            .join("?_HLS_msn=https%3A%2F%2Fcaller-chosen.example")
            .unwrap();
        let body = b"#EXTM3U\n#EXT-X-DEFINE:QUERYPARAM=\"_HLS_msn\"\n#EXTINF:4,\n{$_HLS_msn}/part.ts\n";
        hls.learn(&session, &reload, &reload, body, true);
        let arbitrary = reqwest::Url::parse("https://caller-chosen.example/part.ts").unwrap();
        assert!(hls.admit(&session, &arbitrary).is_err());
    }
}
