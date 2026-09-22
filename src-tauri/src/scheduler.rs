//! The alarm clock itself.
//!
//! Scheduling deliberately lives in Rust rather than in a `setInterval` in
//! the webview: WebView2 throttles timers in hidden or occluded windows, and
//! an alarm that only fires when you are already looking at the window is no
//! alarm at all. The webview is told *when* to ring and *what* to play; it
//! only does the playing.

use std::collections::HashMap;
use std::path::{Path, PathBuf};
use std::time::{Duration, Instant};

use aerowave_core::ring::RingHolds;
use aerowave_core::schedule;
use aerowave_core::source_resolution::{accepts_result, cancel_unresolved_ring, Decision, Resolution};
use aerowave_core::sleep::{
    power_clock_interrupted, wake_plan, AlarmWakeHold, SleepAction, SleepEffect, SleepOutcome,
    SleepSnapshot,
};
use chrono::Local;
use serde::Serialize;
use tauri::{AppHandle, Emitter, Manager};
use tokio::sync::oneshot;

use crate::power;
use crate::source_scan::Pick;
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
    pub payload: Option<FirePayload>,
    ring_generation: u64,
    /// The track each folder alarm's current occurrence is playing, held so
    /// that the snoozes after it come back with the same one.
    holds: RingHolds,
    last_tick: i64,
    pub sleep: SleepSnapshot,
    thread: Option<std::thread::Thread>,
    wake_at_ms: Option<i64>,
    wake_error: Option<String>,
    wake_hold: AlarmWakeHold,
    reported_wake_hold: bool,
    power_committed: bool,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PowerStatus {
    #[serde(flatten)]
    capabilities: power::PowerCapabilities,
    armed_at_ms: Option<i64>,
    staying_awake: bool,
    error: Option<String>,
}

pub fn power_status(app: &AppHandle) -> PowerStatus {
    let capabilities = power::capabilities();
    let state = app.state::<AppState>();
    let wake_enabled = state.store.data.lock().unwrap().settings.wake_for_alarms;
    let sched = state.sched.lock().unwrap();
    PowerStatus {
        capabilities,
        armed_at_ms: sched.wake_at_ms,
        staying_awake: wake_enabled && sched.wake_hold.active(),
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

pub async fn test_alarm(app: &AppHandle, alarm: Alarm) -> Result<FirePayload, String> {
    let (snapshot, generation) = {
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
        sched.payload = None;
        sched.ring_generation = sched.ring_generation.wrapping_add(1);
        sched.sleep.cancel(SleepOutcome::Alarm);
        (sched.sleep.clone(), sched.ring_generation)
    };
    let _ = app.emit("sleep-timer-updated", snapshot);
    refresh(app);
    match begin_source(app, &alarm, "test", generation) {
        Ok(payload) => {
            let state = app.state::<AppState>();
            let mut sched = state.sched.lock().unwrap();
            if !accepts_result(sched.ringing.as_deref(), &alarm.id, sched.ring_generation, generation) {
                return Err("alarm test was dismissed".into());
            }
            sched.payload = Some(payload.clone());
            let _ = app.emit("alarm-fire", payload.clone());
            Ok(payload)
        }
        Err(mut pending) => loop {
            if let Some(payload) = pending.poll(app) {
                let state = app.state::<AppState>();
                let sched = state.sched.lock().unwrap();
                if !accepts_result(sched.ringing.as_deref(), &alarm.id, sched.ring_generation, generation) {
                    return Err("alarm test was dismissed".into());
                }
                let _ = app.emit("alarm-fire", payload.clone());
                return Ok(payload);
            }
            let active = {
                let state = app.state::<AppState>();
                let sched = state.sched.lock().unwrap();
                accepts_result(sched.ringing.as_deref(), &alarm.id, sched.ring_generation, generation)
            };
            if !active { return Err("alarm test was dismissed".into()); }
            tokio::time::sleep(Duration::from_millis(50)).await;
        },
    }
}

impl SchedState {
    pub fn set_wake_enabled(&mut self, enabled: bool) {
        self.wake_hold.set_enabled(enabled);
    }

    pub fn cancel_pending(&mut self, alarm_id: &str) {
        self.snoozed.remove(alarm_id);
        self.holds.release(alarm_id);
        if cancel_unresolved_ring(self.ringing.as_deref(), alarm_id,
            self.preview_alarm.is_some(), self.payload.is_some()) {
            self.ringing = None;
            self.preview_alarm = None;
            self.payload = None;
            self.ring_generation = self.ring_generation.wrapping_add(1);
        }
    }
}

#[derive(Serialize, Clone, Debug)]
#[serde(rename_all = "camelCase")]
pub struct FirePayload {
    pub occurrence_id: String,
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

/// What a track shows under: its file name, with the extension trimmed off
/// later by the webview.
fn track_name(path: &Path) -> String {
    path.file_name()
        .map(|n| n.to_string_lossy().to_string())
        .unwrap_or_else(|| "track".into())
}

impl FirePayload {
    fn new(alarm: &Alarm, trigger: &str, occurrence_id: u64) -> Self {
        Self {
            occurrence_id: occurrence_id.to_string(),
            alarm_id: alarm.id.clone(), label: alarm.label.clone(),
            hour: alarm.hour, minute: alarm.minute, trigger: trigger.into(),
            kind: "none".into(), url: None, path: None, folder: None,
            title: None, volume: alarm.volume, fade_secs: alarm.fade_secs,
            snooze_mins: alarm.snooze_mins, auto_stop_mins: alarm.auto_stop_mins,
            auto_snoozes: alarm.auto_snoozes, note: None,
        }
    }
}

struct PendingSource {
    alarm: Alarm,
    trigger: String,
    generation: u64,
    payload: FirePayload,
    preferred_folder: Option<String>,
    preferred: Option<oneshot::Receiver<Result<Option<Pick>, String>>>,
    backup: Option<oneshot::Receiver<Result<Option<Pick>, String>>>,
    gate: Resolution<Pick>,
    started: Instant,
}

fn begin_source(app: &AppHandle, alarm: &Alarm, trigger: &str, generation: u64) -> Result<FirePayload, PendingSource> {
    let state = app.state::<AppState>();
    let (station, backup_folder) = {
        let data = state.store.data.lock().unwrap();
        let station = match &alarm.source {
            AlarmSource::Station { station_id } => data.stations.iter().find(|s| &s.id == station_id).cloned(),
            AlarmSource::Folder { .. } => None,
        };
        (station, data.settings.backup_folder.clone())
    };
    let mut payload = FirePayload::new(alarm, trigger, generation);
    if let Some(station) = station {
        payload.kind = "station".into();
        payload.url = Some(station.url);
        payload.title = Some(station.name);
        return Ok(payload);
    }
    let preferred_folder = match &alarm.source {
        AlarmSource::Folder { path } => Some(path.clone()),
        AlarmSource::Station { .. } => None,
    };
    let mut gate = Resolution::new(0);
    // Submit backup first. It has its own worker, so a stalled preferred share
    // cannot prevent a responsive local backup from returning.
    let backup = backup_folder.as_ref().and_then(|folder| {
        let app = app.clone();
        state.scanner.pick(PathBuf::from(folder), None, state.recent.clone(), true,
            move |path| app.asset_protocol_scope().allow_file(path).map_err(|e| e.to_string())).ok()
    });
    if backup.is_none() { gate.backup(None, 0); }
    let held = preferred_folder.as_deref().and_then(|folder| state.sched.lock().unwrap()
        .holds.held_for(&alarm.id, trigger, folder).map(PathBuf::from));
    let preferred = preferred_folder.as_ref().and_then(|folder| {
        let app = app.clone();
        state.scanner.pick(PathBuf::from(folder), held, state.recent.clone(), false,
            move |path| app.asset_protocol_scope().allow_file(path).map_err(|e| e.to_string())).ok()
    });
    if preferred.is_none() { gate.preferred(None, 0); }
    Err(PendingSource {
        alarm: alarm.clone(), trigger: trigger.into(), generation, payload,
        preferred_folder, preferred, backup, gate, started: Instant::now(),
    })
}

impl PendingSource {
    fn poll(&mut self, app: &AppHandle) -> Option<FirePayload> {
        if let Some(receiver) = &mut self.preferred {
            match receiver.try_recv() {
                Ok(result) => {
                    let elapsed = result.as_ref().ok().and_then(Option::as_ref)
                        .map(|pick| pick.completed_at.saturating_duration_since(self.started).as_millis() as u64)
                        .unwrap_or_else(|| self.started.elapsed().as_millis() as u64);
                    self.gate.preferred(result.ok().flatten(), elapsed);
                    self.preferred = None;
                }
                Err(oneshot::error::TryRecvError::Closed) => {
                    self.gate.preferred(None, self.started.elapsed().as_millis() as u64);
                    self.preferred = None;
                }
                Err(oneshot::error::TryRecvError::Empty) => {}
            }
        }
        if let Some(receiver) = &mut self.backup {
            match receiver.try_recv() {
                Ok(result) => {
                    let elapsed = result.as_ref().ok().and_then(Option::as_ref)
                        .map(|pick| pick.completed_at.saturating_duration_since(self.started).as_millis() as u64)
                        .unwrap_or_else(|| self.started.elapsed().as_millis() as u64);
                    self.gate.backup(result.ok().flatten(), elapsed);
                    self.backup = None;
                }
                Err(oneshot::error::TryRecvError::Closed) => {
                    self.gate.backup(None, self.started.elapsed().as_millis() as u64);
                    self.backup = None;
                }
                Err(oneshot::error::TryRecvError::Empty) => {}
            }
        }
        let now = self.started.elapsed().as_millis().min(u64::MAX as u128) as u64;
        let decision = self.gate.decide(now)?;
        let state = app.state::<AppState>();
        let mut payload = self.payload.clone();
        let mut sched = state.sched.lock().unwrap();
        if !accepts_result(sched.ringing.as_deref(), &self.alarm.id, sched.ring_generation, self.generation) {
            return None;
        }
        // The snapshot claimed at fire time owns this occurrence. Ordinary
        // edits apply to later rings; disabling or deleting this alarm cancels
        // an unresolved ring by advancing its generation in cancel_pending.
        let (picked, own) = match decision {
            Decision::Preferred(picked) => (Some(picked), true),
            Decision::Backup(picked) => (Some(picked), false),
            Decision::Unavailable => (None, false),
        };
        if let Some(Pick { path: track, total, .. }) = picked {
            let path = track.to_string_lossy().to_string();
            if own && self.trigger != "test" {
                sched.holds.remember(&self.alarm.id, self.preferred_folder.as_deref().unwrap(), &path);
            }
            state.recent.remember(&track, total);
            payload.kind = "folder".into();
            payload.path = Some(path);
            payload.title = Some(track_name(&track));
            if own { payload.folder = self.preferred_folder.clone(); }
            else { payload.note = Some("alarm source unavailable - playing from the backup folder instead".into()); }
        }
        if payload.kind == "none" {
            payload.note = Some(if now >= 5_000 {
                "alarm source did not respond within five seconds, and no backup track was available"
            } else {
                "no playable alarm source or backup track was available"
            }.into());
        }
        sched.payload = Some(payload.clone());
        Some(payload)
    }
}

/// Bring the window back from wherever it went and put it in front.
fn surface_window(app: &AppHandle) {
    #[cfg(desktop)]
    if let Some(w) = app.get_webview_window("main") {
        let _ = w.unminimize();
        let _ = w.show();
        let _ = w.set_always_on_top(true);
        let _ = w.set_focus();
    }
}

fn fire(app: &AppHandle, alarm: &Alarm, trigger: &str, pending: &mut Option<PendingSource>) -> bool {
    let state = app.state::<AppState>();
    let (one_shot, sleep, generation, claimed_alarm) = {
        // Claim the occurrence and wake hold before any filesystem request.
        let mut data = state.store.data.lock().unwrap();
        let mut sched = state.sched.lock().unwrap();
        let wake_enabled = cfg!(windows) && data.settings.wake_for_alarms;
        let Some(current) = data.alarms.iter_mut().find(|a| a.id == alarm.id) else {
            return false;
        };
        if current != alarm { return false; }
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
        sched.payload = None;
        sched.ring_generation = sched.ring_generation.wrapping_add(1);
        sched.snoozed.remove(&alarm.id);
        sched
            .fired
            .insert(alarm.id.clone(), now.format("%Y-%m-%d %H:%M").to_string());
        let one_shot = current.days.is_empty();
        if one_shot {
            current.enabled = false;
        }
        let claimed_alarm = current.clone();
        // Ending the sound must not send an unattended alarm wake back to
        // sleep. Preview alarms use a separate path and never claim this hold.
        sched.wake_hold.alarm_fired(wake_enabled);
        sched.sleep.cancel(SleepOutcome::Alarm);
        (one_shot, sched.sleep.clone(), sched.ring_generation, claimed_alarm)
    };
    let _ = app.emit("sleep-timer-updated", sleep);
    match begin_source(app, &claimed_alarm, trigger, generation) {
        Ok(payload) => {
            let mut sched = state.sched.lock().unwrap();
            if accepts_result(sched.ringing.as_deref(), &alarm.id, sched.ring_generation, generation) {
                sched.payload = Some(payload.clone());
                surface_window(app);
                let _ = app.emit("alarm-fire", payload);
            }
        }
        Err(work) => *pending = Some(work),
    }

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
        sched.payload = None;
        sched.ring_generation = sched.ring_generation.wrapping_add(1);
        if sched.ringing.as_deref() == Some(alarm_id) {
            sched.ringing = None;
        }
        // Keep the decision and the window change together so a newly claimed
        // real ring cannot lose its always-on-top grab to this old preview.
        if sched.ringing.is_none() {
            #[cfg(desktop)]
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
        sched.payload = None;
        sched.ring_generation = sched.ring_generation.wrapping_add(1);
    }
    drop(sched);
    refresh(app);
    #[cfg(desktop)]
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
            sched.payload = None;
            sched.ring_generation = sched.ring_generation.wrapping_add(1);
        }
    }
    #[cfg(desktop)]
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
fn tick(app: &AppHandle, power: &mut power::PowerManager, pending_source: &mut Option<PendingSource>) {
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
            let _ = power.keep_awake(true, true);
            fired_this_pass = fire(app, alarm, "snooze", pending_source);
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
        let _ = power.keep_awake(true, true);
        fired_this_pass = fire(app, alarm, if on_time { "scheduled" } else { "catchup" }, pending_source);
    }
}

fn poll_source(app: &AppHandle, pending: &mut Option<PendingSource>) {
    let Some(work) = pending.as_mut() else { return; };
    let active = {
        let state = app.state::<AppState>();
        let sched = state.sched.lock().unwrap();
        accepts_result(sched.ringing.as_deref(), &work.alarm.id,
            sched.ring_generation, work.generation)
    };
    if !active {
        *pending = None;
        return;
    }
    if let Some(payload) = work.poll(app) {
        let generation = work.generation;
        let alarm_id = work.alarm.id.clone();
        *pending = None;
        let state = app.state::<AppState>();
        let sched = state.sched.lock().unwrap();
        if accepts_result(sched.ringing.as_deref(), &alarm_id, sched.ring_generation, generation) {
            surface_window(app);
            let _ = app.emit("alarm-fire", payload);
        }
    }
}

/// Start the once-a-second clock. Runs for the life of the process.
pub fn spawn(app: AppHandle, power_window: Option<isize>) {
    std::thread::spawn(move || {
        let mut power = power::PowerManager::new();
        let mut pending_power = None;
        let mut pending_source: Option<PendingSource> = None;
        {
            let state = app.state::<AppState>();
            let mut sched = state.sched.lock().unwrap();
            sched.last_tick = Local::now().timestamp();
            sched.thread = Some(std::thread::current());
        }
        loop {
            poll_power_action(&app, &mut pending_power);
            poll_source(&app, &mut pending_source);
            let last_tick = app.state::<AppState>().sched.lock().unwrap().last_tick;
            // Alarms get first refusal, including a timer expiring on the
            // very same tick. tick holds Windows awake before any due
            // alarm enters source resolution, which can involve folder I/O.
            update_power(&app, &mut power);
            tick(&app, &mut power, &mut pending_source);
            update_power(&app, &mut power);
            tick_sleep(&app, &mut power, last_tick, &mut pending_power, power_window);
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
    let (plan, sleep_active, wake_hold) = {
        let mut sched = state.sched.lock().unwrap();
        let ringing = sched.ringing.is_some();
        let plan = sched
            .wake_hold
            .plan(Local::now().timestamp_millis(), next, ringing, enabled);
        (plan, sched.sleep.timer.is_some(), sched.wake_hold.active())
    };
    let awake_result = power.keep_awake(plan.keep_awake || sleep_active, plan.keep_display_awake);
    let result = power.sync_wake(plan.arm_at_ms);
    let (wake_at_ms, wake_error) = match result {
        Ok(()) => (plan.arm_at_ms, awake_result.err()),
        Err(error) => (None, Some(error)),
    };
    let changed = {
        let mut sched = state.sched.lock().unwrap();
        let changed = sched.wake_at_ms != wake_at_ms
            || sched.wake_error != wake_error
            || sched.reported_wake_hold != wake_hold;
        sched.wake_at_ms = wake_at_ms;
        sched.wake_error = wake_error;
        sched.reported_wake_hold = wake_hold;
        changed
    };
    // Saving only unparks this thread. Notify after the Windows calls finish
    // so a frontend query made before them cannot leave stale success visible.
    if changed {
        let _ = app.emit("power-status-updated", ());
    }
}

struct PendingPowerAction {
    task: power::PendingAction,
    action: SleepAction,
    revision: u64,
    wake_hold: AlarmWakeHold,
}

fn finish_power_action(
    app: &AppHandle,
    action: SleepAction,
    revision: u64,
    wake_hold: AlarmWakeHold,
    result: Result<(), String>,
) {
    let state = app.state::<AppState>();
    let failed = {
        let mut sched = state.sched.lock().unwrap();
        sched.power_committed = false;
        sched
            .wake_hold
            .finish_power_action(wake_hold, action, result.is_ok());
        result
            .err()
            .filter(|error| sched.sleep.fail(revision, error.clone()))
            .map(|_| sched.sleep.clone())
    };
    if let Some(failed) = failed {
        let _ = app.emit("sleep-timer-updated", failed);
    }
}

fn poll_power_action(app: &AppHandle, pending: &mut Option<PendingPowerAction>) {
    let Some(result) = pending.as_ref().and_then(|p| p.task.try_result()) else {
        return;
    };
    let completed = pending.take().unwrap();
    finish_power_action(
        app,
        completed.action,
        completed.revision,
        completed.wake_hold,
        result,
    );
}

fn tick_sleep(
    app: &AppHandle,
    power: &mut power::PowerManager,
    last_tick: i64,
    pending_power: &mut Option<PendingPowerAction>,
    power_window: Option<isize>,
) {
    let now_ms = Local::now().timestamp_millis();
    let next = next_alarm(app).map(|a| a.at_ms);
    let state = app.state::<AppState>();
    let (effect, snapshot) = {
        let mut sched = state.sched.lock().unwrap();
        let ringing = sched.ringing.is_some();
        let imminent = wake_plan(now_ms, next, false).keep_awake;
        let resumed = power_clock_interrupted(last_tick * 1000, now_ms);
        let effect = sched.sleep.advance(now_ms, ringing, imminent, resumed);
        (effect, sched.sleep.clone())
    };
    if effect == SleepEffect::None {
        return;
    }
    if effect == SleepEffect::Countdown {
        // Do not take the alarm's always-on-top grab for a timer prompt.
        #[cfg(desktop)]
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
        let (committed, wake_hold) = {
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
            // A later, explicit power timer is still allowed. Release only
            // at dispatch, so selecting or cancelling one cannot lose the hold.
            let wake_hold = std::mem::take(&mut sched.wake_hold);
            (sched.sleep.clone(), wake_hold)
        };
        let _ = app.emit("sleep-timer-updated", &committed);
        // Windows power calls must not occupy the clock thread. An automatic wake
        // needs this thread to restore power requests and fire the alarm even
        // while the Windows call is still pending on the worker.
        // Modern Standby starts through display power-off, so a leftover
        // system request could leave only the screen asleep. Keep the failure
        // visible and restore the prior alarm hold instead of claiming sleep.
        if let Err(error) = power.keep_awake(false, false) {
            finish_power_action(app, action, committed.revision, wake_hold, Err(error));
            return;
        }
        match power::PendingAction::spawn(action, move |action| {
            power::execute(action, power_window)
        }) {
            Ok(task) => {
                *pending_power = Some(PendingPowerAction {
                    task,
                    action,
                    revision: committed.revision,
                    wake_hold,
                });
            }
            Err(error) => {
                finish_power_action(app, action, committed.revision, wake_hold, Err(error));
            }
        }
    } else {
        let _ = app.emit("sleep-timer-updated", &snapshot);
    }
}
