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

/// A response head from a radio server.
///
/// Shoutcast v1 answers `ICY 200 OK`, which is not an HTTP status line and
/// which no HTTP client will parse - hyper rejects the response before a
/// single header is seen. Those servers are still perfectly playable, so the
/// head is parsed here instead.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Head {
    /// Set when the status line said `ICY` rather than `HTTP/1.x`.
    pub icy: bool,
    pub code: u16,
    fields: Vec<(String, String)>,
}

impl Head {
    /// A header by name, case-insensitively, empty values treated as absent.
    pub fn get(&self, name: &str) -> Option<&str> {
        self.fields
            .iter()
            .find(|(key, _)| key.eq_ignore_ascii_case(name))
            .map(|(_, value)| value.as_str())
            .filter(|value| !value.is_empty())
    }
}

/// Parse a response head - everything before the blank line.
pub fn parse_head(head: &str) -> Option<Head> {
    let mut lines = head.lines();
    let status = lines.next()?;
    let mut parts = status.split_whitespace();
    let version = parts.next()?;
    let icy = version.eq_ignore_ascii_case("ICY");
    if !icy && !version.to_ascii_uppercase().starts_with("HTTP/") {
        return None;
    }
    let code: u16 = parts.next()?.parse().ok()?;

    let fields = lines
        .filter_map(|line| {
            let (key, value) = line.split_once(':')?;
            Some((key.trim().to_string(), value.trim().to_string()))
        })
        .collect();
    Some(Head { icy, code, fields })
}

/// Where the response head ends and the audio begins: the offsets of the
/// blank line that separates them, as (head length, body start).
pub fn head_end(buf: &[u8]) -> Option<(usize, usize)> {
    buf.windows(4)
        .position(|w| w == b"\r\n\r\n")
        .map(|at| (at, at + 4))
        .or_else(|| {
            // Not every server that speaks this dialect bothers with the \r.
            buf.windows(2)
                .position(|w| w == b"\n\n")
                .map(|at| (at, at + 2))
        })
}

/// Where a walk through the interleaved metadata blocks got to.
#[derive(Debug, Default, PartialEq, Eq)]
pub struct MetaScan {
    /// The first title found, if any block held one.
    pub title: Option<String>,
    /// How far in whole blocks have been stepped over, so the next read can
    /// carry on from there rather than re-reading what it has already seen.
    pub cursor: usize,
    /// How many blocks that was.
    pub blocks: usize,
}

/// Walk the metadata blocks in what has arrived so far, starting at `from`.
///
/// An ICY stream is `metaint` bytes of audio, then one length byte counting
/// sixteen-byte units, then that many bytes of metadata, over and over. A
/// block that has not fully arrived is left alone: the cursor does not move
/// past it, so the next pass picks it up whole.
pub fn scan_metadata(buf: &[u8], metaint: usize, from: usize) -> MetaScan {
    let mut scan = MetaScan {
        cursor: from,
        ..Default::default()
    };
    if metaint == 0 {
        return scan;
    }
    while buf.len() > scan.cursor + metaint {
        let len_at = scan.cursor + metaint;
        let block_len = buf[len_at] as usize * 16;
        let block_start = len_at + 1;
        if buf.len() < block_start + block_len {
            break;
        }
        if block_len > 0 && scan.title.is_none() {
            let text = String::from_utf8_lossy(&buf[block_start..block_start + block_len]);
            scan.title = stream_title(&text);
        }
        scan.cursor = block_start + block_len;
        scan.blocks += 1;
        if scan.title.is_some() {
            break;
        }
    }
    scan
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
    fn parses_a_shoutcast_status_line() {
        let head = parse_head("ICY 200 OK\r\nicy-br:320\r\nicy-name: Radio Caprice \r\n").unwrap();
        assert!(head.icy);
        assert_eq!(head.code, 200);
        assert_eq!(head.get("icy-br"), Some("320"));
        // Case does not matter, and the value is trimmed.
        assert_eq!(head.get("ICY-NAME"), Some("Radio Caprice"));
        assert_eq!(head.get("icy-genre"), None);
    }

    #[test]
    fn parses_an_ordinary_http_head_too() {
        let head = parse_head("HTTP/1.0 200 OK\r\nContent-Type: audio/mpeg\r\n").unwrap();
        assert!(!head.icy);
        assert_eq!(head.code, 200);
        assert_eq!(head.get("content-type"), Some("audio/mpeg"));
    }

    #[test]
    fn refuses_a_head_that_is_neither() {
        assert_eq!(parse_head(""), None);
        assert_eq!(parse_head("<html><body>nope"), None);
        assert_eq!(parse_head("ICY not-a-number OK"), None);
    }

    #[test]
    fn an_error_status_is_read_rather_than_refused() {
        let head = parse_head("ICY 404 Resource Not Found\r\n").unwrap();
        assert_eq!(head.code, 404);
    }

    /// `metaint` bytes of audio, a length byte in sixteens, then the block.
    fn stream_with(metaint: usize, blocks: &[&str]) -> Vec<u8> {
        let mut out = Vec::new();
        for block in blocks {
            out.extend(std::iter::repeat_n(b'a', metaint));
            let mut padded = block.as_bytes().to_vec();
            while padded.len() % 16 != 0 {
                padded.push(0);
            }
            out.push((padded.len() / 16) as u8);
            out.extend_from_slice(&padded);
        }
        out
    }

    #[test]
    fn finds_a_title_between_the_audio() {
        let buf = stream_with(64, &["StreamTitle='Autechre - Gantz Graf';"]);
        let scan = scan_metadata(&buf, 64, 0);
        assert_eq!(scan.title.as_deref(), Some("Autechre - Gantz Graf"));
        assert_eq!(scan.blocks, 1);
    }

    #[test]
    fn steps_over_an_empty_block_to_the_next_one() {
        // Servers routinely send an empty block before the real thing.
        let mut buf = Vec::new();
        buf.extend(std::iter::repeat_n(b'a', 32));
        buf.push(0);
        buf.extend(stream_with(32, &["StreamTitle='Boards of Canada';"]));
        let scan = scan_metadata(&buf, 32, 0);
        assert_eq!(scan.title.as_deref(), Some("Boards of Canada"));
        assert_eq!(scan.blocks, 2);
    }

    #[test]
    fn a_half_arrived_block_is_left_for_the_next_pass() {
        let whole = stream_with(16, &["StreamTitle='Later';"]);
        let cut = &whole[..whole.len() - 4];
        let scan = scan_metadata(cut, 16, 0);
        assert_eq!(scan.title, None);
        // Nothing consumed, so the next read starts the block again.
        assert_eq!(scan.cursor, 0);
        assert_eq!(scan.blocks, 0);

        let scan = scan_metadata(&whole, 16, 0);
        assert_eq!(scan.title.as_deref(), Some("Later"));
    }

    #[test]
    fn finds_where_the_head_stops() {
        let crlf = b"ICY 200 OK\r\nicy-br:64\r\n\r\nAUDIO";
        assert_eq!(head_end(crlf), Some((21, 25)));
        assert_eq!(&crlf[..21], b"ICY 200 OK\r\nicy-br:64");

        // Not every server that speaks this dialect bothers with the \r.
        let bare = b"ICY 200 OK\nicy-br:64\n\nAUDIO";
        assert_eq!(head_end(bare), Some((20, 22)));

        // A head that has not finished arriving yet.
        assert_eq!(head_end(b"ICY 200 OK\r\nicy-br:64\r\n"), None);
    }

    #[test]
    fn a_metaint_of_zero_scans_nothing() {
        assert_eq!(scan_metadata(&[1, 2, 3], 0, 0), MetaScan::default());
    }

    #[test]
    fn spots_hls() {
        assert!(is_hls("#EXTM3U\n#EXT-X-VERSION:3\n"));
        assert!(!is_hls("#EXTM3U\nhttps://x/y.mp3\n"));
    }
}
