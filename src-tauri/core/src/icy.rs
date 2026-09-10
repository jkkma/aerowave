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

/// Is this an HLS playlist? WebView2 will not play one by itself, so these
/// take the other path - hls.js, fed through `hls.rs` - rather than being
/// handed to the media element as if they were a stream.
pub fn is_hls(body: &str) -> bool {
    body.contains("#EXT-X-")
}

/// The relay must honor every valid interval after asking for metadata. Its
/// streaming stripper keeps only the metadata block, so the probe's buffer
/// budget is not a limit on the distance between blocks.
pub fn metadata_interval(raw: Option<&str>) -> Result<usize, &'static str> {
    match raw {
        None => Ok(0),
        Some(value) => value.trim().parse().map_err(|_| "invalid icy-metaint header"),
    }
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
            let text = decode_text(&buf[block_start..block_start + block_len]);
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

/// Where in the `metaint` audio / length byte / block cycle a stream is.
enum Frame {
    /// This many bytes of audio still to pass through before the length byte.
    Audio(usize),
    /// The next byte says how long the block is, in sixteens.
    Length,
    /// This many bytes of metadata still to collect.
    Block(usize),
}

/// Pulls the metadata blocks back out of an ICY stream as it flows past.
///
/// `scan_metadata` walks a buffer kept whole: the probe reads until it finds a
/// title and throws the lot away. The relay cannot work that way. It copies an
/// endless stream and must keep nothing, so this consumes chunk by chunk and
/// remembers only which part of the frame it stopped in - a block split across
/// two reads is finished by the next call.
///
/// Getting this exactly right is the whole job. What comes out has to be
/// byte-for-byte the audio a request without `Icy-MetaData: 1` would have
/// produced. One `StreamTitle='...'` left in it is `MEDIA_ERR_DECODE`
/// mid-song, on a full buffer, which is what asking for metadata and copying
/// it verbatim used to do: Radio Paradise, metaint 16000, died about sixteen
/// seconds in, every time.
pub struct MetaStrip {
    metaint: usize,
    frame: Frame,
    block: Vec<u8>,
}

impl MetaStrip {
    /// `metaint` is what the server's `icy-metaint` header said. Zero means it
    /// is interleaving nothing, and no stripper should be built at all.
    pub fn new(metaint: usize) -> Self {
        Self {
            metaint,
            frame: Frame::Audio(metaint),
            block: Vec::new(),
        }
    }

    /// Feed one chunk. The audio in it is appended to `audio`; any titles that
    /// finished arriving inside it come back, in the order they were sent.
    pub fn push(&mut self, chunk: &[u8], audio: &mut Vec<u8>) -> Vec<String> {
        let mut titles = Vec::new();
        if self.metaint == 0 {
            audio.extend_from_slice(chunk);
            return titles;
        }
        let mut at = 0;
        while at < chunk.len() {
            match self.frame {
                Frame::Audio(left) => {
                    let take = left.min(chunk.len() - at);
                    audio.extend_from_slice(&chunk[at..at + take]);
                    at += take;
                    self.frame = if take == left {
                        Frame::Length
                    } else {
                        Frame::Audio(left - take)
                    };
                }
                Frame::Length => {
                    // The length is counted in sixteen-byte units, so a whole
                    // block is at most 255 * 16 - the buffer cannot run away.
                    let len = chunk[at] as usize * 16;
                    at += 1;
                    self.block.clear();
                    self.frame = if len == 0 {
                        Frame::Audio(self.metaint)
                    } else {
                        Frame::Block(len)
                    };
                }
                Frame::Block(left) => {
                    let take = left.min(chunk.len() - at);
                    self.block.extend_from_slice(&chunk[at..at + take]);
                    at += take;
                    if take == left {
                        if let Some(title) = stream_title(&decode_text(&self.block)) {
                            titles.push(title);
                        }
                        self.frame = Frame::Audio(self.metaint);
                    } else {
                        self.frame = Frame::Block(left - take);
                    }
                }
            }
        }
        titles
    }
}

/// Turn a run of ICY bytes into text.
///
/// An ICY metadata block carries no charset. The server sends bytes and never
/// says what it meant by them, so the reader has to guess, and that guess is
/// the difference between a title and a row of replacement marks.
///
/// UTF-8 first: Icecast has required it since 2.4, and it is what most of the
/// estate now sends. Bytes that are not UTF-8 are almost always Windows-1252,
/// the codepage of the machines the older servers run on, so that is the
/// fallback. `from_utf8_lossy` used to stand in for both, and it turned every
/// byte it could not read into U+FFFD: a station sending the curly apostrophe
/// at 0x92 showed `It?s not you` with a replacement mark where the apostrophe
/// belonged.
///
/// Windows-1252 also decodes every possible byte, so there is no second
/// failure to handle. It remains a guess: a station broadcasting CP1251
/// Cyrillic comes out as the wrong letters rather than as no letters. That is
/// what other players show for it too, and wrong letters can at least be
/// recognised - a run of U+FFFD cannot.
pub fn decode_text(bytes: &[u8]) -> String {
    match std::str::from_utf8(bytes) {
        Ok(text) => text.to_string(),
        Err(_) => bytes.iter().map(|&b| windows_1252(b)).collect(),
    }
}

/// One Windows-1252 byte as a character. Only 0x80-0x9F differs from Latin-1:
/// the codepage puts printable punctuation - the curly quotes, the dashes, the
/// euro sign - in the range Latin-1 leaves as control codes, and those are
/// precisely the bytes that turn up in a mangled title.
fn windows_1252(byte: u8) -> char {
    const HIGH: [char; 32] = [
        '\u{20ac}', '\u{81}', '\u{201a}', '\u{192}', '\u{201e}', '\u{2026}', '\u{2020}',
        '\u{2021}', '\u{2c6}', '\u{2030}', '\u{160}', '\u{2039}', '\u{152}', '\u{8d}', '\u{17d}',
        '\u{8f}', '\u{90}', '\u{2018}', '\u{2019}', '\u{201c}', '\u{201d}', '\u{2022}', '\u{2013}',
        '\u{2014}', '\u{2dc}', '\u{2122}', '\u{161}', '\u{203a}', '\u{153}', '\u{9d}', '\u{17e}',
        '\u{178}',
    ];
    match byte {
        0x80..=0x9f => HIGH[byte as usize - 0x80],
        _ => byte as char,
    }
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

    /// An ICY stream and the audio that is hiding in it: `metaint` bytes, a
    /// length byte in sixteens, the block, and round again. The audio is a
    /// counting pattern so a byte lost or gained anywhere shows up.
    fn interleaved(metaint: usize, blocks: &[&str]) -> (Vec<u8>, Vec<u8>) {
        let mut wire = Vec::new();
        let mut audio = Vec::new();
        for (n, block) in blocks.iter().enumerate() {
            let run: Vec<u8> = (0..metaint).map(|i| (i + n) as u8).collect();
            wire.extend_from_slice(&run);
            audio.extend_from_slice(&run);
            let mut padded = block.as_bytes().to_vec();
            while padded.len() % 16 != 0 {
                padded.push(0);
            }
            wire.push((padded.len() / 16) as u8);
            wire.extend_from_slice(&padded);
        }
        (wire, audio)
    }

    #[test]
    fn the_audio_comes_out_whole_however_the_chunks_fall() {
        // The third block is the empty one servers send between tracks.
        let (wire, want) = interleaved(
            64,
            &["StreamTitle='One - First';", "StreamTitle='Two - Second';", ""],
        );
        // A read can stop anywhere: mid-audio, on the length byte, inside a
        // block. Every split has to leave the audio identical.
        for size in [1, 2, 3, 16, 63, 64, 65, 100, 4096] {
            let mut strip = MetaStrip::new(64);
            let mut audio = Vec::new();
            let mut titles = Vec::new();
            for piece in wire.chunks(size) {
                titles.extend(strip.push(piece, &mut audio));
            }
            assert_eq!(audio, want, "audio, chunked by {size}");
            assert_eq!(titles, ["One - First", "Two - Second"], "titles by {size}");
        }
    }

    #[test]
    fn a_stream_with_no_metadata_is_copied_untouched() {
        let mut strip = MetaStrip::new(0);
        let mut audio = Vec::new();
        assert!(strip.push(b"just audio", &mut audio).is_empty());
        assert_eq!(audio, b"just audio".to_vec());
    }

    #[test]
    fn a_repeated_title_is_reported_every_time_it_is_sent() {
        // The relay reports what arrived; deciding that a title is the same
        // one as last time is the front end's business, not this one's.
        let (wire, _) = interleaved(16, &["StreamTitle='Same';", "StreamTitle='Same';"]);
        let mut strip = MetaStrip::new(16);
        let mut audio = Vec::new();
        assert_eq!(strip.push(&wire, &mut audio), ["Same", "Same"]);
    }

    #[test]
    fn utf8_metadata_is_read_as_utf8() {
        assert_eq!(decode_text("Björk - Jóga".as_bytes()), "Björk - Jóga");
        assert_eq!(decode_text("猫 シ Corp.".as_bytes()), "猫 シ Corp.");
    }

    #[test]
    fn a_byte_that_is_not_utf8_becomes_its_character_not_a_replacement_mark() {
        // 0x92 is the curly apostrophe in Windows-1252 and is not valid UTF-8
        // in any position - the byte behind titles that read `It<fffd>s`.
        assert_eq!(decode_text(b"It\x92s not you"), "It\u{2019}s not you");
        assert_eq!(decode_text(b"caf\xe9 \x96 live"), "café \u{2013} live");
    }

    #[test]
    fn every_byte_decodes_to_something() {
        let all: Vec<u8> = (0..=255).collect();
        let text = decode_text(&all);
        assert_eq!(text.chars().count(), 256);
        assert!(!text.contains('\u{fffd}'));
    }

    #[test]
    fn a_title_in_windows_1252_survives_the_scan() {
        let mut block = b"StreamTitle='Bj\xf6rk - It\x92s in our hands';".to_vec();
        while block.len() % 16 != 0 {
            block.push(0);
        }
        let mut buf = vec![b'a'; 16];
        buf.push((block.len() / 16) as u8);
        buf.extend_from_slice(&block);
        let scan = scan_metadata(&buf, 16, 0);
        assert_eq!(
            scan.title.as_deref(),
            Some("Björk - It\u{2019}s in our hands")
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

    #[test]
    fn strips_intervals_larger_than_a_probe_can_buffer() {
        for metaint in [512 * 1024, 512 * 1024 + 1, 1024 * 1024] {
            let raw = metaint.to_string();
            let interval = metadata_interval(Some(&raw)).unwrap();
            let mut strip = MetaStrip::new(interval);
            let stream = stream_with(metaint, &["StreamTitle='Long interval';"]);
            let mut audio = Vec::new();
            let mut titles = Vec::new();
            for chunk in stream.chunks(8191) {
                titles.extend(strip.push(chunk, &mut audio));
            }
            assert_eq!(audio, vec![b'a'; metaint]);
            assert_eq!(titles, ["Long interval"]);
        }
    }

    #[test]
    fn invalid_intervals_cannot_silently_turn_stripping_off() {
        assert_eq!(metadata_interval(None), Ok(0));
        assert_eq!(metadata_interval(Some("0")), Ok(0));
        assert_eq!(metadata_interval(Some(&usize::MAX.to_string())), Ok(usize::MAX));
        for raw in ["", "abc", "-1", "184467440737095516160"] {
            assert!(metadata_interval(Some(raw)).is_err());
        }
    }
}
