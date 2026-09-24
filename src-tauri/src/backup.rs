//! User-visible backup I/O and the guarded restore boundary.

use std::fs;
use std::io::Read;
use std::path::Path;
#[cfg(desktop)]
use std::fs::OpenOptions;
#[cfg(desktop)]
use std::io::Write;

use serde::Serialize;
use tauri::{AppHandle, Emitter, State};
#[cfg(desktop)]
use tauri::Manager;
#[cfg(desktop)]
use tauri_plugin_dialog::DialogExt;

use crate::store::{Alarm, AppData};
use crate::{scheduler, AppState};

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct BackupPreview {
    data: AppData,
    station_count: usize,
    alarm_count: usize,
    enabled_alarm_count: usize,
    has_folder_paths: bool,
    warnings: Vec<String>,
}

fn parsed(content: &str) -> Result<(AppData, Vec<String>), String> {
    let (value, warnings) = aerowave_core::backup::inspect(content)?;
    let data: AppData = serde_json::from_value(value).map_err(|e| format!("Backup data is invalid: {e}"))?;
    Ok((data, warnings))
}

fn encode(data: &AppData) -> Result<String, String> {
    let value = serde_json::to_value(data).map_err(|e| e.to_string())?;
    aerowave_core::backup::encode(&value)
}

#[tauri::command]
pub fn create_backup(app: AppHandle, state: State<AppState>, alarms: Option<Vec<Alarm>>) -> Result<String, String> {
    let mut data = state.store.snapshot();
    if let Some(alarms) = alarms {
        data.alarms = alarms;
    } else {
        #[cfg(mobile)]
        {
            let native = tauri_plugin_android_audio::android_alarm_state(&app)?;
            data.alarms = native.alarms.into_iter().map(|a| {
                serde_json::to_value(a).and_then(serde_json::from_value).map_err(|e| e.to_string())
            }).collect::<Result<Vec<_>, _>>()?;
        }
    }
    #[cfg(desktop)]
    let _ = app;
    encode(&data)
}

#[tauri::command]
pub fn inspect_backup(content: String) -> Result<BackupPreview, String> {
    let (data, mut warnings) = parsed(&content)?;
    let has_folder_paths = data.settings.backup_folder.as_deref().is_some_and(|s| !s.is_empty())
        || data.settings.shuffle_folder.as_deref().is_some_and(|s| !s.is_empty())
        || data.alarms.iter().any(|a| matches!(&a.source, crate::store::AlarmSource::Folder { path } if !path.is_empty()))
        || data.settings.alarm_defaults.as_ref().is_some_and(|a|
            matches!(&a.source, crate::store::AlarmSource::Folder { path } if !path.is_empty()));
    if has_folder_paths {
        warnings.push("Music folder paths may need to be selected again on this device.".into());
    }
    Ok(BackupPreview {
        station_count: data.stations.len(),
        alarm_count: data.alarms.len(),
        enabled_alarm_count: data.alarms.iter().filter(|a| a.enabled).count(),
        has_folder_paths,
        warnings,
        data,
    })
}

#[tauri::command]
pub async fn read_backup_file(app: AppHandle) -> Result<Option<String>, String> {
    #[cfg(mobile)]
    return tauri_plugin_android_audio::read_android_backup_file(&app);
    #[cfg(desktop)]
    {
        let (tx, rx) = tokio::sync::oneshot::channel();
        let mut dialog = app.dialog().file().add_filter("Aerowave backup", &["json"]);
        if let Some(window) = app.get_webview_window("main") { dialog = dialog.set_parent(&window); }
        dialog.pick_file(move |path| { let _ = tx.send(path); });
        let Some(chosen) = rx.await.map_err(|_| "Backup picker closed unexpectedly".to_string())? else { return Ok(None); };
        let path = chosen.into_path().map_err(|e| format!("Could not read the chosen path: {e}"))?;
        let mut file = fs::File::open(&path).map_err(|e| format!("Could not open backup: {e}"))?;
        let mut bytes = Vec::new();
        (&mut file).take((aerowave_core::backup::MAX_BYTES + 1) as u64).read_to_end(&mut bytes)
            .map_err(|e| format!("Could not read backup: {e}"))?;
        if bytes.len() > aerowave_core::backup::MAX_BYTES { return Err("Backup exceeds the 2 MiB limit".into()); }
        String::from_utf8(bytes).map(Some).map_err(|_| "Backup must be UTF-8 JSON".into())
    }
}

/// Read the last recorded copy made by Restore. Older versions had no pointer,
/// so retain a filename scan for those and for an unusable pointer.
#[tauri::command]
pub fn read_recovery_backup(state: State<AppState>) -> Result<Option<String>, String> {
    let dir = state.store.path().parent()
        .ok_or_else(|| "Could not locate the settings directory".to_string())?;
    let pointer = dir.join("aerowave.latest-recovery");
    if fs::symlink_metadata(&pointer).is_ok_and(|meta| meta.file_type().is_file()) {
        if let Ok(file) = fs::File::open(&pointer) {
            let mut bytes = Vec::new();
            if file.take(129).read_to_end(&mut bytes).is_ok() && bytes.len() <= 128 {
                if let Ok(target) = std::str::from_utf8(&bytes) {
                    if let Some(name) = aerowave_core::backup::recovery_pointer_target(target) {
                        if let Some(content) = read_valid_recovery(&dir.join(name)) {
                            return Ok(Some(content));
                        }
                    }
                }
            }
        }
    }
    let mut copies = Vec::new();
    for entry in fs::read_dir(dir).map_err(|e| format!("Could not list recovery backups: {e}"))? {
        let entry = entry.map_err(|e| format!("Could not list recovery backups: {e}"))?;
        // Reject links, including a link that happens to carry our file name.
        if !entry.file_type().is_ok_and(|kind| kind.is_file()) { continue; }
        let name = entry.file_name();
        let Some(name) = name.to_str() else { continue; };
        let Some((stamp, number)) = aerowave_core::backup::recovery_name(name) else { continue; };
        copies.push((stamp.to_string(), number, entry.path()));
    }
    copies.sort_unstable_by(|a, b| b.0.cmp(&a.0).then_with(|| b.1.cmp(&a.1)));
    for (_, _, path) in copies {
        if let Some(content) = read_valid_recovery(&path) { return Ok(Some(content)); }
    }
    Ok(None)
}

fn read_valid_recovery(path: &Path) -> Option<String> {
    if !fs::symlink_metadata(path).ok()?.file_type().is_file() { return None; }
    let file = fs::File::open(path).ok()?;
    let mut bytes = Vec::new();
    file.take((aerowave_core::backup::MAX_BYTES + 1) as u64)
        .read_to_end(&mut bytes).ok()?;
    if bytes.len() > aerowave_core::backup::MAX_BYTES { return None; }
    let content = String::from_utf8(bytes).ok()?;
    parsed(&content).ok()?;
    Some(content)
}

#[tauri::command]
pub async fn save_backup_file(app: AppHandle, content: String) -> Result<Option<String>, String> {
    parsed(&content)?;
    #[cfg(mobile)]
    return tauri_plugin_android_audio::save_android_backup_file(
        &app, tauri_plugin_android_audio::SaveBackupFilePayload { content });
    #[cfg(desktop)]
    {
        let (tx, rx) = tokio::sync::oneshot::channel();
        let mut dialog = app.dialog().file().add_filter("Aerowave backup", &["json"])
            .set_file_name("aerowave-backup.json");
        if let Some(window) = app.get_webview_window("main") { dialog = dialog.set_parent(&window); }
        dialog.save_file(move |path| { let _ = tx.send(path); });
        let Some(chosen) = rx.await.map_err(|_| "Backup picker closed unexpectedly".to_string())? else { return Ok(None); };
        let path = chosen.into_path().map_err(|e| format!("Could not use the chosen path: {e}"))?;
        let mut file = OpenOptions::new().write(true).create_new(true).open(&path)
            .map_err(|e| format!("Could not create backup (choose a new file name): {e}"))?;
        file.write_all(content.as_bytes()).and_then(|_| file.sync_all())
            .map_err(|e| format!("Could not finish backup: {e}"))?;
        Ok(Some(path.to_string_lossy().to_string()))
    }
}

#[tauri::command]
pub fn restore_backup(app: AppHandle, state: State<AppState>, content: String) -> Result<AppData, String> {
    let value = aerowave_core::backup::prepare_restore(&content)?;
    let imported: AppData = serde_json::from_value(value).map_err(|e| format!("Backup data is invalid: {e}"))?;
    let _power_update = state.power_updates.lock().unwrap();
    state.sched.lock().unwrap().ensure_restore_idle()?;

    #[cfg(mobile)]
    let native = tauri_plugin_android_audio::android_alarm_state(&app)?;
    #[cfg(mobile)]
    let recovery_alarms: Vec<Alarm> = native.alarms.iter().map(|a| {
        serde_json::to_value(a).and_then(serde_json::from_value).map_err(|e| e.to_string())
    }).collect::<Result<_, _>>()?;
    #[cfg(desktop)]
    let recovery_alarms = None;
    #[cfg(mobile)]
    let recovery_alarms = Some(recovery_alarms);

    let (restored, _) = state.store.restore_with(imported, recovery_alarms, |candidate, recovery_path| {
        state.sched.lock().unwrap().ensure_restore_idle()?;
        #[cfg(mobile)]
        {
            let alarms = candidate.alarms.iter().map(|a| {
                serde_json::to_value(a).and_then(serde_json::from_value).map_err(|e| e.to_string())
            }).collect::<Result<Vec<tauri_plugin_android_audio::AlarmDefinition>, _>>()?;
            let stations = candidate.stations.iter().map(|s| tauri_plugin_android_audio::AlarmStation {
                id: s.id.clone(), name: s.name.clone(), url: s.url.clone(),
            }).collect();
            let payload = tauri_plugin_android_audio::SyncAlarmsPayload {
                alarms: Some(alarms), expected_revision: Some(native.revision), stations,
                backup_folder: candidate.settings.backup_folder.clone(),
            };
            tauri_plugin_android_audio::restore_android_alarms(&app, payload).map_err(|e| format!(
                "Native alarms were not restored ({e}). Settings are unchanged. Recovery backup: {}",
                recovery_path.display()
            ))?;
        }
        #[cfg(desktop)]
        let _ = (candidate, recovery_path);
        Ok(())
    }, |current, candidate| {
        let mut sched = state.sched.lock().unwrap();
        for alarm in &current.alarms { sched.cancel_pending(&alarm.id); }
        *current = candidate;
    })?;
    scheduler::refresh(&app);
    let _ = app.emit("alarms-updated", ());
    let _ = app.emit("settings-updated", ());
    Ok(restored)
}
