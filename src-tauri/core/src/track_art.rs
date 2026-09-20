//! Bounded extraction of embedded artwork from local audio files.

use std::io::{self, Read, Seek, SeekFrom};

use base64::engine::general_purpose::STANDARD as BASE64;
use base64::Engine as _;
use lofty::config::{apply_global_options, GlobalOptions, ParseOptions};
use lofty::file::TaggedFileExt as _;
use lofty::picture::{Picture, PictureType};
use lofty::probe::Probe;

/// Large enough for unusually detailed cover art without letting a malformed
/// tag turn a shuffle transition into an unbounded allocation.
pub const MAX_ARTWORK_BYTES: usize = 8 * 1024 * 1024;

// Lofty's allocation guard covers the tag formats that allocate from a
// declared item size. Some readers grow a Vec while consuming the item, so
// the input itself also gets a finite budget before parsing starts.
const PARSE_READ_BUDGET: usize = MAX_ARTWORK_BYTES + 1024 * 1024;

/// Read the first usable front cover, or the first usable picture when the
/// format has no picture roles (MP4 is one example).
///
/// Invalid containers, malformed tags, unsupported images and oversized art
/// are all absence rather than playback errors. The caller can keep playing
/// the track and show the normal orb.
pub fn embedded_artwork_data_url<R>(reader: &mut R) -> Option<String>
where
    R: Read + Seek,
{
    // These options are thread-local in Lofty. The app calls this function on
    // a blocking worker, so applying them here covers the thread doing the
    // allocations rather than whichever async thread dispatched the work.
    apply_global_options(
        GlobalOptions::new()
            .allocation_limit(MAX_ARTWORK_BYTES)
            .use_custom_resolvers(false)
            .preserve_format_specific_items(false),
    );

    let reader = ReadBudget::new(reader, PARSE_READ_BUDGET);
    let options = ParseOptions::new()
        .read_properties(false)
        .read_cover_art(true);
    let tagged = Probe::new(reader)
        .guess_file_type()
        .ok()?
        .options(options)
        .read()
        .ok()?;

    picture_data_url(tagged.tags().iter().flat_map(|tag| tag.pictures().iter()))
}

fn picture_data_url<'a>(pictures: impl IntoIterator<Item = &'a Picture>) -> Option<String> {
    let mut fallback = None;
    for picture in pictures {
        let Some(mime) = supported_image_mime(picture.data()) else {
            continue;
        };
        if picture.data().is_empty() || picture.data().len() > MAX_ARTWORK_BYTES {
            continue;
        }

        if picture.pic_type() == PictureType::CoverFront {
            return Some(format_data_url(mime, picture.data()));
        }
        fallback.get_or_insert((mime, picture.data()));
    }

    fallback.map(|(mime, data)| format_data_url(mime, data))
}

fn supported_image_mime(data: &[u8]) -> Option<&'static str> {
    if data.starts_with(b"\x89PNG\r\n\x1a\n") {
        Some("image/png")
    } else if data.starts_with(&[0xff, 0xd8, 0xff]) {
        Some("image/jpeg")
    } else if data.starts_with(b"GIF87a") || data.starts_with(b"GIF89a") {
        Some("image/gif")
    } else if data.starts_with(b"BM") {
        Some("image/bmp")
    } else if data.len() >= 12 && data.starts_with(b"RIFF") && &data[8..12] == b"WEBP" {
        Some("image/webp")
    } else {
        None
    }
}

fn format_data_url(mime: &str, data: &[u8]) -> String {
    format!("data:{mime};base64,{}", BASE64.encode(data))
}

struct ReadBudget<R> {
    inner: R,
    remaining: usize,
}

impl<R> ReadBudget<R> {
    fn new(inner: R, limit: usize) -> Self {
        Self {
            inner,
            remaining: limit,
        }
    }
}

impl<R: Read> Read for ReadBudget<R> {
    fn read(&mut self, buf: &mut [u8]) -> io::Result<usize> {
        if buf.is_empty() {
            return Ok(0);
        }
        if self.remaining == 0 {
            return Err(io::Error::new(
                io::ErrorKind::InvalidData,
                "metadata read budget exceeded",
            ));
        }

        let len = buf.len().min(self.remaining);
        let read = self.inner.read(&mut buf[..len])?;
        self.remaining -= read;
        Ok(read)
    }
}

impl<R: Seek> Seek for ReadBudget<R> {
    fn seek(&mut self, pos: SeekFrom) -> io::Result<u64> {
        self.inner.seek(pos)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use lofty::picture::{MimeType, Picture};
    use std::io::Cursor;

    // A real 1x1 transparent PNG keeps the fixtures representative without
    // making the repository carry binary test files.
    const PNG: &[u8] = &[
        0x89, b'P', b'N', b'G', 0x0d, 0x0a, 0x1a, 0x0a, 0x00, 0x00, 0x00, 0x0d, b'I', b'H', b'D',
        b'R', 0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01, 0x08, 0x06, 0x00, 0x00, 0x00, 0x1f,
        0x15, 0xc4, 0x89, 0x00, 0x00, 0x00, 0x0d, b'I', b'D', b'A', b'T', 0x08, 0xd7, 0x63, 0xf8,
        0xcf, 0xc0, 0xf0, 0x1f, 0x00, 0x05, 0x00, 0x01, 0xff, 0x89, 0x99, 0x3d, 0x1d, 0x00, 0x00,
        0x00, 0x00, b'I', b'E', b'N', b'D', 0xae, 0x42, 0x60, 0x82,
    ];

    #[test]
    fn reads_front_cover_from_an_mp3_id3_tag() {
        let back = [0xff, 0xd8, 0xff, 0xd9];
        let bytes = mp3_with_pictures(&[
            (PictureType::CoverBack, &back),
            (PictureType::CoverFront, PNG),
        ]);

        let actual = embedded_artwork_data_url(&mut Cursor::new(bytes));

        assert_eq!(actual, Some(format_data_url("image/png", PNG)));
    }

    #[test]
    fn reads_native_flac_picture_block() {
        let bytes = flac_with_picture(PictureType::CoverFront, PNG);

        let actual = embedded_artwork_data_url(&mut Cursor::new(bytes));

        assert_eq!(actual, Some(format_data_url("image/png", PNG)));
    }

    #[test]
    fn reads_m4a_covr_atom_without_audio_properties() {
        let bytes = m4a_with_picture(PNG);

        let actual = embedded_artwork_data_url(&mut Cursor::new(bytes));

        assert_eq!(actual, Some(format_data_url("image/png", PNG)));
    }

    #[test]
    fn reads_opus_metadata_block_picture() {
        let bytes = opus_with_picture(PictureType::CoverFront, PNG);

        let actual = embedded_artwork_data_url(&mut Cursor::new(bytes));

        assert_eq!(actual, Some(format_data_url("image/png", PNG)));
    }

    #[test]
    fn corrupt_unknown_and_absent_art_are_none() {
        assert_eq!(
            embedded_artwork_data_url(&mut Cursor::new(b"not audio")),
            None
        );
        assert_eq!(
            embedded_artwork_data_url(&mut Cursor::new(mp3_with_pictures(&[]))),
            None
        );

        let invalid = Picture::new_unchecked(
            PictureType::CoverFront,
            Some(MimeType::Png),
            None,
            b"not a png".to_vec(),
        );
        assert_eq!(picture_data_url([&invalid]), None);
    }

    #[test]
    fn artwork_larger_than_the_limit_is_rejected() {
        let mut bytes = vec![0; MAX_ARTWORK_BYTES + 1];
        bytes[..PNG.len()].copy_from_slice(PNG);
        let picture =
            Picture::new_unchecked(PictureType::CoverFront, Some(MimeType::Png), None, bytes);

        assert_eq!(picture_data_url([&picture]), None);
    }

    #[test]
    fn read_budget_fails_before_consuming_unbounded_input() {
        let mut reader = ReadBudget::new(Cursor::new([0; 32]), 8);
        let mut bytes = [0; 16];

        assert_eq!(reader.read(&mut bytes).unwrap(), 8);
        assert_eq!(
            reader.read(&mut bytes).unwrap_err().kind(),
            io::ErrorKind::InvalidData
        );
    }

    fn mp3_with_pictures(pictures: &[(PictureType, &[u8])]) -> Vec<u8> {
        let mut tag = Vec::new();
        for (picture_type, data) in pictures {
            let mut frame = Vec::new();
            frame.push(0); // Latin-1 description
            frame.extend_from_slice(b"image/png\0");
            frame.push(picture_type.as_u8());
            frame.push(0); // Empty description
            frame.extend_from_slice(data);

            tag.extend_from_slice(b"APIC");
            tag.extend_from_slice(&(frame.len() as u32).to_be_bytes());
            tag.extend_from_slice(&[0, 0]);
            tag.extend_from_slice(&frame);
        }

        let mut out = Vec::new();
        out.extend_from_slice(b"ID3\x03\x00\x00");
        out.extend_from_slice(&synchsafe(tag.len()));
        out.extend_from_slice(&tag);
        // Two ordinary MPEG-1 Layer III frames let Lofty identify where the
        // audio begins without asking it to read any audio properties.
        let mut frame = vec![0; 417];
        frame[..4].copy_from_slice(&[0xff, 0xfb, 0x90, 0x64]);
        out.extend_from_slice(&frame);
        out.extend_from_slice(&frame);
        out
    }

    fn flac_with_picture(picture_type: PictureType, data: &[u8]) -> Vec<u8> {
        let picture = flac_picture(picture_type, data);
        let mut out = b"fLaC".to_vec();
        out.push(0); // STREAMINFO, not the last metadata block
        out.extend_from_slice(&[0, 0, 34]);
        out.extend_from_slice(&[0; 34]);
        out.push(0x80 | 6); // Last metadata block, PICTURE
        out.extend_from_slice(&u24(picture.len()));
        out.extend_from_slice(&picture);
        out
    }

    fn m4a_with_picture(data: &[u8]) -> Vec<u8> {
        let mut data_payload = vec![0, 0, 0, 14, 0, 0, 0, 0]; // PNG, locale 0
        data_payload.extend_from_slice(data);
        let covr = atom(b"covr", &atom(b"data", &data_payload));
        let ilst = atom(b"ilst", &covr);
        let mut meta_payload = vec![0; 4]; // Full-atom version and flags
        meta_payload.extend_from_slice(&ilst);
        let meta = atom(b"meta", &meta_payload);
        let udta = atom(b"udta", &meta);
        let moov = atom(b"moov", &udta);
        let ftyp_payload = b"M4A \x00\x00\x00\x00M4A isom".to_vec();
        let mut out = atom(b"ftyp", &ftyp_payload);
        out.extend_from_slice(&moov);
        out
    }

    fn opus_with_picture(picture_type: PictureType, data: &[u8]) -> Vec<u8> {
        let head = b"OpusHead\x01\x02\x00\x00\x80\xbb\x00\x00\x00\x00\x00";
        let picture = BASE64.encode(flac_picture(picture_type, data));
        let comment = format!("METADATA_BLOCK_PICTURE={picture}");
        let mut tags = b"OpusTags".to_vec();
        tags.extend_from_slice(&8u32.to_le_bytes());
        tags.extend_from_slice(b"Aerowave");
        tags.extend_from_slice(&1u32.to_le_bytes());
        tags.extend_from_slice(&(comment.len() as u32).to_le_bytes());
        tags.extend_from_slice(comment.as_bytes());

        let mut out = ogg_page(head, 2, 0);
        out.extend_from_slice(&ogg_page(&tags, 0, 1));
        out
    }

    fn flac_picture(picture_type: PictureType, data: &[u8]) -> Vec<u8> {
        let mut picture = Vec::new();
        picture.extend_from_slice(&u32::from(picture_type.as_u8()).to_be_bytes());
        picture.extend_from_slice(&9u32.to_be_bytes());
        picture.extend_from_slice(b"image/png");
        picture.extend_from_slice(&0u32.to_be_bytes()); // Description
        picture.extend_from_slice(&1u32.to_be_bytes()); // Width
        picture.extend_from_slice(&1u32.to_be_bytes()); // Height
        picture.extend_from_slice(&32u32.to_be_bytes()); // Color depth
        picture.extend_from_slice(&0u32.to_be_bytes()); // Indexed colors
        picture.extend_from_slice(&(data.len() as u32).to_be_bytes());
        picture.extend_from_slice(data);
        picture
    }

    fn atom(name: &[u8; 4], payload: &[u8]) -> Vec<u8> {
        let mut out = Vec::with_capacity(payload.len() + 8);
        out.extend_from_slice(&((payload.len() + 8) as u32).to_be_bytes());
        out.extend_from_slice(name);
        out.extend_from_slice(payload);
        out
    }

    fn ogg_page(packet: &[u8], header_type: u8, sequence: u32) -> Vec<u8> {
        let mut lacing = vec![255; packet.len() / 255];
        lacing.push((packet.len() % 255) as u8);
        let mut page = b"OggS\x00".to_vec();
        page.push(header_type);
        page.extend_from_slice(&0u64.to_le_bytes());
        page.extend_from_slice(&1u32.to_le_bytes());
        page.extend_from_slice(&sequence.to_le_bytes());
        page.extend_from_slice(&0u32.to_le_bytes());
        page.push(lacing.len() as u8);
        page.extend_from_slice(&lacing);
        page.extend_from_slice(packet);
        let checksum = ogg_crc(&page);
        page[22..26].copy_from_slice(&checksum.to_le_bytes());
        page
    }

    fn ogg_crc(bytes: &[u8]) -> u32 {
        let mut crc = 0u32;
        for byte in bytes {
            crc ^= u32::from(*byte) << 24;
            for _ in 0..8 {
                crc = if crc & 0x8000_0000 != 0 {
                    (crc << 1) ^ 0x04c1_1db7
                } else {
                    crc << 1
                };
            }
        }
        crc
    }

    fn synchsafe(value: usize) -> [u8; 4] {
        [
            ((value >> 21) & 0x7f) as u8,
            ((value >> 14) & 0x7f) as u8,
            ((value >> 7) & 0x7f) as u8,
            (value & 0x7f) as u8,
        ]
    }

    fn u24(value: usize) -> [u8; 3] {
        [
            ((value >> 16) & 0xff) as u8,
            ((value >> 8) & 0xff) as u8,
            (value & 0xff) as u8,
        ]
    }
}
