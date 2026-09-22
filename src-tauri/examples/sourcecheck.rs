//! Non-disruptive probe for the production folder worker lanes. No app,
//! settings, filesystem share, alarm, or power transition is involved.
#![allow(dead_code)]

#[path = "../src/library.rs"]
mod library;
#[path = "../src/source_scan.rs"]
mod source_scan;

use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::{Arc, Condvar, Mutex};
use std::time::{Duration, Instant};

use aerowave_core::source_resolution::{Decision, Resolution};

#[tokio::main(flavor = "multi_thread")]
async fn main() {
    let gate = Arc::new((Mutex::new(false), Condvar::new()));
    let started = Arc::new(AtomicUsize::new(0));
    let preferred_calls = Arc::new(AtomicUsize::new(0));
    let preferred_authorizations = Arc::new(AtomicUsize::new(0));
    let scanner = source_scan::Scanner::with_scan({
        let gate = gate.clone();
        let started = started.clone();
        let preferred_calls = preferred_calls.clone();
        move |path: &Path| {
            if path == Path::new("stalled-preferred") {
                let n = preferred_calls.fetch_add(1, Ordering::SeqCst);
                if n == 0 {
                    started.store(1, Ordering::SeqCst);
                    let (lock, cv) = &*gate;
                    let mut released = lock.lock().unwrap();
                    while !*released {
                        released = cv.wait(released).unwrap();
                    }
                }
                vec![PathBuf::from("preferred.mp3")]
            } else {
                vec![PathBuf::from("backup.mp3")]
            }
        }
    });
    let recent = Arc::new(library::RecentTracks::default());
    let first = scanner
        .pick("stalled-preferred".into(), None, recent.clone(), false, {
            let count = preferred_authorizations.clone();
            move |_| {
                count.fetch_add(1, Ordering::SeqCst);
                Ok(())
            }
        })
        .unwrap();
    let start = Instant::now();
    while started.load(Ordering::SeqCst) == 0 && start.elapsed() < Duration::from_secs(1) {
        tokio::time::sleep(Duration::from_millis(5)).await;
    }
    assert_eq!(started.load(Ordering::SeqCst), 1);

    let backup = scanner
        .pick(
            "responsive-backup".into(),
            None,
            recent.clone(),
            true,
            |_| Ok(()),
        )
        .unwrap();
    let backup = tokio::time::timeout(Duration::from_millis(500), backup)
        .await
        .unwrap()
        .unwrap()
        .unwrap()
        .unwrap();
    assert_eq!(backup.path, PathBuf::from("backup.mp3"));
    let mut resolution = Resolution::new(0);
    resolution.backup(Some(backup.path), 100);
    assert_eq!(
        resolution.decide(2_000),
        Some(Decision::Backup(PathBuf::from("backup.mp3")))
    );

    // A second occurrence for the same hung folder can occupy only its one
    // queue slot. Expiring it must keep the backup lane free, and its closed
    // receiver must make the worker skip the old scan when it finally wakes.
    let second = scanner
        .pick("stalled-preferred".into(), None, recent.clone(), false, {
            let count = preferred_authorizations.clone();
            move |_| {
                count.fetch_add(1, Ordering::SeqCst);
                Ok(())
            }
        })
        .unwrap();
    drop(first);
    drop(second);
    let next_backup = scanner
        .pick(
            "responsive-backup".into(),
            None,
            recent.clone(),
            true,
            |_| Ok(()),
        )
        .unwrap();
    assert!(
        tokio::time::timeout(Duration::from_millis(500), next_backup)
            .await
            .unwrap()
            .unwrap()
            .unwrap()
            .is_some()
    );

    {
        let (lock, cv) = &*gate;
        *lock.lock().unwrap() = true;
        cv.notify_one();
    }
    let retry_deadline = Instant::now() + Duration::from_secs(1);
    let third = loop {
        let count = preferred_authorizations.clone();
        match scanner.pick(
            "stalled-preferred".into(),
            None,
            recent.clone(),
            false,
            move |_| {
                count.fetch_add(1, Ordering::SeqCst);
                Ok(())
            },
        ) {
            Ok(receiver) => break receiver,
            Err(_) if Instant::now() < retry_deadline => {
                tokio::time::sleep(Duration::from_millis(5)).await
            }
            Err(error) => panic!("preferred lane did not recover: {error}"),
        }
    };
    let third = tokio::time::timeout(Duration::from_secs(1), third)
        .await
        .unwrap()
        .unwrap()
        .unwrap()
        .unwrap();
    assert_eq!(third.path, PathBuf::from("preferred.mp3"));
    assert_eq!(
        preferred_calls.load(Ordering::SeqCst),
        2,
        "expired queued scan must not run"
    );
    assert_eq!(
        preferred_authorizations.load(Ordering::SeqCst),
        1,
        "expired scan must not authorize"
    );
    println!("folder scan lanes, fallback deadline, stale queue skipping and capacity: ok");
}
