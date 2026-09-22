//! Local audio folders: scanning and picking a random track.

use std::collections::VecDeque;
use std::fs::File;
use std::io::BufReader;
use std::path::{Path, PathBuf};
use std::sync::Mutex;

use rand::seq::SliceRandom;
use serde::Serialize;
use walkdir::WalkDir;

const MAX_DEPTH: usize = 8;
const MAX_FILES: usize = 50_000;
/// Directory entries looked at before giving up, audio or not. `MAX_FILES`
/// only caps what is collected, so without this a folder pointed at a drive
/// root or a dead network share walks for as long as it takes - and an alarm
/// resolving its backup folder waits for it.
const MAX_ENTRIES: usize = 200_000;

pub fn is_audio(path: &Path) -> bool {
    path.extension()
        .and_then(|e| e.to_str())
        .and_then(aerowave_core::local_media::content_type)
        .is_some()
}

pub fn scan(dir: &Path) -> Vec<PathBuf> {
    let mut out = Vec::new();
    let mut visited = 0usize;
    for entry in WalkDir::new(dir)
        .max_depth(MAX_DEPTH)
        .follow_links(false)
        .into_iter()
        .filter_map(|e| e.ok())
    {
        visited += 1;
        if out.len() >= MAX_FILES || visited >= MAX_ENTRIES {
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

/// Read embedded artwork without making a shuffle failure a playback failure.
/// Parsing and its limits live in core; this module only owns filesystem I/O.
pub fn track_artwork(path: &Path) -> Option<String> {
    let file = File::open(path).ok()?;
    aerowave_core::track_art::embedded_artwork_data_url(&mut BufReader::new(file))
}

/// Remembers what was played recently so a small folder does not repeat
/// itself two mornings running.
#[derive(Default)]
pub struct RecentTracks(Mutex<VecDeque<PathBuf>>);

impl RecentTracks {
    pub fn remember(&self, p: &Path, total: usize) {
        let keep = (total / 3).clamp(1, 100);
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

/// Selection is separate from remembering so a late scan result cannot alter
/// the history of an alarm that was dismissed or replaced while it ran.
pub fn choose_random(files: &[PathBuf], recent: &RecentTracks) -> Option<PathBuf> {
    if files.is_empty() {
        return None;
    }
    // Keep at most a third of the folder in the "recently played" window, so
    // a two-file folder still alternates instead of running out of choices.
    let fresh: Vec<&PathBuf> = files.iter().filter(|p| !recent.seen(p)).collect();
    if fresh.is_empty() {
        files.choose(&mut rand::thread_rng()).cloned()
    } else {
        fresh.choose(&mut rand::thread_rng()).map(|p| (*p).clone())
    }
}
