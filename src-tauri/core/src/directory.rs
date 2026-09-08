//! Tidying up what the radio-browser.info directory hands back.
//!
//! Its entries are submitted by the public, so names arrive padded with
//! newlines and stray control characters, tags arrive as one comma-separated
//! run, and the address that actually plays is sometimes only in
//! `url_resolved`. None of that needs the network, so it lives here where it
//! can be tested.

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
}
