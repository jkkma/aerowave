//! Local audio folders: scanning and picking a random track.

use std::collections::VecDeque;
use std::path::{Path, PathBuf};
use std::sync::Mutex;

use rand::seq::SliceRandom;
use serde::Serialize;
use walkdir::WalkDir;

/// Extensions WebView2 can actually decode. WMA and AIFF are deliberately
/// absent - listing them would only produce alarms that stay silent.
const AUDIO_EXT: &[&str] = &[
    "mp3", "m4a", "m4b", "aac", "mp4", "flac", "ogg", "oga", "opus", "wav", "weba", "webm",
];

const MAX_DEPTH: usize = 8;
const MAX_FILES: usize = 50_000;

pub fn is_audio(path: &Path) -> bool {
    path.extension()
        .and_then(|e| e.to_str())
        .map(|e| AUDIO_EXT.contains(&e.to_ascii_lowercase().as_str()))
        .unwrap_or(false)
}

pub fn scan(dir: &Path) -> Vec<PathBuf> {
    let mut out = Vec::new();
    for entry in WalkDir::new(dir)
        .max_depth(MAX_DEPTH)
        .follow_links(false)
        .into_iter()
        .filter_map(|e| e.ok())
    {
        if out.len() >= MAX_FILES {
            break;
        }
        if entry.file_type().is_file() && is_audio(entry.path()) {
            out.push(entry.into_path());
        }
    }
    out.sort();
    out
}

#[derive(Serialize, Clone, Debug)]
#[serde(rename_all = "camelCase")]
pub struct FolderInfo {
    pub path: String,
    pub count: usize,
    /// A handful of file names, so the UI can show what it found.
    pub sample: Vec<String>,
}

pub fn info(dir: &Path) -> FolderInfo {
    let files = scan(dir);
    let sample = files
        .iter()
        .take(6)
        .filter_map(|p| p.file_name().map(|n| n.to_string_lossy().to_string()))
        .collect();
    FolderInfo {
        path: dir.to_string_lossy().to_string(),
        count: files.len(),
        sample,
    }
}

/// Remembers what was played recently so a small folder does not repeat
/// itself two mornings running.
#[derive(Default)]
pub struct RecentTracks(Mutex<VecDeque<PathBuf>>);

impl RecentTracks {
    fn remember(&self, p: &Path, keep: usize) {
        let mut q = self.0.lock().unwrap();
        q.push_back(p.to_path_buf());
        while q.len() > keep {
            q.pop_front();
        }
    }

    fn seen(&self, p: &Path) -> bool {
        self.0.lock().unwrap().iter().any(|x| x == p)
    }
}

/// Pick a random audio file from `dir`, avoiding recent picks where possible.
pub fn pick_random(dir: &Path, recent: &RecentTracks) -> Option<PathBuf> {
    let files = scan(dir);
    if files.is_empty() {
        return None;
    }
    // Keep at most a third of the folder in the "recently played" window, so
    // a two-file folder still alternates instead of running out of choices.
    let keep = (files.len() / 3).clamp(1, 100);
    let fresh: Vec<&PathBuf> = files.iter().filter(|p| !recent.seen(p)).collect();
    let chosen = if fresh.is_empty() {
        files.choose(&mut rand::thread_rng()).cloned()
    } else {
        fresh.choose(&mut rand::thread_rng()).map(|p| (*p).clone())
    }?;
    recent.remember(&chosen, keep);
    Some(chosen)
}
