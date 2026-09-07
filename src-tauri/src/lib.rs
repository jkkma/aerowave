//! Aerowave - internet radio player and alarm clock.
//!
//! The split of work: Rust owns the clock, the config file, folder scanning
//! and everything that has to keep working while the window is hidden. The
//! webview owns playback and the face.

mod library;
mod scheduler;
mod store;
mod stream;

use std::sync::Mutex;

use serde::Serialize;
use tauri::menu::{Menu, MenuItem, PredefinedMenuItem};
use tauri::tray::{MouseButton, MouseButtonState, TrayIconBuilder, TrayIconEvent};
use tauri::{AppHandle, Emitter, Manager, State, WindowEvent};
use tauri_plugin_autostart::ManagerExt;
use tauri_plugin_dialog::DialogExt;

use library::{FolderInfo, RecentTracks};
use scheduler::{FirePayload, NextAlarm};
use store::{Alarm, AppData, Settings, Station, Store};

pub struct AppState {
    pub store: Store,
    pub sched: Mutex<scheduler::SchedState>,
    pub recent: RecentTracks,
}

#[derive(Serialize, Clone, Debug)]
#[serde(rename_all = "camelCase")]
pub struct TrackPick {
    pub path: String,
    pub name: String,
    pub total: usize,
}

// ---------------------------------------------------------------- commands

#[tauri::command]
fn get_state(state: State<AppState>) -> AppData {
    state.store.snapshot()
}

#[tauri::command]
fn save_stations(state: State<AppState>, stations: Vec<Station>) -> Result<(), String> {
    state.store.update(|d| d.stations = stations)
}

#[tauri::command]
fn save_alarms(app: AppHandle, state: State<AppState>, alarms: Vec<Alarm>) -> Result<(), String> {
    state.store.update(|d| d.alarms = alarms)?;
    let _ = app.emit("alarms-updated", ());
    Ok(())
}

#[tauri::command]
fn save_settings(app: AppHandle, state: State<AppState>, settings: Settings) -> Result<(), String> {
    let want_autostart = settings.start_with_windows;
    state.store.update(|d| d.settings = settings)?;

    // Keep the registry entry in step with the toggle, but only when it is
    // actually out of step: settings are saved on every volume nudge, and
    // deleting a registry value that is not there is an error.
    let manager = app.autolaunch();
    if manager.is_enabled().unwrap_or(false) != want_autostart {
        let result = if want_autostart {
            manager.enable()
        } else {
            manager.disable()
        };
        // Never let this lose the rest of the settings - they are saved already.
        if let Err(e) = result {
            return Err(format!("settings saved, but start-with-Windows failed: {e}"));
        }
    }
    Ok(())
}

/// Open the folder picker. Async so the dialog does not block the main
/// thread; the callback hands the answer back over a channel.
#[tauri::command]
async fn pick_folder(app: AppHandle) -> Option<FolderInfo> {
    let (tx, rx) = tokio::sync::oneshot::channel();
    app.dialog().file().pick_folder(move |chosen| {
        let _ = tx.send(chosen);
    });
    let chosen = rx.await.ok().flatten()?;
    let path = chosen.into_path().ok()?;
    Some(library::info(&path))
}

#[tauri::command]
fn folder_info(path: String) -> FolderInfo {
    library::info(std::path::Path::new(&path))
}

/// Pick one random file out of a folder and open it to the asset protocol.
#[tauri::command]
fn random_track(app: AppHandle, state: State<AppState>, path: String) -> Result<TrackPick, String> {
    let dir = std::path::Path::new(&path);
    if !dir.is_dir() {
        return Err(format!("{path} is not a folder"));
    }
    let total = library::scan(dir).len();
    let track = library::pick_random(dir, &state.recent)
        .ok_or_else(|| "no playable audio files in that folder".to_string())?;
    app.asset_protocol_scope()
        .allow_file(&track)
        .map_err(|e| e.to_string())?;
    Ok(TrackPick {
        name: track
            .file_name()
            .map(|n| n.to_string_lossy().to_string())
            .unwrap_or_default(),
        path: track.to_string_lossy().to_string(),
        total,
    })
}

/// A random track from the backup folder - what the webview reaches for when
/// a stream will not play.
#[tauri::command]
fn backup_track(app: AppHandle, state: State<AppState>) -> Result<TrackPick, String> {
    let folder = state
        .store
        .data
        .lock()
        .unwrap()
        .settings
        .backup_folder
        .clone()
        .ok_or_else(|| "no backup folder set".to_string())?;
    let total = library::scan(std::path::Path::new(&folder)).len();
    scheduler::backup_track(&app)
        .map(|(path, name)| TrackPick { path, name, total })
        .ok_or_else(|| format!("nothing playable in the backup folder ({folder})"))
}

#[tauri::command]
async fn probe_stream(url: String, want_title: bool) -> Result<stream::StreamInfo, String> {
    stream::probe(&url, want_title).await
}

#[tauri::command]
fn next_alarm(app: AppHandle) -> Option<NextAlarm> {
    scheduler::next_alarm(&app)
}

/// Ring an alarm right now, to hear what it will sound like.
#[tauri::command]
fn test_alarm(app: AppHandle, state: State<AppState>, alarm_id: String) -> Result<FirePayload, String> {
    let alarm = state
        .store
        .data
        .lock()
        .unwrap()
        .alarms
        .iter()
        .find(|a| a.id == alarm_id)
        .cloned()
        .ok_or_else(|| "no such alarm".to_string())?;
    Ok(scheduler::resolve_source(&app, &alarm, "test"))
}

#[tauri::command]
fn snooze_alarm(app: AppHandle, alarm_id: String, minutes: u32) -> Result<i64, String> {
    scheduler::snooze(&app, &alarm_id, minutes)
}

#[tauri::command]
fn dismiss_alarm(app: AppHandle, alarm_id: String) {
    scheduler::dismiss(&app, &alarm_id);
}

#[tauri::command]
fn hide_window(app: AppHandle) {
    if let Some(w) = app.get_webview_window("main") {
        let _ = w.hide();
    }
}

#[tauri::command]
fn quit_app(app: AppHandle) {
    app.exit(0);
}

// ------------------------------------------------------------------- setup

fn build_tray(app: &AppHandle) -> tauri::Result<()> {
    let show = MenuItem::with_id(app, "show", "Show Aerowave", true, None::<&str>)?;
    let stop = MenuItem::with_id(app, "stop", "Stop playback", true, None::<&str>)?;
    let quit = MenuItem::with_id(app, "quit", "Quit", true, None::<&str>)?;
    let sep = PredefinedMenuItem::separator(app)?;
    let menu = Menu::with_items(app, &[&show, &stop, &sep, &quit])?;

    TrayIconBuilder::with_id("aerowave-tray")
        .icon(app.default_window_icon().cloned().expect("bundled icon"))
        .tooltip("Aerowave")
        .menu(&menu)
        .show_menu_on_left_click(false)
        .on_menu_event(|app, event| match event.id().as_ref() {
            "show" => surface(app),
            "stop" => {
                let _ = app.emit("tray-stop", ());
            }
            "quit" => app.exit(0),
            _ => {}
        })
        .on_tray_icon_event(|tray, event| {
            if let TrayIconEvent::Click {
                button: MouseButton::Left,
                button_state: MouseButtonState::Up,
                ..
            } = event
            {
                surface(tray.app_handle());
            }
        })
        .build(app)?;
    Ok(())
}

fn surface(app: &AppHandle) {
    if let Some(w) = app.get_webview_window("main") {
        let _ = w.unminimize();
        let _ = w.show();
        let _ = w.set_focus();
    }
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .plugin(tauri_plugin_single_instance::init(|app, _argv, _cwd| {
            // A second launch just brings the running one forward.
            surface(app);
        }))
        .plugin(tauri_plugin_dialog::init())
        .plugin(tauri_plugin_autostart::init(
            tauri_plugin_autostart::MacosLauncher::LaunchAgent,
            Some(vec!["--minimized"]),
        ))
        .setup(|app| {
            let handle = app.handle().clone();
            let store = Store::load(&handle);
            app.manage(AppState {
                store,
                sched: Mutex::new(scheduler::SchedState::default()),
                recent: RecentTracks::default(),
            });

            build_tray(&handle)?;
            scheduler::spawn(handle.clone());

            // Launched by the autostart entry: go straight to the tray.
            if std::env::args().any(|a| a == "--minimized") {
                if let Some(w) = app.get_webview_window("main") {
                    let _ = w.hide();
                }
            }
            Ok(())
        })
        .on_window_event(|window, event| {
            if let WindowEvent::CloseRequested { api, .. } = event {
                let app = window.app_handle();
                let to_tray = app
                    .state::<AppState>()
                    .store
                    .data
                    .lock()
                    .unwrap()
                    .settings
                    .minimize_to_tray;
                if to_tray {
                    // Closing the window must not silence the alarms.
                    api.prevent_close();
                    let _ = window.hide();
                }
            }
        })
        .invoke_handler(tauri::generate_handler![
            get_state,
            save_stations,
            save_alarms,
            save_settings,
            pick_folder,
            folder_info,
            random_track,
            backup_track,
            probe_stream,
            next_alarm,
            test_alarm,
            snooze_alarm,
            dismiss_alarm,
            hide_window,
            quit_app,
        ])
        .run(tauri::generate_context!())
        .expect("error while running Aerowave");
}
