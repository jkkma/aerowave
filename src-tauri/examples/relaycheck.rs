//! Exercises the real relay against real broadcasters, without the app.
//!
//!     cargo run --example relaycheck [url ...]
//!
//! `relay.rs` and `stream.rs` touch no Tauri, so they can be pulled into a
//! plain binary that links neither WebView2 nor the Win32 GUI stack - which is
//! the only reason this can run outside a real app process at all.
//!
//! Checks that a routed station actually delivers audio bytes through the
//! loopback listener, and that the listener refuses everything it should.

// Only the relay half of stream.rs is reached from here; the probe half is
// the app's business.
#[allow(dead_code)]
#[path = "../src/stream.rs"]
mod stream;

#[path = "../src/relay.rs"]
mod relay;

#[allow(dead_code)]
#[path = "../src/hls.rs"]
mod hls;

#[allow(dead_code)]
#[path = "../src/browse.rs"]
mod browse;

use std::sync::{Arc, Mutex};
use std::time::Duration;

use tokio::io::{AsyncReadExt, AsyncWriteExt};

/// Read the head and a little body straight off the socket, so the check does
/// not depend on the same HTTP client the relay itself uses.
async fn fetch(url: &str, want: usize) -> Result<(String, Vec<u8>), String> {
    let parsed = reqwest::Url::parse(url).map_err(|e| e.to_string())?;
    let addr = format!(
        "{}:{}",
        parsed.host_str().unwrap_or("127.0.0.1"),
        parsed.port().unwrap_or(80)
    );
    let mut socket = tokio::net::TcpStream::connect(&addr)
        .await
        .map_err(|e| e.to_string())?;
    let request = format!(
        "GET {} HTTP/1.1\r\nHost: {}\r\nConnection: close\r\n\r\n",
        parsed.path(),
        addr
    );
    socket
        .write_all(request.as_bytes())
        .await
        .map_err(|e| e.to_string())?;

    let mut buf = Vec::new();
    let deadline = tokio::time::Instant::now() + Duration::from_secs(20);
    while buf.len() < want {
        let mut chunk = [0u8; 8192];
        let read = match tokio::time::timeout_at(deadline, socket.read(&mut chunk)).await {
            Ok(Ok(n)) => n,
            _ => break,
        };
        if read == 0 {
            break;
        }
        buf.extend_from_slice(&chunk[..read]);
    }
    let split = buf
        .windows(4)
        .position(|w| w == b"\r\n\r\n")
        .map(|i| i + 4)
        .unwrap_or(buf.len());
    let head = String::from_utf8_lossy(&buf[..split]).to_string();
    Ok((head, buf[split..].to_vec()))
}

/// Does this look like a stream of whole MPEG audio frames?
///
/// Byte count alone cannot answer the question the relay's stripping raises.
/// A metadata block left in the audio does not shorten it - it lengthens it,
/// with `StreamTitle='...'` sitting where a frame header should be - and the
/// player reports that as `MEDIA_ERR_DECODE` a long way from the cause. So
/// walk the frames: read a header, work out that frame's length, and expect a
/// header at the end of it. A block spliced in breaks the chain within one
/// `icy-metaint` of the start.
///
/// Returns how many frames chained and where it lost sync, if it did. AAC and
/// anything else that is not MPEG audio is not walked - `None` means "not
/// something this check understands", not "bad".
fn mpeg_frames(audio: &[u8]) -> Option<(usize, Option<usize>)> {
    const RATES: [u32; 15] = [
        0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320,
    ];
    const FREQS: [u32; 3] = [44100, 48000, 32000];

    // Frames need not start at byte zero: a stream is joined mid-song.
    let start = (0..audio.len().saturating_sub(4))
        .find(|&i| audio[i] == 0xff && audio[i + 1] & 0xe0 == 0xe0)?;

    let mut at = start;
    let mut frames = 0;
    while at + 4 <= audio.len() {
        let h = &audio[at..at + 4];
        if h[0] != 0xff || h[1] & 0xe0 != 0xe0 {
            return Some((frames, Some(at)));
        }
        // Layer III, MPEG 1 or 2, and a bitrate and sample rate that exist.
        let version = (h[1] >> 3) & 0x03;
        let bitrate = RATES[((h[2] >> 4) & 0x0f) as usize];
        let freq = FREQS.get(((h[2] >> 2) & 0x03) as usize).copied();
        let (Some(freq), true) = (freq, bitrate > 0) else {
            return Some((frames, Some(at)));
        };
        // MPEG 2 and 2.5 halve both.
        let (bitrate, freq) = if version == 3 {
            (bitrate, freq)
        } else {
            (bitrate / 2, freq / 2)
        };
        let padding = ((h[2] >> 1) & 1) as u32;
        let len = (144 * bitrate * 1000 / freq + padding) as usize;
        if len < 4 {
            return Some((frames, Some(at)));
        }
        at += len;
        frames += 1;
    }
    Some((frames, None))
}

/// Send a raw request the relay is meant to turn away.
async fn raw(port: u16, request: &str) -> String {
    let Ok(mut socket) = tokio::net::TcpStream::connect(("127.0.0.1", port)).await else {
        return "connect failed".into();
    };
    if socket.write_all(request.as_bytes()).await.is_err() {
        return "write failed".into();
    }
    let mut buf = vec![0u8; 128];
    match tokio::time::timeout(Duration::from_secs(5), socket.read(&mut buf)).await {
        Ok(Ok(n)) if n > 0 => String::from_utf8_lossy(&buf[..n])
            .lines()
            .next()
            .unwrap_or("")
            .to_string(),
        Ok(Ok(_)) => "closed with no reply".into(),
        _ => "no reply".into(),
    }
}

#[tokio::main]
async fn main() {
    // Titles the relay pulled out of the streams, in the order they arrived.
    // In the app these become `icy-title` events; here they are the evidence
    // that stripping found the blocks rather than leaving them in the audio.
    let heard: Arc<Mutex<Vec<(String, String)>>> = Arc::new(Mutex::new(Vec::new()));
    let recorder = heard.clone();
    let on_title: relay::TitleSink = Arc::new(move |url: &str, title: &str| {
        recorder
            .lock()
            .unwrap()
            .push((url.to_string(), title.to_string()));
    });

    let relay = match relay::Relay::start(on_title).await {
        Ok(r) => r,
        Err(e) => {
            eprintln!("relay would not start: {e}");
            std::process::exit(1);
        }
    };
    println!("relay listening on 127.0.0.1:{}\n", relay.port);

    let mut args: Vec<String> = std::env::args().skip(1).collect();

    // --serve: route the given stations, print the table, and stay up, so a
    // real browser can pull from the real relay. Verification wants a media
    // element on the other end, not another Rust client.
    if args.first().map(|a| a == "--serve").unwrap_or(false) {
        args.remove(0);
        let hls_port = serve_hls_for_testing().await;
        println!("{{");
        println!("  \"port\": {},", relay.port);
        println!("  \"hlsPort\": {},", hls_port);
        println!("  \"routes\": {{");
        let last = args.len().saturating_sub(1);
        for (i, url) in args.iter().enumerate() {
            let comma = if i == last { "" } else { "," };
            println!("    \"{}\": \"{}\"{}", url, relay.route(url), comma);
        }
        println!("  }}");
        println!("}}");
        loop {
            tokio::time::sleep(std::time::Duration::from_secs(3600)).await;
        }
    }
    let defaults = vec![
        // The station the whole exercise is about.
        "https://ice1.somafm.com/groovesalad-128-mp3".to_string(),
        // Chunked transfer-encoding: forwarding the socket raw would splice
        // the chunk framing into the audio, so this proves it is decoded.
        "https://icecast.radiofrance.fr/fip-midfi.mp3".to_string(),
        // A seeded control.
        "https://stream.radioparadise.com/mp3-192".to_string(),
        // A .pls the relay has to follow before it can stream anything.
        "https://somafm.com/groovesalad.pls".to_string(),
        // Must be refused rather than relayed.
        "https://stream.radiofrance.fr/franceinter/franceinter_hifi.m3u8".to_string(),
    ];
    let urls = if args.is_empty() { defaults } else { args };

    let mut played = 0;
    for url in &urls {
        let local = relay.route(url);
        let label = url.split('/').next_back().unwrap_or(url);
        match fetch(&local, 64 * 1024).await {
            Ok((head, audio)) => {
                let bytes = audio.len();
                let status = head.lines().next().unwrap_or("(no status)").trim();
                let ctype = head
                    .lines()
                    .find(|l| l.to_ascii_lowercase().starts_with("content-type:"))
                    .unwrap_or("(no content-type)")
                    .trim();
                let verdict = if bytes > 16 * 1024 { "AUDIO" } else { "no audio" };
                if bytes > 16 * 1024 {
                    played += 1;
                }
                // What the strip has to guarantee: no metadata left in the
                // audio, and frames that still chain end to end.
                let intact = match mpeg_frames(&audio) {
                    _ if audio.windows(12).any(|w| w == b"StreamTitle=") => {
                        "METADATA IN THE AUDIO".to_string()
                    }
                    Some((frames, None)) => format!("{frames} frames chain"),
                    Some((frames, Some(at))) => {
                        format!("LOST SYNC after {frames} frames, at byte {at}")
                    }
                    None => "not MPEG - frames not walked".to_string(),
                };
                println!("{verdict:9} {bytes:>7} B  {status}  {ctype}  <- {label}");
                println!("{:9} {intact}", "");
            }
            Err(e) => println!("{:9} {label}: {e}", "FAILED"),
        }
    }

    // A title needs a metadata block to have gone past, which is one
    // `icy-metaint` in - 16 KB on most stations, so the 64 KB read above is
    // usually enough. A station between tracks may not have said anything
    // yet, so this reports what it heard rather than judging it.
    println!("\ntitles read out of the audio:");
    {
        let titles = heard.lock().unwrap();
        if titles.is_empty() {
            println!("  (none - no station announced one in the bytes read)");
        }
        for (url, title) in titles.iter() {
            let label = url.split('/').next_back().unwrap_or(url);
            println!("  {label}: {title}");
        }
    }

    println!("\nrefusals:");
    for (what, request) in [
        ("unknown token", "GET /s/deadbeef HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n"),
        ("non-hex token", "GET /s/../../etc HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n"),
        ("wrong path", "GET /admin HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n"),
        ("POST", "POST /s/deadbeef HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n"),
        ("foreign Host", "GET /s/deadbeef HTTP/1.1\r\nHost: evil.example\r\n\r\n"),
    ] {
        println!("  {:14} -> {}", what, raw(relay.port, request).await);
    }

    println!("\n{played}/{} streamed audio", urls.len());

    hls_checks().await;
    probe_checks().await;
    facet_checks().await;
    agreement_checks().await;
}

/// The number on a dropdown is a promise about the list underneath it. This
/// checks the two agree - the count against the rows the search actually
/// returns - which is where Albania's "AAC+ (2)" over one station came from.
async fn agreement_checks() {
    println!("\ncounts vs. what the search returns:");
    for (label, country, codec, bitrate) in [
        ("AL / AAC+ / >=128k", "AL", "AAC+", 128u32),
        ("AL / any / any", "AL", "", 0),
        ("IS / MP3 / any", "IS", "MP3", 0),
        ("IS / any / >=128k", "IS", "", 128),
    ] {
        let mut fq = browse::Query::default();
        fq.country_code = country.into();
        fq.bitrate_min = bitrate;
        // The country facet is counted without its own filter, so ask the way
        // the format dropdown does: country applied, format left out.
        fq.codec = codec.into();

        let counted = match browse::facets(browse::Query {
            country_code: country.into(),
            bitrate_min: bitrate,
            codec: codec.into(),
            ..Default::default()
        })
        .await
        {
            Ok(f) => f
                .countries
                .iter()
                .find(|c| c.code == country)
                .map(|c| c.stations)
                .unwrap_or(0),
            Err(e) => {
                println!("  {label:<22} facet FAILED: {e}");
                continue;
            }
        };

        let mut sq = browse::Query {
            country_code: country.into(),
            bitrate_min: bitrate,
            codec: codec.into(),
            ..Default::default()
        };
        sq.limit = 100;
        let listed = match browse::search(sq).await {
            Ok(page) => page.stations.len() as u32,
            Err(e) => {
                println!("  {label:<22} search FAILED: {e}");
                continue;
            }
        };

        let verdict = if counted == listed { "agree" } else { "MISMATCH" };
        println!("  {label:<22} count={counted:<4} search returns={listed:<4} {verdict}");
    }
}

/// The format and bitrate dropdowns are annotated from these counts, so they
/// have to answer for whatever else is filtering - and never for themselves.
async fn facet_checks() {
    println!("\nbrowse facets (what the format/bitrate dropdowns are built from):");

    let show = |label: &str, f: &browse::Facets| {
        let codecs: Vec<String> = f
            .codecs
            .iter()
            .map(|b| format!("{}={}", b.key, b.stations))
            .collect();
        let bitrates: Vec<String> = f
            .bitrates
            .iter()
            .map(|b| format!("{}={}", b.key, b.stations))
            .collect();
        println!("  {label}");
        println!("    formats  {}", codecs.join("  "));
        println!("    bitrates {}", bitrates.join("  "));
        println!(
            "    (tags {}, countries {}, sampled {})",
            f.tags.len(),
            f.countries.len(),
            f.sampled
        );
    };

    // Iceland: small enough that the whole country fits inside the tally limit,
    // so the counts are exact and the arithmetic below is checkable by eye.
    let build = |bitrate_min: u32, codec: &str| {
        let mut q = browse::Query::default();
        q.country_code = "IS".into();
        q.bitrate_min = bitrate_min;
        q.codec = codec.into();
        q
    };
    for (label, query) in [
        ("country=IS, nothing else", build(0, "")),
        ("country=IS, bitrate>=128 (formats must shrink)", build(128, "")),
        ("country=IS, format=MP3 (bitrates must shrink)", build(0, "MP3")),
    ] {
        match browse::facets(query).await {
            Ok(f) => show(label, &f),
            Err(e) => println!("  {label} FAILED: {e}"),
        }
    }
}

/// What the now-playing poll actually gets back. The third line of the display
/// is built from name / bitrate / genre, and the second from the title.
async fn probe_checks() {
    println!("\nprobe_stream (what fills the now-playing lines):");
    for url in [
        "https://ice1.somafm.com/groovesalad-128-mp3",
        "https://stream.radioparadise.com/mp3-192",
        "https://icecast.radiofrance.fr/fip-midfi.mp3",
        "https://stream0.wfmu.org/freeform-128k",
        "https://strm112.1.fm/chilloutlounge_mobile_mp3",
    ] {
        let label = url.split('/').next_back().unwrap_or(url);
        match stream::probe(url, true, false).await {
            Ok(info) => println!(
                "  {:<28} name={:<22} br={:<5} genre={:<14} title={}",
                label,
                info.name.unwrap_or_else(|| "-".into()).chars().take(20).collect::<String>(),
                info.bitrate.unwrap_or_else(|| "-".into()),
                info.genre.unwrap_or_else(|| "-".into()).chars().take(12).collect::<String>(),
                info.title.unwrap_or_else(|| "(none)".into())
            ),
            Err(e) => println!("  {label:<28} FAILED: {e}"),
        }
    }
}

/// A stand-in for the app's `awhls` custom protocol, so a browser can drive
/// the REAL hls.rs. Testing only: the app serves these over a Tauri protocol,
/// which no other process can reach, whereas this is an open loopback port
/// with permissive CORS. Never wire this into the app.
///
///   GET /open?u=<base64url station url>  -> session id
///   GET /h/<session>?u=<base64url url>   -> the playlist or segment
async fn serve_hls_for_testing() -> u16 {
    use std::sync::Arc;
    use tokio::io::{AsyncReadExt, AsyncWriteExt};

    let listener = tokio::net::TcpListener::bind(("127.0.0.1", 0))
        .await
        .expect("could not bind the HLS test listener");
    let port = listener.local_addr().unwrap().port();
    let sessions = Arc::new(hls::Hls::default());

    tokio::spawn(async move {
        loop {
            let Ok((mut socket, _)) = listener.accept().await else {
                break;
            };
            let sessions = sessions.clone();
            tokio::spawn(async move {
                let mut buf = vec![0u8; 8192];
                let Ok(read) = socket.read(&mut buf).await else {
                    return;
                };
                let head = String::from_utf8_lossy(&buf[..read]).to_string();
                let mut parts = head.split_whitespace();
                let method = parts.next().unwrap_or("");
                let target = parts.next().unwrap_or("");
                let cors = "Access-Control-Allow-Origin: *\r\nAccess-Control-Allow-Headers: *\r\nAccess-Control-Expose-Headers: X-Aerowave-Final\r\n";

                if method == "OPTIONS" {
                    let _ = socket
                        .write_all(
                            format!("HTTP/1.1 204 No Content\r\n{cors}Content-Length: 0\r\nConnection: close\r\n\r\n")
                                .as_bytes(),
                        )
                        .await;
                    return;
                }

                let (path, query) = target.split_once('?').unwrap_or((target, ""));
                let param = |key: &str| -> Option<String> {
                    query
                        .split('&')
                        .filter_map(|p| p.split_once('='))
                        .find(|(k, _)| *k == key)
                        .map(|(_, v)| v.to_string())
                };
                let decode = |v: &str| {
                    use base64::Engine;
                    base64::engine::general_purpose::URL_SAFE_NO_PAD
                        .decode(v)
                        .ok()
                        .and_then(|b| String::from_utf8(b).ok())
                };

                if path == "/open" {
                    let body = param("u")
                        .and_then(|v| decode(&v))
                        .and_then(|url| sessions.open(&url))
                        .unwrap_or_default();
                    let _ = socket
                        .write_all(
                            format!(
                                "HTTP/1.1 200 OK\r\n{cors}Content-Type: text/plain\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{}",
                                body.len(),
                                body
                            )
                            .as_bytes(),
                        )
                        .await;
                    return;
                }

                let session = path.trim_start_matches("/h/").to_string();
                let url = param("u").and_then(|v| decode(&v)).unwrap_or_default();
                match sessions.fetch(&session, &url, None).await {
                    Ok(got) => {
                        let head = format!(
                            "HTTP/1.1 {} OK\r\n{cors}Content-Type: {}\r\nX-Aerowave-Final: {}\r\nContent-Length: {}\r\nConnection: close\r\n\r\n",
                            got.status,
                            got.content_type,
                            got.final_url,
                            got.body.len()
                        );
                        let _ = socket.write_all(head.as_bytes()).await;
                        let _ = socket.write_all(&got.body).await;
                    }
                    Err(e) => {
                        eprintln!("hls test listener: {url}: {e}");
                        let _ = socket
                            .write_all(
                                format!("HTTP/1.1 502 Bad Gateway\r\n{cors}Content-Length: 0\r\nConnection: close\r\n\r\n")
                                    .as_bytes(),
                            )
                            .await;
                    }
                }
            });
        }
    });
    port
}

/// Exercise hls.rs directly - no transport in the way. In the app these calls
/// sit behind the `awhls` custom protocol; here they are just functions.
async fn hls_checks() {
    println!("\nHLS sub-resource fetching:");
    // Two shapes on purpose: a media playlist that lists segments directly,
    // and a master whose variants live on another host - which is the case
    // that proves origin harvesting, since the session starts trusting only
    // the station's own origin.
    for station in [
        "https://stream.radiofrance.fr/franceinter/franceinter_hifi.m3u8",
        "https://live.m6radio.quortex.io/webM89Hc99XApzgfhXNX8ASN5/grouprtl/national/short/audio-64000/index.m3u8",
    ] {
        walk(station).await;
    }

    println!("\n  refusals:");
    let sessions = hls::Hls::default();
    let station = "https://stream.radiofrance.fr/franceinter/franceinter_hifi.m3u8";
    let session = sessions.open(station).unwrap_or_default();
    let cases: Vec<(&str, String, String)> = vec![
        ("unknown session", "0000000000000000".to_string(), station.to_string()),
        ("untrusted origin", session.clone(), "https://example.com/evil.m3u8".to_string()),
        ("loopback target", session.clone(), "http://127.0.0.1:9/x.ts".to_string()),
        ("private LAN target", session.clone(), "http://192.168.1.1/x.ts".to_string()),
        ("file scheme", session.clone(), "file:///etc/passwd".to_string()),
        ("closed session", session.clone(), station.to_string()),
    ];
    for (what, sess, url) in cases {
        if what == "closed session" {
            sessions.close(&session);
        }
        match sessions.fetch(&sess, &url, None).await {
            Ok(got) => println!("  {:20} -> ALLOWED ({} B) <-- SHOULD NOT HAPPEN", what, got.body.len()),
            Err(e) => println!("  {:20} -> refused: {}", what, e),
        }
    }
}

fn host_of(url: &str) -> String {
    reqwest::Url::parse(url)
        .ok()
        .and_then(|u| u.host_str().map(str::to_string))
        .unwrap_or_default()
}

/// Follow a station the way hls.js would: playlist, then variant if it is a
/// master, then one real segment.
async fn walk(station: &str) {
    let sessions = hls::Hls::default();
    let label = host_of(station);
    println!("\n  {label}");
    let Some(session) = sessions.open(station) else {
        println!("    could not open a session");
        return;
    };

    let mut url = station.to_string();
    for hop in 0..3 {
        let got = match sessions.fetch(&session, &url, None).await {
            Ok(got) => got,
            Err(e) => {
                println!("    FAILED at hop {hop}: {e}");
                return;
            }
        };
        let cross = if host_of(&url) == label { "" } else { "  CROSS-HOST" };
        let text = String::from_utf8_lossy(&got.body).to_string();

        if !text.starts_with("#EXTM3U") {
            let kind = if got.body.first() == Some(&0x47) {
                "MPEG-TS"
            } else if got.body.starts_with(b"ID3") {
                "ID3 + elementary AAC"
            } else {
                "unrecognised"
            };
            println!(
                "    segment   {} {:>7} B  {:<28} {}{}",
                got.status, got.body.len(), got.content_type, kind, cross
            );
            return;
        }

        let master = text.contains("#EXT-X-STREAM-INF");
        println!(
            "    {:<9} {} {:>7} B  {:<28} {}{}",
            if master { "master" } else { "media" },
            got.status,
            got.body.len(),
            got.content_type,
            if master { "variants" } else { "segments" },
            cross
        );

        let base = match reqwest::Url::parse(&got.final_url) {
            Ok(b) => b,
            Err(_) => return,
        };
        let next = text
            .lines()
            .map(str::trim)
            .find(|l| !l.is_empty() && !l.starts_with('#'))
            .and_then(|l| base.join(l).ok());
        match next {
            Some(n) => url = n.to_string(),
            None => {
                println!("    (nothing to follow)");
                return;
            }
        }
    }
}
