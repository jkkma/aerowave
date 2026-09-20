//! Searching the radio-browser.info directory.
//!
//! radio-browser is a community-run catalogue of internet radio stations,
//! served by a handful of volunteer mirrors. The polite way to use it is to
//! ask which mirrors exist, pick one at random and stay on it for the run,
//! and to say who you are in the User-Agent. The directory client uses the
//! same application identity as the stream client.
//!
//! Nothing here writes to the station list. A search hands the webview a list
//! of candidates; saving one is a deliberate press of ADD, and what gets
//! saved is an ordinary station like any other.

use std::collections::{HashMap, HashSet};
use std::sync::{Arc, Mutex, OnceLock};
use std::time::Duration;

use aerowave_core::directory::{
    bitrate_band_by_key, clean_name, directory_failure_forgets_mirror, directory_host_candidates,
    directory_page_window, exact_tag_filter, first_tag, image_kind, is_country_code, is_hostname,
    playable_url, select_directory_host, sort_key, stream_key, stream_matches,
    tally_directory_facets, DirectoryFacetEntry, DirectoryFailure, BITRATE_BANDS, DIRECTORY_CODECS,
};
use aerowave_core::network::public_http_destination_allowed;
use base64::engine::general_purpose::STANDARD as BASE64;
use base64::Engine as _;
use futures_util::StreamExt;
use rand::seq::SliceRandom;
use serde::de::DeserializeOwned;
use serde::{Deserialize, Serialize};
use serde_json::Value;

use crate::stream::{describe, UA};

/// Round-robin DNS across every mirror: the one address that is always right.
const SERVERS_URL: &str = "https://all.api.radio-browser.info/json/servers";
/// Stood in for when the mirror list cannot be had. Always asking the same
/// mirror is a worse citizen than asking a chosen one, and a directory that
/// cannot be searched at all is worse still.
const FALLBACK_HOST: &str = "de1.api.radio-browser.info";
/// A page of results is tens of kilobytes. Anything running to megabytes is a
/// broken or hostile mirror, and buffering it whole would let it decide how
/// much memory this process uses.
const MAX_BODY_BYTES: usize = 4 * 1024 * 1024;
/// A facet tally is the exception: it reads a country's whole shelf, which for
/// a big one really is several megabytes.
const MAX_FACET_BYTES: usize = 32 * 1024 * 1024;
/// How many stations a tally may look at. The directory publishes no
/// per-country genre counts, so the only way to know what a country has is to
/// look at what is in it; beyond this the counts are a floor, not a total.
const FACET_LIMIT: u32 = 5000;
/// Genres to keep from a tally. A large country turns up two thousand distinct
/// tags, nearly all of them one station's own label - the same reason the
/// global list is trimmed.
const FACET_TAGS: usize = 200;
/// Results come back in name order, A to Z, and page that way too: a list
/// you are reading down is easier to find something in than one ranked by a
/// popularity you cannot see.
const ORDER: &str = "name";
/// A station logo is an icon: a few kilobytes as a rule, and the biggest ones
/// worth having are a couple of hundred. Past this it is not artwork, and it
/// has to be carried into the webview as text besides.
const MAX_LOGO_BYTES: usize = 512 * 1024;
/// Artwork addresses to try for one station. The directory offers several
/// where a station has been submitted more than once, and the first one is
/// often a link that has rotted - but a station with four dead ones has none.
const MAX_ART_TRIES: usize = 4;
/// Genres to offer. The directory holds tens of thousands of tags, almost all
/// of them one station's private label; the busiest couple of hundred are the
/// ones worth putting in a list, and they reach down to around 150 stations.
const TAG_LIMIT: u32 = 200;
/// Mirror discovery is only a preface to the useful request. Leave the rest
/// of the operation budget available for the documented fallback host when
/// the round-robin endpoint itself is slow.
const DISCOVERY_TIMEOUT: Duration = Duration::from_secs(10);
/// A failed mirror gets one more attempt after a short pause. Long enough not
/// to hammer a volunteer server, short enough that Browse still feels like a
/// single request.
const RETRY_BACKOFF: Duration = Duration::from_millis(250);

static DIRECTORY_CLIENT: OnceLock<DirectoryClient> = OnceLock::new();
static LOGO_CLIENT: OnceLock<reqwest::Client> = OnceLock::new();

type OriginResolver = Arc<dyn Fn(&str) -> String + Send + Sync>;

/// One directory session, including the mirror it has settled on.
///
/// The configurable endpoints are an internal seam for `directorycheck`: the
/// app constructs only the HTTPS production form below, while the example can
/// route validated pretend hostnames to loopback fixtures and exercise this
/// exact retry loop.
pub(crate) struct DirectoryClient {
    http: reqwest::Client,
    discovery_url: String,
    fallback_host: String,
    origin_for_host: OriginResolver,
    /// Staying with one host spreads load as requested and keeps paging
    /// coherent when mirrors are at slightly different points in their sync.
    host: Mutex<Option<String>>,
}

impl DirectoryClient {
    pub(crate) fn with_endpoints(
        discovery_url: impl Into<String>,
        fallback_host: impl Into<String>,
        origin_for_host: OriginResolver,
    ) -> Result<Self, String> {
        let fallback_host = fallback_host.into();
        if !is_hostname(&fallback_host) {
            return Err("the directory fallback is not a hostname".into());
        }
        let http = reqwest::Client::builder()
            .user_agent(UA)
            .connect_timeout(Duration::from_secs(8))
            .redirect(reqwest::redirect::Policy::limited(4))
            .build()
            .map_err(|error| error.to_string())?;
        Ok(Self {
            http,
            discovery_url: discovery_url.into(),
            fallback_host: fallback_host.to_ascii_lowercase(),
            origin_for_host,
            host: Mutex::new(None),
        })
    }

    /// Clear only the host that actually failed. A slow request to an old
    /// mirror must not erase a healthier mirror another request selected in
    /// the meantime.
    fn forget(&self, host: &str) {
        let mut slot = self.host.lock().unwrap();
        if slot
            .as_deref()
            .map(|current| current.eq_ignore_ascii_case(host))
            .unwrap_or(false)
        {
            *slot = None;
        }
    }

    async fn discover(&self, deadline: tokio::time::Instant) -> Vec<String> {
        let discovery_cap = tokio::time::Instant::now() + DISCOVERY_TIMEOUT;
        let discovery_deadline = if discovery_cap < deadline {
            discovery_cap
        } else {
            deadline
        };
        let response = match tokio::time::timeout_at(
            discovery_deadline,
            self.http.get(&self.discovery_url).send(),
        )
        .await
        {
            Ok(Ok(response)) if response.status().is_success() => response,
            _ => return Vec::new(),
        };
        let body = match tokio::time::timeout_at(
            discovery_deadline,
            read_capped(response, MAX_BODY_BYTES),
        )
        .await
        {
            Ok(Ok(body)) => body,
            _ => return Vec::new(),
        };
        let mirrors: Vec<Mirror> = match serde_json::from_str(&body) {
            Ok(mirrors) => mirrors,
            Err(_) => return Vec::new(),
        };
        let mut names =
            directory_host_candidates(mirrors.into_iter().filter_map(|mirror| mirror.name));
        names.shuffle(&mut rand::thread_rng());
        names
    }

    async fn host(&self, failed_host: Option<&str>, deadline: tokio::time::Instant) -> String {
        // Drop the std guard before discovery. Holding it across an await
        // would make the future unsendable, and Tauri requires Send futures.
        let known = self.host.lock().unwrap().clone();
        if let Some(known) = known {
            if failed_host
                .map(|failed| !known.eq_ignore_ascii_case(failed))
                .unwrap_or(true)
            {
                return known;
            }
        }

        let discovered = self.discover(deadline).await;
        let picked = select_directory_host(&discovered, failed_host, &self.fallback_host);

        // Searches racing through discovery must converge on the host already
        // chosen after their await. The sole exception is the exact failed
        // host when this discovery found a different mirror for the retry.
        let mut slot = self.host.lock().unwrap();
        match slot.as_ref() {
            Some(current)
                if failed_host
                    .map(|failed| current.eq_ignore_ascii_case(failed))
                    .unwrap_or(false)
                    && !current.eq_ignore_ascii_case(&picked) =>
            {
                *slot = Some(picked.clone());
                picked
            }
            Some(current) => current.clone(),
            None => {
                *slot = Some(picked.clone());
                picked
            }
        }
    }

    async fn request_json_once<T: DeserializeOwned>(
        &self,
        host: &str,
        path: &str,
        params: &[(&str, String)],
        deadline: tokio::time::Instant,
        cap: usize,
        description: &str,
    ) -> Result<T, DirectoryAttemptFailure> {
        // Hostnames remain validated even for the internal fixture seam. Only
        // the origin mapping changes; production always resolves to HTTPS.
        if !is_hostname(host) {
            return Err(DirectoryAttemptFailure::new(
                DirectoryFailure::Request,
                "radio-browser selected an invalid mirror hostname",
            ));
        }
        let origin = (self.origin_for_host)(host);
        let url = format!("{}/json/{path}", origin.trim_end_matches('/'));
        let response = tokio::time::timeout_at(deadline, self.http.get(url).query(params).send())
            .await
            .map_err(|_| {
                DirectoryAttemptFailure::new(
                    DirectoryFailure::Request,
                    "radio-browser request exceeded its total time limit",
                )
            })?
            .map_err(|error| {
                DirectoryAttemptFailure::new(
                    DirectoryFailure::Request,
                    format!("radio-browser: {}", describe(&error)),
                )
            })?;

        let status = response.status();
        if !status.is_success() {
            return Err(DirectoryAttemptFailure::new(
                DirectoryFailure::HttpStatus(status.as_u16()),
                format!("radio-browser answered {status} from {host}"),
            ));
        }

        let body = tokio::time::timeout_at(deadline, read_capped(response, cap))
            .await
            .map_err(|_| {
                DirectoryAttemptFailure::new(
                    DirectoryFailure::Body,
                    "radio-browser response exceeded its total time limit",
                )
            })?
            .map_err(|message| DirectoryAttemptFailure::new(DirectoryFailure::Body, message))?;
        serde_json::from_str(&body).map_err(|error| {
            DirectoryAttemptFailure::new(DirectoryFailure::Json, format!("{description}: {error}"))
        })
    }

    /// Fetch and decode one endpoint, retrying once when a mirror is
    /// transiently unavailable or returns an unreadable response. Discovery,
    /// both attempts, response bodies and the pause all share one deadline.
    pub(crate) async fn get_json<T: DeserializeOwned>(
        &self,
        path: &str,
        params: &[(&str, String)],
        budget: Duration,
        cap: usize,
        description: &str,
    ) -> Result<T, String> {
        let deadline = tokio::time::Instant::now() + budget;
        let mut failed_host: Option<String> = None;

        for attempt in 0..2 {
            let host = self.host(failed_host.as_deref(), deadline).await;
            if tokio::time::Instant::now() >= deadline {
                return Err("radio-browser request exceeded its total time limit".into());
            }
            match self
                .request_json_once(&host, path, params, deadline, cap, description)
                .await
            {
                Ok(value) => return Ok(value),
                Err(failure) => {
                    let retryable = directory_failure_forgets_mirror(failure.kind);
                    if retryable {
                        self.forget(&host);
                    }
                    if !retryable || attempt == 1 {
                        return Err(failure.message);
                    }
                    failed_host = Some(host);
                    if deadline.saturating_duration_since(tokio::time::Instant::now())
                        <= RETRY_BACKOFF
                    {
                        return Err(failure.message);
                    }
                    tokio::time::sleep(RETRY_BACKOFF).await;
                }
            }
        }
        unreachable!("the directory request loop has exactly two attempts")
    }
}

struct DirectoryAttemptFailure {
    kind: DirectoryFailure,
    message: String,
}

impl DirectoryAttemptFailure {
    fn new(kind: DirectoryFailure, message: impl Into<String>) -> Self {
        Self {
            kind,
            message: message.into(),
        }
    }
}

fn directory_client() -> Result<&'static DirectoryClient, String> {
    if let Some(existing) = DIRECTORY_CLIENT.get() {
        return Ok(existing);
    }
    let built = DirectoryClient::with_endpoints(
        SERVERS_URL,
        FALLBACK_HOST,
        Arc::new(|host| format!("https://{host}")),
    )?;
    Ok(DIRECTORY_CLIENT.get_or_init(|| built))
}

/// Artwork addresses come from a public directory and are therefore no more
/// trusted than HLS subresources. Reuse that path's checked DNS connector,
/// redirect policy and no-proxy rule so neither an address nor a redirect can
/// turn a logo load into a request to this machine or its private network.
fn logo_client() -> Result<reqwest::Client, String> {
    if let Some(existing) = LOGO_CLIENT.get() {
        return Ok(existing.clone());
    }
    let built = crate::hls::client_builder()
        .build()
        .map_err(|e| e.to_string())?;
    Ok(LOGO_CLIENT.get_or_init(|| built).clone())
}

#[derive(Deserialize, Debug, Default)]
#[serde(rename_all = "camelCase")]
pub struct Query {
    #[serde(default)]
    pub name: String,
    #[serde(default)]
    pub tag: String,
    /// ISO 3166-1 alpha-2, or empty for anywhere.
    #[serde(default)]
    pub country_code: String,
    /// Exactly what the directory calls a format - "MP3", "AAC+" - or empty
    /// for any of them. It matches the whole string, so AAC and AAC+ are two
    /// different answers rather than one being a sort of the other.
    #[serde(default)]
    pub codec: String,
    /// Which band of the bitrate dropdown a station has to fall in - a round
    /// number like "192", or "low" and "high" for the tails either side of
    /// them. Empty for any, which is also the only setting that keeps the many
    /// stations the directory has no bitrate on file for.
    #[serde(default)]
    pub bitrate: String,
    #[serde(default)]
    pub limit: u32,
    #[serde(default)]
    pub offset: u32,
}

/// A page of results, and how many entries the directory offered before the
/// tidying below dropped any. The next page starts at `offered` further in:
/// counting the survivors instead would walk the offset back over ground
/// already covered, and a page that lost one entry would look like the end.
#[derive(Serialize, Clone, Debug)]
#[serde(rename_all = "camelCase")]
pub struct Page {
    pub offered: u32,
    pub stations: Vec<BrowseStation>,
    /// Whether advancing by `offered` reaches a distinct page inside the
    /// catalogue window Browse deliberately exposes.
    pub has_more: bool,
}

/// What is actually available underneath the filter that is already set: the
/// genres in a chosen country, or the countries that have a chosen genre.
#[derive(Serialize, Clone, Debug)]
#[serde(rename_all = "camelCase")]
pub struct Facets {
    pub tags: Vec<Tag>,
    pub countries: Vec<Country>,
    /// The formats present, counted. Only the ones the format dropdown offers:
    /// the directory carries WMA and a long tail of others, and a filter whose
    /// every result is a station the player cannot open is not worth offering.
    pub codecs: Vec<Bucket>,
    /// One per band the dropdown offers, counted as the filter reads it -
    /// "192" is how many stations report exactly 192k, and "low" how many
    /// report anything under the lowest round number. The counts do not nest
    /// the way a floor's would, and they do not add up to the whole either:
    /// the directory is full of bitrates that are nobody's round number, and
    /// fuller still of stations it has no bitrate for at all.
    pub bitrates: Vec<Bucket>,
    /// Set when the directory had more stations than the tally was allowed to
    /// read, so every count below is a floor rather than the whole truth.
    pub sampled: bool,
}

/// A count against one fixed dropdown entry - a format, or a bitrate.
#[derive(Serialize, Clone, Debug)]
#[serde(rename_all = "camelCase")]
pub struct Bucket {
    /// Exactly the `value` of the option this belongs to.
    pub key: String,
    pub stations: u32,
}

/// One genre the directory has a useful number of stations under.
#[derive(Serialize, Clone, Debug)]
#[serde(rename_all = "camelCase")]
pub struct Tag {
    /// Exactly what the directory calls it, and what a filter must send back.
    pub value: String,
    /// The same thing, tidied for the list. Not interchangeable with `value`:
    /// a tag long enough to be capped would no longer match anything.
    pub name: String,
    pub stations: u32,
}

/// One country the directory has stations in.
#[derive(Serialize, Clone, Debug)]
#[serde(rename_all = "camelCase")]
pub struct Country {
    pub code: String,
    pub name: String,
    pub stations: u32,
}

/// A station's artwork: where it was found, and the picture itself.
///
/// Both, because the address is worth remembering on the station - so this
/// costs one lookup ever - while the picture is what actually goes on screen.
#[derive(Serialize, Clone, Debug)]
#[serde(rename_all = "camelCase")]
pub struct Art {
    pub url: String,
    pub picture: String,
}

/// One search result, cleaned up enough to show and to save.
#[derive(Serialize, Clone, Debug)]
#[serde(rename_all = "camelCase")]
pub struct BrowseStation {
    pub uuid: String,
    pub name: String,
    pub url: String,
    pub homepage: String,
    /// The first tag, ready to go into a saved station.
    pub tag: String,
    /// The whole list as the directory has it, for the line underneath.
    pub tags: String,
    pub country: String,
    pub codec: String,
    pub bitrate: u32,
    /// The station's own artwork, if it has any that could be played back.
    pub favicon: String,
    pub votes: i64,
    /// An HLS station. Flagged, not hidden, and no longer a warning: these
    /// play, through hls.js rather than by the webview itself.
    pub hls: bool,
}

/// The directory's own shape. Every field is optional and every number is
/// read loosely: mirrors run different versions of the API, and one field
/// arriving as a string is no reason to throw a whole page of stations away.
#[derive(Deserialize, Default)]
#[serde(default)]
struct Raw {
    stationuuid: Option<String>,
    name: Option<String>,
    url: Option<String>,
    url_resolved: Option<String>,
    homepage: Option<String>,
    tags: Option<String>,
    country: Option<String>,
    countrycode: Option<String>,
    favicon: Option<String>,
    codec: Option<String>,
    bitrate: Option<Value>,
    votes: Option<Value>,
    hls: Option<Value>,
}

#[derive(Deserialize, Default)]
#[serde(default)]
struct RawTag {
    name: Option<String>,
    stationcount: Option<Value>,
}

#[derive(Deserialize, Default)]
#[serde(default)]
struct RawCountry {
    name: Option<String>,
    iso_3166_1: Option<String>,
    stationcount: Option<Value>,
}

#[derive(Deserialize)]
struct Mirror {
    name: Option<String>,
}

fn number(value: &Option<Value>) -> i64 {
    match value {
        Some(Value::Number(n)) => n.as_i64().or_else(|| n.as_f64().map(|f| f as i64)),
        Some(Value::String(s)) => s.trim().parse().ok(),
        _ => None,
    }
    .unwrap_or(0)
}

fn text(value: &Option<String>) -> &str {
    value.as_deref().unwrap_or("")
}

/// Read a response body, refusing to buffer more than the cap.
async fn read_capped(response: reqwest::Response, cap: usize) -> Result<String, String> {
    let mut body: Vec<u8> = Vec::new();
    let mut stream = response.bytes_stream();
    while let Some(chunk) = stream.next().await {
        let chunk = chunk.map_err(|e| describe(&e))?;
        if body.len() + chunk.len() > cap {
            return Err("that mirror sent far more than a page of stations".into());
        }
        body.extend_from_slice(&chunk);
    }
    Ok(String::from_utf8_lossy(&body).into_owned())
}

/// The countries the directory has anything in, in the order a person would
/// look through them.
pub async fn countries() -> Result<Vec<Country>, String> {
    let raw: Vec<RawCountry> = directory_client()?
        .get_json(
            "countries",
            &[("hidebroken", "true".into())],
            Duration::from_secs(20),
            MAX_BODY_BYTES,
            "radio-browser sent an unreadable country list",
        )
        .await?;
    let mut out: Vec<Country> = raw
        .into_iter()
        .filter_map(|entry| {
            let code = clean_name(text(&entry.iso_3166_1), 2).to_ascii_uppercase();
            let name = clean_name(text(&entry.name), 60);
            let stations = number(&entry.stationcount).clamp(0, 10_000_000) as u32;
            // A country with nothing in it is a line to scroll past, not a
            // filter anybody wants.
            if !is_country_code(&code) || name.is_empty() || stations == 0 {
                return None;
            }
            Some(Country {
                code,
                name,
                stations,
            })
        })
        .collect();
    out.sort_by_key(|entry| sort_key(&entry.name));
    Ok(out)
}

/// The genres worth offering as a filter, in the order a person would look
/// through them.
pub async fn tags() -> Result<Vec<Tag>, String> {
    let raw: Vec<RawTag> = directory_client()?
        .get_json(
            "tags",
            &[
                ("hidebroken", "true".into()),
                ("order", "stationcount".into()),
                ("reverse", "true".into()),
                ("limit", TAG_LIMIT.to_string()),
            ],
            Duration::from_secs(20),
            MAX_BODY_BYTES,
            "radio-browser sent an unreadable genre list",
        )
        .await?;
    let mut out: Vec<Tag> = raw
        .into_iter()
        .filter_map(|entry| {
            let value = text(&entry.name).trim().to_string();
            let name = clean_name(&value, 40);
            let stations = number(&entry.stationcount).clamp(0, 10_000_000) as u32;
            if value.is_empty() || name.is_empty() || stations == 0 {
                return None;
            }
            Some(Tag {
                value,
                name,
                stations,
            })
        })
        .collect();
    out.sort_by_key(|entry| sort_key(&entry.name));
    Ok(out)
}

/// What the directory holds underneath one filter: the genres found in a
/// chosen country, and the countries that carry a chosen genre.
///
/// There is no endpoint for this - radio-browser publishes global tag and
/// country counts and nothing crossed - so the stations themselves are read
/// and tallied. That is a megabyte or two, which is why the webview asks only
/// when a filter actually changes, and remembers the answer.
/// The format and bitrate filters as the directory wants them. Both are left
/// out when unset: `codec=` empty asks for stations whose format is the empty
/// string, which is not what "any format" means.
fn quality_params(query: &Query, params: &mut Vec<(&'static str, String)>) {
    let codec = clean_name(&query.codec, 16);
    if !codec.is_empty() {
        params.push(("codec", codec));
    }
    // Always a floor and a ceiling together: the directory has no "equals"
    // filter, and a floor on its own hands back everything above the number
    // asked for. A round number sets both to itself; "low" and "high" are the
    // same pair of parameters with the two ends further apart.
    if let Some(band) = bitrate_band_by_key(query.bitrate.trim()) {
        params.push(("bitrateMin", band.min.to_string()));
        params.push(("bitrateMax", band.max.to_string()));
    }
}

pub async fn facets(query: Query) -> Result<Facets, String> {
    let mut params: Vec<(&str, String)> = vec![
        ("hidebroken", "true".into()),
        ("limit", FACET_LIMIT.to_string()),
        ("name", query.name.trim().to_string()),
    ];
    if let Some(tag) = exact_tag_filter(&query.tag) {
        params.push(("tag", tag.value));
        params.push(("tagExact", tag.exact.to_string()));
    }
    let code = query.country_code.trim().to_ascii_uppercase();
    if is_country_code(&code) {
        params.push(("countrycode", code));
    }
    // Counted under the format and bitrate as well. A country offered as
    // "Paraguay (68)" while the format filter leaves it four is worse than no
    // count at all: it promises stations that the next search cannot find.
    quality_params(&query, &mut params);

    let raw: Vec<Raw> = directory_client()?
        .get_json(
            "stations/search",
            &params,
            Duration::from_secs(60),
            MAX_FACET_BYTES,
            "radio-browser sent something unreadable",
        )
        .await?;
    let sampled = raw.len() as u32 >= FACET_LIMIT;

    let counted = tally_directory_facets(raw.iter().map(|entry| DirectoryFacetEntry {
        url: text(&entry.url),
        resolved_url: text(&entry.url_resolved),
        name: text(&entry.name),
        tags: text(&entry.tags),
        country_code: text(&entry.countrycode),
        country_name: text(&entry.country),
        codec: text(&entry.codec),
        bitrate: number(&entry.bitrate).clamp(0, u32::MAX as i64) as u32,
    }));

    // Busiest first to decide what makes the cut, then alphabetical to read.
    let mut tags: Vec<Tag> = counted
        .tags
        .into_iter()
        .map(|(value, stations)| Tag {
            name: clean_name(&value, 40),
            value,
            stations,
        })
        .filter(|tag| !tag.name.is_empty())
        .collect();
    tags.sort_by_key(|tag| std::cmp::Reverse(tag.stations));
    tags.truncate(FACET_TAGS);
    tags.sort_by_key(|tag| sort_key(&tag.name));

    let mut countries: Vec<Country> = counted
        .countries
        .into_iter()
        .map(|(code, (name, stations))| Country {
            code,
            name,
            stations,
        })
        .collect();
    countries.sort_by_key(|country| sort_key(&country.name));

    let codecs = DIRECTORY_CODECS
        .iter()
        .zip(counted.codecs)
        .map(|(key, stations)| Bucket {
            key: key.to_string(),
            stations,
        })
        .collect();
    let bitrates = BITRATE_BANDS
        .iter()
        .zip(counted.bitrates)
        .map(|(band, stations)| Bucket {
            key: band.key.to_string(),
            stations,
        })
        .collect();

    Ok(Facets {
        tags,
        countries,
        codecs,
        bitrates,
        sampled,
    })
}

#[derive(Debug, Serialize)]
pub struct LogoFailure {
    pub message: String,
    pub retryable: bool,
}

impl LogoFailure {
    fn permanent(message: impl Into<String>) -> Self {
        Self {
            message: message.into(),
            retryable: false,
        }
    }

    fn retryable(message: impl Into<String>) -> Self {
        Self {
            message: message.into(),
            retryable: true,
        }
    }
}

/// Fetch a station's artwork and hand it back as a data URL.
///
/// It has to come through here rather than being loaded by the webview: the
/// content security policy allows no remote images, and even without it a
/// broadcaster's logo host does not send the CORS headers WebGL wants before
/// it will accept a cross-origin picture as a texture. Reading it here and
/// passing the bytes along inline sidesteps both.
async fn fetch_logo(url: &str) -> Result<String, LogoFailure> {
    let url = reqwest::Url::parse(url.trim())
        .map_err(|_| LogoFailure::permanent("that is not an http address"))?;
    if !public_http_destination_allowed(url.scheme(), url.host_str().unwrap_or("")) {
        return Err(LogoFailure::permanent(
            "that logo address is not a public HTTP address",
        ));
    }

    let response = logo_client()
        .map_err(LogoFailure::retryable)?
        .get(url)
        .timeout(Duration::from_secs(10))
        .send()
        .await
        .map_err(|e| {
            let message = describe(&e);
            let detail = message.to_ascii_lowercase();
            if e.is_redirect() || detail.contains("non-public address") {
                LogoFailure::permanent(message)
            } else {
                LogoFailure::retryable(message)
            }
        })?;
    if !response.status().is_success() {
        let status = response.status();
        let message = format!("the logo host answered {status}");
        return Err(
            if status.is_server_error()
                || status == reqwest::StatusCode::REQUEST_TIMEOUT
                || status == reqwest::StatusCode::TOO_MANY_REQUESTS
            {
                LogoFailure::retryable(message)
            } else {
                LogoFailure::permanent(message)
            },
        );
    }

    let mut bytes: Vec<u8> = Vec::new();
    let mut stream = response.bytes_stream();
    while let Some(chunk) = stream.next().await {
        let chunk = chunk.map_err(|e| LogoFailure::retryable(describe(&e)))?;
        if bytes.len() + chunk.len() > MAX_LOGO_BYTES {
            return Err(LogoFailure::permanent(
                "that is far larger than a station logo",
            ));
        }
        bytes.extend_from_slice(&chunk);
    }

    // By what it is, not by what it was labelled: half of these are served as
    // octet-stream, and a fair few links have rotted into an error page.
    let kind = image_kind(&bytes)
        .ok_or_else(|| LogoFailure::permanent("that address is not a picture"))?;
    Ok(format!("data:{kind};base64,{}", BASE64.encode(&bytes)))
}

pub async fn logo(url: &str) -> Result<String, LogoFailure> {
    fetch_logo(url).await
}

/// Artwork submitted for one exact stream URL, still carrying both URL forms
/// long enough to verify the directory did not answer with a namesake.
async fn art_candidates(
    query_url: &str,
    requested_url: &str,
    requested_resolved: &str,
) -> Result<Vec<String>, String> {
    let params = &[("url", query_url.to_string())];
    let raw: Vec<Raw> = directory_client()?
        .get_json(
            "stations/byurl",
            params,
            Duration::from_secs(20),
            MAX_BODY_BYTES,
            "radio-browser sent something unreadable",
        )
        .await?;
    Ok(raw
        .iter()
        .filter(|entry| {
            stream_matches(
                text(&entry.url),
                text(&entry.url_resolved),
                requested_url,
                requested_resolved,
            )
        })
        .filter_map(|entry| playable_url(text(&entry.favicon), ""))
        .collect())
}

/// Find artwork only on directory entries for this exact stream.
///
/// The submitted and resolved addresses are both searched and matched. A
/// station name is not an identity: the directory contains many unrelated
/// broadcasters called "Radio One", and borrowing the first one's logo is
/// worse than leaving the crystal bare. Previously failed or decode-rejected
/// addresses may be excluded so another submission for the stream can win.
pub async fn art(
    url: &str,
    resolved_url: &str,
    excluded_urls: &[String],
) -> Result<Option<Art>, String> {
    let requested_url = url.trim();
    let requested_resolved = resolved_url.trim();
    let mut queries: Vec<String> = Vec::new();
    for candidate in [requested_url, requested_resolved] {
        let Some(candidate) = playable_url(candidate, "") else {
            continue;
        };
        if !queries.iter().any(|seen| seen == &candidate) {
            queries.push(candidate);
        }
    }
    if queries.is_empty() {
        return Ok(None);
    }

    let excluded: HashSet<&str> = excluded_urls
        .iter()
        .map(|url| url.trim())
        .filter(|url| !url.is_empty())
        .collect();
    let mut candidates: Vec<String> = Vec::new();
    let mut lookup_failure: Option<String> = None;
    for query in queries {
        match art_candidates(&query, requested_url, requested_resolved).await {
            Ok(found) => candidates.extend(found),
            Err(error) => {
                if lookup_failure.is_none() {
                    lookup_failure = Some(error);
                }
            }
        }
    }

    let mut tried: HashSet<String> = HashSet::new();
    let mut fetch_failure: Option<String> = None;
    for favicon in candidates {
        if excluded.contains(favicon.trim()) || tried.contains(&favicon) {
            continue;
        }
        if tried.len() >= MAX_ART_TRIES {
            break;
        }
        tried.insert(favicon.clone());
        match fetch_logo(&favicon).await {
            Ok(picture) => {
                return Ok(Some(Art {
                    url: favicon,
                    picture,
                }));
            }
            Err(failure) if failure.retryable => {
                if fetch_failure.is_none() {
                    fetch_failure = Some(failure.message);
                }
            }
            Err(_) => {}
        }
    }

    if let Some(error) = fetch_failure.or(lookup_failure) {
        Err(error)
    } else {
        Ok(None)
    }
}

pub async fn search(query: Query) -> Result<Page, String> {
    let page = directory_page_window(query.limit, query.offset);
    let mut params: Vec<(&str, String)> = vec![
        // Stations the directory's own checker cannot reach are not worth
        // offering: everything here is meant to be pressed and heard.
        ("hidebroken", "true".into()),
        ("order", ORDER.into()),
        ("reverse", "false".into()),
        ("limit", page.limit.to_string()),
        // A catalogue this size is for searching, not for reading to the end.
        ("offset", page.offset.to_string()),
        ("name", query.name.trim().to_string()),
    ];
    if let Some(tag) = exact_tag_filter(&query.tag) {
        params.push(("tag", tag.value));
        params.push(("tagExact", tag.exact.to_string()));
    }
    // Sent only when it is really a country code. A filter the directory
    // cannot make sense of would quietly return nothing at all.
    let code = query.country_code.trim().to_ascii_uppercase();
    if is_country_code(&code) {
        params.push(("countrycode", code));
    }
    quality_params(&query, &mut params);

    let raw: Vec<Raw> = directory_client()?
        .get_json(
            "stations/search",
            &params,
            Duration::from_secs(20),
            MAX_BODY_BYTES,
            "radio-browser sent something unreadable",
        )
        .await?;
    let offered = raw.len() as u32;
    let has_more = page.has_more(offered);

    // One row per stream. Which submission gets to be that row is decided by
    // votes rather than by whichever the directory sorted first: ordered by
    // name, radio.plaza.one came back as "Nightwave Plaza" with 171 votes and
    // its other submission, "Vaporwave" with 379, was the one thrown away -
    // so the station could not be found under the name most people file it
    // under. The row keeps the place the stream first appeared, so the page
    // stays in the order the directory paged it.
    let mut order: Vec<String> = Vec::new();
    let mut best: HashMap<String, BrowseStation> = HashMap::new();
    for entry in raw {
        let Some(url) = playable_url(text(&entry.url), text(&entry.url_resolved)) else {
            continue;
        };
        let name = clean_name(text(&entry.name), 60);
        if name.is_empty() {
            continue;
        }

        let tags = clean_name(text(&entry.tags), 120);
        let station = BrowseStation {
            uuid: entry.stationuuid.unwrap_or_default(),
            name,
            url,
            homepage: clean_name(text(&entry.homepage), 200),
            tag: first_tag(&tags, 24),
            tags,
            country: clean_name(text(&entry.country), 40),
            // http(s) only, and by the same rule a stream URL is held to: the
            // directory carries `file:` and worse in this field.
            favicon: playable_url(text(&entry.favicon), "").unwrap_or_default(),
            codec: clean_name(text(&entry.codec), 12),
            bitrate: number(&entry.bitrate).clamp(0, 100_000) as u32,
            votes: number(&entry.votes),
            hls: number(&entry.hls) != 0,
        };

        let key = stream_key(&station.url);
        match best.get_mut(&key) {
            Some(kept) if kept.votes >= station.votes => {}
            Some(kept) => *kept = station,
            None => {
                order.push(key.clone());
                best.insert(key, station);
            }
        }
    }
    let stations = order.iter().filter_map(|key| best.remove(key)).collect();
    Ok(Page {
        offered,
        stations,
        has_more,
    })
}
