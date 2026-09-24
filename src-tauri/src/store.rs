//! Persisted application data: stations, alarms and settings.
//!
//! Everything lives in one JSON file in the app config dir. Writes go to a
//! temp file first and are renamed over the original, so a crash mid-write
//! cannot leave a half-written config behind.

use std::fs::{self, OpenOptions};
use std::io::Write;
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
#[derive(Serialize, Deserialize, Clone, Debug, PartialEq)]
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

#[derive(Serialize, Deserialize, Clone, Debug, PartialEq)]
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
    /// One local YYYY-MM-DD occurrence to omit from an enabled recurring alarm.
    #[serde(default)]
    pub skip_date: Option<String>,
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

/// How the alarm editor was left the last time an alarm was saved, so the
/// next new one starts from the last one set up rather than from the factory
/// defaults. Everything an alarm has except the two nobody wants filled in
/// for them: the time it goes off, and what it is called.
#[derive(Serialize, Deserialize, Clone, Debug)]
#[serde(rename_all = "camelCase")]
pub struct AlarmDefaults {
    #[serde(default)]
    pub days: Vec<u32>,
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
    #[serde(default)]
    pub sleep_timer_action: aerowave_core::sleep::SleepAction,
    #[serde(default = "yes")]
    pub wake_for_alarms: bool,
    #[serde(default = "default_volume")]
    pub volume: f64,
    #[serde(default)]
    pub last_station: Option<String>,
    /// Recent listening history includes stations that were never saved.
    #[serde(default)]
    pub recent_stations: Vec<Station>,
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
    /// The last alarm setup, kept so the editor can offer it again. None
    /// until an alarm has been saved at least once.
    #[serde(default)]
    pub alarm_defaults: Option<AlarmDefaults>,
}

impl Default for Settings {
    fn default() -> Self {
        Settings {
            sleep_timer_action: aerowave_core::sleep::SleepAction::Stop,
            wake_for_alarms: true,
            volume: 0.8,
            last_station: None,
            recent_stations: Vec::new(),
            backup_folder: None,
            shuffle_folder: None,
            minimize_to_tray: true,
            start_with_windows: false,
            clock_24h: true,
            show_metadata: true,
            alarm_defaults: None,
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
            // No stations to begin with. A shipped list is a list of other
            // people's taste that has to be cleared out before the app is
            // yours, and it rots besides - a seeded stream that goes off the
            // air looks like the app is broken on first run.
            stations: Vec::new(),
            alarms: Vec::new(),
            settings: Settings::default(),
        }
    }
}

/// A `data` folder beside the executable makes this a portable install:
/// settings and the webview's cache stay in the app directory and nothing is
/// written to the user profile. That is how the Scoop package ships, with
/// `data` persisted across updates. Without that folder, settings go to the
/// usual per-user config directory.
#[cfg(desktop)]
pub fn portable_data_dir() -> Option<PathBuf> {
    let exe = std::env::current_exe().ok()?;
    let dir = exe.parent()?.join("data");
    if dir.is_dir() {
        Some(dir)
    } else {
        None
    }
}

#[cfg(mobile)]
pub fn portable_data_dir() -> Option<PathBuf> {
    None
}

pub struct Store {
    path: PathBuf,
    /// Set when a config file existed but could not be used. Without it a
    /// reset is indistinguishable from a first run, and on an alarm clock
    /// that means every alarm quietly ceases to exist.
    pub load_error: Option<String>,
    /// Serializes every data mutation and config write. The shared temp file
    /// would be corrupted by interleaved writes, and a direct mutation of
    /// `data` would invalidate a candidate while it is being persisted.
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
        self.write_snapshot(&data)
    }

    fn write_snapshot(&self, data: &AppData) -> Result<(), String> {
        let tmp = self.stage_snapshot(data)?;
        self.commit_staged(&tmp)
    }

    fn stage_snapshot(&self, data: &AppData) -> Result<PathBuf, String> {
        let json = serde_json::to_string_pretty(data).map_err(|e| e.to_string())?;
        let tmp = self.path.with_extension("json.tmp");
        let mut file = OpenOptions::new().write(true).create(true).truncate(true).open(&tmp)
            .map_err(|e| format!("open {}: {e}", tmp.display()))?;
        file.write_all(json.as_bytes()).and_then(|_| file.sync_all())
            .map_err(|e| format!("write {}: {e}", tmp.display()))?;
        Ok(tmp)
    }

    fn commit_staged(&self, tmp: &std::path::Path) -> Result<(), String> {
        fs::rename(tmp, &self.path).map_err(|e| format!("rename config: {e}"))
    }

    /// A failed Add must not enter memory and hitch a ride on the next save.
    pub fn replace_stations(&self, stations: Vec<Station>) -> Result<(), String> {
        self.update(|candidate| candidate.stations = stations)
    }

    /// Persist a private candidate before making it visible to other readers.
    pub fn update<F: FnOnce(&mut AppData)>(&self, f: F) -> Result<(), String> {
        self.update_with(f, |current, candidate| *current = candidate)
    }

    /// Keep writes serialized while the data lock is free during filesystem I/O.
    /// Commit dependent scheduler state with data-before-scheduler lock order.
    pub fn update_with<F, C>(&self, change: F, commit: C) -> Result<(), String>
    where
        F: FnOnce(&mut AppData),
        C: FnOnce(&mut AppData, AppData),
    {
        self.update_checked_with(|candidate| { change(candidate); Ok(()) }, commit)
    }

    /// Validate and stage under the same writer lock as persistence. A rejected
    /// change leaves both the file and scheduler-visible data untouched.
    pub fn update_checked_with<F, C>(&self, change: F, commit: C) -> Result<(), String>
    where
        F: FnOnce(&mut AppData) -> Result<(), String>,
        C: FnOnce(&mut AppData, AppData),
    {
        let _writing = self.write_lock.lock().unwrap();
        let mut staged = self.snapshot();
        change(&mut staged)?;
        aerowave_core::persistence::update_with(
            &mut staged,
            |_| {},
            |candidate| self.write_snapshot(candidate),
            |_, candidate| {
                let mut data = self.data.lock().unwrap();
                commit(&mut data, candidate);
            },
        )
    }

    /// Restore under the writer lock. Keep the previous full state beside the
    /// config before another system (Android's alarm store) is changed.
    /// Stage and sync the candidate before the callback changes native alarms.
    /// Only the final rename then remains as a cross-store failure point.
    pub fn restore_with<F, C>(
        &self,
        mut imported: AppData,
        recovery_alarms: Option<Vec<Alarm>>,
        before_write: F,
        commit: C,
    ) -> Result<(AppData, PathBuf), String>
    where
        F: FnOnce(&AppData, &PathBuf) -> Result<(), String>,
        C: FnOnce(&mut AppData, AppData),
    {
        let _writing = self.write_lock.lock().unwrap();
        let current = self.snapshot();
        let mut recovery = current.clone();
        if let Some(alarms) = recovery_alarms { recovery.alarms = alarms; }
        let recovery_data = serde_json::to_value(&recovery).map_err(|e| e.to_string())?;
        let recovery_content = aerowave_core::backup::encode(&recovery_data)?;
        let recovery_path = self.write_recovery(&recovery_content)?;
        // The clock may move backward between restores. Record creation order
        // durably before changing either store, and never rely on file names
        // to find the latest completed recovery copy.
        self.write_latest_recovery_pointer(&recovery_path).map_err(|e| format!(
            "{e}. Restore stopped before changing settings or alarms. Recovery backup: {}",
            recovery_path.display()
        ))?;

        imported.settings.start_with_windows = current.settings.start_with_windows;
        imported.settings.wake_for_alarms = current.settings.wake_for_alarms;
        imported.settings.sleep_timer_action = current.settings.sleep_timer_action;
        let staged_path = self.stage_snapshot(&imported).map_err(|e| format!(
            "Restore could not stage settings ({e}). Settings and alarms are unchanged. Recovery backup: {}",
            recovery_path.display()
        ))?;
        before_write(&imported, &recovery_path)?;
        self.commit_staged(&staged_path).map_err(|e| format!(
            "Restore could not save settings ({e}). Recovery backup: {}. On Android, imported alarms may already be disabled; retry the import.",
            recovery_path.display()
        ))?;
        let mut data = self.data.lock().unwrap();
        commit(&mut data, imported);
        Ok((data.clone(), recovery_path))
    }

    fn write_recovery(&self, content: &str) -> Result<PathBuf, String> {
        let stamp = Local::now().format("%Y%m%d-%H%M%S");
        for suffix in 0..100 {
            let path = self.path.with_file_name(format!("aerowave.before-restore-{stamp}-{suffix}.json"));
            let file = OpenOptions::new().write(true).create_new(true).open(&path);
            let mut file = match file {
                Ok(file) => file,
                Err(e) if e.kind() == std::io::ErrorKind::AlreadyExists => continue,
                Err(e) => return Err(format!("Could not create recovery backup: {e}")),
            };
            file.write_all(content.as_bytes()).and_then(|_| file.sync_all())
                .map_err(|e| format!("Could not finish recovery backup {}: {e}", path.display()))?;
            return Ok(path);
        }
        Err("Could not choose a unique recovery backup name".into())
    }

    fn write_latest_recovery_pointer(&self, recovery_path: &std::path::Path) -> Result<(), String> {
        let name = recovery_path.file_name().and_then(|n| n.to_str())
            .filter(|name| aerowave_core::backup::recovery_pointer_target(name).is_some())
            .ok_or_else(|| "Recovery backup name is invalid".to_string())?;
        let pointer = self.path.with_file_name("aerowave.latest-recovery");
        let nonce = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap_or_default()
            .as_nanos();
        for suffix in 0..100 {
            let staged = self.path.with_file_name(format!(
                "aerowave.latest-recovery-{}-{nonce}-{suffix}.tmp",
                std::process::id()
            ));
            let file = OpenOptions::new().write(true).create_new(true).open(&staged);
            let mut file = match file {
                Ok(file) => file,
                Err(e) if e.kind() == std::io::ErrorKind::AlreadyExists => continue,
                Err(e) => return Err(format!("Could not stage recovery pointer: {e}")),
            };
            file.write_all(name.as_bytes()).and_then(|_| file.sync_all())
                .map_err(|e| format!("Could not sync recovery pointer: {e}"))?;
            fs::rename(&staged, &pointer)
                .map_err(|e| format!("Could not record latest recovery backup: {e}"))?;
            return Ok(());
        }
        Err("Could not choose a unique recovery pointer name".into())
    }
}
