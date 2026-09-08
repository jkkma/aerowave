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
