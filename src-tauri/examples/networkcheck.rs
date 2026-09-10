//! Local regression fixtures for the production stream, relay and HLS paths.
//! Run with `cargo run --example networkcheck`; no app or user settings open.

#![allow(dead_code)]

#[path = "../src/hls.rs"]
mod hls;
#[path = "../src/relay.rs"]
mod relay;
#[path = "../src/stream.rs"]
mod stream;

use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::{TcpListener, TcpStream};

async fn request(socket: &mut TcpStream) -> String {
    let mut head = Vec::new();
    while aerowave_core::icy::head_end(&head).is_none() {
        let mut buf = [0; 2048];
        let read = socket.read(&mut buf).await.unwrap();
        assert!(read > 0 && head.len() < 8192);
        head.extend_from_slice(&buf[..read]);
    }
    String::from_utf8(head).unwrap()
}

async fn icy_fixture(
    body: Vec<u8>,
    interval: Option<usize>,
    stall_head: bool,
    stall_body: bool,
) -> String {
    let listener = TcpListener::bind(("127.0.0.1", 0)).await.unwrap();
    let port = listener.local_addr().unwrap().port();
    tokio::spawn(async move {
        loop {
            let (mut socket, _) = listener.accept().await.unwrap();
            let body = body.clone();
            tokio::spawn(async move {
                let head = request(&mut socket).await.to_ascii_lowercase();
                assert!(head.contains(&format!("user-agent: {}", stream::UA.to_ascii_lowercase())));
                assert!(head.contains("icy-metadata: 1"));
                let mut reply = "ICY 200 OK\r\nContent-Type: audio/mpeg\r\n".to_string();
                if let Some(interval) = interval {
                    reply.push_str(&format!("icy-metaint: {interval}\r\n"));
                }
                if !stall_head {
                    reply.push_str("\r\n");
                }
                if socket.write_all(reply.as_bytes()).await.is_err() {
                    return;
                }
                if !stall_head && socket.write_all(&body).await.is_err() {
                    return;
                }
                if stall_head {
                    loop {
                        tokio::time::sleep(Duration::from_millis(500)).await;
                        if socket.write_all(b"x").await.is_err() {
                            break;
                        }
                    }
                } else if stall_body {
                    let mut buf = [0; 16];
                    while matches!(socket.read(&mut buf).await, Ok(n) if n > 0) {}
                }
            });
        }
    });
    format!("http://127.0.0.1:{port}/stream")
}

async fn large_interval() {
    let interval = 512 * 1024 + 1;
    let mut body = vec![b'a'; interval];
    let mut metadata = b"StreamTitle='Large interval';".to_vec();
    metadata.resize(32, 0);
    body.push(2);
    body.extend(metadata);
    body.extend(vec![b'b'; 4096]);
    let source = icy_fixture(body, Some(interval), false, false).await;
    let heard = Arc::new(Mutex::new(Vec::new()));
    let sink = heard.clone();
    let relay = relay::Relay::start(Arc::new(move |_, title| {
        sink.lock().unwrap().push(title.to_string());
    }))
    .await
    .unwrap();
    let response = reqwest::get(relay.route(&source)).await.unwrap();
    assert!(response.status().is_success());
    let audio = response.bytes().await.unwrap();
    assert_eq!(audio.len(), interval + 4096);
    assert!(audio[..interval].iter().all(|b| *b == b'a'));
    assert!(audio[interval..].iter().all(|b| *b == b'b'));
    assert_eq!(*heard.lock().unwrap(), ["Large interval"]);
    println!("PASS: raw ICY relay strips a metadata interval above 512 KiB");
}

async fn raw_idle_deadline() {
    let source = icy_fixture(b"audio".to_vec(), None, false, true).await;
    let relay = relay::Relay::start(Arc::new(|_, _| {})).await.unwrap();
    let started = Instant::now();
    let audio = tokio::time::timeout(Duration::from_secs(35), async {
        reqwest::get(relay.route(&source))
            .await
            .unwrap()
            .bytes()
            .await
            .unwrap()
    })
    .await
    .expect("the raw relay never closed its stalled response");
    assert_eq!(audio.as_ref(), b"audio");
    assert!(started.elapsed() >= Duration::from_secs(29));
    println!("PASS: raw ICY inactivity closes the downstream response after 30 seconds");
}

async fn raw_head_deadline() {
    let source = icy_fixture(Vec::new(), None, true, false).await;
    let started = Instant::now();
    let result = tokio::time::timeout(Duration::from_secs(11), stream::open_for_relay(&source))
        .await
        .expect("a partial raw ICY head never timed out");
    assert!(result.is_err());
    assert!(started.elapsed() >= Duration::from_secs(7));
    println!("PASS: a trickling raw ICY response head expires after 8 seconds");
}

async fn guarded_redirects() {
    use reqwest::dns::Resolve;

    let listener = TcpListener::bind(("127.0.0.1", 0)).await.unwrap();
    let addr = listener.local_addr().unwrap();
    let paths = Arc::new(Mutex::new(Vec::new()));
    let seen = paths.clone();
    tokio::spawn(async move {
        loop {
            let (mut socket, _) = listener.accept().await.unwrap();
            let seen = seen.clone();
            tokio::spawn(async move {
                let head = request(&mut socket).await;
                let path = head.split_whitespace().nth(1).unwrap();
                seen.lock().unwrap().push(path.to_string());
                let target = match path {
                    "/private" => format!("http://127.0.0.1:{}/forbidden", addr.port()),
                    "/ipv6" => "http://[::1]/forbidden".to_string(),
                    "/localname" => format!("http://localhost:{}/forbidden", addr.port()),
                    "/chain" => format!("http://cdn.test:{}/private", addr.port()),
                    "/public" => format!("http://cdn.test:{}/audio", addr.port()),
                    "/loop" => format!("http://station.test:{}/loop", addr.port()),
                    "/audio" => {
                        assert!(head.to_ascii_lowercase().contains("range: bytes=2-4"));
                        socket.write_all(b"HTTP/1.1 206 Partial Content\r\nContent-Type: audio/aac\r\nContent-Length: 3\r\nConnection: close\r\n\r\ncde").await.unwrap();
                        return;
                    }
                    other => panic!("a forbidden destination received a request: {other}"),
                };
                socket.write_all(format!("HTTP/1.1 302 Found\r\nLocation: {target}\r\nContent-Length: 0\r\nConnection: close\r\n\r\n").as_bytes()).await.unwrap();
            });
        }
    });
    // Only this fixture replaces DNS for two public-looking names, allowing
    // the real redirect policy to run entirely on a local server. Production
    // uses PublicDns without any address overrides.
    let client = hls::client_builder()
        .resolve("station.test", addr)
        .resolve("cdn.test", addr)
        .build()
        .unwrap();
    for path in ["private", "ipv6", "localname", "chain", "loop"] {
        let result = stream::fetch_once(
            &client,
            &format!("http://station.test:{}/{path}", addr.port()),
            None,
            1024,
        )
        .await;
        assert!(result.is_err(), "redirect {path} was allowed");
    }
    let got = stream::fetch_once(
        &client,
        &format!("http://station.test:{}/public", addr.port()),
        Some((2, 4)),
        3,
    )
    .await
    .unwrap();
    assert_eq!(got.status, 206);
    assert_eq!(got.content_type, "audio/aac");
    assert_eq!(got.body, b"cde");
    assert!(got.final_url.contains("cdn.test"));
    assert!(!paths
        .lock()
        .unwrap()
        .iter()
        .any(|path| path == "/forbidden"));
    let result = hls::PublicDns.resolve("localhost".parse().unwrap()).await;
    assert!(result.is_err(), "private DNS answers were admitted");
    for url in [
        "http://127.0.0.1/audio",
        "http://[::1]/audio",
        "http://localhost/audio",
    ] {
        let sessions = hls::Hls::default();
        let id = sessions.open(url).unwrap();
        assert!(sessions.fetch(&id, url, None).await.is_err());
        sessions.close(&id);
    }
    println!("PASS: initial private targets, private redirects, chained redirects and private DNS are blocked; public cross-host redirects preserve byte ranges");
}

#[tokio::main]
async fn main() {
    large_interval().await;
    guarded_redirects().await;
    tokio::join!(raw_head_deadline(), raw_idle_deadline());
    println!("All network regression fixtures passed.");
}
