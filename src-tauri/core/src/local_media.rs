//! HTTP details shared by the loopback relay and its local-file responses.

#[derive(Debug, PartialEq, Eq)]
pub struct Request {
    pub token: String,
    pub file: bool,
    pub range: Option<String>,
}

/// Accept only opaque relay routes and an explicit loopback Host, preventing
/// a web page's DNS name from being used to reach the local listener.
pub fn request(head: &str) -> Option<Request> {
    let mut lines = head.split("\r\n");
    let mut parts = lines.next()?.split_whitespace();
    if parts.next()? != "GET" {
        return None;
    }
    let path = parts.next()?;
    if !matches!(parts.next()?, "HTTP/1.0" | "HTTP/1.1") || parts.next().is_some() {
        return None;
    }
    let (file, token) = if let Some(token) = path.strip_prefix("/f/") {
        (true, token)
    } else {
        (false, path.strip_prefix("/s/")?)
    };
    if token.len() != 32 || !token.bytes().all(|c| c.is_ascii_hexdigit()) {
        return None;
    }
    let mut host = None;
    let mut range = None;
    for line in lines.take_while(|line| !line.is_empty()) {
        let (key, value) = line.split_once(':')?;
        if key.eq_ignore_ascii_case("host") {
            if host.replace(value.trim()).is_some() {
                return None;
            }
        } else if key.eq_ignore_ascii_case("range")
            && range.replace(value.trim().to_string()).is_some()
        {
            return None;
        }
    }
    let host = host?;
    let host = if let Some((name, port)) = host.rsplit_once(':') {
        if host == "[::1]" {
            host
        } else {
            port.parse::<u16>().ok()?;
            name
        }
    } else {
        host
    };
    if !matches!(host, "127.0.0.1" | "localhost" | "[::1]") {
        return None;
    }
    Some(Request {
        token: token.to_string(),
        file,
        range,
    })
}

#[derive(Debug, PartialEq, Eq)]
pub enum ByteRange {
    Full,
    Partial { start: u64, end: u64 },
    Unsatisfiable,
}

/// Media elements ask for one interval at a time. Unsupported or malformed
/// ranges are ignored; a valid interval outside the file gets HTTP 416.
pub fn byte_range(header: Option<&str>, length: u64) -> ByteRange {
    let Some(value) = header.and_then(|value| value.strip_prefix("bytes=")) else {
        return ByteRange::Full;
    };
    let Some((first, last)) = value.split_once('-') else {
        return ByteRange::Full;
    };
    let number = |value: &str| {
        (!value.is_empty() && value.bytes().all(|b| b.is_ascii_digit()))
            .then(|| value.parse::<u64>().ok())
            .flatten()
    };
    if first.is_empty() {
        let Some(suffix) = number(last) else {
            return ByteRange::Full;
        };
        return if suffix == 0 || length == 0 {
            ByteRange::Unsatisfiable
        } else {
            ByteRange::Partial {
                start: length.saturating_sub(suffix),
                end: length - 1,
            }
        };
    }
    let Some(start) = number(first) else {
        return ByteRange::Full;
    };
    let end = if last.is_empty() {
        u64::MAX
    } else if let Some(end) = number(last) {
        end
    } else {
        return ByteRange::Full;
    };
    if end < start {
        return ByteRange::Full;
    }
    if start >= length {
        return ByteRange::Unsatisfiable;
    }
    ByteRange::Partial {
        start,
        end: end.min(length - 1),
    }
}

/// Kept in one place so folder picks cannot choose a format the relay refuses.
/// WMA and AIFF are absent because the player cannot reliably decode them.
pub fn content_type(extension: &str) -> Option<&'static str> {
    match extension.to_ascii_lowercase().as_str() {
        "mp3" => Some("audio/mpeg"),
        "m4a" | "m4b" | "mp4" => Some("audio/mp4"),
        "aac" => Some("audio/aac"),
        "flac" => Some("audio/flac"),
        "ogg" | "oga" | "opus" => Some("audio/ogg"),
        "wav" => Some("audio/wav"),
        "weba" | "webm" => Some("audio/webm"),
        _ => None,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const TOKEN: &str = "0123456789abcdef0123456789abcdef";

    #[test]
    fn relay_requests_keep_paths_private_and_require_loopback() {
        for host in ["127.0.0.1:4321", "localhost", "[::1]:4321", "[::1]"] {
            let head =
                format!("GET /f/{TOKEN} HTTP/1.1\r\nHost: {host}\r\nRange: bytes=2-4\r\n\r\n");
            assert_eq!(
                request(&head),
                Some(Request {
                    token: TOKEN.to_string(),
                    file: true,
                    range: Some("bytes=2-4".to_string())
                })
            );
        }
        for head in [
            format!("POST /f/{TOKEN} HTTP/1.1\r\nHost: localhost\r\n\r\n"),
            format!("GET /f/{TOKEN} HTTP/1.1\r\nHost: attacker.test\r\n\r\n"),
            format!("GET /f/{TOKEN} HTTP/1.1\r\nHost: localhost\r\nHost: localhost\r\n\r\n"),
            format!("GET /f/{TOKEN} HTTP/1.1\r\nHost: localhost\r\nRange: bytes=0-1\r\nRange: bytes=2-3\r\n\r\n"),
            "GET /f/%2Fhome%2Fuser%2Fsecret HTTP/1.1\r\nHost: localhost\r\n\r\n".to_string(),
            format!("GET /f/{TOKEN}?path=secret HTTP/1.1\r\nHost: localhost\r\n\r\n"),
            format!("GET /f/{TOKEN} HTTP/1.1\r\n\r\n"),
        ] {
            assert!(request(&head).is_none(), "{head}");
        }
        assert!(
            !request(&format!(
                "GET /s/{TOKEN} HTTP/1.1\r\nHost: localhost\r\n\r\n"
            ))
            .unwrap()
            .file
        );
    }

    #[test]
    fn file_ranges_cover_seeks_suffixes_empty_and_invalid_requests() {
        use ByteRange::*;
        for (range, length, expected) in [
            (None, 10, Full),
            (Some("bytes=2-4"), 10, Partial { start: 2, end: 4 }),
            (Some("bytes=7-"), 10, Partial { start: 7, end: 9 }),
            (Some("bytes=7-999"), 10, Partial { start: 7, end: 9 }),
            (Some("bytes=-3"), 10, Partial { start: 7, end: 9 }),
            (Some("bytes=-99"), 10, Partial { start: 0, end: 9 }),
            (Some("bytes=10-"), 10, Unsatisfiable),
            (Some("bytes=-0"), 10, Unsatisfiable),
            (Some("bytes=0-"), 0, Unsatisfiable),
            (Some("bytes=-1"), 0, Unsatisfiable),
            (Some("bytes=5-4"), 10, Full),
            (Some("bytes=0-1,4-5"), 10, Full),
            (Some("bytes=-"), 10, Full),
            (Some("bytes=+1-2"), 10, Full),
            (Some("bytes=18446744073709551616-"), 10, Full),
            (Some("items=1-2"), 10, Full),
        ] {
            assert_eq!(byte_range(range, length), expected, "{range:?}");
        }
    }

    #[test]
    fn every_folder_audio_extension_has_a_matching_media_type() {
        for (extension, expected) in [
            ("mp3", "audio/mpeg"),
            ("m4a", "audio/mp4"),
            ("m4b", "audio/mp4"),
            ("mp4", "audio/mp4"),
            ("aac", "audio/aac"),
            ("flac", "audio/flac"),
            ("ogg", "audio/ogg"),
            ("oga", "audio/ogg"),
            ("opus", "audio/ogg"),
            ("wav", "audio/wav"),
            ("weba", "audio/webm"),
            ("webm", "audio/webm"),
        ] {
            assert_eq!(content_type(extension), Some(expected));
            assert_eq!(
                content_type(&extension.to_ascii_uppercase()),
                Some(expected)
            );
        }
        for extension in ["", "html", "wma", "aiff"] {
            assert_eq!(content_type(extension), None);
        }
    }
}
