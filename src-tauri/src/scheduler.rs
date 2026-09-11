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
use aerowave_core::sleep::{
    power_clock_interrupted, wake_plan, SleepAction, SleepEffect, SleepOutcome, SleepSnapshot,
};
use chrono::Local;
use serde::Serialize;
use tauri::{AppHandle, Emitter, Manager};

use crate::library;
use crate::power;
use crate::store::{Alarm, AlarmSource};
use crate::AppState;

#[derive(Default)]
pub struct SchedState {
    /// alarm id -> the "%Y-%m-%d %H:%M" it last rang at, so a 1 s tick
    /// cannot fire the same alarm sixty times in its minute.
    fired: HashMap<String, String>,
    /// alarm id -> unix seconds when a snooze runs out.
    snoozed: HashMap<String, schedule::Snooze>,
    /// Whatever is ringing right now.
    pub ringing: Option<String>,
    pub preview_alarm: Option<Alarm>,
    /// The track each folder alarm's current occurrence is playing, held so
    /// that the snoozes after it come back with the same one.
    holds: RingHolds,
    last_tick: i64,
    pub sleep: SleepSnapshot,
    thread: Option<std::thread::Thread>,
    wake_at_ms: Option<i64>,
    wake_error: Option<String>,
    power_committed: bool,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PowerStatus {
    #[serde(flatten)]
    capabilities: power::PowerCapabilities,
    armed_at_ms: Option<i64>,
    error: Option<String>,
}

pub fn power_status(app: &AppHandle) -> PowerStatus {
    let capabilities = power::capabilities();
    let state = app.state::<AppState>();
    let sched = state.sched.lock().unwrap();
    PowerStatus {
        capabilities,
        armed_at_ms: sched.wake_at_ms,
        error: sched.wake_error.clone(),
    }
}

/// Changes to alarms, snoozes and settings should update Windows' timer
/// immediately, including when the next clock tick has not arrived yet.
pub fn refresh(app: &AppHandle) {
    if let Some(thread) = &app.state::<AppState>().sched.lock().unwrap().thread {
        thread.unpark();
    }
}

pub fn ensure_power_idle(app: &AppHandle) -> Result<(), String> {
    if app
        .state::<AppState>()
        .sched
        .lock()
        .unwrap()
        .power_committed
    {
        Err("Windows is already starting the power action".into())
    } else {
        Ok(())
    }
}

pub fn set_sleep_timer(
    app: &AppHandle,
    minutes: u32,
    action: SleepAction,
) -> Result<SleepSnapshot, String> {
    if minutes == 0 {
        return cancel_sleep_timer(app);
    }
    if action != SleepAction::Stop {
        let caps = power::capabilities();
        if (action == SleepAction::Sleep && !caps.sleep_supported)
            || (action == SleepAction::Shutdown && !caps.shutdown_supported)
        {
            return Err(caps.message);
        }
    }
    let snapshot = {
        let state = app.state::<AppState>();
        let mut sched = state.sched.lock().unwrap();
        if sched.power_committed {
            return Err("Windows is already starting the power action".into());
        }
        if sched.ringing.is_some() {
            return Err("Dismiss or snooze the alarm before setting a sleep timer".into());
        }
        sched
            .sleep
            .start(Local::now().timestamp_millis(), minutes, action)?;
        sched.sleep.clone()
    };
    let _ = app.emit("sleep-timer-updated", &snapshot);
    refresh(app);
    Ok(snapshot)
}

pub fn cancel_sleep_timer(app: &AppHandle) -> Result<SleepSnapshot, String> {
    let snapshot = {
        let state = app.state::<AppState>();
        let mut sched = state.sched.lock().unwrap();
        if sched.power_committed {
            return Err("Windows is already starting the power action".into());
        }
        sched.sleep.cancel(SleepOutcome::Cancelled);
        sched.sleep.clone()
    };
    let _ = app.emit("sleep-timer-updated", &snapshot);
    refresh(app);
    Ok(snapshot)
}

pub fn test_alarm(app: &AppHandle, alarm: Alarm) -> Result<FirePayload, String> {
    let snapshot = {
        let state = app.state::<AppState>();
        let mut sched = state.sched.lock().unwrap();
        if sched.power_committed {
            return Err("Windows is already starting the power action".into());
        }
        if sched.ringing.is_some() {
            return Err("Dismiss the ringing alarm before testing another one".into());
        }
        sched.ringing = Some(alarm.id.clone());
        sched.preview_alarm = Some(alarm.clone());
        sched.sleep.cancel(SleepOutcome::Alarm);
        sched.sleep.clone()
    };
    let _ = app.emit("sleep-timer-updated", snapshot);
    refresh(app);
    Ok(resolve_source(app, &alarm, "test"))
}

impl SchedState {
    pub fn cancel_pending(&mut self, alarm_id: &str) {
        self.snoozed.remove(alarm_id);
        self.holds.release(alarm_id);
    }
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
                    if trigger != "test" {
                        state
                            .sched
                            .lock()
                            .unwrap()
                            .holds
                            .remember(&alarm.id, path, &track);
                    }
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

pub fn fire(app: &AppHandle, alarm: &Alarm, trigger: &str) -> bool {
    let payload = resolve_source(app, alarm, trigger);
    let state = app.state::<AppState>();
    let (one_shot, sleep) = {
        // Edits take these locks in the same order. Recheck after disk/network
        // source work so a cancelled snooze cannot publish an obsolete ring.
        let mut data = state.store.data.lock().unwrap();
        let mut sched = state.sched.lock().unwrap();
        let Some(current) = data.alarms.iter_mut().find(|a| a.id == alarm.id) else {
            return false;
        };
        let now = Local::now();
        if !schedule::alarm_may_fire(
            trigger == "snooze",
            current.enabled,
            sched.snoozed.get(&alarm.id).copied(),
            now.timestamp(),
            sched.ringing.is_some(),
        ) {
            return false;
        }
        sched.ringing = Some(alarm.id.clone());
        sched.preview_alarm = None;
        sched.snoozed.remove(&alarm.id);
        sched
            .fired
            .insert(alarm.id.clone(), now.format("%Y-%m-%d %H:%M").to_string());
        let one_shot = current.days.is_empty();
        if one_shot {
            current.enabled = false;
        }
        sched.sleep.cancel(SleepOutcome::Alarm);
        (one_shot, sched.sleep.clone())
    };
    let _ = app.emit("sleep-timer-updated", sleep);
    surface_window(app);
    let _ = app.emit("alarm-fire", payload);

    // A one-shot alarm has now done its job.
    if one_shot {
        let _ = state.store.save();
        let _ = app.emit("alarms-updated", ());
    }
    true
}

/// A preview shares the display with real alarms but must not dismiss their
/// occurrences or discard a saved snooze, even when it uses the same alarm id.
pub fn dismiss_test(app: &AppHandle, alarm_id: &str) {
    let state = app.state::<AppState>();
    {
        let mut sched = state.sched.lock().unwrap();
        if sched.preview_alarm.as_ref().map(|a| a.id.as_str()) != Some(alarm_id) {
            return;
        }
        sched.preview_alarm = None;
        if sched.ringing.as_deref() == Some(alarm_id) {
            sched.ringing = None;
        }
        // Keep the decision and the window change together so a newly claimed
        // real ring cannot lose its always-on-top grab to this old preview.
        if sched.ringing.is_none() {
            if let Some(w) = app.get_webview_window("main") {
                let _ = w.set_always_on_top(false);
            }
        }
    }
    refresh(app);
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
        sched.preview_alarm = None;
    }
    drop(sched);
    refresh(app);
    if let Some(w) = app.get_webview_window("main") {
        let _ = w.set_always_on_top(false);
    }
}

pub fn snooze(app: &AppHandle, alarm_id: &str, minutes: u32) -> Result<i64, String> {
    let at = Local::now().timestamp() + (minutes.max(1) as i64) * 60;
    let state = app.state::<AppState>();
    {
        let data = state.store.data.lock().unwrap();
        if !data.alarms.iter().any(|alarm| alarm.id == alarm_id) {
            return Err("that alarm is no longer saved".into());
        }
        let mut sched = state.sched.lock().unwrap();
        if sched.ringing.as_deref() != Some(alarm_id) {
            return Err("that alarm is no longer ringing".into());
        }
        if sched
            .preview_alarm
            .as_ref()
            .is_some_and(|a| a.id == alarm_id)
        {
            return Err("A test alarm cannot schedule a snooze".into());
        }
        sched
            .snoozed
            .insert(alarm_id.to_string(), schedule::Snooze::new(at));
        if sched.ringing.as_deref() == Some(alarm_id) {
            sched.ringing = None;
        }
    }
    if let Some(w) = app.get_webview_window("main") {
        let _ = w.set_always_on_top(false);
    }
    let _ = app.emit("alarms-updated", ());
    refresh(app);
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
        if let Some(snooze) = snoozed.get(&alarm.id) {
            candidates.push((snooze.at, true));
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
fn tick(app: &AppHandle, power: &mut power::PowerManager) {
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

    let (due_snooze, stale_snoozes, last_tick) = {
        let mut sched = state.sched.lock().unwrap();
        let last = sched.last_tick;
        sched.last_tick = now_secs;

        let (due, stale) = schedule::next_due_snooze(&mut sched.snoozed, now_secs, busy);
        for id in &stale {
            sched.holds.release(id);
        }
        (due, stale, last)
    };

    if !stale_snoozes.is_empty() {
        // Nothing rings, but the readout should stop advertising it.
        let _ = app.emit("alarms-updated", ());
    }

    if let Some(id) = due_snooze {
        if let Some(alarm) = alarms.iter().find(|a| a.id == id) {
            power.keep_awake(true);
            fired_this_pass = fire(app, alarm, "snooze");
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
        power.keep_awake(true);
        fired_this_pass = fire(app, alarm, if missed { "catchup" } else { "scheduled" });
    }
}

/// Start the once-a-second clock. Runs for the life of the process.
pub fn spawn(app: AppHandle) {
    std::thread::spawn(move || {
        let mut power = power::PowerManager::new();
        {
            let state = app.state::<AppState>();
            let mut sched = state.sched.lock().unwrap();
            sched.last_tick = Local::now().timestamp();
            sched.thread = Some(std::thread::current());
        }
        loop {
            let last_tick = app.state::<AppState>().sched.lock().unwrap().last_tick;
            // Alarms get first refusal, including a timer expiring on the
            // very same tick. tick holds Windows awake before any due
            // alarm enters source resolution, which can involve folder I/O.
            update_power(&app, &mut power);
            tick(&app, &mut power);
            update_power(&app, &mut power);
            tick_sleep(&app, &mut power, last_tick);
            std::thread::park_timeout(Duration::from_secs(1));
        }
    });
}

fn update_power(app: &AppHandle, power: &mut power::PowerManager) {
    let state = app.state::<AppState>();
    let enabled = state.store.data.lock().unwrap().settings.wake_for_alarms;
    let next = if enabled {
        next_alarm(app).map(|a| a.at_ms)
    } else {
        None
    };
    let (ringing, sleep_active) = {
        let sched = state.sched.lock().unwrap();
        (sched.ringing.is_some(), sched.sleep.timer.is_some())
    };
    let plan = wake_plan(Local::now().timestamp_millis(), next, ringing);
    power.keep_awake(plan.keep_awake || sleep_active);
    let result = power.sync_wake(plan.arm_at_ms);
    let mut sched = state.sched.lock().unwrap();
    match result {
        Ok(()) => {
            sched.wake_at_ms = plan.arm_at_ms;
            sched.wake_error = None;
        }
        Err(error) => {
            sched.wake_at_ms = None;
            sched.wake_error = Some(error);
        }
    }
}

fn tick_sleep(app: &AppHandle, power: &mut power::PowerManager, last_tick: i64) {
    let now_ms = Local::now().timestamp_millis();
    let next = next_alarm(app).map(|a| a.at_ms);
    let state = app.state::<AppState>();
    let (effect, snapshot) = {
        let mut sched = state.sched.lock().unwrap();
        let priority = wake_plan(now_ms, next, sched.ringing.is_some()).keep_awake;
        let resumed = power_clock_interrupted(last_tick * 1000, now_ms);
        let effect = sched.sleep.advance(now_ms, priority, resumed);
        (effect, sched.sleep.clone())
    };
    if effect == SleepEffect::None {
        return;
    }
    if effect == SleepEffect::Countdown {
        // Do not take the alarm's always-on-top grab for a timer prompt.
        if let Some(w) = app.get_webview_window("main") {
            let _ = w.unminimize();
            let _ = w.show();
            let _ = w.set_focus();
        }
    }
    if let SleepEffect::Execute(action) = effect {
        if action == SleepAction::Stop {
            let _ = app.emit("sleep-timer-updated", &snapshot);
            return;
        }
        let committed = {
            // Alarm/settings writers use this gate too. Refresh Windows from
            // their latest saved state before committing suspension, then
            // refuse later writes until the OS call has returned.
            let _power_update = state.power_updates.lock().unwrap();
            update_power(app, power);
            let now_ms = Local::now().timestamp_millis();
            let next = next_alarm(app).map(|a| a.at_ms);
            let mut sched = state.sched.lock().unwrap();
            if sched.sleep.revision != snapshot.revision {
                return;
            }
            let alarm_priority = wake_plan(now_ms, next, sched.ringing.is_some()).keep_awake;
            // A settings write may have held the gate while the PC slept or
            // its storage stalled. Recheck clock continuity at dispatch too.
            if alarm_priority || power_clock_interrupted(last_tick * 1000, now_ms) {
                sched.sleep.cancel(if alarm_priority {
                    SleepOutcome::Alarm
                } else {
                    SleepOutcome::Cancelled
                });
                let cancelled = sched.sleep.clone();
                drop(sched);
                let _ = app.emit("sleep-timer-updated", cancelled);
                return;
            }
            if !sched.sleep.commit_power(snapshot.revision) {
                return;
            }
            // This lock is the cancellation boundary. Commands arriving
            // afterwards report that Windows is starting the action, rather
            // than reporting success for a cancellation they cannot honor.
            sched.power_committed = true;
            sched.sleep.clone()
        };
        let _ = app.emit("sleep-timer-updated", &committed);
        // Do not hold the scheduler lock across SetSuspendState: it returns
        // only after resume, while the webview may be processing cancellation.
        power.keep_awake(false);
        let result = power::execute(action);
        let failed = {
            let mut sched = state.sched.lock().unwrap();
            sched.power_committed = false;
            result
                .err()
                .filter(|error| sched.sleep.fail(committed.revision, error.clone()))
                .map(|_| sched.sleep.clone())
        };
        if let Some(failed) = failed {
            let _ = app.emit("sleep-timer-updated", failed);
        }
    } else {
        let _ = app.emit("sleep-timer-updated", &snapshot);
    }
}
