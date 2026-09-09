//! Aerowave - internet radio player and alarm clock.
//!
//! The split of work: Rust owns the clock, the config file, folder scanning
//! and everything that has to keep working while the window is hidden. The
//! webview owns playback and the face.

mod browse;
mod hls;
mod library;
mod relay;
mod scheduler;
mod store;
mod stream;

use std::collections::HashMap;
use std::sync::{Arc, Mutex};

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
    /// None if the loopback listener would not bind. Playback then falls back
    /// to handing <audio> the station URL directly, which is what it did
    /// before the relay existed - fewer stations, but not none.
    pub relay: Mutex<Option<Arc<relay::Relay>>>,
    /// HLS sessions. Unlike the relay this needs no socket, so there is
    /// nothing to fail at startup and no Option to unwrap.
    pub hls: Arc<hls::Hls>,
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
    let mut dialog = app.dialog().file();
    // Owned by the main window, so the picker is modal to the app and cannot
    // end up behind it - an unowned dialog is a stray top-level window, and
    // whatever the user does next can bury it.
    if let Some(window) = app.get_webview_window("main") {
        dialog = dialog.set_parent(&window);
    }
    dialog.pick_folder(move |chosen| {
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

/// Open an HLS session for `url` and hand back the base the webview's loader
/// should build its requests on. See `hls.rs` for why HLS does not go through
/// the loopback relay the way ordinary audio does.
#[tauri::command]
fn hls_session(state: State<'_, AppState>, url: String) -> Option<String> {
    state
        .hls
        .open(&url)
        .map(|session| format!("http://awhls.localhost/{session}"))
}

#[tauri::command]
fn hls_close(state: State<'_, AppState>, session: String) {
    state.hls.close(&session);
}

/// Serve one playlist or segment to hls.js.
///
/// The path is the session, `u` is the absolute upstream URL base64url-encoded
/// - encoded because 8 of 45 measured radio HLS streams sign their segment
/// URLs with query strings that have to survive byte-exact, and because an
/// opaque blob can never be confused with our own parameters.
///
/// CORS is named exactly rather than starred: this answers our own webview and
/// nothing else should be asking.
async fn hls_response(
    hls: &Arc<hls::Hls>,
    request: tauri::http::Request<Vec<u8>>,
) -> tauri::http::Response<Vec<u8>> {
    use base64::Engine;

    let reply = |status: u16, body: Vec<u8>, content_type: &str, final_url: &str| {
        let mut builder = tauri::http::Response::builder()
            .status(status)
            .header("Content-Type", content_type)
            .header("Cache-Control", "no-store")
            .header("Access-Control-Allow-Origin", WEBVIEW_ORIGIN)
            .header("Access-Control-Allow-Headers", "Range")
            .header("Access-Control-Expose-Headers", "X-Aerowave-Final");
        if !final_url.is_empty() {
            builder = builder.header("X-Aerowave-Final", final_url);
        }
        builder.body(body).unwrap_or_else(|_| {
            tauri::http::Response::builder()
                .status(500)
                .body(Vec::new())
                .unwrap()
        })
    };

    if request.method() == tauri::http::Method::OPTIONS {
        return reply(204, Vec::new(), "text/plain", "");
    }
    if request.method() != tauri::http::Method::GET {
        return reply(405, Vec::new(), "text/plain", "");
    }

    let uri = request.uri();
    let session = uri.path().trim_start_matches('/').to_string();
    let query: HashMap<String, String> = uri
        .query()
        .unwrap_or("")
        .split('&')
        .filter_map(|pair| pair.split_once('='))
        .map(|(k, v)| (k.to_string(), v.to_string()))
        .collect();

    let Some(encoded) = query.get("u") else {
        return reply(400, b"no url".to_vec(), "text/plain", "");
    };
    let Ok(raw) = base64::engine::general_purpose::URL_SAFE_NO_PAD.decode(encoded) else {
        return reply(400, b"bad url encoding".to_vec(), "text/plain", "");
    };
    let Ok(url) = String::from_utf8(raw) else {
        return reply(400, b"bad url encoding".to_vec(), "text/plain", "");
    };
    let range = query.get("r").and_then(|r| {
        let (start, end) = r.split_once('-')?;
        Some((start.parse().ok()?, end.parse().ok()?))
    });

    match hls.fetch(&session, &url, range).await {
        Ok(got) => {
            let content_type = got.content_type.clone();
            let final_url = got.final_url.clone();
            reply(got.status, got.body, &content_type, &final_url)
        }
        Err(e) => {
            eprintln!("aerowave hls: {url}: {e}");
            reply(502, e.into_bytes(), "text/plain", "")
        }
    }
}

/// A now-playing title, and the station it belongs to.
///
/// The URL is carried because a relay connection outlives by a moment the
/// station that opened it, and the front end has to be able to tell a late
/// title from a current one.
#[derive(Clone, Serialize)]
struct IcyTitle {
    url: String,
    title: String,
}

/// Hand back a loopback URL that plays `url`, or None when the relay is not
/// running. See `relay.rs` for why a station is worth relaying at all.
#[tauri::command]
fn relay_url(state: State<'_, AppState>, url: String) -> Option<String> {
    let relay = state.relay.lock().unwrap();
    relay.as_ref().map(|r| r.route(&url))
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

/// Search the radio-browser.info directory of public stations.
///
/// Async, and off the store entirely: a mirror having a slow morning must
/// not hold up the clock, and a search changes nothing that is saved.
#[tauri::command]
async fn browse_stations(query: browse::Query) -> Result<browse::Page, String> {
    browse::search(query).await
}

/// The countries the directory has stations in, for the browse filter.
#[tauri::command]
async fn browse_countries() -> Result<Vec<browse::Country>, String> {
    browse::countries().await
}

/// The genres worth filtering by, for the same pair of dropdowns.
#[tauri::command]
async fn browse_tags() -> Result<Vec<browse::Tag>, String> {
    browse::tags().await
}

/// A station's own artwork, as a data URL the orb can wear.
///
/// Fetched here rather than by the webview because the content security
/// policy allows it no remote images, and because a texture uploaded to WebGL
/// has to be same-origin or CORS-cleared, which a broadcaster's logo host
/// will not be.
#[tauri::command]
async fn station_logo(url: String) -> Result<String, String> {
    browse::logo(&url).await
}

/// Look a station's artwork up in the directory, for one saved without any.
///
/// `None` means the directory has nothing usable for it, which is a perfectly
/// ordinary answer - the webview remembers that and stops asking.
#[tauri::command]
async fn station_art(name: String, url: String) -> Result<Option<browse::Art>, String> {
    browse::art(&name, &url).await
}

/// What one filter leaves available to the other: the genres in a country, or
/// the countries carrying a genre. Costly enough that the webview asks only
/// when a filter changes.
#[tauri::command]
async fn browse_facets(query: browse::Query) -> Result<browse::Facets, String> {
    browse::facets(query).await
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
/// Where the webview itself is served from. Tauri v2 uses this on Windows;
/// it is the only origin the HLS protocol answers.
const WEBVIEW_ORIGIN: &str = "http://tauri.localhost";


pub fn run() {
    let hls_state: Arc<hls::Hls> = Arc::new(hls::Hls::default());
    let hls_protocol = hls_state.clone();
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
        .register_asynchronous_uri_scheme_protocol("awhls", {
            let hls = hls_protocol.clone();
            move |_ctx, request, responder| {
                let hls = hls.clone();
                tauri::async_runtime::spawn(async move {
                    responder.respond(hls_response(&hls, request).await);
                });
            }
        })
        .setup(move |app| {
            let handle = app.handle().clone();
            let store = Store::load(&handle);
            app.manage(AppState {
                store,
                sched: Mutex::new(scheduler::SchedState::default()),
                recent: RecentTracks::default(),
                relay: Mutex::new(None),
                hls: hls_state,
            });

            // The relay binds a port, so it cannot be built before the async
            // runtime is up. Nothing waits on it: the first station is played
            // long after this resolves, and if it never does, playback still
            // works the old way.
            let relay_handle = handle.clone();
            tauri::async_runtime::spawn(async move {
                // The relay reads now-playing titles off the connection that
                // is already playing, and knows nothing about Tauri; this is
                // how they reach the window.
                let emitter = relay_handle.clone();
                let on_title: relay::TitleSink = Arc::new(move |url: &str, title: &str| {
                    let _ = emitter.emit(
                        "icy-title",
                        IcyTitle {
                            url: url.to_string(),
                            title: title.to_string(),
                        },
                    );
                });
                match relay::Relay::start(on_title).await {
                    Ok(relay) => {
                        *relay_handle.state::<AppState>().relay.lock().unwrap() = Some(relay);
                    }
                    Err(e) => eprintln!("aerowave: no local relay ({e}) - playing streams direct"),
                }
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
            relay_url,
            hls_session,
            hls_close,
            browse_stations,
            station_logo,
            station_art,
            browse_countries,
            browse_tags,
            browse_facets,
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
