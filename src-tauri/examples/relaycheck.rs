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

use std::time::Duration;

use tokio::io::{AsyncReadExt, AsyncWriteExt};

/// Read the head and a little body straight off the socket, so the check does
/// not depend on the same HTTP client the relay itself uses.
async fn fetch(url: &str, want: usize) -> Result<(String, usize), String> {
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
    Ok((head, buf.len().saturating_sub(split)))
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
    let relay = match relay::Relay::start().await {
        Ok(r) => r,
        Err(e) => {
            eprintln!("relay would not start: {e}");
            std::process::exit(1);
        }
    };
    println!("relay listening on 127.0.0.1:{}\n", relay.port);

    let args: Vec<String> = std::env::args().skip(1).collect();
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
            Ok((head, bytes)) => {
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
                println!("{verdict:9} {bytes:>7} B  {status}  {ctype}  <- {label}");
            }
            Err(e) => println!("{:9} {label}: {e}", "FAILED"),
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
}
