//! The alarm clock itself.
//!
//! Scheduling deliberately lives in Rust rather than in a `setInterval` in
//! the webview: WebView2 throttles timers in hidden or occluded windows, and
//! an alarm that only fires when you are already looking at the window is no
//! alarm at all. The webview is told *when* to ring and *what* to play; it
//! only does the playing.

use std::collections::HashMap;
use std::time::Duration;

use aerowave_core::ring::RingHolds;
use aerowave_core::schedule;
use chrono::Local;
use serde::Serialize;
use tauri::{AppHandle, Emitter, Manager};

use crate::library;
use crate::store::{Alarm, AlarmSource};
use crate::AppState;

#[derive(Default)]
pub struct SchedState {
    /// alarm id -> the "%Y-%m-%d %H:%M" it last rang at, so a 1 s tick
    /// cannot fire the same alarm sixty times in its minute.
    fired: HashMap<String, String>,
    /// alarm id -> unix seconds when a snooze runs out.
    snoozed: HashMap<String, i64>,
    /// Whatever is ringing right now.
    pub ringing: Option<String>,
    /// The track each folder alarm's current occurrence is playing, held so
    /// that the snoozes after it come back with the same one.
    holds: RingHolds,
    last_tick: i64,
}

#[derive(Serialize, Clone, Debug)]
#[serde(rename_all = "camelCase")]
pub struct FirePayload {
    pub alarm_id: String,
    pub label: String,
    pub hour: u32,
    pub minute: u32,
    /// "scheduled", "snooze", "catchup" or "test".
    pub trigger: String,
    /// "station", "folder" or "tone" - what the webview should play.
    pub kind: String,
    /// Stream URL, for `kind == "station"`.
    pub url: Option<String>,
    /// Absolute file path, for `kind == "folder"`.
    pub path: Option<String>,
    /// The folder that path came from, when it is the alarm's own. Lets the
    /// webview keep pulling tracks from it without looking the alarm up in
    /// stored state - which a test ring, deliberately unsaved, is not in.
    pub folder: Option<String>,
    /// Station name or track file name, for the display.
    pub title: Option<String>,
    pub volume: f64,
    pub fade_secs: u32,
    pub snooze_mins: u32,
    pub auto_stop_mins: u32,
    pub auto_snoozes: u32,
    /// Set when the intended source was unusable and the tone stood in.
    pub note: Option<String>,
}

#[derive(Serialize, Clone, Debug)]
#[serde(rename_all = "camelCase")]
pub struct NextAlarm {
    pub alarm_id: String,
    pub label: String,
    pub at_ms: i64,
    pub in_secs: i64,
    pub snoozed: bool,
}

/// Try to turn a folder into a playable track, opening it to the asset
/// protocol (whose scope starts empty) on the way out.
fn track_from_folder(app: &AppHandle, dir: &std::path::Path) -> Option<(String, String, usize)> {
    let state = app.state::<AppState>();
    let (track, total) = library::pick_random(dir, &state.recent)?;
    let _ = app.asset_protocol_scope().allow_file(&track);
    let name = track_name(&track);
    Some((track.to_string_lossy().to_string(), name, total))
}

/// What a track shows under: its file name, with the extension trimmed off
/// later by the webview.
fn track_name(path: &std::path::Path) -> String {
    path.file_name()
        .map(|n| n.to_string_lossy().to_string())
        .unwrap_or_else(|| "track".into())
}

/// The track a snooze should come back with, if this alarm is holding one and
/// it is still on disk. A held track that has been moved or deleted between
/// snoozes is no reason to wake nobody up: dropping it here sends the ring
/// back through an ordinary draw.
fn held_track(app: &AppHandle, alarm_id: &str, trigger: &str, folder: &str) -> Option<String> {
    let state = app.state::<AppState>();
    let held = state
        .sched
        .lock()
        .unwrap()
        .holds
        .held_for(alarm_id, trigger, folder)?
        .to_string();
    let path = std::path::Path::new(&held);
    if !path.is_file() {
        return None;
    }
    // Granted on the first draw and good for the life of the process, but
    // asking again costs nothing and does not rely on that being true.
    let _ = app.asset_protocol_scope().allow_file(path);
    Some(held)
}

/// The backup folder from settings, if it has anything playable in it.
pub fn backup_track(app: &AppHandle) -> Option<(String, String, usize)> {
    let folder = app
        .state::<AppState>()
        .store
        .data
        .lock()
        .unwrap()
        .settings
        .backup_folder
        .clone()?;
    track_from_folder(app, std::path::Path::new(&folder))
}

/// Work out what the webview should play for this alarm. If the alarm's own
/// source has gone missing, the backup folder stands in; if there is no
/// usable backup either, the alarm still fires, but silently, and says so.
pub fn resolve_source(app: &AppHandle, alarm: &Alarm, trigger: &str) -> FirePayload {
    let state = app.state::<AppState>();
    let mut payload = FirePayload {
        alarm_id: alarm.id.clone(),
        label: alarm.label.clone(),
        hour: alarm.hour,
        minute: alarm.minute,
        trigger: trigger.to_string(),
        kind: "none".into(),
        url: None,
        path: None,
        folder: None,
        title: None,
        volume: alarm.volume,
        fade_secs: alarm.fade_secs,
        snooze_mins: alarm.snooze_mins,
        auto_stop_mins: alarm.auto_stop_mins,
        auto_snoozes: alarm.auto_snoozes,
        note: None,
    };

    // Why the first choice failed, set only when it did.
    let fell_back: String;

    match &alarm.source {
        AlarmSource::Station { station_id } => {
            let station = state
                .store
                .data
                .lock()
                .unwrap()
                .stations
                .iter()
                .find(|s| &s.id == station_id)
                .cloned();
            match station {
                Some(s) => {
                    payload.kind = "station".into();
                    payload.url = Some(s.url);
                    payload.title = Some(s.name);
                    return payload;
                }
                None => fell_back = "that station has been deleted".into(),
            }
        }
        AlarmSource::Folder { path } => {
            let dir = std::path::Path::new(path);
            // A snooze is the same alarm coming back, not a new one, so it
            // comes back with the track it was already playing. Waking to a
            // different song every nine minutes reads as a different alarm
            // each time. See `aerowave_core::ring` for when a hold applies.
            if let Some(track) = held_track(app, &alarm.id, trigger, path) {
                payload.kind = "folder".into();
                payload.title = Some(track_name(std::path::Path::new(&track)));
                payload.path = Some(track);
                payload.folder = Some(path.clone());
                return payload;
            }
            match track_from_folder(app, dir) {
                Some((track, name, _)) => {
                    // Held for the snoozes this ring turns into. Only the
                    // alarm's own folder: the backup folder below is the
                    // sound of something having gone wrong, and is meant to
                    // be played through rather than held.
                    state
                        .sched
                        .lock()
                        .unwrap()
                        .holds
                        .remember(&alarm.id, path, &track);
                    payload.kind = "folder".into();
                    payload.path = Some(track);
                    payload.folder = Some(path.clone());
                    payload.title = Some(name);
                    return payload;
                }
                None => {
                    fell_back = if dir.is_dir() {
                        "no playable audio in that folder".into()
                    } else {
                        "that folder is missing".into()
                    }
                }
            }
        }
    }

    // First choice unusable - reach for the backup folder.
    match backup_track(app) {
        Some((track, name, _)) => {
            payload.kind = "folder".into();
            payload.path = Some(track);
            payload.title = Some(name);
            payload.note = Some(format!(
                "{fell_back} - playing from the backup folder instead"
            ));
        }
        None => {
            payload.note = Some(format!(
                "{fell_back}, and there is no usable backup folder set"
            ));
        }
    }
    payload
}

/// Bring the window back from wherever it went and put it in front.
fn surface_window(app: &AppHandle) {
    if let Some(w) = app.get_webview_window("main") {
        let _ = w.unminimize();
        let _ = w.show();
        let _ = w.set_always_on_top(true);
        let _ = w.set_focus();
    }
}

pub fn fire(app: &AppHandle, alarm: &Alarm, trigger: &str) {
    let payload = resolve_source(app, alarm, trigger);
    {
        let state = app.state::<AppState>();
        let mut sched = state.sched.lock().unwrap();
        sched.ringing = Some(alarm.id.clone());
        sched.snoozed.remove(&alarm.id);
    }
    surface_window(app);
    let _ = app.emit("alarm-fire", payload);

    // A one-shot alarm has now done its job.
    if alarm.days.is_empty() {
        let state = app.state::<AppState>();
        let id = alarm.id.clone();
        let _ = state.store.update(|d| {
            if let Some(a) = d.alarms.iter_mut().find(|a| a.id == id) {
                a.enabled = false;
            }
        });
        let _ = app.emit("alarms-updated", ());
    }
}

/// Stop the ringing: drop the always-on-top grab and clear the snooze.
pub fn dismiss(app: &AppHandle, alarm_id: &str) {
    let state = app.state::<AppState>();
    let mut sched = state.sched.lock().unwrap();
    sched.snoozed.remove(alarm_id);
    // The occurrence is over, so the track it was holding is too - otherwise
    // tomorrow's ring would come back with the song answered today.
    sched.holds.release(alarm_id);
    if sched.ringing.as_deref() == Some(alarm_id) {
        sched.ringing = None;
    }
    drop(sched);
    if let Some(w) = app.get_webview_window("main") {
        let _ = w.set_always_on_top(false);
    }
}

pub fn snooze(app: &AppHandle, alarm_id: &str, minutes: u32) -> Result<i64, String> {
    let at = Local::now().timestamp() + (minutes.max(1) as i64) * 60;
    let state = app.state::<AppState>();
    {
        let mut sched = state.sched.lock().unwrap();
        sched.snoozed.insert(alarm_id.to_string(), at);
        if sched.ringing.as_deref() == Some(alarm_id) {
            sched.ringing = None;
        }
    }
    if let Some(w) = app.get_webview_window("main") {
        let _ = w.set_always_on_top(false);
    }
    let _ = app.emit("alarms-updated", ());
    Ok(at * 1000)
}

pub fn next_alarm(app: &AppHandle) -> Option<NextAlarm> {
    let state = app.state::<AppState>();
    let alarms = state.store.data.lock().unwrap().alarms.clone();
    let snoozed = state.sched.lock().unwrap().snoozed.clone();
    let now = Local::now();

    let mut best: Option<(i64, &Alarm, bool)> = None;
    for alarm in &alarms {
        let mut candidates: Vec<(i64, bool)> = Vec::new();
        if let Some(at) = snoozed.get(&alarm.id) {
            candidates.push((*at, true));
        }
        if alarm.enabled {
            if let Some(at) = schedule::next_occurrence(alarm.hour, alarm.minute, &alarm.days, &now)
            {
                candidates.push((at, false));
            }
        }
        for (at, is_snooze) in candidates {
            if best.map_or(true, |(b, _, _)| at < b) {
                best = Some((at, alarm, is_snooze));
            }
        }
    }

    best.map(|(at, alarm, is_snooze)| NextAlarm {
        alarm_id: alarm.id.clone(),
        label: alarm.label.clone(),
        at_ms: at * 1000,
        in_secs: at - now.timestamp(),
        snoozed: is_snooze,
    })
}

/// One pass of the clock. Split out from the thread so the logic stays
/// readable and the borrow of the store stays short.
fn tick(app: &AppHandle) {
    let now = Local::now();
    let now_secs = now.timestamp();
    let key = now.format("%Y-%m-%d %H:%M").to_string();

    let state = app.state::<AppState>();
    let alarms: Vec<Alarm> = state.store.data.lock().unwrap().alarms.clone();

    // Is something already ringing? Firing a second alarm on top of it would
    // tear down the first mid-connect in the webview - and mark its minute
    // consumed, so nobody ever hears it.
    let busy = state.sched.lock().unwrap().ringing.is_some();
    let mut fired_this_pass = false;

    let (due_snoozes, stale_snoozes, last_tick) = {
        let mut sched = state.sched.lock().unwrap();
        let last = sched.last_tick;
        sched.last_tick = now_secs;

        let expired: Vec<(String, i64)> = sched
            .snoozed
            .iter()
            .filter(|(_, at)| **at <= now_secs)
            .map(|(id, at)| (id.clone(), *at))
            .collect();

        let mut due = Vec::new();
        let mut stale = Vec::new();
        for (id, at) in expired {
            // Out of the map either way: a snooze left sitting in the past
            // reports a negative countdown in the NEXT ALARM readout.
            sched.snoozed.remove(&id);
            if now_secs - at > schedule::CATCHUP_GRACE_SECS {
                // Snoozes live in memory only, so a suspended machine can wake
                // hours later with one still due. Ringing then is not waking
                // anybody up on time, it is just a fright.
                stale.push(id);
            } else if busy {
                sched.snoozed.insert(id, at);
            } else {
                due.push(id);
            }
        }
        (due, stale, last)
    };

    if !stale_snoozes.is_empty() {
        // Nothing rings, but the readout should stop advertising it.
        let _ = app.emit("alarms-updated", ());
    }

    for id in due_snoozes {
        if let Some(alarm) = alarms.iter().find(|a| a.id == id) {
            fire(app, alarm, "snooze");
            fired_this_pass = true;
        }
    }

    for alarm in &alarms {
        if !alarm.enabled {
            continue;
        }
        let already = {
            let sched = state.sched.lock().unwrap();
            sched.fired.get(&alarm.id).cloned()
        };

        let on_time = schedule::due_now(alarm.hour, alarm.minute, &alarm.days, &now);
        // Did its moment pass while the machine was asleep?
        let missed =
            schedule::missed_while_asleep(alarm.hour, alarm.minute, &alarm.days, &now, last_tick);

        if !(on_time || missed) {
            continue;
        }
        if already.as_deref() == Some(key.as_str()) {
            continue;
        }
        // Deliberately before the `fired` stamp: leaving this minute unconsumed
        // is the point. Clear the current ring inside the same minute and this
        // alarm still gets its turn; otherwise it is dropped without being
        // stamped, and without a one-shot being disabled for a ring nobody
        // heard.
        if busy || fired_this_pass {
            continue;
        }
        {
            let mut sched = state.sched.lock().unwrap();
            sched.fired.insert(alarm.id.clone(), key.clone());
        }
        fire(app, alarm, if missed { "catchup" } else { "scheduled" });
        fired_this_pass = true;
    }
}

/// Start the once-a-second clock. Runs for the life of the process.
pub fn spawn(app: AppHandle) {
    std::thread::spawn(move || {
        {
            let state = app.state::<AppState>();
            state.sched.lock().unwrap().last_tick = Local::now().timestamp();
        }
        loop {
            std::thread::sleep(Duration::from_secs(1));
            tick(&app);
        }
    });
}
