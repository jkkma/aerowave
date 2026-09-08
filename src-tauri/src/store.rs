//! Persisted application data: stations, alarms and settings.
//!
//! Everything lives in one JSON file in the app config dir. Writes go to a
//! temp file first and are renamed over the original, so a crash mid-write
//! cannot leave a half-written config behind.

use std::fs;
use std::path::PathBuf;
use std::sync::Mutex;

use chrono::Local;
use serde::{Deserialize, Serialize};
use tauri::{AppHandle, Manager};

#[derive(Serialize, Deserialize, Clone, Debug)]
#[serde(rename_all = "camelCase")]
pub struct Station {
    pub id: String,
    pub name: String,
    pub url: String,
    #[serde(default)]
    pub tag: String,
    /// The station's own artwork, as the directory had it. Empty for anything
    /// typed in by hand, and for everything saved before this field existed.
    #[serde(default)]
    pub logo: String,
    #[serde(default)]
    pub favorite: bool,
}

/// Where an alarm gets its sound from. When a station will not play, the
/// backup folder in `Settings` stands in for it - there is no synthesised
/// fallback tone.
#[derive(Serialize, Deserialize, Clone, Debug)]
#[serde(tag = "kind", rename_all = "camelCase", rename_all_fields = "camelCase")]
pub enum AlarmSource {
    /// A saved station, by id.
    Station { station_id: String },
    /// A folder; one audio file is picked at random each time it rings.
    Folder { path: String },
}

impl Default for AlarmSource {
    fn default() -> Self {
        AlarmSource::Folder {
            path: String::new(),
        }
    }
}

#[derive(Serialize, Deserialize, Clone, Debug)]
#[serde(rename_all = "camelCase")]
pub struct Alarm {
    pub id: String,
    #[serde(default)]
    pub label: String,
    pub hour: u32,
    pub minute: u32,
    /// Weekdays it repeats on, 0 = Monday .. 6 = Sunday.
    /// Empty means "once, at the next occurrence", and disables itself after.
    #[serde(default)]
    pub days: Vec<u32>,
    #[serde(default = "yes")]
    pub enabled: bool,
    #[serde(default)]
    pub source: AlarmSource,
    #[serde(default = "default_volume")]
    pub volume: f64,
    #[serde(default = "default_fade")]
    pub fade_secs: u32,
    #[serde(default = "default_snooze")]
    pub snooze_mins: u32,
    #[serde(default = "default_auto_stop")]
    pub auto_stop_mins: u32,
    /// How many times giving up snoozes the alarm instead of ending it. 0 -
    /// the default - stops on the first give-up, which is what every alarm
    /// written before this field existed did.
    #[serde(default)]
    pub auto_snoozes: u32,
}

fn yes() -> bool {
    true
}
fn default_volume() -> f64 {
    0.8
}
fn default_fade() -> u32 {
    20
}
fn default_snooze() -> u32 {
    10
}
fn default_auto_stop() -> u32 {
    30
}

#[derive(Serialize, Deserialize, Clone, Debug)]
#[serde(rename_all = "camelCase")]
pub struct Settings {
    #[serde(default = "default_volume")]
    pub volume: f64,
    #[serde(default)]
    pub last_station: Option<String>,
    /// Played when an alarm's own source will not make a sound.
    #[serde(default)]
    pub backup_folder: Option<String>,
    /// The folder the shuffle button plays from.
    #[serde(default)]
    pub shuffle_folder: Option<String>,
    #[serde(default = "yes")]
    pub minimize_to_tray: bool,
    #[serde(default)]
    pub start_with_windows: bool,
    #[serde(default = "yes")]
    pub clock_24h: bool,
    #[serde(default = "yes")]
    pub show_metadata: bool,
}

impl Default for Settings {
    fn default() -> Self {
        Settings {
            volume: 0.8,
            last_station: None,
            backup_folder: None,
            shuffle_folder: None,
            minimize_to_tray: true,
            start_with_windows: false,
            clock_24h: true,
            show_metadata: true,
        }
    }
}

#[derive(Serialize, Deserialize, Clone, Debug)]
#[serde(rename_all = "camelCase")]
pub struct AppData {
    #[serde(default)]
    pub stations: Vec<Station>,
    #[serde(default)]
    pub alarms: Vec<Alarm>,
    #[serde(default)]
    pub settings: Settings,
}

impl Default for AppData {
    fn default() -> Self {
        AppData {
            stations: default_stations(),
            alarms: Vec::new(),
            settings: Settings::default(),
        }
    }
}

/// Stations the app ships with. Every one of these was checked by loading it
/// in a Chromium media element, not just by fetching it: plenty of stations
/// answer a plain HTTP request perfectly well and are still refused by the
/// media element (SomaFM's mounts, for one), and a station that cannot play
/// is worse than no station at all.
fn default_stations() -> Vec<Station> {
    let seed: &[(&str, &str, &str)] = &[
        ("FIP", "https://icecast.radiofrance.fr/fip-midfi.mp3", "eclectic"),
        ("FIP Rock", "https://icecast.radiofrance.fr/fiprock-midfi.mp3", "rock"),
        ("FIP Jazz", "https://icecast.radiofrance.fr/fipjazz-midfi.mp3", "jazz"),
        ("FIP Groove", "https://icecast.radiofrance.fr/fipgroove-midfi.mp3", "groove"),
        ("FIP Electro", "https://icecast.radiofrance.fr/fipelectro-midfi.mp3", "electro"),
        ("Radio Paradise", "https://stream.radioparadise.com/mp3-192", "eclectic"),
        ("Radio Paradise Mellow", "https://stream.radioparadise.com/mellow-192", "mellow"),
        ("Radio Paradise Rock", "https://stream.radioparadise.com/rock-192", "rock"),
        ("Radio Paradise Global", "https://stream.radioparadise.com/global-192", "global"),
        ("WFMU Freeform", "https://stream0.wfmu.org/freeform-128k", "freeform"),
        ("1.FM Chillout Lounge", "https://strm112.1.fm/chilloutlounge_mobile_mp3", "chillout"),
        ("France Musique", "https://icecast.radiofrance.fr/francemusique-midfi.mp3", "classical"),
    ];
    seed.iter()
        .enumerate()
        .map(|(i, (name, url, tag))| Station {
            id: format!("seed-{i}"),
            name: name.to_string(),
            url: url.to_string(),
            tag: tag.to_string(),
            // No artwork: these are hand-written, not directory entries, and
            // pinning third-party image hosts into the seed list would only
            // rot. Anything added from BROWSE brings its own.
            logo: String::new(),
            favorite: i < 3,
        })
        .collect()
}

/// A `data` folder beside the executable makes this a portable install:
/// settings and the webview's cache stay in the app directory and nothing is
/// written to the user profile. That is how the Scoop package ships, with
/// `data` persisted across updates. Without that folder, settings go to the
/// usual per-user config directory.
pub fn portable_data_dir() -> Option<PathBuf> {
    let exe = std::env::current_exe().ok()?;
    let dir = exe.parent()?.join("data");
    if dir.is_dir() {
        Some(dir)
    } else {
        None
    }
}

pub struct Store {
    path: PathBuf,
    /// Set when a config file existed but could not be used. Without it a
    /// reset is indistinguishable from a first run, and on an alarm clock
    /// that means every alarm quietly ceases to exist.
    pub load_error: Option<String>,
    /// Held across the whole of `save()` - snapshot, write, rename. The temp
    /// file is shared, so without this the scheduler thread and the UI thread
    /// can interleave two writes into it and rename the mixture into place,
    /// producing exactly the unparseable file this struct then has to cope
    /// with.
    write_lock: Mutex<()>,
    pub data: Mutex<AppData>,
}

impl Store {
    pub fn load(app: &AppHandle) -> Store {
        let dir = portable_data_dir()
            .or_else(|| app.path().app_config_dir().ok())
            .unwrap_or_else(|| PathBuf::from("."));
        let _ = fs::create_dir_all(&dir);
        let path = dir.join("aerowave.json");

        let (data, load_error) = match fs::read_to_string(&path) {
            // No file is the normal first run, and says nothing.
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => (AppData::default(), None),

            // There but unreadable: it may be perfectly good and merely locked
            // this once, so leave it exactly where it is and say so.
            Err(e) => (
                AppData::default(),
                Some(format!(
                    "Could not read {} ({e}). Showing defaults - your settings have not been \
                     overwritten, but they will be as soon as anything is changed.",
                    path.display()
                )),
            ),

            Ok(raw) => match serde_json::from_str::<AppData>(&raw) {
                Ok(d) => (d, None),
                // Unparseable. Move it aside now, before the first settings
                // change writes the defaults straight over it.
                Err(e) => {
                    let kept = dir.join(format!(
                        "aerowave.bad-{}.json",
                        Local::now().format("%Y%m%d-%H%M%S")
                    ));
                    let note = match fs::rename(&path, &kept) {
                        Ok(()) => format!(
                            "{} could not be parsed ({e}). It was kept as {} and the app \
                             started from defaults.",
                            path.display(),
                            kept.display()
                        ),
                        Err(rename_err) => format!(
                            "{} could not be parsed ({e}) and could not be set aside \
                             ({rename_err}). The app started from defaults.",
                            path.display()
                        ),
                    };
                    (AppData::default(), Some(note))
                }
            },
        };

        if let Some(note) = &load_error {
            eprintln!("aerowave: {note}");
        }

        Store {
            path,
            load_error,
            write_lock: Mutex::new(()),
            data: Mutex::new(data),
        }
    }

    pub fn snapshot(&self) -> AppData {
        self.data.lock().unwrap().clone()
    }

    /// Where the settings file actually ended up, for the UI to show.
    pub fn path(&self) -> &std::path::Path {
        &self.path
    }

    pub fn save(&self) -> Result<(), String> {
        // One writer at a time, all the way through the rename.
        let _writing = self.write_lock.lock().unwrap();
        let data = self.snapshot();
        let json = serde_json::to_string_pretty(&data).map_err(|e| e.to_string())?;
        let tmp = self.path.with_extension("json.tmp");
        fs::write(&tmp, json).map_err(|e| format!("write {}: {e}", tmp.display()))?;
        fs::rename(&tmp, &self.path).map_err(|e| format!("rename config: {e}"))
    }

    /// Mutate the data under lock, then persist.
    pub fn update<F: FnOnce(&mut AppData)>(&self, f: F) -> Result<(), String> {
        {
            let mut d = self.data.lock().unwrap();
            f(&mut d);
        }
        self.save()
    }
}
