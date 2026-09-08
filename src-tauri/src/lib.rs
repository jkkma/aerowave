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
#[cfg(not(windows))]
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
pub struct ConfigLocation {
    pub path: String,
    pub portable: bool,
    /// Set when the settings file was there but unusable, so the UI can say
    /// so rather than let a reset pass for a clean first run.
    pub load_error: Option<String>,
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
    // Never let this lose the rest of the settings - they are saved already.
    sync_autostart(&app, &state, want_autostart)
}

/// What Windows would actually run at logon, and whether the user has said no
/// to it outside this app.
#[cfg(windows)]
mod logon {
    use winreg::enums::{HKEY_CURRENT_USER, KEY_SET_VALUE};
    use winreg::RegKey;

    const RUN: &str = r"SOFTWARE\Microsoft\Windows\CurrentVersion\Run";
    const APPROVED: &str =
        r"SOFTWARE\Microsoft\Windows\CurrentVersion\Explorer\StartupApproved\Run";
    const VALUE: &str = "Aerowave";

    /// The command line in the Run key, if there is one.
    pub fn entry() -> Option<String> {
        RegKey::predef(HKEY_CURRENT_USER)
            .open_subkey(RUN)
            .ok()?
            .get_value::<String, _>(VALUE)
            .ok()
    }

    /// Task Manager and Settings > Startup record their override here; an odd
    /// first byte means the user switched it off.
    pub fn switched_off_by_user() -> bool {
        RegKey::predef(HKEY_CURRENT_USER)
            .open_subkey(APPROVED)
            .ok()
            .and_then(|k| k.get_raw_value(VALUE).ok())
            .and_then(|v| v.bytes.first().copied())
            .map(|b| b % 2 == 1)
            .unwrap_or(false)
    }

    /// Write the logon entry ourselves, quoted.
    ///
    /// tauri-plugin-autostart writes it unquoted with the argument appended -
    /// `C:\Aero Space Test\aerowave.exe --minimized`. Windows parses that
    /// left to right, trying `C:\Aero.exe`, then `C:\Aero Space.exe`, before
    /// reaching the real one: if any earlier candidate exists it launches that
    /// instead, and if none does it only works by falling through. Quoting
    /// removes the ambiguity, and this is a feature whose failure mode is an
    /// alarm clock that never starts.
    pub fn enable_for_this_exe() -> Result<String, String> {
        let exe = std::env::current_exe().map_err(|e| format!("cannot find this exe: {e}"))?;
        let value = format!("\"{}\" --minimized", exe.display());
        let (key, _) = RegKey::predef(HKEY_CURRENT_USER)
            .create_subkey(RUN)
            .map_err(|e| format!("cannot open the Run key: {e}"))?;
        key.set_value(VALUE, &value)
            .map_err(|e| format!("cannot write the Run entry: {e}"))?;
        Ok(value)
    }

    /// Remove it. Already gone counts as success.
    pub fn disable() -> Result<(), String> {
        let Ok(key) = RegKey::predef(HKEY_CURRENT_USER).open_subkey_with_flags(RUN, KEY_SET_VALUE)
        else {
            return Ok(());
        };
        match key.delete_value(VALUE) {
            Ok(()) => Ok(()),
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(()),
            Err(e) => Err(format!("cannot remove the Run entry: {e}")),
        }
    }

    /// Drop that override, so the app's own toggle is the only truth again.
    pub fn clear_override() {
        if let Ok(key) =
            RegKey::predef(HKEY_CURRENT_USER).open_subkey_with_flags(APPROVED, KEY_SET_VALUE)
        {
            let _ = key.delete_value(VALUE);
        }
    }

    /// Does the entry point at the copy that is running now? A portable copy
    /// that has been moved leaves one pointing at nothing.
    pub fn entry_is_this_exe() -> bool {
        let (Some(entry), Ok(exe)) = (entry(), std::env::current_exe()) else {
            return false;
        };
        entry
            .to_lowercase()
            .contains(&exe.to_string_lossy().to_lowercase())
    }
}

/// Bring the logon entry in line with the toggle.
///
/// `auto-launch`'s `is_enabled()` is a single bit and cannot separate "no
/// entry", "an entry pointing at a copy that has since moved" and "the user
/// switched it off in Task Manager". That matters twice over: a moved
/// portable copy would keep an entry that launches nothing while SETUP still
/// reads ON, and settings are written on every volume nudge, so acting on a
/// bare mismatch would quietly overturn the user's own choice every time.
#[cfg(windows)]
fn sync_autostart(app: &AppHandle, state: &AppState, want: bool) -> Result<(), String> {
    let present = logon::entry().is_some();

    let result: Result<(), String> = if want {
        if !present || !logon::entry_is_this_exe() {
            logon::enable_for_this_exe().map(|_| ())
        } else if logon::switched_off_by_user() {
            // Present, correct, and disabled outside the app. That is the
            // user's decision; make our toggle agree rather than fight it.
            let _ = state.store.update(|d| d.settings.start_with_windows = false);
            let _ = app.emit("settings-updated", ());
            return Ok(());
        } else {
            Ok(())
        }
    } else if present {
        let disabled = logon::disable();
        logon::clear_override();
        disabled
    } else {
        Ok(())
    };

    result.map_err(|e| format!("settings saved, but start-with-Windows failed: {e}"))
}

#[cfg(not(windows))]
fn sync_autostart(app: &AppHandle, _state: &AppState, want: bool) -> Result<(), String> {
    let manager = app.autolaunch();
    if manager.is_enabled().unwrap_or(false) == want {
        return Ok(());
    }
    let result = if want {
        manager.enable()
    } else {
        manager.disable()
    };
    result.map_err(|e| format!("settings saved, but start-with-Windows failed: {e}"))
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
    let (track, total) = library::pick_random(dir, &state.recent)
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
    scheduler::backup_track(&app)
        .map(|(path, name, total)| TrackPick { path, name, total })
        .ok_or_else(|| format!("nothing playable in the backup folder ({folder})"))
}

#[tauri::command]
async fn probe_stream(
    url: String,
    want_title: bool,
    // Optional so the call sites that do not care keep deserializing. Set by
    // the now-playing poll, which already holds a direct URL.
    skip_resolve: Option<bool>,
) -> Result<stream::StreamInfo, String> {
    stream::probe(&url, want_title, skip_resolve.unwrap_or(false)).await
}

#[tauri::command]
fn next_alarm(app: AppHandle) -> Option<NextAlarm> {
    scheduler::next_alarm(&app)
}

/// Ring an alarm right now, to hear what it will sound like.
///
/// Takes the alarm by value rather than by id deliberately. Looking it up
/// meant the UI had to save the edit first, which armed the edited time the
/// moment TEST was pressed and left CANCEL with nothing to undo. Resolving
/// the source from the passed alarm gives the same answer the real ring will
/// get, without touching the stored copy.
#[tauri::command]
fn test_alarm(app: AppHandle, alarm: Alarm) -> FirePayload {
    scheduler::resolve_source(&app, &alarm, "test")
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

/// Tells the UI where its settings live, and whether this is a portable copy.
#[tauri::command]
fn config_location(state: State<AppState>) -> ConfigLocation {
    ConfigLocation {
        path: state.store.path().to_string_lossy().to_string(),
        portable: store::portable_data_dir().is_some(),
        load_error: state.store.load_error.clone(),
    }
}

/// Whatever is ringing right now, if anything.
///
/// The scheduler starts ticking in `setup`, seconds before the webview has
/// finished loading and registered its `alarm-fire` listener - and an event
/// emitted with nobody listening is dropped, while the tick has already
/// written the minute into `fired` so it never retries. The page therefore
/// asks on boot rather than trusting it was listening in time. Nothing
/// ringing is also the moment to undo a previous ring's always-on-top, which
/// otherwise only `dismiss` and `snooze` clear.
#[tauri::command]
fn pending_alarm(app: AppHandle, state: State<AppState>) -> Option<FirePayload> {
    let ringing = state.sched.lock().unwrap().ringing.clone();
    let Some(id) = ringing else {
        if let Some(w) = app.get_webview_window("main") {
            let _ = w.set_always_on_top(false);
        }
        return None;
    };

    let alarm = state
        .store
        .data
        .lock()
        .unwrap()
        .alarms
        .iter()
        .find(|a| a.id == id)
        .cloned();

    match alarm {
        // Re-resolve rather than replay: the backup folder may have changed,
        // and a folder alarm should get a fresh track.
        Some(a) => Some(scheduler::resolve_source(&app, &a, "catchup")),
        None => {
            // Deleted while it was ringing; let go of the window.
            scheduler::dismiss(&app, &id);
            None
        }
    }
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    // A portable copy keeps the webview's cache in the app directory too,
    // rather than leaving it behind in the user profile. WebView2 reads this
    // before the environment is created, so it has to be set first thing.
    if let Some(data) = store::portable_data_dir() {
        let webview = data.join("webview");
        if std::fs::create_dir_all(&webview).is_ok() {
            std::env::set_var("WEBVIEW2_USER_DATA_FOLDER", &webview);
        }
    }

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

            // A portable copy that has been moved, or reinstalled to a new
            // versioned directory, leaves a logon entry pointing at a path
            // that no longer exists. One registry read puts it right.
            #[cfg(windows)]
            {
                let want = handle
                    .state::<AppState>()
                    .store
                    .data
                    .lock()
                    .unwrap()
                    .settings
                    .start_with_windows;
                if want && !logon::entry_is_this_exe() {
                    let _ = logon::enable_for_this_exe();
                }
            }

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
            config_location,
            pending_alarm,
        ])
        .run(tauri::generate_context!())
        .expect("error while running Aerowave");
}
