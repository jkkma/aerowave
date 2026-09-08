//! Reading what Icecast and Shoutcast servers hand back: playlist files and
//! the ICY metadata blocks interleaved with the audio.

/// Is this URL (or content type) a playlist file rather than a stream?
pub fn looks_like_playlist(url: &str, content_type: &str) -> bool {
    let lower = url.split('?').next().unwrap_or(url).to_ascii_lowercase();
    lower.ends_with(".pls")
        || lower.ends_with(".m3u")
        || lower.ends_with(".m3u8")
        || lower.ends_with(".asx")
        || content_type.contains("scpls")
        || content_type.contains("pls+xml")
        || content_type.contains("mpegurl")
        || content_type.contains("ms-asf")
}

/// Pull the first stream URL out of a .pls or .m3u body.
pub fn first_url_in_playlist(body: &str) -> Option<String> {
    for line in body.lines() {
        let line = line.trim();
        if line.is_empty() || line.starts_with('#') || line.starts_with(';') {
            continue;
        }
        // .pls entries look like `File1=http://...`
        let candidate = match line.split_once('=') {
            Some((key, value)) if key.to_ascii_lowercase().starts_with("file") => value.trim(),
            _ => line,
        };
        if candidate.starts_with("http://") || candidate.starts_with("https://") {
            return Some(candidate.to_string());
        }
    }
    None
}

/// Is this an HLS playlist? WebView2 has no native HLS decoder, so these are
/// worth calling out rather than handing over a URL that plays silence.
pub fn is_hls(body: &str) -> bool {
    body.contains("#EXT-X-")
}

/// Parse `StreamTitle='...';` out of an ICY metadata block. Returns None for
/// an absent or empty title - servers pad blocks with NULs and often send an
/// empty one before the real thing.
pub fn stream_title(block: &str) -> Option<String> {
    let start = block.find("StreamTitle=")? + "StreamTitle=".len();
    let rest = &block[start..];
    // Most servers quote the title, but not all. An unquoted one ends at the
    // first semicolon; looking for `';` in it runs past the end of the field
    // and swallows StreamUrl with it.
    let quoted = rest.starts_with('\'');
    let rest = rest.strip_prefix('\'').unwrap_or(rest);
    let terminator = if quoted { rest.find("';") } else { rest.find(';') };
    let end = terminator.unwrap_or_else(|| rest.trim_end_matches('\0').trim_end().len());
    let title = rest.get(..end)?.trim();
    if title.is_empty() {
        None
    } else {
        Some(title.to_string())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn reads_a_stream_title() {
        assert_eq!(
            stream_title("StreamTitle='Boards of Canada - Roygbiv';StreamUrl='';"),
            Some("Boards of Canada - Roygbiv".to_string())
        );
    }

    #[test]
    fn an_empty_or_absent_title_is_none() {
        assert_eq!(stream_title("StreamTitle='';StreamUrl='';"), None);
        assert_eq!(stream_title("StreamTitle='   ';"), None);
        assert_eq!(stream_title("nothing here"), None);
    }

    #[test]
    fn survives_a_block_with_no_terminator() {
        assert_eq!(
            stream_title("StreamTitle='Half a title\0\0\0"),
            Some("Half a title".to_string())
        );
    }

    #[test]
    fn keeps_quotes_inside_a_title() {
        assert_eq!(
            stream_title("StreamTitle='Don't Stop';StreamUrl='';"),
            Some("Don't Stop".to_string())
        );
    }

    #[test]
    fn reads_an_unquoted_stream_title() {
        assert_eq!(
            stream_title("StreamTitle=Artist - Track;StreamUrl='https://x/art.jpg';"),
            Some("Artist - Track".to_string())
        );
    }

    #[test]
    fn an_unquoted_title_padded_with_nuls_keeps_no_semicolon() {
        assert_eq!(
            stream_title("StreamTitle=Artist - Track;\0\0"),
            Some("Artist - Track".to_string())
        );
    }

    #[test]
    fn finds_the_url_in_a_pls_file() {
        let pls = "[playlist]\nnumberofentries=1\nFile1=http://ice.example/stream\nTitle1=X\n";
        assert_eq!(
            first_url_in_playlist(pls),
            Some("http://ice.example/stream".to_string())
        );
    }

    #[test]
    fn finds_the_url_in_an_m3u_file() {
        let m3u = "#EXTM3U\n#EXTINF:-1,Radio\nhttps://ice.example/hi.mp3\n";
        assert_eq!(
            first_url_in_playlist(m3u),
            Some("https://ice.example/hi.mp3".to_string())
        );
    }

    #[test]
    fn a_playlist_with_no_urls_yields_nothing() {
        assert_eq!(first_url_in_playlist("[playlist]\nnumberofentries=0\n"), None);
    }

    #[test]
    fn spots_playlists_by_url_or_type() {
        assert!(looks_like_playlist("http://x/y.pls", ""));
        assert!(looks_like_playlist("http://x/y.M3U?token=1", ""));
        assert!(looks_like_playlist("http://x/y", "audio/x-scpls"));
        assert!(!looks_like_playlist("http://x/stream-128-mp3", "audio/mpeg"));
    }

    #[test]
    fn spots_hls() {
        assert!(is_hls("#EXTM3U\n#EXT-X-VERSION:3\n"));
        assert!(!is_hls("#EXTM3U\nhttps://x/y.mp3\n"));
    }
}
