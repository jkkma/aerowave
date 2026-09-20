//! Tidying up what the radio-browser.info directory hands back.
//!
//! Its entries are submitted by the public, so names arrive padded with
//! newlines and stray control characters, tags arrive as one comma-separated
//! run, and the address that actually plays is sometimes only in
//! `url_resolved`. None of that needs the network, so it lives here where it
//! can be tested.

use std::collections::{HashMap, HashSet};

/// Formats the Browse tab can hand to the player.
pub const DIRECTORY_CODECS: [&str; 5] = ["MP3", "AAC", "AAC+", "OGG", "FLAC"];

/// The last directory offset Browse deliberately exposes. The catalogue is
/// for narrowing and searching rather than walking indefinitely.
pub const DIRECTORY_MAX_OFFSET: u32 = 1000;

/// The directory request derived from a caller's paging values.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct DirectoryPageWindow {
    pub limit: u32,
    pub offset: u32,
}

impl DirectoryPageWindow {
    /// A next request can advance only while this page was full and the offset
    /// cap has not been reached. In particular, a full page at the cap must not
    /// invite the caller to request the same clamped page forever.
    pub fn has_more(self, offered: u32) -> bool {
        offered >= self.limit
            && self.offset.saturating_add(offered) <= DIRECTORY_MAX_OFFSET
    }
}

pub fn directory_page_window(limit: u32, offset: u32) -> DirectoryPageWindow {
    DirectoryPageWindow {
        limit: limit.clamp(1, 100),
        offset: offset.min(DIRECTORY_MAX_OFFSET),
    }
}

/// A selected tag is one concrete dropdown value. Radio Browser's ordinary
/// `tag` search is a substring search, so it must travel with `tagExact=true`.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DirectoryTagFilter {
    pub value: String,
    pub exact: bool,
}

pub fn exact_tag_filter(raw: &str) -> Option<DirectoryTagFilter> {
    let value = raw.trim();
    (!value.is_empty()).then(|| DirectoryTagFilter {
        value: value.to_string(),
        exact: true,
    })
}

/// Which directory failures mean the session should choose another mirror.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum DirectoryFailure {
    Request,
    HttpStatus(u16),
    Body,
    Json,
}

pub fn directory_failure_forgets_mirror(failure: DirectoryFailure) -> bool {
    match failure {
        DirectoryFailure::Request | DirectoryFailure::Body | DirectoryFailure::Json => true,
        DirectoryFailure::HttpStatus(status) => (500..600).contains(&status),
    }
}

/// Valid mirror names, once each and in the order the directory supplied.
///
/// The servers endpoint currently repeats a hostname when both its IPv4 and
/// IPv6 addresses are healthy. Those are two routes to one mirror, not two
/// useful retry choices. Hostnames compare without regard to ASCII case, so
/// normalising here also prevents a differently-cased duplicate from slipping
/// through.
pub fn directory_host_candidates<I, S>(names: I) -> Vec<String>
where
    I: IntoIterator<Item = S>,
    S: AsRef<str>,
{
    let mut seen = HashSet::new();
    names
        .into_iter()
        .filter_map(|name| {
            let name = name.as_ref();
            if !is_hostname(name) {
                return None;
            }
            let normalised = name.to_ascii_lowercase();
            seen.insert(normalised.clone()).then_some(normalised)
        })
        .collect()
}

/// Choose from an already ordered mirror list, avoiding the failed host when
/// another one is available. An empty discovery on a retry keeps the previous
/// host: a temporary failure to list mirrors must not invent a second server.
pub fn select_directory_host(
    candidates: &[String],
    failed_host: Option<&str>,
    fallback_host: &str,
) -> String {
    if let Some(candidate) = candidates.iter().find(|candidate| {
        failed_host
            .map(|failed| !candidate.eq_ignore_ascii_case(failed))
            .unwrap_or(true)
    }) {
        return candidate.clone();
    }
    candidates
        .first()
        .cloned()
        .or_else(|| failed_host.map(str::to_string))
        .unwrap_or_else(|| fallback_host.to_string())
}

/// Collapse a directory name onto one line and cap it at `max` characters.
///
/// Control characters are turned into spaces rather than dropped: a name sent
/// as "Jazz\nFM" is two words, not "JazzFM".
pub fn clean_name(raw: &str, max: usize) -> String {
    let flattened: String = raw
        .chars()
        .map(|c| if c.is_control() { ' ' } else { c })
        .collect();
    let mut out = String::new();
    for word in flattened.split_whitespace() {
        if !out.is_empty() {
            out.push(' ');
        }
        out.push_str(word);
    }
    // By characters, not bytes: half of a multi-byte character is not a name.
    if out.chars().count() > max {
        out = out.chars().take(max).collect::<String>().trim_end().to_string();
    }
    out
}

/// The most useful tag out of the directory's comma-separated list, cleaned
/// and capped the same way a hand-typed one would be.
///
/// Bare years are skipped where there is anything else - the directory is
/// full of stations tagged "1930,1940,1950,...,jazz", and the genre eleven
/// entries along says far more than the decade at the front.
pub fn first_tag(raw: &str, max: usize) -> String {
    let mut fallback = String::new();
    for tag in raw.split(',').map(|tag| clean_name(tag, max)) {
        if tag.is_empty() {
            continue;
        }
        if !tag.chars().all(|c| c.is_ascii_digit()) {
            return tag;
        }
        if fallback.is_empty() {
            fallback = tag;
        }
    }
    fallback
}

/// The address to hand the player, preferring the one the directory has
/// already followed. Anything that is not http(s) - a `file:` entry, or a
/// scheme the media element has never heard of - is refused rather than
/// saved as a station that can only ever fail.
pub fn playable_url(url: &str, resolved: &str) -> Option<String> {
    [resolved, url]
        .into_iter()
        .map(str::trim)
        .find(|candidate| {
            let lower = candidate.to_ascii_lowercase();
            lower.starts_with("http://") || lower.starts_with("https://")
        })
        .map(str::to_string)
}

/// Is this an ISO 3166-1 alpha-2 country code? Anything else is refused
/// rather than passed to the directory as a filter it would have to guess at.
pub fn is_country_code(code: &str) -> bool {
    code.len() == 2 && code.chars().all(|c| c.is_ascii_alphabetic())
}

/// How a country name sorts. The directory calls a good few of them "The
/// ..." - a list where a fifth of the world files under T is no use to
/// anybody looking for one.
pub fn sort_key(name: &str) -> String {
    let lower = name.to_lowercase();
    lower.strip_prefix("the ").unwrap_or(&lower).to_string()
}

/// What kind of image these bytes are, by what they start with, or `None` if
/// they are not an image at all.
///
/// The Content-Type a logo arrives under is not worth trusting: station art
/// is hosted on whatever the broadcaster had lying around, and comes back
/// labelled `text/html`, `application/octet-stream` and worse. The bytes
/// themselves are not so easily wrong - and something that is not an image
/// must not be handed to the webview as one.
pub fn image_kind(bytes: &[u8]) -> Option<&'static str> {
    const PNG: [u8; 8] = [0x89, b'P', b'N', b'G', 0x0d, 0x0a, 0x1a, 0x0a];
    const JPEG: [u8; 3] = [0xff, 0xd8, 0xff];
    // An icon directory: two zero bytes, then type 1 (icon) or 2 (cursor).
    const ICO: [u8; 4] = [0, 0, 1, 0];
    const CUR: [u8; 4] = [0, 0, 2, 0];

    if bytes.starts_with(&PNG) {
        return Some("image/png");
    }
    if bytes.starts_with(&JPEG) {
        return Some("image/jpeg");
    }
    if bytes.starts_with(b"GIF87a") || bytes.starts_with(b"GIF89a") {
        return Some("image/gif");
    }
    // RIFF carries a good deal besides; the tag four bytes further in is the
    // part that says which - and a WAV file opens exactly the same way.
    if bytes.starts_with(b"RIFF") && bytes.len() >= 12 && &bytes[8..12] == b"WEBP" {
        return Some("image/webp");
    }
    if bytes.starts_with(b"BM") {
        return Some("image/bmp");
    }
    if bytes.starts_with(&ICO) || bytes.starts_with(&CUR) {
        return Some("image/x-icon");
    }

    // SVG has no magic number, so this is the best that can be done: what the
    // document opens with, and whether the root element is in there at all.
    // Both halves are needed - an RSS feed also starts with the XML
    // declaration. Shown in an <img>, an SVG runs no script and fetches
    // nothing, so it is no more dangerous than a PNG.
    let head = String::from_utf8_lossy(bytes.get(..512).unwrap_or(bytes));
    let head = head.trim_start();
    let opens_a_document =
        head.starts_with("<svg") || head.starts_with("<?xml") || head.starts_with("<!DOCTYPE svg");
    if opens_a_document && head.contains("<svg") {
        return Some("image/svg+xml");
    }
    None
}

/// Is this a plain hostname, and so safe to build an API URL out of?
///
/// The mirror list is fetched over the network, and a name carrying a slash
/// or a scheme would let whatever served it decide which URL this app calls.
pub fn is_hostname(name: &str) -> bool {
    !name.is_empty()
        && name.len() <= 253
        && !name.starts_with('.')
        && !name.starts_with('-')
        && name
            .chars()
            .all(|c| c.is_ascii_alphanumeric() || c == '.' || c == '-')
}

/// The key two directory entries share when they are the same stream.
///
/// Submissions are public and the same stream arrives more than once: under
/// two different names, and more often under one name with two schemes. Of
/// 5000 entries sampled in September 2026, ignoring the scheme merged 61
/// pairs that the whole URL kept apart and ignoring a trailing slash merged
/// one more. Stripping a default port or a leading `www.` merged nothing at
/// all, so neither is done - a normalisation that never fires is only a way
/// to collapse two stations that were never the same.
///
/// The path is lower-cased along with the host, which is not strictly correct
/// for a URL path but is correct for this directory: the same stream is filed
/// as `LOS40.mp3` and `Los40.mp3`.
pub fn stream_key(url: &str) -> String {
    let lower = url.trim().to_ascii_lowercase();
    let bare = match lower.strip_prefix("https://") {
        Some(rest) => rest,
        None => lower.strip_prefix("http://").unwrap_or(lower.as_str()),
    };
    bare.trim_end_matches('/').to_string()
}

/// The directory fields one facet tally needs from a submitted station.
pub struct DirectoryFacetEntry<'a> {
    pub url: &'a str,
    pub resolved_url: &'a str,
    pub name: &'a str,
    pub tags: &'a str,
    pub country_code: &'a str,
    pub country_name: &'a str,
    pub codec: &'a str,
    pub bitrate: u32,
}

/// Counts aligned with the Browse dropdowns after duplicate streams have been
/// collapsed independently inside each target bucket.
#[derive(Debug, Default, PartialEq, Eq)]
pub struct DirectoryFacetTally {
    pub tags: HashMap<String, u32>,
    pub countries: HashMap<String, (String, u32)>,
    pub codecs: Vec<u32>,
    pub bitrates: Vec<u32>,
}

/// Count every stream once in each classification a corresponding filtered
/// search can find. A public stream may have several submissions with different
/// countries or tags. Discarding the later submission globally would erase a
/// real target bucket even though filtering by that bucket still returns it.
pub fn tally_directory_facets<'a>(
    entries: impl IntoIterator<Item = DirectoryFacetEntry<'a>>,
) -> DirectoryFacetTally {
    let mut tally = DirectoryFacetTally {
        codecs: vec![0; DIRECTORY_CODECS.len()],
        bitrates: vec![0; BITRATE_BANDS.len()],
        ..DirectoryFacetTally::default()
    };
    let mut seen_tags: HashSet<(String, String)> = HashSet::new();
    let mut seen_countries: HashSet<(String, String)> = HashSet::new();
    let mut seen_codecs: HashSet<(String, usize)> = HashSet::new();
    let mut seen_bitrates: HashSet<(String, usize)> = HashSet::new();

    for entry in entries {
        let Some(url) = playable_url(entry.url, entry.resolved_url) else {
            continue;
        };
        if clean_name(entry.name, 60).is_empty() {
            continue;
        }
        let stream = stream_key(&url);

        let codec = clean_name(entry.codec, 16).to_ascii_uppercase();
        if let Some(slot) = DIRECTORY_CODECS.iter().position(|known| *known == codec) {
            if seen_codecs.insert((stream.clone(), slot)) {
                tally.codecs[slot] += 1;
            }
        }

        if let Some(slot) = bitrate_band_index(entry.bitrate) {
            if seen_bitrates.insert((stream.clone(), slot)) {
                tally.bitrates[slot] += 1;
            }
        }

        for tag in entry.tags.split(',') {
            let value = tag.trim();
            if !value.is_empty() && seen_tags.insert((stream.clone(), value.to_string())) {
                *tally.tags.entry(value.to_string()).or_default() += 1;
            }
        }

        let code = clean_name(entry.country_code, 2).to_ascii_uppercase();
        let name = clean_name(entry.country_name, 60);
        if is_country_code(&code)
            && !name.is_empty()
            && seen_countries.insert((stream.clone(), code.clone()))
        {
            let slot = tally.countries.entry(code).or_insert((name, 0));
            slot.1 += 1;
        }
    }

    tally
}

/// Does a directory entry describe one of the stream addresses the player
/// actually reached?
///
/// Both sides keep their submitted and resolved forms. A playlist wrapper is
/// often what the person saved while the directory only carries its final
/// stream, or vice versa; matching only the preferred form would miss that
/// identity. Names and homepages are deliberately absent from this rule: two
/// unrelated broadcasters routinely share either one.
pub fn stream_matches(
    entry_url: &str,
    entry_resolved: &str,
    requested_url: &str,
    requested_resolved: &str,
) -> bool {
    let requested: Vec<String> = [requested_url, requested_resolved]
        .into_iter()
        .filter_map(|url| playable_url(url, ""))
        .map(|url| stream_key(&url))
        .collect();
    if requested.is_empty() {
        return false;
    }

    [entry_url, entry_resolved]
        .into_iter()
        .filter_map(|url| playable_url(url, ""))
        .map(|url| stream_key(&url))
        .any(|candidate| requested.iter().any(|wanted| wanted == &candidate))
}

/// One entry in the bitrate dropdown, and the reported rates it stands for.
///
/// Most bands are a single round number, because that is what encoders are set
/// to and what the directory stores. The two on the ends are ranges: the
/// directory carries a long tail below the lowest round number and a handful
/// above the highest, and without a band of their own those stations can only
/// be reached by asking for any bitrate at all.
pub struct BitrateBand {
    /// The `value` of the dropdown option this belongs to, and the key its
    /// count comes back under.
    pub key: &'static str,
    /// The inclusive range a reported rate has to fall in.
    pub min: u32,
    pub max: u32,
}

/// The bands the dropdown offers, in the order it offers them. They do not
/// meet: 56k and 112k are in none of them, because the round numbers are what
/// encoders are actually set to and a band wide enough to catch everything
/// would stop meaning anything. Nor do they cover 0, which is what the
/// directory stores for a station it has no bitrate for at all - unreported is
/// not the same as low, and only "any bitrate" keeps those.
pub const BITRATE_BANDS: [BitrateBand; 9] = [
    BitrateBand { key: "low", min: 1, max: 47 },
    BitrateBand { key: "48", min: 48, max: 48 },
    BitrateBand { key: "64", min: 64, max: 64 },
    BitrateBand { key: "96", min: 96, max: 96 },
    BitrateBand { key: "128", min: 128, max: 128 },
    BitrateBand { key: "192", min: 192, max: 192 },
    BitrateBand { key: "256", min: 256, max: 256 },
    BitrateBand { key: "320", min: 320, max: 320 },
    // No encoder goes near the top of this, but the directory is public and
    // the number it holds is whatever was typed in.
    BitrateBand { key: "high", min: 321, max: 10_000_000 },
];

/// Where in `BITRATE_BANDS` a reported rate falls, or `None` when it falls
/// between them - including the 0 that means the directory has no bitrate for
/// the station. The index is what a tally counting into a fixed row of slots
/// wants; `bitrate_band` is the same answer for anyone who wants the band.
pub fn bitrate_band_index(kbps: u32) -> Option<usize> {
    BITRATE_BANDS
        .iter()
        .position(|band| kbps >= band.min && kbps <= band.max)
}

/// The band a reported rate falls in, on the same terms.
pub fn bitrate_band(kbps: u32) -> Option<&'static BitrateBand> {
    bitrate_band_index(kbps).map(|slot| &BITRATE_BANDS[slot])
}

/// The band a dropdown value names, or `None` for "any bitrate" - and for
/// anything else the webview might send, which is the same answer.
pub fn bitrate_band_by_key(key: &str) -> Option<&'static BitrateBand> {
    BITRATE_BANDS.iter().find(|band| band.key == key)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn reads_an_image_by_its_first_bytes() {
        assert_eq!(image_kind(&[0x89, b'P', b'N', b'G', 13, 10, 26, 10]), Some("image/png"));
        assert_eq!(image_kind(&[0xff, 0xd8, 0xff, 0xe0]), Some("image/jpeg"));
        assert_eq!(image_kind(b"GIF89a...."), Some("image/gif"));
        assert_eq!(image_kind(b"RIFF____WEBPVP8 "), Some("image/webp"));
        assert_eq!(image_kind(b"BM__"), Some("image/bmp"));
        assert_eq!(image_kind(&[0, 0, 1, 0, 1, 0]), Some("image/x-icon"));
        assert_eq!(
            image_kind(br#"  <svg xmlns="http://www.w3.org/2000/svg"/>"#),
            Some("image/svg+xml")
        );
    }

    #[test]
    fn refuses_what_is_not_an_image() {
        // The ones that actually turn up: a logo link that has rotted into an
        // error page, a host that answers with nothing, and a RIFF file that
        // is not a picture at all.
        assert_eq!(image_kind(b"<!DOCTYPE html><html>404"), None);
        assert_eq!(image_kind(b""), None);
        assert_eq!(image_kind(b"RIFF____WAVEfmt "), None);
        assert_eq!(image_kind(&[0, 0, 3, 0]), None);
        assert_eq!(image_kind(br#"<?xml version="1.0"?><rss><channel/></rss>"#), None);
    }

    #[test]
    fn flattens_and_trims_a_name() {
        assert_eq!(clean_name("  Jazz\n FM \r\n", 60), "Jazz FM");
        assert_eq!(clean_name("\u{0}\u{1}", 60), "");
    }

    #[test]
    fn caps_a_name_without_splitting_a_character() {
        assert_eq!(clean_name("Rádio Água Viva", 6), "Rádio");
        assert_eq!(clean_name("ααααα", 3), "ααα");
    }

    #[test]
    fn takes_the_first_tag_that_has_anything_in_it() {
        assert_eq!(first_tag(",, ,jazz,blues", 24), "jazz");
        assert_eq!(first_tag("", 24), "");
        assert_eq!(first_tag("a-very-long-tag-that-runs-on", 8), "a-very-l");
    }

    #[test]
    fn steps_over_years_to_reach_a_genre() {
        assert_eq!(first_tag("1930,1940,big band,jazz", 24), "big band");
        assert_eq!(first_tag("80s,90s", 24), "80s");
        // Nothing but years: better than handing back no tag at all.
        assert_eq!(first_tag("1930,1940", 24), "1930");
    }

    #[test]
    fn prefers_the_resolved_url_and_refuses_the_rest() {
        assert_eq!(
            playable_url("http://example/x.pls", "https://example/live.mp3"),
            Some("https://example/live.mp3".to_string())
        );
        assert_eq!(
            playable_url("http://example/live", ""),
            Some("http://example/live".to_string())
        );
        assert_eq!(playable_url("file:///etc/passwd", "rtsp://example/x"), None);
    }

    #[test]
    fn country_codes_are_two_letters() {
        assert!(is_country_code("FR"));
        assert!(is_country_code("gb"));
        assert!(!is_country_code(""));
        assert!(!is_country_code("FRA"));
        assert!(!is_country_code("F1"));
    }

    #[test]
    fn a_country_sorts_under_its_own_name_not_under_the() {
        assert_eq!(sort_key("The United States Of America"), "united states of america");
        assert_eq!(sort_key("Netherlands"), "netherlands");
        // Not a prefix to strip: this one really is called Theresienstadt.
        assert_eq!(sort_key("Theresienstadt"), "theresienstadt");
    }

    #[test]
    fn only_plain_hostnames_are_hostnames() {
        assert!(is_hostname("de1.api.radio-browser.info"));
        assert!(!is_hostname("de1.api.radio-browser.info/../evil"));
        assert!(!is_hostname("https://elsewhere.example"));
        assert!(!is_hostname(""));
    }

    #[test]
    fn the_same_stream_under_two_schemes_is_one_stream() {
        assert_eq!(
            stream_key("https://radio.plaza.one/ogg"),
            stream_key("http://radio.plaza.one/ogg")
        );
        assert_eq!(
            stream_key("http://stream.live.vc.bbcmedia.co.uk/bbc_world_service"),
            stream_key("https://stream.live.vc.bbcmedia.co.uk/bbc_world_service")
        );
    }

    #[test]
    fn a_trailing_slash_and_a_capital_do_not_make_a_second_station() {
        assert_eq!(stream_key("http://a.example/live/"), stream_key("http://a.example/live"));
        assert_eq!(stream_key("http://a.example/LOS40.mp3"), stream_key("http://a.example/los40.mp3"));
        assert_eq!(stream_key("  http://a.example/live  "), stream_key("http://a.example/live"));
    }

    #[test]
    fn two_streams_on_one_host_stay_two_streams() {
        // The case that started this: plaza.one serves /ogg and /opus, and
        // they are different streams at different bitrates.
        assert_ne!(stream_key("http://radio.plaza.one/ogg"), stream_key("http://radio.plaza.one/opus"));
        assert_ne!(stream_key("http://a.example/one"), stream_key("http://a.example/two"));
        // Not normalised, because the sample said neither ever fires.
        assert_ne!(stream_key("http://a.example:8000/x"), stream_key("http://a.example/x"));
        assert_ne!(stream_key("http://www.a.example/x"), stream_key("http://a.example/x"));
    }

    #[test]
    fn artwork_matching_uses_submitted_and_resolved_stream_addresses() {
        assert!(stream_matches(
            "https://radio.example/listen.pls",
            "https://cdn.example/live.mp3",
            "https://radio.example/listen.pls",
            ""
        ));
        assert!(stream_matches(
            "https://radio.example/listen.pls",
            "https://cdn.example/live.mp3",
            "https://elsewhere.example/wrapper.m3u",
            "http://cdn.example/live.mp3"
        ));
    }

    #[test]
    fn artwork_matching_never_uses_a_shared_name_or_host_as_identity() {
        assert!(!stream_matches(
            "https://radio.example/one",
            "",
            "https://radio.example/two",
            ""
        ));
        assert!(!stream_matches("", "file:///station", "", "rtsp://station"));
    }

    fn band_of(kbps: u32) -> Option<&'static str> {
        bitrate_band(kbps).map(|band| band.key)
    }

    #[test]
    fn a_round_bitrate_lands_on_its_own_band() {
        assert_eq!(band_of(48), Some("48"));
        assert_eq!(band_of(128), Some("128"));
        assert_eq!(band_of(320), Some("320"));
    }

    #[test]
    fn the_end_bands_take_what_falls_outside_the_round_numbers() {
        assert_eq!(band_of(1), Some("low"));
        assert_eq!(band_of(32), Some("low"));
        assert_eq!(band_of(47), Some("low"));
        assert_eq!(band_of(321), Some("high"));
        assert_eq!(band_of(1411), Some("high"));
    }

    #[test]
    fn an_unreported_bitrate_is_not_a_low_one() {
        // The directory stores 0 for a station it has no bitrate for. Counting
        // those as low would promise quiet stations that are only unmeasured.
        assert_eq!(band_of(0), None);
    }

    #[test]
    fn a_bitrate_between_the_round_numbers_is_in_no_band() {
        assert_eq!(band_of(56), None);
        assert_eq!(band_of(112), None);
        assert_eq!(band_of(319), None);
    }

    #[test]
    fn the_index_and_the_band_are_the_same_answer() {
        for kbps in [0u32, 1, 47, 48, 56, 192, 320, 321, 9999] {
            let by_index = bitrate_band_index(kbps).map(|slot| BITRATE_BANDS[slot].key);
            assert_eq!(by_index, band_of(kbps), "disagreed about {kbps}");
        }
    }

    #[test]
    fn the_bands_do_not_overlap_and_stay_in_order() {
        for pair in BITRATE_BANDS.windows(2) {
            assert!(pair[0].max < pair[1].min, "{} runs into {}", pair[0].key, pair[1].key);
        }
    }

    #[test]
    fn a_dropdown_value_finds_its_band_and_anything_else_finds_none() {
        assert_eq!(bitrate_band_by_key("192").map(|b| b.min), Some(192));
        assert_eq!(bitrate_band_by_key("high").map(|b| b.min), Some(321));
        // "Any bitrate" sends an empty value, and means no filter at all.
        assert!(bitrate_band_by_key("").is_none());
        assert!(bitrate_band_by_key("0").is_none());
        assert!(bitrate_band_by_key("nonsense").is_none());
    }

    #[test]
    fn a_selected_directory_tag_is_always_exact() {
        assert_eq!(
            exact_tag_filter("  rock  "),
            Some(DirectoryTagFilter {
                value: "rock".into(),
                exact: true,
            })
        );
        assert_eq!(exact_tag_filter("  "), None);
    }

    #[test]
    fn duplicate_streams_are_collapsed_inside_each_facet_bucket() {
        let entries = [
            DirectoryFacetEntry {
                url: "http://radio.example/live",
                resolved_url: "",
                name: "Radio One",
                tags: "dance,dance",
                country_code: "MX",
                country_name: "Mexico",
                codec: "AAC",
                bitrate: 0,
            },
            DirectoryFacetEntry {
                url: "https://radio.example/live/",
                resolved_url: "",
                name: "Radio One UK",
                tags: "dance,edm",
                country_code: "GB",
                country_name: "United Kingdom",
                codec: "AAC",
                bitrate: 128,
            },
            DirectoryFacetEntry {
                url: "http://radio.example/live",
                resolved_url: "",
                name: "Radio One alternate",
                tags: "dance",
                country_code: "MX",
                country_name: "Mexico",
                codec: "MP3",
                bitrate: 64,
            },
            DirectoryFacetEntry {
                url: "https://other.example/stream",
                resolved_url: "",
                name: "Other",
                tags: "dance",
                country_code: "MX",
                country_name: "Mexico",
                codec: "AAC",
                bitrate: 128,
            },
        ];

        let tally = tally_directory_facets(entries);
        assert_eq!(tally.tags.get("dance"), Some(&2));
        assert_eq!(tally.tags.get("edm"), Some(&1));
        assert_eq!(tally.countries.get("MX").map(|entry| entry.1), Some(2));
        assert_eq!(tally.countries.get("GB").map(|entry| entry.1), Some(1));
        assert_eq!(tally.codecs[0], 1); // MP3
        assert_eq!(tally.codecs[1], 2); // AAC
        assert_eq!(tally.bitrates[2], 1); // 64k
        assert_eq!(tally.bitrates[4], 2); // 128k
    }

    #[test]
    fn body_and_json_failures_release_a_directory_mirror() {
        assert!(directory_failure_forgets_mirror(DirectoryFailure::Request));
        assert!(directory_failure_forgets_mirror(
            DirectoryFailure::HttpStatus(503)
        ));
        assert!(directory_failure_forgets_mirror(DirectoryFailure::Body));
        assert!(directory_failure_forgets_mirror(DirectoryFailure::Json));
        assert!(!directory_failure_forgets_mirror(
            DirectoryFailure::HttpStatus(404)
        ));
        assert!(!directory_failure_forgets_mirror(
            DirectoryFailure::HttpStatus(429)
        ));
    }

    #[test]
    fn mirror_candidates_are_valid_unique_hostnames() {
        assert_eq!(
            directory_host_candidates([
                "de1.api.radio-browser.info",
                "DE1.API.RADIO-BROWSER.INFO",
                "https://not-a-host.example",
                "nl1.api.radio-browser.info",
                "de1.api.radio-browser.info",
            ]),
            vec![
                "de1.api.radio-browser.info".to_string(),
                "nl1.api.radio-browser.info".to_string(),
            ]
        );
    }

    #[test]
    fn retry_prefers_another_mirror_but_never_invents_one() {
        let discovered = vec![
            "de1.api.radio-browser.info".to_string(),
            "nl1.api.radio-browser.info".to_string(),
        ];
        assert_eq!(
            select_directory_host(
                &discovered,
                Some("de1.api.radio-browser.info"),
                "fallback.example",
            ),
            "nl1.api.radio-browser.info"
        );
        assert_eq!(
            select_directory_host(
                &["de1.api.radio-browser.info".to_string()],
                Some("de1.api.radio-browser.info"),
                "fallback.example",
            ),
            "de1.api.radio-browser.info"
        );
        assert_eq!(
            select_directory_host(&[], Some("de1.api.radio-browser.info"), "fallback.example"),
            "de1.api.radio-browser.info"
        );
        assert_eq!(
            select_directory_host(&[], None, "fallback.example"),
            "fallback.example"
        );
    }

    #[test]
    fn a_full_page_at_the_offset_cap_has_no_next_page() {
        let first = directory_page_window(40, 0);
        assert_eq!(
            first,
            DirectoryPageWindow {
                limit: 40,
                offset: 0
            }
        );
        assert!(first.has_more(40));
        assert!(!first.has_more(39));

        assert!(directory_page_window(40, 960).has_more(40));
        assert!(!directory_page_window(40, 980).has_more(40));
        let last = directory_page_window(40, DIRECTORY_MAX_OFFSET);
        assert!(!last.has_more(40));
        assert_eq!(directory_page_window(0, u32::MAX).limit, 1);
        assert_eq!(
            directory_page_window(500, u32::MAX).offset,
            DIRECTORY_MAX_OFFSET
        );
    }
}
