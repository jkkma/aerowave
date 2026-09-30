//! Persisted application data: stations, alarms and settings.
//!
//! Everything lives in one JSON file in the app config dir. Writes go to a
//! temp file first and are renamed over the original, so a crash mid-write
//! cannot leave a half-written config behind.

use std::fs::{self, OpenOptions};
use std::io::Write;
use std::path::PathBuf;
use std::sync::Mutex;

use aerowave_core::one_shot::{self, Arm, Completions, Receipts};

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
/// backup folder in `Settings` stands in for it before the native emergency
/// tone is needed.
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
    /// Backend-owned version of this arm. A consumed one-shot and a stale
    /// editor cannot share a version and silently re-enable each other.
    #[serde(default)]
    pub arming_revision: String,
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

#[derive(Clone, Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct StorageStatus {
    pub error: Option<String>,
    pub writes_blocked: bool,
    pub pending_completions: bool,
    pub restart_safe: bool,
}

#[derive(Default)]
struct StoreHealth {
    recovery_error: Option<String>,
    receipts_required: bool,
    completion_error: Option<String>,
}

pub struct Store {
    path: PathBuf,
    health: Mutex<StoreHealth>,
    completions: Mutex<Completions>,
    /// Serializes every filesystem mutation. Completion claims are deliberately
    /// memory-only so disk latency cannot postpone the alarm's sound.
    write_lock: Mutex<()>,
    pub data: Mutex<AppData>,
}

impl Alarm {
    fn arm(&self) -> Arm {
        Arm { alarm_id: self.id.clone(), revision: self.arming_revision.clone() }
    }
}

/// Called on alarm-editor saves only. Other settings writes must retain the
/// current arm, and the frontend is never allowed to invent its own version.
pub fn prepare_alarm_revisions(current: &[Alarm], proposed: &mut [Alarm]) -> Result<(), String> {
    for alarm in proposed {
        if let Some(old) = current.iter().find(|old| old.id == alarm.id) {
            one_shot::check_revision(&alarm.arming_revision, &old.arming_revision)
                .map_err(str::to_string)?;
            if alarm == old { continue; }
        }
        alarm.arming_revision = format!("{:032x}", rand::random::<u128>());
    }
    Ok(())
}

impl Store {
    pub fn load(app: &AppHandle) -> Store {
        let dir = portable_data_dir()
            .or_else(|| app.path().app_config_dir().ok())
            .unwrap_or_else(|| PathBuf::from("."));
        let _ = fs::create_dir_all(&dir);
        let path = dir.join("aerowave.json");
        let (data, completions, recovery_error, receipts_required) = Self::read_files(&path, true, true);
        if let Some(note) = &recovery_error { eprintln!("aerowave: {note}"); }
        Store {
            path,
            health: Mutex::new(StoreHealth { recovery_error, receipts_required, completion_error: None }),
            completions: Mutex::new(completions),
            write_lock: Mutex::new(()),
            data: Mutex::new(data),
        }
    }

    fn receipts_path(path: &std::path::Path) -> PathBuf {
        path.with_file_name("aerowave.one-shot-receipts.json")
    }

    fn read_files(path: &std::path::Path, allow_missing: bool, allow_missing_receipts: bool) -> (AppData, Completions, Option<String>, bool) {
        let (mut data, mut error) = match aerowave_core::persistence::load_json(fs::read(path), allow_missing) {
            Ok(data) => (data, None),
            Err(error) => (AppData::default(), Some(format!(
                "Could not load {} ({error}). Showing defaults; the original file is untouched and settings writes are blocked.", path.display()
            ))),
        };
        let receipt_path = Self::receipts_path(path);
        let mut receipts_required = !allow_missing_receipts;
        let completions = match aerowave_core::persistence::load_json::<Receipts>(fs::read(&receipt_path), allow_missing_receipts) {
            Ok(receipts) => Completions::from_receipts(receipts),
            Err(receipt_error) => {
                receipts_required = true;
                // Unknown receipts must not turn yesterday's consumed one-shot
                // into today's alarm. Repeating alarms remain available.
                for alarm in &mut data.alarms {
                    if alarm.days.is_empty() { alarm.enabled = false; }
                }
                let note = format!("Could not load {} ({receipt_error}). One-shot alarms and settings writes are blocked until recovery; the original file is untouched.", receipt_path.display());
                error = Some(error.map_or_else(|| note.clone(), |old| format!("{old} {note}")));
                Completions::default()
            }
        };
        (data, completions, error, receipts_required)
    }

    fn apply_receipts(data: &mut AppData, receipts: &Receipts) {
        for alarm in &mut data.alarms {
            if alarm.days.is_empty() && receipts.contains(&alarm.arm()) {
                alarm.enabled = false;
                alarm.arming_revision = one_shot::consumed_revision(&alarm.arming_revision);
            }
        }
    }

    fn snapshot_and_receipts(&self) -> (AppData, Receipts) {
        let mut data = self.data.lock().unwrap().clone();
        let receipts = self.completions.lock().unwrap().receipts();
        Self::apply_receipts(&mut data, &receipts);
        (data, receipts)
    }

    pub fn snapshot(&self) -> AppData { self.snapshot_and_receipts().0 }

    pub fn load_error(&self) -> Option<String> { self.health.lock().unwrap().recovery_error.clone() }

    pub fn storage_status(&self) -> StorageStatus {
        let completions = self.completions.lock().unwrap();
        let pending_completions = completions.has_pending();
        let restart_safe = !completions.has_volatile();
        drop(completions);
        let health = self.health.lock().unwrap();
        let error = health.recovery_error.clone().or_else(|| health.completion_error.clone());
        StorageStatus { error, writes_blocked: health.recovery_error.is_some(), pending_completions, restart_safe }
    }

    fn ensure_writable(&self) -> Result<(), String> {
        aerowave_core::persistence::require_writable(self.health.lock().unwrap().recovery_error.as_deref())
    }

    /// A recovery retry must read valid files before it changes any live data.
    /// The caller serializes this with the clock and verifies no active ring.
    pub fn retry_load_with<C>(&self, commit: C) -> Result<AppData, String>
    where C: FnOnce(&mut AppData, AppData),
    {
        let _writing = self.write_lock.lock().unwrap();
        if self.load_error().is_none() { return Ok(self.snapshot()); }
        let allow_missing_receipts = !self.health.lock().unwrap().receipts_required;
        let (data, completions, error, receipts_required) = Self::read_files(&self.path, false, allow_missing_receipts);
        if let Some(error) = error {
            let mut health = self.health.lock().unwrap();
            health.recovery_error = Some(error.clone());
            health.receipts_required = receipts_required;
            return Err(error);
        }
        let mut current = self.data.lock().unwrap();
        let mut live_completions = self.completions.lock().unwrap();
        live_completions.merge_durable_receipts(completions.receipts());
        let pending = live_completions.has_pending();
        drop(live_completions);
        commit(&mut current, data);
        let mut health = self.health.lock().unwrap();
        health.recovery_error = None;
        health.receipts_required = false;
        if !pending { health.completion_error = None; }
        drop(health);
        drop(current);
        Ok(self.snapshot())
    }

    pub fn scheduled_enabled(&self, alarm: &Alarm) -> bool {
        alarm.enabled && (!alarm.days.is_empty() || !self.completions.lock().unwrap().contains(&alarm.arm()))
    }

    pub fn claim_one_shot(&self, alarm: &Alarm) {
        self.completions.lock().unwrap().claim(alarm.arm());
        self.health.lock().unwrap().completion_error = Some(
            "Saving one-shot completion. It will not repeat in this session; keep Aerowave open until the completion is saved.".into());
    }

    pub fn has_pending_completions(&self) -> bool { self.completions.lock().unwrap().has_pending() }

    fn completed_save(&self, receipts: &Receipts) {
        let mut completions = self.completions.lock().unwrap();
        completions.persisted(receipts, false, true);
        if !completions.has_pending() { self.health.lock().unwrap().completion_error = None; }
    }

    /// Either successful write prevents a repeat after a normal restart. If
    /// both fail, the in-memory receipt still protects this session and remains
    /// queued for retry; storage failure cannot honestly promise more.
    pub fn retry_completions(&self) -> Result<bool, String> {
        let _writing = self.write_lock.lock().unwrap();
        if !self.has_pending_completions() { return Ok(false); }
        self.ensure_writable()?;
        let (candidate, receipts) = self.snapshot_and_receipts();
        let receipt_result = serde_json::to_vec_pretty(&receipts).map_err(|error| error.to_string())
            .and_then(|bytes| self.write_atomic(&Self::receipts_path(&self.path), &bytes));
        let settings_result = self.write_snapshot(&candidate);
        if settings_result.is_ok() { *self.data.lock().unwrap() = candidate; }
        let mut completions = self.completions.lock().unwrap();
        completions.persisted(&receipts, receipt_result.is_ok(), settings_result.is_ok());
        let error = settings_result.err().map(|error| {
            if completions.has_volatile() {
                format!("Could not save one-shot completion ({error}; receipt: {}). It will not repeat in this session, but may ring again after restarting. Keep Aerowave open while saving retries.", receipt_result.err().unwrap_or_default())
            } else {
                format!("The one-shot completion is saved separately and will not repeat after restart, but the settings update failed ({error}). Saving will retry.")
            }
        });
        self.health.lock().unwrap().completion_error = error.clone();
        error.map_or(Ok(true), Err)
    }

    /// Where the settings file actually ended up, for the UI to show.
    pub fn path(&self) -> &std::path::Path {
        &self.path
    }

    pub fn save(&self) -> Result<(), String> {
        // One writer at a time, all the way through the rename.
        let _writing = self.write_lock.lock().unwrap();
        self.ensure_writable()?;
        let (data, receipts) = self.snapshot_and_receipts();
        self.write_snapshot(&data)?;
        *self.data.lock().unwrap() = data;
        self.completed_save(&receipts);
        Ok(())
    }

    fn write_snapshot(&self, data: &AppData) -> Result<(), String> {
        let bytes = serde_json::to_vec_pretty(data).map_err(|error| error.to_string())?;
        self.write_atomic(&self.path, &bytes)
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
        aerowave_core::persistence::replace_synced(
            || Ok(()),
            |_| fs::rename(tmp, &self.path).map_err(|error| format!("rename config: {error}")),
            || self.sync_directory(),
        ).map_err(|failure| self.write_failure(&self.path, failure))
    }

    fn write_failure(&self, path: &std::path::Path, failure: aerowave_core::persistence::ReplaceError<String>) -> String {
        use aerowave_core::persistence::ReplaceError;
        match failure {
            ReplaceError::BeforeRename(error) => error,
            ReplaceError::AfterRename(error) => {
                let note = format!("The save to {} may already have applied: rename succeeded, but directory sync failed ({error}).", path.display());
                if path == self.path {
                    let note = format!("{note} Further settings writes are blocked. Retry reading saved settings before making another change.");
                    self.health.lock().unwrap().recovery_error = Some(note.clone());
                    note
                } else {
                    // Receipt uncertainty must not prevent the independent
                    // settings write from completing this one-shot safely.
                    note
                }
            }
        }
    }

    fn sync_directory(&self) -> Result<(), String> {
        #[cfg(unix)]
        if let Some(directory) = self.path.parent() {
            fs::File::open(directory).and_then(|file| file.sync_all())
                .map_err(|error| format!("sync settings directory: {error}"))?;
        }
        Ok(())
    }

    fn write_atomic(&self, path: &std::path::Path, bytes: &[u8]) -> Result<(), String> {
        let tmp = path.with_extension("json.tmp");
        aerowave_core::persistence::replace_synced(
            || {
                let mut file = OpenOptions::new().write(true).create(true).truncate(true).open(&tmp)
                    .map_err(|error| format!("open {}: {error}", tmp.display()))?;
                file.write_all(bytes).and_then(|_| file.sync_all())
                    .map_err(|error| format!("write {}: {error}", tmp.display()))?;
                Ok::<_, String>(())
            },
            |_| fs::rename(&tmp, path).map_err(|error| format!("rename {}: {error}", path.display())),
            || self.sync_directory(),
        ).map_err(|failure| self.write_failure(path, failure))
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
        self.ensure_writable()?;
        let (mut staged, receipts) = self.snapshot_and_receipts();
        change(&mut staged)?;
        aerowave_core::persistence::update_with(
            &mut staged,
            |_| {},
            |candidate| self.write_snapshot(candidate),
            |_, candidate| {
                let mut data = self.data.lock().unwrap();
                // Automatic consumption is not a user's enabled-to-disabled
                // edit. Publish the effective previous state to callbacks so
                // an unrelated alarm save cannot cancel a legitimate snooze.
                Self::apply_receipts(&mut data, &self.completions.lock().unwrap().receipts());
                commit(&mut data, candidate);
            },
        )?;
        self.completed_save(&receipts);
        Ok(())
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
        let was_blocked = self.load_error().is_some();
        // A restore is an explicit recovery action, but may not destroy the
        // unreadable/invalid originals that normal saves are protecting.
        if was_blocked { self.preserve_blocked_originals()?; }
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

        for alarm in &mut imported.alarms {
            alarm.enabled = false;
            alarm.skip_date = None;
            alarm.arming_revision = format!("{:032x}", rand::random::<u128>());
        }
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
        let result = data.clone();
        drop(data);
        // Imported alarms have fresh revisions and are disabled. If repairing
        // a broken receipt file now fails, those alarms still cannot ring.
        if was_blocked {
            if let Err(error) = self.write_atomic(&Self::receipts_path(&self.path), b"[]") {
                let error = format!("The backup settings were restored with alarms off, but the completion receipt file could not be repaired ({error}). Original files were preserved. Retry the restore.");
                let mut health = self.health.lock().unwrap();
                health.recovery_error = Some(error.clone());
                health.receipts_required = true;
                return Err(error);
            }
        }
        *self.completions.lock().unwrap() = Completions::default();
        *self.health.lock().unwrap() = StoreHealth::default();
        Ok((result, recovery_path))
    }

    fn preserve_blocked_originals(&self) -> Result<(), String> {
        for original in [self.path.clone(), Self::receipts_path(&self.path)] {
            match fs::symlink_metadata(&original) {
                Err(error) if error.kind() == std::io::ErrorKind::NotFound => continue,
                Err(error) => return Err(format!("Cannot preserve {}: {error}. Restore stopped.", original.display())),
                Ok(_) => {}
            }
            aerowave_core::persistence::preserve_original(
                || fs::read(&original).map_err(|error| format!("Cannot read and preserve {}: {error}. Restore stopped.", original.display())),
                |bytes| {
                    let name = original.file_stem().unwrap_or_default().to_string_lossy();
                    let stamp = Local::now().format("%Y%m%d-%H%M%S");
                    for suffix in 0..100 {
                        let kept = original.with_file_name(format!("{name}.before-recovery-{stamp}-{suffix}.json"));
                        match OpenOptions::new().write(true).create_new(true).open(&kept) {
                            Err(error) if error.kind() == std::io::ErrorKind::AlreadyExists => continue,
                            Err(error) => return Err(format!("Cannot preserve {}: {error}. Restore stopped.", kept.display())),
                            Ok(mut file) => {
                                file.write_all(bytes).and_then(|_| file.sync_all())
                                    .map_err(|error| format!("Cannot sync original copy {}: {error}. Restore stopped.", kept.display()))?;
                                self.sync_directory()?;
                                return Ok(());
                            }
                        }
                    }
                    Err("Cannot choose a unique original recovery filename. Restore stopped.".into())
                },
            )?;
        }
        Ok(())
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
