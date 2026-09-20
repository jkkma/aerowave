//! Directory retry fixtures without opening the app or its settings.
//! Add `--live` to also check the public directory with the production client.

#![allow(dead_code)]

#[path = "../src/browse.rs"]
mod browse;
#[path = "../src/hls.rs"]
mod hls;
#[path = "../src/stream.rs"]
mod stream;

use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use serde::Deserialize;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::{TcpListener, TcpStream};

#[derive(Clone, Copy)]
enum Failure {
    Closed,
    Truncated,
    Json,
    Shape,
    Oversized,
    Status(u16),
    Slow,
    SlowRetry,
    DiscoveryUnavailable,
}

#[derive(Default)]
struct Observed {
    discoveries: AtomicUsize,
    requests: Mutex<Vec<String>>,
}

#[derive(Debug, Deserialize)]
struct Station {
    name: String,
}

async fn read_request(socket: &mut TcpStream) -> Option<String> {
    let mut bytes = Vec::new();
    while !bytes.windows(4).any(|part| part == b"\r\n\r\n") {
        let mut buffer = [0; 2048];
        let read = socket.read(&mut buffer).await.ok()?;
        if read == 0 {
            return None;
        }
        assert!(bytes.len() < 8192);
        bytes.extend_from_slice(&buffer[..read]);
    }
    Some(String::from_utf8(bytes).unwrap())
}

async fn reply(socket: &mut TcpStream, status: u16, body: &str) {
    let response = format!(
        "HTTP/1.1 {status} Fixture\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}",
        body.len()
    );
    let _ = socket.write_all(response.as_bytes()).await;
}

async fn fixture(
    failure: Failure,
    alternate: bool,
    persistent: bool,
    slow_discovery: bool,
) -> (String, Arc<Observed>, tokio::task::JoinHandle<()>) {
    let listener = TcpListener::bind(("127.0.0.1", 0)).await.unwrap();
    let origin = format!("http://127.0.0.1:{}", listener.local_addr().unwrap().port());
    let observed = Arc::new(Observed::default());
    let record = observed.clone();
    let task = tokio::spawn(async move {
        loop {
            let (mut socket, _) = listener.accept().await.unwrap();
            let record = record.clone();
            tokio::spawn(async move {
                let Some(request) = read_request(&mut socket).await else {
                    return;
                };
                let target = request.split_whitespace().nth(1).unwrap();
                if target == "/json/servers" {
                    let number = record.discoveries.fetch_add(1, Ordering::SeqCst);
                    if matches!(failure, Failure::DiscoveryUnavailable) {
                        reply(&mut socket, 503, "[]").await;
                        return;
                    }
                    if slow_discovery {
                        tokio::time::sleep(Duration::from_secs(3)).await;
                    }
                    let mut mirrors = vec![
                        serde_json::json!({"name": "bad.example"}),
                        serde_json::json!({"name": "bad.example"}),
                    ];
                    if alternate && number > 0 {
                        mirrors.push(serde_json::json!({"name": "good.example"}));
                    }
                    reply(&mut socket, 200, &serde_json::to_string(&mirrors).unwrap()).await;
                    return;
                }

                let number = {
                    let mut requests = record.requests.lock().unwrap();
                    requests.push(request.clone());
                    requests.len()
                };
                if number > 1 && !persistent {
                    if matches!(failure, Failure::SlowRetry) {
                        tokio::time::sleep(Duration::from_secs(3)).await;
                    }
                    reply(&mut socket, 200, r#"[{"name":"Recovered"}]"#).await;
                    return;
                }
                match failure {
                    Failure::Closed => {}
                    Failure::Truncated => {
                        let _ = socket.write_all(
                            b"HTTP/1.1 200 OK\r\nContent-Length: 100\r\nConnection: close\r\n\r\n["
                        ).await;
                    }
                    Failure::Json => reply(&mut socket, 200, "not JSON").await,
                    Failure::Shape => reply(&mut socket, 200, "[1]").await,
                    Failure::Oversized => reply(&mut socket, 200, &"x".repeat(2048)).await,
                    Failure::Status(status) => reply(&mut socket, status, "[]").await,
                    Failure::SlowRetry | Failure::DiscoveryUnavailable => {
                        reply(&mut socket, 503, "[]").await
                    }
                    Failure::Slow => {
                        tokio::time::sleep(Duration::from_secs(3)).await;
                        reply(&mut socket, 200, "[]").await;
                    }
                }
            });
        }
    });
    (origin, observed, task)
}

async fn check_case(
    label: &str,
    failure: Failure,
    alternate: bool,
    persistent: bool,
    slow_discovery: bool,
    seconds: u64,
    expected_requests: usize,
    success: bool,
) {
    let (origin, observed, task) = fixture(failure, alternate, persistent, slow_discovery).await;
    let discovery = format!("{origin}/json/servers");
    let client = browse::DirectoryClient::with_endpoints(
        discovery,
        "bad.example".to_string(),
        Arc::new(move |host| format!("{origin}/{host}")),
    )
    .unwrap();
    let params = [
        ("name", "test station".to_string()),
        ("offset", "40".to_string()),
    ];
    let started = Instant::now();
    let result = client
        .get_json::<Vec<Station>>(
            "stations/search",
            &params,
            Duration::from_secs(seconds),
            256,
            "Unreadable fixture stations",
        )
        .await;
    let elapsed = started.elapsed();
    assert_eq!(result.is_ok(), success, "{label}: {result:?}");
    if let Ok(stations) = result {
        assert_eq!(stations.len(), 1, "{label}");
        assert_eq!(stations[0].name, "Recovered", "{label}");
    }
    let requests = observed.requests.lock().unwrap();
    assert_eq!(requests.len(), expected_requests, "{label}: {requests:?}");
    for (index, request) in requests.iter().enumerate() {
        let host = if alternate && index > 0 {
            "good.example"
        } else {
            "bad.example"
        };
        assert!(
            request.starts_with(&format!("GET /{host}/json/stations/search?")),
            "{label}: {request}"
        );
        assert!(
            request.contains("name=test+station&offset=40"),
            "{label}: {request}"
        );
        assert!(request
            .to_ascii_lowercase()
            .contains(&format!("user-agent: {}", stream::UA.to_ascii_lowercase())));
    }
    assert!(
        elapsed < Duration::from_secs(seconds) + Duration::from_millis(750),
        "{label}: {elapsed:?}"
    );
    task.abort();
    println!("PASS: {label}");
}

#[tokio::main]
async fn main() {
    check_case(
        "closed connection retries the sole mirror",
        Failure::Closed,
        false,
        false,
        false,
        5,
        2,
        true,
    )
    .await;
    check_case(
        "truncated body switches mirrors",
        Failure::Truncated,
        true,
        false,
        false,
        5,
        2,
        true,
    )
    .await;
    check_case(
        "malformed JSON retries",
        Failure::Json,
        true,
        false,
        false,
        5,
        2,
        true,
    )
    .await;
    check_case(
        "wrong JSON shape retries",
        Failure::Shape,
        true,
        false,
        false,
        5,
        2,
        true,
    )
    .await;
    check_case(
        "oversized body retries within its cap",
        Failure::Oversized,
        true,
        false,
        false,
        5,
        2,
        true,
    )
    .await;
    check_case(
        "server error retries",
        Failure::Status(503),
        true,
        false,
        false,
        5,
        2,
        true,
    )
    .await;
    check_case(
        "404 does not retry",
        Failure::Status(404),
        true,
        false,
        false,
        5,
        1,
        false,
    )
    .await;
    check_case(
        "429 does not retry",
        Failure::Status(429),
        true,
        false,
        false,
        5,
        1,
        false,
    )
    .await;
    check_case(
        "persistent failure stops after two attempts",
        Failure::Status(503),
        true,
        true,
        false,
        5,
        2,
        false,
    )
    .await;
    check_case(
        "failed discovery still retries the fallback",
        Failure::DiscoveryUnavailable,
        false,
        false,
        false,
        5,
        2,
        true,
    )
    .await;
    check_case(
        "slow response respects total deadline",
        Failure::Slow,
        false,
        false,
        false,
        1,
        1,
        false,
    )
    .await;
    check_case(
        "retry shares the first attempt's deadline",
        Failure::SlowRetry,
        true,
        false,
        false,
        1,
        2,
        false,
    )
    .await;
    check_case(
        "discovery shares the total deadline",
        Failure::Closed,
        false,
        false,
        true,
        1,
        0,
        false,
    )
    .await;
    println!("All directory regression fixtures passed.");

    if std::env::args().any(|arg| arg == "--live") {
        let (page, countries, tags) = tokio::try_join!(
            browse::search(browse::Query {
                limit: 40,
                ..Default::default()
            }),
            browse::countries(),
            browse::tags(),
        )
        .expect("live directory check failed");
        assert!(!page.stations.is_empty() && !countries.is_empty() && !tags.is_empty());
        println!(
            "PASS: live directory returned {} stations ({} offered), {} countries and {} genres",
            page.stations.len(),
            page.offered,
            countries.len(),
            tags.len()
        );
        for (codec, bitrate) in [("OGG", ""), ("AAC", "192")] {
            let query = || browse::Query {
                name: "techno".into(),
                country_code: "DE".into(),
                codec: codec.into(),
                bitrate: bitrate.into(),
                limit: 100,
                ..Default::default()
            };
            let (formats, countries, rates, page) = tokio::try_join!(
                browse::facets(browse::Query {
                    codec: String::new(),
                    ..query()
                }),
                browse::facets(browse::Query {
                    country_code: String::new(),
                    ..query()
                }),
                browse::facets(browse::Query {
                    bitrate: String::new(),
                    ..query()
                }),
                browse::search(query()),
            )
            .expect("live name-filtered check failed");
            if formats.sampled || countries.sampled || rates.sampled || page.has_more {
                println!("SKIP: exact count comparison for {codec}/{bitrate} needs more than one tally or page");
                continue;
            }
            let expected = page.stations.len() as u32;
            let format_count = formats
                .codecs
                .iter()
                .find(|bucket| bucket.key == codec)
                .map_or(0, |bucket| bucket.stations);
            let country_count = countries
                .countries
                .iter()
                .find(|country| country.code == "DE")
                .map_or(0, |country| country.stations);
            assert_eq!(
                format_count, expected,
                "selected format must match the search"
            );
            assert_eq!(
                country_count, expected,
                "selected country must match the search"
            );
            if !bitrate.is_empty() {
                let rate_count = rates
                    .bitrates
                    .iter()
                    .find(|bucket| bucket.key == bitrate)
                    .map_or(0, |bucket| bucket.stations);
                assert_eq!(
                    rate_count, expected,
                    "selected bitrate must match the search"
                );
            }
            println!("PASS: live techno/DE/{codec}/{bitrate} counts agree with {expected} search results");
        }
    }
}
