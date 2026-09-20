//! Metadata carried by the audio container rather than by ICY intervals.
//!
//! Ogg radio streams announce a new track with the Vorbis comments in each
//! chained logical stream. FLAC can carry the same comment block at the start
//! of a stream (and again if a broadcaster chains another stream). These
//! readers only observe those bytes: the relay still forwards them unchanged
//! because the decoder needs the container headers too.

use std::collections::{HashMap, VecDeque};

const MAX_COMMENT_BYTES: usize = 256 * 1024;
const MAX_OGG_STREAMS: usize = 8;

/// A bounded, incremental observer for the native tags a stream format owns.
pub struct StreamTags {
    parser: Parser,
}

enum Parser {
    Ogg(OggTags),
    Flac(FlacTags),
}

impl StreamTags {
    /// Build a reader when either the response type or final URL identifies a
    /// container whose live tags we understand.
    pub fn for_stream(content_type: &str, url: &str) -> Option<Self> {
        let mime = content_type
            .split(';')
            .next()
            .unwrap_or(content_type)
            .trim()
            .to_ascii_lowercase();
        let path = url
            .split(['?', '#'])
            .next()
            .unwrap_or(url)
            .to_ascii_lowercase();
        let parser = if mime.contains("ogg")
            || mime.contains("opus")
            || mime.contains("vorbis")
            || path.ends_with(".ogg")
            || path.ends_with(".oga")
            || path.ends_with(".opus")
        {
            Parser::Ogg(OggTags::default())
        } else if mime.contains("flac") || path.ends_with(".flac") {
            Parser::Flac(FlacTags::default())
        } else {
            return None;
        };
        Some(Self { parser })
    }

    /// Observe another run of container bytes. An empty string is an explicit
    /// title clear; an empty vector means the stream announced no change.
    pub fn push(&mut self, chunk: &[u8]) -> Vec<String> {
        match &mut self.parser {
            Parser::Ogg(parser) => parser.push(chunk),
            Parser::Flac(parser) => parser.push(chunk),
        }
    }

    /// The first container header has said all it can say. Probes may stop at
    /// this point; the relay keeps the reader alive for later chained headers.
    pub fn initial_done(&self) -> bool {
        match &self.parser {
            Parser::Ogg(parser) => parser.initial_done,
            Parser::Flac(parser) => parser.initial_done,
        }
    }
}

#[derive(Default)]
struct OggTags {
    page: Vec<u8>,
    streams: HashMap<u32, OggPacket>,
    stream_order: VecDeque<u32>,
    initial_done: bool,
}

#[derive(Default)]
struct OggPacket {
    bytes: Vec<u8>,
    too_large: bool,
    next_sequence: Option<u32>,
}

impl OggTags {
    fn push(&mut self, chunk: &[u8]) -> Vec<String> {
        self.page.extend_from_slice(chunk);
        let mut updates = Vec::new();

        loop {
            let Some(at) = find(&self.page, b"OggS") else {
                keep_tail(&mut self.page, 3);
                break;
            };
            if at > 0 {
                self.page.drain(..at);
            }
            if self.page.len() < 27 {
                break;
            }
            if self.page[4] != 0 {
                self.page.drain(..1);
                continue;
            }
            let segments = self.page[26] as usize;
            if self.page.len() < 27 + segments {
                break;
            }
            let body_len: usize = self.page[27..27 + segments]
                .iter()
                .map(|&n| n as usize)
                .sum();
            let page_len = 27 + segments + body_len;
            if self.page.len() < page_len {
                break;
            }

            let header_type = self.page[5];
            let serial = u32::from_le_bytes(self.page[14..18].try_into().unwrap());
            let sequence = u32::from_le_bytes(self.page[18..22].try_into().unwrap());
            if !self.streams.contains_key(&serial) {
                while self.stream_order.len() >= MAX_OGG_STREAMS {
                    if let Some(oldest) = self.stream_order.pop_front() {
                        self.streams.remove(&oldest);
                    }
                }
                self.streams.insert(serial, OggPacket::default());
                self.stream_order.push_back(serial);
            }
            let continued = header_type & 0x01 != 0;
            let laces: Vec<u8> = self.page[27..27 + segments].to_vec();
            let mut body_at = 27 + segments;
            let mut saw_comments = false;
            {
                let packet = self.streams.get_mut(&serial).unwrap();
                if header_type & 0x02 != 0
                    || packet.next_sequence.is_some_and(|next| next != sequence)
                {
                    packet.bytes.clear();
                    packet.too_large = false;
                }
                packet.next_sequence = Some(sequence.wrapping_add(1));
                let mut discard_packet = continued && packet.bytes.is_empty();
                if !continued && (!packet.bytes.is_empty() || packet.too_large) {
                    packet.bytes.clear();
                    packet.too_large = false;
                }

                for lace in laces {
                    let length = lace as usize;
                    if !discard_packet {
                        if !packet.too_large
                            && packet.bytes.len().saturating_add(length) <= MAX_COMMENT_BYTES
                        {
                            packet
                                .bytes
                                .extend_from_slice(&self.page[body_at..body_at + length]);
                        } else {
                            packet.bytes.clear();
                            packet.too_large = true;
                        }
                    }
                    body_at += length;

                    if lace < 255 {
                        if discard_packet {
                            discard_packet = false;
                        } else if !packet.too_large {
                            if let Some(update) = ogg_packet_title(&packet.bytes) {
                                saw_comments = true;
                                if let Some(update) = update {
                                    updates.push(update);
                                }
                            }
                        }
                        packet.bytes.clear();
                        packet.too_large = false;
                    }
                }
            }
            self.initial_done |= saw_comments;
            self.page.drain(..page_len);
        }

        updates
    }
}

fn ogg_packet_title(packet: &[u8]) -> Option<Option<String>> {
    let comments = if packet.starts_with(b"OpusTags") {
        Some(&packet[8..])
    } else if packet.starts_with(b"\x03vorbis") {
        Some(&packet[7..])
    } else {
        None
    }?;
    Some(title_from_comments(comments))
}

enum FlacMode {
    Seek,
    Header,
    Body {
        kind: u8,
        last: bool,
        left: usize,
        comment: Option<Vec<u8>>,
    },
}

struct FlacTags {
    input: Vec<u8>,
    mode: FlacMode,
    initial_done: bool,
}

impl Default for FlacTags {
    fn default() -> Self {
        Self {
            input: Vec::new(),
            mode: FlacMode::Seek,
            initial_done: false,
        }
    }
}

impl FlacTags {
    fn push(&mut self, chunk: &[u8]) -> Vec<String> {
        self.input.extend_from_slice(chunk);
        let mut updates = Vec::new();

        loop {
            match &mut self.mode {
                FlacMode::Seek => {
                    let Some(at) = find(&self.input, b"fLaC") else {
                        keep_tail(&mut self.input, 3);
                        break;
                    };
                    if self.input.len() < at + 8 {
                        if at > 0 {
                            self.input.drain(..at);
                        }
                        break;
                    }
                    // A native FLAC stream starts with a 34-byte STREAMINFO
                    // block. Requiring that shape keeps an `fLaC` byte run in
                    // compressed audio from inventing a chained stream.
                    let first = self.input[at + 4];
                    let length = be24(&self.input[at + 5..at + 8]);
                    if first & 0x7f != 0 || length != 34 {
                        self.input.drain(..at + 1);
                        continue;
                    }
                    self.input.drain(..at + 4);
                    self.mode = FlacMode::Header;
                }
                FlacMode::Header => {
                    if self.input.len() < 4 {
                        break;
                    }
                    let first = self.input[0];
                    let kind = first & 0x7f;
                    let last = first & 0x80 != 0;
                    let length = be24(&self.input[1..4]);
                    self.input.drain(..4);
                    if kind > 6 {
                        self.mode = FlacMode::Seek;
                        continue;
                    }
                    self.mode = FlacMode::Body {
                        kind,
                        last,
                        left: length,
                        comment: (kind == 4 && length <= MAX_COMMENT_BYTES)
                            .then(|| Vec::with_capacity(length)),
                    };
                }
                FlacMode::Body {
                    kind,
                    last,
                    left,
                    comment,
                } => {
                    let take = (*left).min(self.input.len());
                    if let Some(body) = comment {
                        body.extend_from_slice(&self.input[..take]);
                    }
                    self.input.drain(..take);
                    *left -= take;
                    if *left > 0 {
                        break;
                    }

                    let kind = *kind;
                    let last = *last;
                    if kind == 4 {
                        self.initial_done = true;
                        if let Some(update) = comment.as_deref().and_then(title_from_comments) {
                            updates.push(update);
                        }
                    }
                    if last {
                        self.initial_done = true;
                        self.mode = FlacMode::Seek;
                    } else {
                        self.mode = FlacMode::Header;
                    }
                }
            }
        }

        updates
    }
}

fn title_from_comments(mut bytes: &[u8]) -> Option<String> {
    let vendor_len = take_le32(&mut bytes)?;
    take(&mut bytes, vendor_len)?;
    let comments = take_le32(&mut bytes)?;
    // Each entry needs at least its four-byte length. This also stops a bogus
    // count from turning a tiny malformed packet into a long loop.
    if comments > bytes.len() / 4 {
        return None;
    }

    let mut title: Option<String> = None;
    let mut artist: Option<String> = None;
    for _ in 0..comments {
        let length = take_le32(&mut bytes)?;
        let field = take(&mut bytes, length)?;
        let Some(equal) = field.iter().position(|&byte| byte == b'=') else {
            continue;
        };
        let Ok(key) = std::str::from_utf8(&field[..equal]) else {
            continue;
        };
        let Ok(value) = std::str::from_utf8(&field[equal + 1..]) else {
            continue;
        };
        let value = value.trim().to_string();
        if key.eq_ignore_ascii_case("TITLE") && title.is_none() {
            title = Some(value);
        } else if key.eq_ignore_ascii_case("ARTIST") && artist.is_none() {
            artist = Some(value);
        }
    }

    match title {
        Some(title) if title.is_empty() => Some(String::new()),
        Some(title) => match artist.filter(|artist| !artist.is_empty()) {
            Some(artist) => Some(format!("{artist} — {title}")),
            None => Some(title),
        },
        None => artist.filter(|artist| !artist.is_empty()),
    }
}

fn take_le32(bytes: &mut &[u8]) -> Option<usize> {
    let raw: [u8; 4] = take(bytes, 4)?.try_into().ok()?;
    usize::try_from(u32::from_le_bytes(raw)).ok()
}

fn take<'a>(bytes: &mut &'a [u8], length: usize) -> Option<&'a [u8]> {
    if length > bytes.len() {
        return None;
    }
    let (head, tail) = bytes.split_at(length);
    *bytes = tail;
    Some(head)
}

fn be24(bytes: &[u8]) -> usize {
    (bytes[0] as usize) << 16 | (bytes[1] as usize) << 8 | bytes[2] as usize
}

fn find(haystack: &[u8], needle: &[u8]) -> Option<usize> {
    haystack
        .windows(needle.len())
        .position(|window| window == needle)
}

fn keep_tail(bytes: &mut Vec<u8>, count: usize) {
    if bytes.len() > count {
        bytes.drain(..bytes.len() - count);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn comments(fields: &[&str]) -> Vec<u8> {
        let vendor = b"Aerowave fixture";
        let mut out = Vec::new();
        out.extend_from_slice(&(vendor.len() as u32).to_le_bytes());
        out.extend_from_slice(vendor);
        out.extend_from_slice(&(fields.len() as u32).to_le_bytes());
        for field in fields {
            out.extend_from_slice(&(field.len() as u32).to_le_bytes());
            out.extend_from_slice(field.as_bytes());
        }
        out
    }

    fn ogg_page(serial: u32, sequence: u32, packet: &[u8]) -> Vec<u8> {
        let mut laces = vec![255; packet.len() / 255];
        laces.push((packet.len() % 255) as u8);
        let mut out = vec![0; 27];
        out[..4].copy_from_slice(b"OggS");
        out[4] = 0;
        out[5] = if sequence == 0 { 2 } else { 0 };
        out[14..18].copy_from_slice(&serial.to_le_bytes());
        out[18..22].copy_from_slice(&sequence.to_le_bytes());
        out[26] = laces.len() as u8;
        out.extend_from_slice(&laces);
        out.extend_from_slice(packet);
        out
    }

    fn ogg_packet_page_vecs(serial: u32, packet: &[u8]) -> Vec<Vec<u8>> {
        let mut out = Vec::new();
        let mut at = 0usize;
        let mut sequence = 0u32;
        loop {
            let left = packet.len() - at;
            let full_segments = (left / 255).min(255);
            let ends_here = full_segments < 255;
            let remainder = if ends_here { left % 255 } else { 0 };
            let mut laces = vec![255; full_segments];
            if ends_here {
                laces.push(remainder as u8);
            }
            let body_len: usize = laces.iter().map(|&lace| lace as usize).sum();
            let mut page = vec![0; 27];
            page[..4].copy_from_slice(b"OggS");
            page[5] = if sequence == 0 { 2 } else { 1 };
            page[14..18].copy_from_slice(&serial.to_le_bytes());
            page[18..22].copy_from_slice(&sequence.to_le_bytes());
            page[26] = laces.len() as u8;
            page.extend(laces);
            page.extend_from_slice(&packet[at..at + body_len]);
            out.push(page);
            at += body_len;
            sequence += 1;
            if ends_here {
                break;
            }
        }
        out
    }

    fn ogg_packet_pages(serial: u32, packet: &[u8]) -> Vec<u8> {
        ogg_packet_page_vecs(serial, packet).concat()
    }

    fn opus_tags(serial: u32, sequence: u32, fields: &[&str]) -> Vec<u8> {
        let mut packet = b"OpusTags".to_vec();
        packet.extend(comments(fields));
        ogg_page(serial, sequence, &packet)
    }

    fn vorbis_tags(serial: u32, sequence: u32, fields: &[&str]) -> Vec<u8> {
        let mut packet = b"\x03vorbis".to_vec();
        packet.extend(comments(fields));
        packet.push(1);
        ogg_page(serial, sequence, &packet)
    }

    fn flac(fields: Option<&[&str]>) -> Vec<u8> {
        let mut out = b"fLaC".to_vec();
        let streaminfo_last = fields.is_none();
        out.push(if streaminfo_last { 0x80 } else { 0 });
        out.extend_from_slice(&[0, 0, 34]);
        out.extend_from_slice(&[0; 34]);
        if let Some(fields) = fields {
            let body = comments(fields);
            out.push(0x80 | 4);
            out.push(((body.len() >> 16) & 0xff) as u8);
            out.push(((body.len() >> 8) & 0xff) as u8);
            out.push((body.len() & 0xff) as u8);
            out.extend(body);
        }
        out
    }

    #[test]
    fn content_type_or_extension_selects_the_reader() {
        assert!(StreamTags::for_stream("application/ogg", "https://x/live").is_some());
        assert!(StreamTags::for_stream("audio/opus", "https://x/live").is_some());
        assert!(StreamTags::for_stream("", "https://x/live.oga?token=1").is_some());
        assert!(StreamTags::for_stream("audio/flac; charset=binary", "https://x/live").is_some());
        assert!(StreamTags::for_stream("", "https://x/live.FLAC#x").is_some());
        assert!(StreamTags::for_stream("audio/mpeg", "https://x/live").is_none());
    }

    #[test]
    fn opus_tags_survive_every_chunk_boundary_and_chained_stream() {
        let mut wire = ogg_page(1, 0, b"OpusHead");
        wire.extend(opus_tags(
            1,
            1,
            &["ARTIST=Manuru. 猫", "TITLE=Virtual Landscape"],
        ));
        wire.extend(ogg_page(2, 0, b"OpusHead"));
        wire.extend(opus_tags(2, 1, &["ARTIST=猫 シ Corp.", "TITLE=Sunday"]));

        for size in 1..=97 {
            let mut tags = StreamTags::for_stream("audio/ogg", "https://x/live").unwrap();
            let mut got = Vec::new();
            for chunk in wire.chunks(size) {
                got.extend(tags.push(chunk));
            }
            assert_eq!(
                got,
                ["Manuru. 猫 — Virtual Landscape", "猫 シ Corp. — Sunday"],
                "chunk size {size}"
            );
            assert!(tags.initial_done());
        }
    }

    #[test]
    fn vorbis_title_and_explicit_clear_are_distinct_from_no_title() {
        let mut wire = vorbis_tags(1, 0, &["TITLE=LARA FABIAN - JE TAIME"]);
        wire.extend(vorbis_tags(2, 0, &["ENCODER=Liquidsoap"]));
        wire.extend(vorbis_tags(3, 0, &["TITLE=   "]));
        let mut tags = StreamTags::for_stream("application/ogg", "https://x/live").unwrap();
        assert_eq!(
            tags.push(&wire),
            ["LARA FABIAN - JE TAIME".to_string(), String::new()]
        );
    }

    #[test]
    fn flac_comments_survive_chunks_and_chained_streams() {
        let mut wire = flac(Some(&["ARTIST=One", "TITLE=First"]));
        wire.extend_from_slice(&[0xff, 0xf8, 0, 1, 2, 3]);
        wire.extend(flac(Some(&["TITLE=Second"])));
        for size in 1..=71 {
            let mut tags = StreamTags::for_stream("audio/flac", "https://x/live").unwrap();
            let mut got = Vec::new();
            for chunk in wire.chunks(size) {
                got.extend(tags.push(chunk));
            }
            assert_eq!(got, ["One — First", "Second"], "chunk size {size}");
            assert!(tags.initial_done());
        }
    }

    #[test]
    fn a_flac_stream_without_comments_finishes_initial_metadata() {
        let mut tags = StreamTags::for_stream("audio/flac", "https://x/live").unwrap();
        assert!(tags.push(&flac(None)).is_empty());
        assert!(tags.initial_done());
    }

    #[test]
    fn malformed_and_oversized_packets_do_not_poison_the_next_chain() {
        let mut bad = b"OpusTags".to_vec();
        bad.extend_from_slice(&u32::MAX.to_le_bytes());
        let mut wire = ogg_page(1, 0, &bad);
        wire.extend(opus_tags(2, 0, &["TITLE=Recovered"]));
        let mut tags = StreamTags::for_stream("audio/ogg", "https://x/live").unwrap();
        assert_eq!(tags.push(&wire), ["Recovered"]);

        let mut huge = vec![b'x'; MAX_COMMENT_BYTES + 1];
        huge[..8].copy_from_slice(b"OpusTags");
        let mut wire = ogg_packet_pages(3, &huge);
        // A new serial safely abandons an overlarge multi-page packet.
        wire.extend(opus_tags(4, 0, &["TITLE=Still recovered"]));
        for size in [1, 251, 8191, wire.len()] {
            let mut tags = StreamTags::for_stream("audio/ogg", "https://x/live").unwrap();
            let mut got = Vec::new();
            for chunk in wire.chunks(size) {
                got.extend(tags.push(chunk));
            }
            assert_eq!(got, ["Still recovered"], "chunk size {size}");
        }
    }

    #[test]
    fn interleaved_serial_does_not_discard_a_partial_comment_packet() {
        let mut packet = b"OpusTags".to_vec();
        let vendor = vec![b'x'; 70_000];
        packet.extend_from_slice(&(vendor.len() as u32).to_le_bytes());
        packet.extend_from_slice(&vendor);
        packet.extend_from_slice(&1u32.to_le_bytes());
        packet.extend_from_slice(&7u32.to_le_bytes());
        packet.extend_from_slice(b"TITLE=A");
        let pages = ogg_packet_page_vecs(1, &packet);
        assert!(pages.len() > 1);

        let mut wire = pages[0].clone();
        wire.extend(opus_tags(2, 0, &["TITLE=B"]));
        for page in &pages[1..] {
            wire.extend(page);
        }

        for size in [1, 509, 8191, wire.len()] {
            let mut tags = StreamTags::for_stream("audio/ogg", "https://x/live").unwrap();
            let mut got = Vec::new();
            for chunk in wire.chunks(size) {
                got.extend(tags.push(chunk));
            }
            assert_eq!(got, ["B", "A"], "chunk size {size}");
        }
    }

    #[test]
    fn a_missing_ogg_page_discards_its_partial_packet_and_recovers() {
        let mut packet = b"OpusTags".to_vec();
        let vendor = vec![b'x'; 70_000];
        packet.extend_from_slice(&(vendor.len() as u32).to_le_bytes());
        packet.extend_from_slice(&vendor);
        packet.extend_from_slice(&1u32.to_le_bytes());
        packet.extend_from_slice(&7u32.to_le_bytes());
        packet.extend_from_slice(b"TITLE=A");
        let mut pages = ogg_packet_page_vecs(1, &packet);
        assert!(pages.len() > 1);
        pages[1][18..22].copy_from_slice(&2u32.to_le_bytes());

        let mut wire = pages.concat();
        wire.extend(opus_tags(2, 0, &["TITLE=Recovered"]));
        let mut tags = StreamTags::for_stream("audio/ogg", "https://x/live").unwrap();
        assert_eq!(tags.push(&wire), ["Recovered"]);
    }

    #[test]
    fn malformed_flac_marker_is_skipped_before_a_real_stream() {
        let mut wire = b"junkfLaC\x00\x00\x00\x01x".to_vec();
        wire.extend(flac(Some(&["TITLE=Recovered"])));
        let mut tags = StreamTags::for_stream("audio/flac", "https://x/live").unwrap();
        assert_eq!(tags.push(&wire), ["Recovered"]);
    }
}
