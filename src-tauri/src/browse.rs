//! Searching the radio-browser.info directory.
//!
//! radio-browser is a community-run catalogue of internet radio stations,
//! served by a handful of volunteer mirrors. The polite way to use it is to
//! ask which mirrors exist, pick one at random and stay on it for the run,
//! and to say who you are in the User-Agent - which `stream`'s client, shared
//! with this module, already does.
//!
//! Nothing here writes to the station list. A search hands the webview a list
//! of candidates; saving one is a deliberate press of ADD, and what gets
//! saved is an ordinary station like any other.

use std::collections::{HashMap, HashSet};
use std::sync::{Mutex, OnceLock};
use std::time::Duration;

use aerowave_core::directory::{
    bitrate_band_by_key, bitrate_band_index, clean_name, first_tag, image_kind, is_country_code,
    is_hostname, playable_url, sort_key, stream_key, BITRATE_BANDS,
};
use base64::engine::general_purpose::STANDARD as BASE64;
use base64::Engine as _;
use futures_util::StreamExt;
use rand::seq::SliceRandom;
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

/// The mirror this run settled on. Staying with one spreads the load the way
/// the directory asks, and keeps paging honest: mirrors sync on their own
/// schedule, so a second page from another one can repeat half of the first.
static HOST: Mutex<Option<String>> = Mutex::new(None);
static CLIENT: OnceLock<reqwest::Client> = OnceLock::new();

/// The directory gets its own client rather than borrowing `stream`'s.
///
/// That one sets a five-second read timeout, which is right for a radio
/// server - it should start talking at once - and wrong for a mirror running
/// a five-thousand-row query before it writes a status line. reqwest applies
/// that timeout to the wait for the response head, so sharing the client
/// would quietly cap every request here at five seconds no matter what the
/// per-request budget below says.
fn client() -> Result<reqwest::Client, String> {
    if let Some(existing) = CLIENT.get() {
        return Ok(existing.clone());
    }
    let built = reqwest::Client::builder()
        .user_agent(UA)
        .connect_timeout(Duration::from_secs(8))
        .read_timeout(Duration::from_secs(30))
        .redirect(reqwest::redirect::Policy::limited(4))
        .build()
        .map_err(|e| e.to_string())?;
    Ok(CLIENT.get_or_init(|| built).clone())
}

/// Stop asking a mirror that would not answer. Without this one bad draw -
/// or one momentary network gap at startup - sticks for the whole run and
/// every search, dropdown and tally after it fails the same way.
fn forget(host: &str) {
    let mut slot = HOST.lock().unwrap();
    if slot.as_deref() == Some(host) {
        *slot = None;
    }
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

/// The formats the dropdown offers, matched whole against what the directory
/// reports - "AAC" and "AAC+" are two different answers, not one with a suffix.
const CODECS: [&str; 5] = ["MP3", "AAC", "AAC+", "OGG", "FLAC"];

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

/// Ask which mirrors are up, and pick one.
async fn discover() -> Option<String> {
    let response = client()
        .ok()?
        .get(SERVERS_URL)
        .timeout(Duration::from_secs(10))
        .send()
        .await
        .ok()?;
    if !response.status().is_success() {
        return None;
    }
    let body = read_capped(response, MAX_BODY_BYTES).await.ok()?;
    let mirrors: Vec<Mirror> = serde_json::from_str(&body).ok()?;
    let names: Vec<String> = mirrors
        .into_iter()
        .filter_map(|m| m.name)
        .filter(|name| is_hostname(name))
        .collect();
    names.choose(&mut rand::thread_rng()).cloned()
}

/// The mirror to talk to: discovered once, then remembered.
async fn host() -> String {
    // Cloned out and the lock dropped before the await. A std guard held
    // across one makes the whole future unsendable, and Tauri wants it sent.
    let known = HOST.lock().unwrap().clone();
    if let Some(host) = known {
        return host;
    }
    let picked = discover().await.unwrap_or_else(|| FALLBACK_HOST.to_string());
    // Two searches racing must still end up on one mirror: first past the
    // post decides, and the other takes that answer rather than its own.
    let mut slot = HOST.lock().unwrap();
    slot.get_or_insert(picked).clone()
}

/// Ask the directory for one of its JSON endpoints and hand back the body.
async fn get(
    path: &str,
    params: &[(&str, String)],
    seconds: u64,
    cap: usize,
) -> Result<String, String> {
    let client = client()?;
    let host = host().await;
    let response = client
        .get(format!("https://{host}/json/{path}"))
        .query(params)
        .timeout(Duration::from_secs(seconds))
        .send()
        .await
        .map_err(|e| {
            forget(&host);
            format!("radio-browser: {}", describe(&e))
        })?;
    if !response.status().is_success() {
        // A mirror having a bad day, rather than a question it refused: worth
        // asking somebody else next time.
        if response.status().is_server_error() {
            forget(&host);
        }
        return Err(format!(
            "radio-browser answered {} from {host}",
            response.status()
        ));
    }
    read_capped(response, cap).await
}

/// The countries the directory has anything in, in the order a person would
/// look through them.
pub async fn countries() -> Result<Vec<Country>, String> {
    let body = get(
        "countries",
        &[("hidebroken", "true".into())],
        20,
        MAX_BODY_BYTES,
    )
    .await?;
    let raw: Vec<RawCountry> = serde_json::from_str(&body)
        .map_err(|e| format!("radio-browser sent an unreadable country list: {e}"))?;
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
    let body = get(
        "tags",
        &[
            ("hidebroken", "true".into()),
            ("order", "stationcount".into()),
            ("reverse", "true".into()),
            ("limit", TAG_LIMIT.to_string()),
        ],
        20,
        MAX_BODY_BYTES,
    )
    .await?;
    let raw: Vec<RawTag> = serde_json::from_str(&body)
        .map_err(|e| format!("radio-browser sent an unreadable genre list: {e}"))?;
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
        ("tag", query.tag.trim().to_string()),
    ];
    let code = query.country_code.trim().to_ascii_uppercase();
    if is_country_code(&code) {
        params.push(("countrycode", code));
    }
    // Counted under the format and bitrate as well. A country offered as
    // "Paraguay (68)" while the format filter leaves it four is worse than no
    // count at all: it promises stations that the next search cannot find.
    quality_params(&query, &mut params);

    let body = get("stations/search", &params, 60, MAX_FACET_BYTES).await?;
    let raw: Vec<Raw> = serde_json::from_str(&body)
        .map_err(|e| format!("radio-browser sent something unreadable: {e}"))?;
    let sampled = raw.len() as u32 >= FACET_LIMIT;

    let mut tag_counts: HashMap<String, u32> = HashMap::new();
    let mut country_counts: HashMap<String, (String, u32)> = HashMap::new();
    let mut codec_counts = [0u32; CODECS.len()];
    let mut bitrate_counts = [0u32; BITRATE_BANDS.len()];
    // Count what the search would actually show, not what the directory holds.
    // The same station is submitted more than once all the time - Albania's two
    // AAC+ stations were "Radio One - Tirana 95.2 FM" and "RadioOne", the same
    // stream twice - and a station with no name or an unplayable address never
    // reaches a row either. A count that promises two and delivers one is the
    // thing these counts exist to avoid.
    let mut seen: HashSet<String> = HashSet::new();
    for entry in &raw {
        let Some(url) = playable_url(text(&entry.url), text(&entry.url_resolved)) else {
            continue;
        };
        if clean_name(text(&entry.name), 60).is_empty() {
            continue;
        }
        if !seen.insert(stream_key(&url)) {
            continue;
        }

        let codec = clean_name(text(&entry.codec), 16).to_ascii_uppercase();
        if let Some(slot) = CODECS.iter().position(|known| *known == codec) {
            codec_counts[slot] += 1;
        }
        if let Some(slot) = bitrate_band_index(number(&entry.bitrate) as u32) {
            bitrate_counts[slot] += 1;
        }
        for tag in text(&entry.tags).split(',') {
            let value = tag.trim();
            if !value.is_empty() {
                *tag_counts.entry(value.to_string()).or_default() += 1;
            }
        }
        let code = clean_name(text(&entry.countrycode), 2).to_ascii_uppercase();
        let name = clean_name(text(&entry.country), 60);
        if is_country_code(&code) && !name.is_empty() {
            let slot = country_counts.entry(code).or_insert((name, 0));
            slot.1 += 1;
        }
    }

    // Busiest first to decide what makes the cut, then alphabetical to read.
    let mut tags: Vec<Tag> = tag_counts
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

    let mut countries: Vec<Country> = country_counts
        .into_iter()
        .map(|(code, (name, stations))| Country {
            code,
            name,
            stations,
        })
        .collect();
    countries.sort_by_key(|country| sort_key(&country.name));

    let codecs = CODECS
        .iter()
        .zip(codec_counts)
        .map(|(key, stations)| Bucket {
            key: key.to_string(),
            stations,
        })
        .collect();
    let bitrates = BITRATE_BANDS
        .iter()
        .zip(bitrate_counts)
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

/// Fetch a station's artwork and hand it back as a data URL.
///
/// It has to come through here rather than being loaded by the webview: the
/// content security policy allows no remote images, and even without it a
/// broadcaster's logo host does not send the CORS headers WebGL wants before
/// it will accept a cross-origin picture as a texture. Reading it here and
/// passing the bytes along inline sidesteps both.
pub async fn logo(url: &str) -> Result<String, String> {
    let url = playable_url(url, "").ok_or("that is not an http address")?;
    let response = client()?
        .get(&url)
        .timeout(Duration::from_secs(10))
        .send()
        .await
        .map_err(|e| describe(&e))?;
    if !response.status().is_success() {
        return Err(format!("the logo host answered {}", response.status()));
    }

    let mut bytes: Vec<u8> = Vec::new();
    let mut stream = response.bytes_stream();
    while let Some(chunk) = stream.next().await {
        let chunk = chunk.map_err(|e| describe(&e))?;
        if bytes.len() + chunk.len() > MAX_LOGO_BYTES {
            return Err("that is far larger than a station logo".into());
        }
        bytes.extend_from_slice(&chunk);
    }

    // By what it is, not by what it was labelled: half of these are served as
    // octet-stream, and a fair few links have rotted into an error page.
    let kind = image_kind(&bytes).ok_or("that address is not a picture")?;
    Ok(format!("data:{kind};base64,{}", BASE64.encode(&bytes)))
}

/// The artwork addresses one directory search offers, in the order given.
async fn art_candidates(path: &str, params: &[(&str, String)]) -> Vec<String> {
    let Ok(body) = get(path, params, 20, MAX_BODY_BYTES).await else {
        return Vec::new();
    };
    let Ok(raw) = serde_json::from_str::<Vec<Raw>>(&body) else {
        return Vec::new();
    };
    raw.iter()
        .filter_map(|entry| playable_url(text(&entry.favicon), ""))
        .collect()
}

/// Find a station's artwork in the directory, for one that has none on file.
///
/// Stations kept from BROWSE arrive with their own; the ones this app ships
/// with, and anything typed in by hand, do not. Asked in order of how sure
/// the answer is: the exact stream first, then the exact name, then the
/// closest name the directory knows. Every candidate is fetched before it is
/// offered, so an address that has rotted - and plenty have - is passed over
/// rather than remembered as this station's picture.
pub async fn art(name: &str, url: &str) -> Result<Option<Art>, String> {
    let name = clean_name(name, 60);
    let url = url.trim().to_string();
    let listing = |extra: Vec<(&'static str, String)>| {
        let mut params: Vec<(&'static str, String)> = vec![
            ("hidebroken", "true".into()),
            ("order", "votes".into()),
            ("reverse", "true".into()),
            ("limit", "20".into()),
        ];
        params.extend(extra);
        params
    };

    let mut searches: Vec<(&str, Vec<(&'static str, String)>)> = Vec::new();
    if !url.is_empty() {
        searches.push(("stations/byurl", vec![("url", url)]));
    }
    if !name.is_empty() {
        searches.push((
            "stations/search",
            listing(vec![("name", name.clone()), ("nameExact", "true".into())]),
        ));
        searches.push(("stations/search", listing(vec![("name", name)])));
    }

    let mut tried: Vec<String> = Vec::new();
    for (path, params) in searches {
        for favicon in art_candidates(path, &params).await {
            if tried.iter().any(|seen| seen == &favicon) {
                continue;
            }
            if tried.len() >= MAX_ART_TRIES {
                return Ok(None);
            }
            tried.push(favicon.clone());
            if let Ok(picture) = logo(&favicon).await {
                return Ok(Some(Art {
                    url: favicon,
                    picture,
                }));
            }
        }
    }
    Ok(None)
}

pub async fn search(query: Query) -> Result<Page, String> {
    let mut params: Vec<(&str, String)> = vec![
        // Stations the directory's own checker cannot reach are not worth
        // offering: everything here is meant to be pressed and heard.
        ("hidebroken", "true".into()),
        ("order", ORDER.into()),
        ("reverse", "false".into()),
        ("limit", query.limit.clamp(1, 100).to_string()),
        // A catalogue this size is for searching, not for reading to the end.
        ("offset", query.offset.min(1000).to_string()),
        ("name", query.name.trim().to_string()),
        ("tag", query.tag.trim().to_string()),
    ];
    // Sent only when it is really a country code. A filter the directory
    // cannot make sense of would quietly return nothing at all.
    let code = query.country_code.trim().to_ascii_uppercase();
    if is_country_code(&code) {
        params.push(("countrycode", code));
    }
    quality_params(&query, &mut params);

    let body = get("stations/search", &params, 20, MAX_BODY_BYTES).await?;
    let raw: Vec<Raw> = serde_json::from_str(&body)
        .map_err(|e| format!("radio-browser sent something unreadable: {e}"))?;
    let offered = raw.len() as u32;

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
    Ok(Page { offered, stations })
}
