//! Deadlines and cancellation rules for the sleep timer. The desktop owns
//! the clock and power APIs; these transitions can be tested without either.

use serde::{Deserialize, Serialize};

pub const POWER_COUNTDOWN_MS: i64 = 30_000;
pub const WAKE_LEAD_MS: i64 = 45_000;
const POWER_DISPATCH_GRACE_MS: u64 = 5_000;

/// A power countdown must have recent elapsed-clock ticks before it can
/// dispatch. Wall-clock corrections must not look like a resume or hide one.
pub fn power_clock_interrupted(last_tick_ms: u64, now_ms: u64) -> bool {
    now_ms.saturating_sub(last_tick_ms) > POWER_DISPATCH_GRACE_MS
}

#[derive(Clone, Copy, Debug, Default, Deserialize, Serialize, PartialEq, Eq)]
#[serde(rename_all = "lowercase")]
pub enum SleepAction {
    #[default]
    Stop,
    Sleep,
    Shutdown,
}

#[derive(Clone, Debug, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct SleepTimer {
    pub minutes: u32,
    pub action: SleepAction,
    /// Projected wall-clock deadline for display, updated after clock changes.
    pub ends_at_ms: i64,
    pub execute_at_ms: Option<i64>,
    pub remaining_ms: i64,
    pub execute_remaining_ms: Option<i64>,
    #[serde(skip)]
    ends_at_elapsed_ms: u64,
    #[serde(skip)]
    execute_at_elapsed_ms: Option<u64>,
}

#[derive(Clone, Copy, Debug, Serialize, PartialEq, Eq)]
#[serde(rename_all = "lowercase")]
pub enum SleepOutcome {
    Cancelled,
    Finished,
    Alarm,
    Failed,
}

#[derive(Clone, Debug, Default, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct SleepSnapshot {
    pub revision: u64,
    /// A strictly increasing sample tag, including samples within one ms.
    pub sampled_at_ms: u64,
    pub timer: Option<SleepTimer>,
    pub outcome: Option<SleepOutcome>,
    pub error: Option<String>,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum SleepEffect {
    None,
    Changed,
    Countdown,
    Execute(SleepAction),
}

impl SleepSnapshot {
    /// Compatibility with fixed-clock callers. The desktop passes an elapsed
    /// clock through `start_monotonic` so wall corrections cannot move a timer.
    pub fn start(&mut self, now_ms: i64, minutes: u32, action: SleepAction) -> Result<(), String> {
        self.start_monotonic(now_ms, now_ms.max(0) as u64, minutes, action)
    }

    pub fn start_monotonic(
        &mut self,
        wall_ms: i64,
        elapsed_ms: u64,
        minutes: u32,
        action: SleepAction,
    ) -> Result<(), String> {
        if !(1..=1440).contains(&minutes) {
            return Err("Choose a sleep timer between 1 minute and 24 hours".into());
        }
        let duration_ms = i64::from(minutes) * 60_000;
        let ends_at_ms = wall_ms
            .checked_add(duration_ms)
            .ok_or("The sleep timer deadline is out of range")?;
        let ends_at_elapsed_ms = elapsed_ms
            .checked_add(duration_ms as u64)
            .ok_or("The sleep timer deadline is out of range")?;
        self.revision += 1;
        self.timer = Some(SleepTimer {
            minutes,
            action,
            ends_at_ms,
            execute_at_ms: None,
            remaining_ms: duration_ms,
            execute_remaining_ms: None,
            ends_at_elapsed_ms,
            execute_at_elapsed_ms: None,
        });
        self.outcome = None;
        self.error = None;
        self.sample(wall_ms, elapsed_ms);
        Ok(())
    }

    /// Refresh the serialized countdown without advancing its transitions.
    /// Wall deadlines are projections; elapsed deadlines own the duration.
    pub fn sample(&mut self, wall_ms: i64, elapsed_ms: u64) {
        self.sampled_at_ms = self.sampled_at_ms.saturating_add(1).max(elapsed_ms);
        if let Some(timer) = self.timer.as_mut() {
            let remaining = timer.ends_at_elapsed_ms.saturating_sub(elapsed_ms);
            timer.remaining_ms = remaining.min(i64::MAX as u64) as i64;
            timer.ends_at_ms = wall_ms.saturating_add(timer.remaining_ms);
            timer.execute_remaining_ms = timer
                .execute_at_elapsed_ms
                .map(|at| at.saturating_sub(elapsed_ms).min(i64::MAX as u64) as i64);
            timer.execute_at_ms = timer
                .execute_remaining_ms
                .map(|remaining| wall_ms.saturating_add(remaining));
        }
    }

    pub fn cancel(&mut self, reason: SleepOutcome) -> bool {
        if self.timer.take().is_none() {
            return false;
        }
        self.revision += 1;
        self.outcome = Some(reason);
        self.error = None;
        true
    }

    /// A resume must not turn an overdue power timer into an immediate second
    /// suspension or a shutdown. Stop-audio timers can safely finish late.
    pub fn advance(
        &mut self,
        now_ms: i64,
        ringing: bool,
        alarm_imminent: bool,
        resumed: bool,
    ) -> SleepEffect {
        self.advance_monotonic(
            now_ms,
            now_ms.max(0) as u64,
            ringing,
            alarm_imminent,
            resumed,
        )
    }

    pub fn advance_monotonic(
        &mut self,
        wall_ms: i64,
        elapsed_ms: u64,
        ringing: bool,
        alarm_imminent: bool,
        resumed: bool,
    ) -> SleepEffect {
        self.sample(wall_ms, elapsed_ms);
        let Some(timer) = self.timer.as_ref() else {
            return SleepEffect::None;
        };
        if ringing || (alarm_imminent && timer.action != SleepAction::Stop) {
            self.cancel(SleepOutcome::Alarm);
            return SleepEffect::Changed;
        }
        if resumed && timer.action != SleepAction::Stop && elapsed_ms >= timer.ends_at_elapsed_ms {
            self.cancel(SleepOutcome::Cancelled);
            return SleepEffect::Changed;
        }
        if elapsed_ms < timer.ends_at_elapsed_ms {
            return SleepEffect::None;
        }
        let action = timer.action;
        if action != SleepAction::Stop {
            if let Some(execute_at) = timer.execute_at_elapsed_ms {
                if elapsed_ms < execute_at {
                    return SleepEffect::None;
                }
                // A busy save gate can make dispatch retry on every healthy
                // tick. Do not perform a power action long after its prompt.
                if elapsed_ms.saturating_sub(execute_at) > POWER_DISPATCH_GRACE_MS {
                    self.cancel(SleepOutcome::Cancelled);
                    return SleepEffect::Changed;
                }
            } else {
                self.timer.as_mut().unwrap().execute_at_elapsed_ms =
                    Some(elapsed_ms.saturating_add(POWER_COUNTDOWN_MS as u64));
                self.sample(wall_ms, elapsed_ms);
                self.revision += 1;
                return SleepEffect::Countdown;
            }
        }
        if action != SleepAction::Stop {
            // Dispatch is claimed separately so cancellation or replacement
            // can still win while the desktop prepares the Windows call.
            return SleepEffect::Execute(action);
        }
        self.finish();
        SleepEffect::Execute(action)
    }

    fn finish(&mut self) {
        self.timer = None;
        self.outcome = Some(SleepOutcome::Finished);
        self.revision += 1;
    }

    pub fn commit_power(&mut self, revision: u64) -> bool {
        if self.revision != revision
            || !self.timer.as_ref().is_some_and(|timer| {
                timer.action != SleepAction::Stop && timer.execute_at_elapsed_ms.is_some()
            })
        {
            return false;
        }
        self.finish();
        true
    }

    /// The OS call completes outside the scheduler lock, possibly after a
    /// suspend/resume. Its failure must not overwrite a newly selected timer.
    pub fn fail(&mut self, revision: u64, error: String) -> bool {
        if self.revision != revision {
            return false;
        }
        self.revision += 1;
        self.outcome = Some(SleepOutcome::Failed);
        self.error = Some(error);
        true
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct WakePlan {
    pub arm_at_ms: Option<i64>,
    pub keep_awake: bool,
    pub keep_display_awake: bool,
}

/// A real alarm owns a system request through its snooze chain. The scheduler
/// supplies whether that chain still has a ring or pending snooze; a completed
/// cycle cannot leave a process-lifetime execution-state request behind.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct AlarmWakeHold {
    active: bool,
}

impl AlarmWakeHold {
    pub fn alarm_fired(&mut self, wake_enabled: bool) {
        self.active |= wake_enabled;
    }

    pub fn active(&self) -> bool {
        self.active
    }

    pub fn set_enabled(&mut self, wake_enabled: bool) {
        if !wake_enabled {
            self.active = false;
        }
    }

    /// Restore a saved request only while an alarm cycle is still active. A
    /// newer ring has its own request and an older completion cannot clear it.
    pub fn finish_power_action(
        &mut self,
        saved_hold: Self,
        action: SleepAction,
        succeeded: bool,
        active_cycle: bool,
        wake_enabled: bool,
    ) {
        if !wake_enabled || !active_cycle {
            self.active = false;
        } else if !succeeded || action == SleepAction::Shutdown {
            self.active |= saved_hold.active;
        }
    }

    pub fn plan(
        &mut self,
        now_ms: i64,
        next_alarm_ms: Option<i64>,
        ringing: bool,
        snoozing: bool,
        wake_enabled: bool,
    ) -> WakePlan {
        if !wake_enabled || (!ringing && !snoozing) {
            self.active = false;
        }
        let mut plan = wake_plan(
            now_ms,
            if wake_enabled { next_alarm_ms } else { None },
            ringing,
        );
        plan.keep_awake |= self.active;
        plan
    }
}

/// Wake early enough for the audio device and network to resume, then keep
/// Windows and its display awake until the alarm rings. An unattended wake
/// leaves the display off unless requested; the alarm must surface on it.
/// Snoozes use the same deadline rule.
pub fn wake_plan(now_ms: i64, next_alarm_ms: Option<i64>, ringing: bool) -> WakePlan {
    match next_alarm_ms {
        Some(at) if at > now_ms + WAKE_LEAD_MS => WakePlan {
            arm_at_ms: Some(at - WAKE_LEAD_MS),
            keep_awake: ringing,
            keep_display_awake: ringing,
        },
        Some(at) if at > now_ms => WakePlan {
            // Keep a second wake request at the alarm itself: a lid close or
            // explicit Sleep overrides the keep-awake request after early wake.
            arm_at_ms: Some(at),
            keep_awake: true,
            keep_display_awake: true,
        },
        Some(_) => WakePlan {
            arm_at_ms: None,
            keep_awake: true,
            keep_display_awake: true,
        },
        None => WakePlan {
            arm_at_ms: None,
            keep_awake: ringing,
            keep_display_awake: ringing,
        },
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn timer(action: SleepAction) -> SleepSnapshot {
        let mut s = SleepSnapshot::default();
        s.start(1_000, 1, action).unwrap();
        s
    }

    #[test]
    fn stopping_finishes_once_at_the_deadline() {
        let mut s = timer(SleepAction::Stop);
        assert_eq!(s.advance(60_999, false, false, false), SleepEffect::None);
        assert_eq!(
            s.advance(61_000, false, false, false),
            SleepEffect::Execute(SleepAction::Stop)
        );
        assert!(s.timer.is_none());
        assert_eq!(s.advance(62_000, false, false, false), SleepEffect::None);
    }

    #[test]
    fn both_power_actions_get_a_full_countdown_even_if_the_tick_is_late() {
        for action in [SleepAction::Sleep, SleepAction::Shutdown] {
            let mut s = timer(action);
            assert_eq!(
                s.advance(80_000, false, false, false),
                SleepEffect::Countdown
            );
            assert_eq!(s.timer.as_ref().unwrap().execute_at_ms, Some(110_000));
            assert_eq!(s.advance(109_999, false, false, false), SleepEffect::None);
            assert_eq!(
                s.advance(110_000, false, false, false),
                SleepEffect::Execute(action)
            );
            assert!(s.commit_power(s.revision));
            assert!(s.timer.is_none());
            assert_eq!(s.advance(111_000, false, false, false), SleepEffect::None);
        }
    }

    #[test]
    fn repeated_uncommitted_dispatch_cancels_after_five_seconds() {
        for action in [SleepAction::Sleep, SleepAction::Shutdown] {
            let mut s = timer(action);
            // A late first tick still starts a full countdown.
            assert_eq!(
                s.advance_monotonic(80_000, 80_000, false, false, false),
                SleepEffect::Countdown
            );
            let revision = s.revision;
            let mut last_tick = 109_000;
            for now in [110_000, 111_000, 112_000, 113_000, 114_000, 115_000] {
                assert!(!power_clock_interrupted(last_tick, now));
                assert_eq!(
                    s.advance_monotonic(now as i64, now, false, false, false),
                    SleepEffect::Execute(action)
                );
                assert_eq!(s.revision, revision);
                last_tick = now;
            }
            assert!(!power_clock_interrupted(last_tick, 115_001));
            assert_eq!(
                s.advance_monotonic(115_001, 115_001, false, false, false),
                SleepEffect::Changed
            );
            assert_eq!(s.outcome, Some(SleepOutcome::Cancelled));
            assert!(s.timer.is_none());
            assert!(!s.commit_power(revision));
        }
    }

    #[test]
    fn off_and_replacement_cancel_the_old_pending_action() {
        let mut s = timer(SleepAction::Shutdown);
        s.advance(61_000, false, false, false);
        let old = s.clone();
        assert!(s.cancel(SleepOutcome::Cancelled));
        assert_eq!(s.advance(100_000, false, false, false), SleepEffect::None);
        s.start(62_000, 2, SleepAction::Stop).unwrap();
        assert!(s.revision > old.revision);
        assert_eq!(s.advance(100_000, false, false, false), SleepEffect::None);
        assert!(!s.fail(old.revision, "late failure".into()));
        assert_eq!(s.timer.unwrap().action, SleepAction::Stop);
    }

    #[test]
    fn an_alarm_wins_at_expiry_and_at_the_end_of_the_power_countdown() {
        for pending in [false, true] {
            for action in [SleepAction::Stop, SleepAction::Sleep, SleepAction::Shutdown] {
                let mut s = timer(action);
                if pending && action != SleepAction::Stop {
                    s.advance(61_000, false, false, false);
                }
                assert_eq!(s.advance(91_000, true, false, false), SleepEffect::Changed);
                assert_eq!(s.outcome, Some(SleepOutcome::Alarm));
                assert!(s.timer.is_none());
            }
        }
    }

    #[test]
    fn alarm_preparation_preserves_stop_audio_until_its_deadline() {
        let mut s = timer(SleepAction::Stop);
        let alarm_at = 91_000;
        let prepare_at = alarm_at - WAKE_LEAD_MS;
        assert!(wake_plan(prepare_at, Some(alarm_at), false).keep_awake);
        let before = s.clone();
        assert_eq!(s.advance(prepare_at, false, true, false), SleepEffect::None);
        assert_eq!(s.revision, before.revision);
        assert_eq!(s.outcome, before.outcome);
        assert_eq!(
            s.timer.as_ref().unwrap().action,
            before.timer.unwrap().action
        );
        assert_eq!(
            s.advance(61_000, false, true, false),
            SleepEffect::Execute(SleepAction::Stop)
        );
        assert_eq!(s.outcome, Some(SleepOutcome::Finished));
        assert!(s.timer.is_none());
    }

    #[test]
    fn alarm_preparation_cancels_both_power_actions_before_and_during_countdown() {
        for action in [SleepAction::Sleep, SleepAction::Shutdown] {
            for pending in [false, true] {
                let mut s = timer(action);
                let prepare_at = if pending {
                    assert_eq!(
                        s.advance(61_000, false, false, false),
                        SleepEffect::Countdown
                    );
                    91_000
                } else {
                    46_000
                };
                assert_eq!(
                    s.advance(prepare_at, false, true, false),
                    SleepEffect::Changed
                );
                assert_eq!(s.outcome, Some(SleepOutcome::Alarm));
                assert!(s.timer.is_none());
            }
        }
    }

    #[test]
    fn resuming_cancels_overdue_power_but_still_stops_audio() {
        for pending in [false, true] {
            let mut s = timer(SleepAction::Sleep);
            if pending {
                s.advance(61_000, false, false, false);
            }
            assert_eq!(s.advance(300_000, false, false, true), SleepEffect::Changed);
            assert_eq!(s.outcome, Some(SleepOutcome::Cancelled));
        }
        let mut s = timer(SleepAction::Stop);
        assert_eq!(
            s.advance(300_000, false, false, true),
            SleepEffect::Execute(SleepAction::Stop)
        );
    }

    #[test]
    fn a_short_suspend_across_the_countdown_cancels_the_power_action() {
        let mut s = timer(SleepAction::Shutdown);
        s.advance(61_000, false, false, false);
        assert!(!power_clock_interrupted(61_000, 62_000));
        assert_eq!(
            s.advance(
                101_000,
                false,
                false,
                power_clock_interrupted(62_000, 101_000)
            ),
            SleepEffect::Changed
        );
        assert_eq!(s.outcome, Some(SleepOutcome::Cancelled));
    }

    #[test]
    fn invalid_durations_leave_the_existing_timer_intact() {
        let mut s = timer(SleepAction::Stop);
        let before = s.clone();
        for minutes in [0, 1441, u32::MAX] {
            assert!(s.start(0, minutes, SleepAction::Shutdown).is_err());
            assert!(s
                .start_monotonic(0, 10_000, minutes, SleepAction::Shutdown)
                .is_err());
        }
        assert!(s
            .start_monotonic(0, u64::MAX, 1, SleepAction::Shutdown)
            .is_err());
        assert_eq!(s, before);
    }

    #[test]
    fn cancellation_and_replacement_win_until_dispatch_is_committed() {
        for replace in [false, true] {
            let mut s = timer(SleepAction::Shutdown);
            s.advance(61_000, false, false, false);
            let revision = s.revision;
            assert_eq!(
                s.advance(91_000, false, false, false),
                SleepEffect::Execute(SleepAction::Shutdown)
            );
            if replace {
                s.start(91_000, 15, SleepAction::Stop).unwrap();
            } else {
                s.cancel(SleepOutcome::Cancelled);
            }
            assert!(!s.commit_power(revision));
        }
    }

    #[test]
    fn snapshots_survive_page_reload_with_deadline_and_captured_action() {
        let mut s = timer(SleepAction::Shutdown);
        s.advance(61_000, false, false, false);
        let value = serde_json::to_value(&s).unwrap();
        assert_eq!(value["timer"]["endsAtMs"], 61_000);
        assert_eq!(value["timer"]["executeAtMs"], 91_000);
        assert_eq!(value["timer"]["remainingMs"], 0);
        assert_eq!(value["timer"]["executeRemainingMs"], 30_000);
        assert_eq!(value["timer"]["action"], "shutdown");
        assert_eq!(value["revision"], 2);
        assert!(value["sampledAtMs"].as_u64().unwrap() >= 61_000);
    }

    #[test]
    fn wall_clock_corrections_do_not_change_elapsed_timer_duration() {
        for wall_at_halfway in [970_000, 1_130_000] {
            let mut s = SleepSnapshot::default();
            s.start_monotonic(1_000_000, 10_000, 1, SleepAction::Sleep)
                .unwrap();
            assert_eq!(
                s.advance_monotonic(wall_at_halfway, 40_000, false, false, true),
                SleepEffect::None
            );
            let timer = s.timer.as_ref().unwrap();
            assert_eq!(timer.remaining_ms, 30_000);
            assert_eq!(timer.ends_at_ms, wall_at_halfway + 30_000);
            assert_eq!(
                s.advance_monotonic(wall_at_halfway + 30_000, 70_000, false, false, false),
                SleepEffect::Countdown
            );
            assert_eq!(s.timer.as_ref().unwrap().execute_remaining_ms, Some(30_000));
        }
    }

    #[test]
    fn countdown_uses_elapsed_time_through_forward_and_backward_clock_changes() {
        for changed_wall in [1_025_000, 1_105_000] {
            let mut s = SleepSnapshot::default();
            s.start_monotonic(1_000_000, 10_000, 1, SleepAction::Shutdown)
                .unwrap();
            assert_eq!(
                s.advance_monotonic(1_060_000, 70_000, false, false, false),
                SleepEffect::Countdown
            );
            assert_eq!(
                s.advance_monotonic(changed_wall, 75_000, false, false, false),
                SleepEffect::None
            );
            assert_eq!(s.timer.as_ref().unwrap().execute_remaining_ms, Some(25_000));
            assert_eq!(
                s.advance_monotonic(changed_wall + 24_999, 99_999, false, false, false),
                SleepEffect::None
            );
            assert_eq!(
                s.advance_monotonic(changed_wall + 25_000, 100_000, false, false, false),
                SleepEffect::Execute(SleepAction::Shutdown)
            );
        }
    }

    #[test]
    fn elapsed_gap_cancels_overdue_power_but_finishes_stop_audio() {
        for action in [SleepAction::Sleep, SleepAction::Shutdown] {
            let mut s = SleepSnapshot::default();
            s.start_monotonic(1_000_000, 10_000, 1, action).unwrap();
            assert!(!power_clock_interrupted(69_000, 70_000));
            assert!(power_clock_interrupted(40_000, 80_000));
            assert_eq!(
                s.advance_monotonic(980_000, 80_000, false, false, true),
                SleepEffect::Changed
            );
            assert_eq!(s.outcome, Some(SleepOutcome::Cancelled));
        }
        let mut stop = SleepSnapshot::default();
        stop.start_monotonic(1_000_000, 10_000, 1, SleepAction::Stop)
            .unwrap();
        assert_eq!(
            stop.advance_monotonic(980_000, 80_000, false, false, true),
            SleepEffect::Execute(SleepAction::Stop)
        );
    }

    #[test]
    fn samples_in_one_millisecond_stay_ordered_and_replacement_resets_remaining() {
        let mut s = SleepSnapshot::default();
        s.start_monotonic(1_000_000, 10_000, 1, SleepAction::Sleep)
            .unwrap();
        let first = s.sampled_at_ms;
        s.sample(1_000_000, 10_000);
        assert!(s.sampled_at_ms > first);
        assert_eq!(s.timer.as_ref().unwrap().remaining_ms, 60_000);
        assert!(s.cancel(SleepOutcome::Cancelled));
        s.start_monotonic(2_000_000, 20_000, 2, SleepAction::Stop)
            .unwrap();
        assert_eq!(s.timer.as_ref().unwrap().remaining_ms, 120_000);
        assert_eq!(
            s.advance_monotonic(2_090_000, 110_000, false, false, false),
            SleepEffect::None
        );
        assert_eq!(s.timer.as_ref().unwrap().remaining_ms, 30_000);
    }

    #[test]
    fn wake_planning_covers_lead_time_snooze_cancellation_and_ringing() {
        assert_eq!(
            wake_plan(0, Some(300_000), false),
            WakePlan {
                arm_at_ms: Some(255_000),
                keep_awake: false,
                keep_display_awake: false
            }
        );
        assert_eq!(
            wake_plan(255_000, Some(300_000), false),
            WakePlan {
                arm_at_ms: Some(300_000),
                keep_awake: true,
                keep_display_awake: true
            }
        );
        assert_eq!(
            wake_plan(270_000, Some(300_000), false).arm_at_ms,
            Some(300_000)
        );
        assert_eq!(
            wake_plan(300_001, Some(300_000), false),
            WakePlan {
                arm_at_ms: None,
                keep_awake: true,
                keep_display_awake: true
            }
        );
        assert_eq!(
            wake_plan(0, None, false),
            WakePlan {
                arm_at_ms: None,
                keep_awake: false,
                keep_display_awake: false
            }
        );
        assert_eq!(
            wake_plan(0, None, true),
            WakePlan {
                arm_at_ms: None,
                keep_awake: true,
                keep_display_awake: true
            }
        );
    }

    #[test]
    fn only_alarm_preparation_and_ringing_request_the_display() {
        for (now_ms, next_alarm_ms, ringing, expected) in [
            (0, None, false, false),
            (0, Some(WAKE_LEAD_MS + 1), false, false),
            (0, Some(WAKE_LEAD_MS), false, true),
            (0, Some(1), false, true),
            (0, Some(0), false, true),
            (0, Some(-1), false, true),
            (0, None, true, true),
            (0, Some(WAKE_LEAD_MS + 1), true, true),
        ] {
            let plan = wake_plan(now_ms, next_alarm_ms, ringing);
            assert_eq!(plan.keep_display_awake, expected);
        }
        // Snoozing or dismissing releases the screen even when a separate
        // sleep timer still needs to hold the system awake until its deadline.
        let snoozed = wake_plan(0, Some(9 * 60_000), false);
        assert!(!snoozed.keep_display_awake);
        assert!(!wake_plan(0, None, false).keep_display_awake);
    }

    #[test]
    fn dismiss_and_automatic_stop_release_the_last_alarm_hold() {
        for ended_at in [300_000, 360_000] {
            let mut hold = AlarmWakeHold::default();
            hold.alarm_fired(true);
            assert!(hold.plan(ended_at, None, true, false, true).keep_awake);
            assert_eq!(
                hold.plan(ended_at, None, false, false, true),
                WakePlan {
                    arm_at_ms: None,
                    keep_awake: false,
                    keep_display_awake: false,
                }
            );
            assert!(!hold.active());
        }
    }

    #[test]
    fn snooze_keeps_the_system_but_releases_the_display_between_rings() {
        let snooze_at = 9 * 60_000;
        let mut hold = AlarmWakeHold::default();
        hold.alarm_fired(true);
        assert!(hold.plan(0, None, true, false, true).keep_display_awake);
        assert_eq!(
            hold.plan(0, Some(snooze_at), false, true, true),
            WakePlan {
                arm_at_ms: Some(snooze_at - WAKE_LEAD_MS),
                keep_awake: true,
                keep_display_awake: false,
            }
        );
        let preparing = hold.plan(snooze_at - WAKE_LEAD_MS, Some(snooze_at), false, true, true);
        assert_eq!(preparing.arm_at_ms, Some(snooze_at));
        assert!(preparing.keep_awake && preparing.keep_display_awake);
        assert!(hold.plan(snooze_at, None, true, false, true).keep_awake);
        // Automatic give-up can schedule another snooze from this ring.
        let again = hold.plan(snooze_at + 1, Some(2 * snooze_at), false, true, true);
        assert!(again.keep_awake && !again.keep_display_awake);
        assert!(!hold.plan(2 * snooze_at + 1, None, false, false, true).keep_awake);
    }

    #[test]
    fn preparation_and_test_ringing_do_not_claim_a_snooze_hold() {
        let mut hold = AlarmWakeHold::default();
        let preparing = hold.plan(0, Some(WAKE_LEAD_MS), false, false, true);
        assert!(preparing.keep_awake && preparing.keep_display_awake);
        assert!(!hold.active());
        assert!(!hold.plan(1, None, false, false, true).keep_awake);

        // TEST protects its current ring without claiming a real occurrence.
        let testing = hold.plan(2, None, true, false, true);
        assert!(testing.keep_awake && testing.keep_display_awake);
        assert!(!hold.active());
        assert!(!hold.plan(3, None, false, false, true).keep_awake);
    }

    #[test]
    fn an_active_snooze_hold_does_not_cancel_an_explicit_power_timer() {
        let mut hold = AlarmWakeHold::default();
        hold.alarm_fired(true);
        let snooze_at = 600_000;
        assert!(hold.plan(61_000, Some(snooze_at), false, true, true).keep_awake);
        let imminent = wake_plan(61_000, Some(snooze_at), false).keep_awake;
        assert!(!imminent);

        let mut sleep = timer(SleepAction::Sleep);
        assert_eq!(sleep.advance(61_000, false, imminent, false), SleepEffect::Countdown);
        assert_eq!(
            sleep.advance(91_000, false, imminent, false),
            SleepEffect::Execute(SleepAction::Sleep)
        );
        assert!(sleep.commit_power(sleep.revision));
        let saved = std::mem::take(&mut hold);
        assert!(!hold.plan(91_000, Some(snooze_at), false, true, true).keep_awake);
        hold.finish_power_action(saved, SleepAction::Sleep, true, true, true);
        assert!(!hold.plan(92_000, Some(snooze_at), false, true, true).keep_awake);
    }

    #[test]
    fn turning_wake_off_clears_snooze_hold_but_protects_a_current_ring() {
        let mut hold = AlarmWakeHold::default();
        hold.alarm_fired(true);
        assert!(hold.plan(0, Some(300_000), false, true, true).keep_awake);
        hold.set_enabled(false);
        hold.set_enabled(true);
        assert!(!hold.plan(0, Some(300_000), false, true, true).keep_awake);
        let disabled_ring = hold.plan(0, Some(300_000), true, false, false);
        assert!(disabled_ring.keep_awake && disabled_ring.keep_display_awake);
        assert_eq!(disabled_ring.arm_at_ms, None);
        assert!(!hold.active());
    }

    #[test]
    fn unrelated_next_alarm_and_stale_snoozes_do_not_end_a_live_chain() {
        use crate::schedule::{next_due_snooze, Snooze};
        use std::collections::HashMap;

        let mut hold = AlarmWakeHold::default();
        hold.alarm_fired(true);
        let mut pending = HashMap::from([
            ("first".to_string(), Snooze::new(100)),
            ("second".to_string(), Snooze::new(30_000)),
        ]);
        let (_, stale) = next_due_snooze(&mut pending, 20_000, false);
        assert_eq!(stale, vec!["first"]);
        // Another scheduled alarm can be earlier than the surviving snooze.
        let plan = hold.plan(20_000_000, Some(21_000_000), false, !pending.is_empty(), true);
        assert!(plan.keep_awake && !plan.keep_display_awake);
        assert_eq!(plan.arm_at_ms, Some(20_955_000));

        let (_, stale) = next_due_snooze(&mut pending, 50_000, false);
        assert_eq!(stale, vec!["second"]);
        assert!(pending.is_empty());
        assert!(!hold.plan(50_000_000, None, false, false, true).keep_awake);
    }

    #[test]
    fn power_completion_restores_only_an_active_pending_chain() {
        for (action, succeeded, restore) in [
            (SleepAction::Sleep, true, false),
            (SleepAction::Sleep, false, true),
            (SleepAction::Shutdown, true, true),
        ] {
            let mut hold = AlarmWakeHold::default();
            hold.alarm_fired(true);
            let saved = std::mem::take(&mut hold);
            assert!(!hold.plan(0, Some(600_000), false, true, true).keep_awake);
            hold.finish_power_action(saved, action, succeeded, true, true);
            assert_eq!(hold.plan(0, Some(600_000), false, true, true).keep_awake, restore);

            let mut ended = AlarmWakeHold::default();
            ended.finish_power_action(saved, action, succeeded, false, true);
            assert!(!ended.plan(0, None, false, false, true).keep_awake);
            let mut disabled = AlarmWakeHold::default();
            disabled.finish_power_action(saved, action, succeeded, true, false);
            assert!(!disabled.active());
        }
    }

    #[test]
    fn old_power_completion_never_clears_a_newer_ring() {
        let mut hold = AlarmWakeHold::default();
        hold.alarm_fired(true);
        let saved = std::mem::take(&mut hold);
        hold.alarm_fired(true);
        for succeeded in [true, false] {
            let mut current = hold;
            current.finish_power_action(saved, SleepAction::Sleep, succeeded, true, true);
            assert!(current.plan(0, None, true, false, true).keep_awake);
            let snoozed = current.plan(1, Some(600_000), false, true, true);
            assert!(snoozed.keep_awake && !snoozed.keep_display_awake);
        }
    }

    #[test]
    fn manual_snooze_after_automatic_dismiss_reclaims_the_hold() {
        use crate::ring::{RingAction, RingActions};

        let mut actions = RingActions::default();
        actions.begin("alarm", 7, false, 1);
        let mut hold = AlarmWakeHold::default();
        hold.alarm_fired(true);
        assert!(actions.complete("alarm", 7, RingAction::Dismiss, true).unwrap());
        assert!(!hold.plan(0, None, false, false, true).keep_awake);
        assert!(actions.complete("alarm", 7, RingAction::Snooze, false).unwrap());
        hold.alarm_fired(true);
        let snoozed = hold.plan(1, Some(600_000), false, true, true);
        assert!(snoozed.keep_awake && !snoozed.keep_display_awake);
    }
}
