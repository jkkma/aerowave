//! Deadlines and cancellation rules for the sleep timer. The desktop owns
//! the clock and power APIs; these transitions can be tested without either.

use serde::{Deserialize, Serialize};

pub const POWER_COUNTDOWN_MS: i64 = 30_000;
pub const WAKE_LEAD_MS: i64 = 45_000;

/// A power countdown must have recent clock ticks before it can dispatch.
/// This is deliberately tighter than the missed-alarm catch-up threshold:
/// even a brief suspend must not resume into an overdue shutdown.
pub fn power_clock_interrupted(last_tick_ms: i64, now_ms: i64) -> bool {
    now_ms.saturating_sub(last_tick_ms) > 5_000
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
    pub ends_at_ms: i64,
    pub execute_at_ms: Option<i64>,
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
    pub fn start(&mut self, now_ms: i64, minutes: u32, action: SleepAction) -> Result<(), String> {
        if !(1..=1440).contains(&minutes) {
            return Err("Choose a sleep timer between 1 minute and 24 hours".into());
        }
        let ends_at_ms = now_ms
            .checked_add(i64::from(minutes) * 60_000)
            .ok_or("The sleep timer deadline is out of range")?;
        self.revision += 1;
        self.timer = Some(SleepTimer {
            minutes,
            action,
            ends_at_ms,
            execute_at_ms: None,
        });
        self.outcome = None;
        self.error = None;
        Ok(())
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
    pub fn advance(&mut self, now_ms: i64, alarm_priority: bool, resumed: bool) -> SleepEffect {
        let Some(timer) = self.timer.as_ref() else {
            return SleepEffect::None;
        };
        if alarm_priority {
            self.cancel(SleepOutcome::Alarm);
            return SleepEffect::Changed;
        }
        if resumed && timer.action != SleepAction::Stop && now_ms >= timer.ends_at_ms {
            self.cancel(SleepOutcome::Cancelled);
            return SleepEffect::Changed;
        }
        if now_ms < timer.ends_at_ms {
            return SleepEffect::None;
        }
        let action = timer.action;
        if action != SleepAction::Stop {
            if let Some(execute_at) = timer.execute_at_ms {
                if now_ms < execute_at {
                    return SleepEffect::None;
                }
            } else {
                self.timer.as_mut().unwrap().execute_at_ms = Some(now_ms + POWER_COUNTDOWN_MS);
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
                timer.action != SleepAction::Stop && timer.execute_at_ms.is_some()
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
}

/// Wake early enough for the audio device and network to resume, then keep
/// Windows awake until the alarm rings. Snoozes use the same deadline rule.
pub fn wake_plan(now_ms: i64, next_alarm_ms: Option<i64>, ringing: bool) -> WakePlan {
    match next_alarm_ms {
        Some(at) if at > now_ms + WAKE_LEAD_MS => WakePlan {
            arm_at_ms: Some(at - WAKE_LEAD_MS),
            keep_awake: ringing,
        },
        Some(at) if at > now_ms => WakePlan {
            // Keep a second wake request at the alarm itself: a lid close or
            // explicit Sleep overrides the keep-awake request after early wake.
            arm_at_ms: Some(at),
            keep_awake: true,
        },
        Some(_) => WakePlan {
            arm_at_ms: None,
            keep_awake: true,
        },
        None => WakePlan {
            arm_at_ms: None,
            keep_awake: ringing,
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
        assert_eq!(s.advance(60_999, false, false), SleepEffect::None);
        assert_eq!(
            s.advance(61_000, false, false),
            SleepEffect::Execute(SleepAction::Stop)
        );
        assert!(s.timer.is_none());
        assert_eq!(s.advance(62_000, false, false), SleepEffect::None);
    }

    #[test]
    fn both_power_actions_get_a_full_countdown_even_if_the_tick_is_late() {
        for action in [SleepAction::Sleep, SleepAction::Shutdown] {
            let mut s = timer(action);
            assert_eq!(s.advance(80_000, false, false), SleepEffect::Countdown);
            assert_eq!(s.timer.as_ref().unwrap().execute_at_ms, Some(110_000));
            assert_eq!(s.advance(109_999, false, false), SleepEffect::None);
            assert_eq!(
                s.advance(110_000, false, false),
                SleepEffect::Execute(action)
            );
            assert!(s.commit_power(s.revision));
            assert!(s.timer.is_none());
            assert_eq!(s.advance(111_000, false, false), SleepEffect::None);
        }
    }

    #[test]
    fn off_and_replacement_cancel_the_old_pending_action() {
        let mut s = timer(SleepAction::Shutdown);
        s.advance(61_000, false, false);
        let old = s.clone();
        assert!(s.cancel(SleepOutcome::Cancelled));
        assert_eq!(s.advance(100_000, false, false), SleepEffect::None);
        s.start(62_000, 2, SleepAction::Stop).unwrap();
        assert!(s.revision > old.revision);
        assert_eq!(s.advance(100_000, false, false), SleepEffect::None);
        assert!(!s.fail(old.revision, "late failure".into()));
        assert_eq!(s.timer.unwrap().action, SleepAction::Stop);
    }

    #[test]
    fn an_alarm_wins_at_expiry_and_at_the_end_of_the_power_countdown() {
        for pending in [false, true] {
            for action in [SleepAction::Stop, SleepAction::Sleep, SleepAction::Shutdown] {
                let mut s = timer(action);
                if pending && action != SleepAction::Stop {
                    s.advance(61_000, false, false);
                }
                assert_eq!(s.advance(91_000, true, false), SleepEffect::Changed);
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
                s.advance(61_000, false, false);
            }
            assert_eq!(s.advance(300_000, false, true), SleepEffect::Changed);
            assert_eq!(s.outcome, Some(SleepOutcome::Cancelled));
        }
        let mut s = timer(SleepAction::Stop);
        assert_eq!(
            s.advance(300_000, false, true),
            SleepEffect::Execute(SleepAction::Stop)
        );
    }

    #[test]
    fn a_short_suspend_across_the_countdown_cancels_the_power_action() {
        let mut s = timer(SleepAction::Shutdown);
        s.advance(61_000, false, false);
        assert!(!power_clock_interrupted(61_000, 62_000));
        assert_eq!(
            s.advance(101_000, false, power_clock_interrupted(62_000, 101_000)),
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
        }
        assert_eq!(s, before);
    }

    #[test]
    fn cancellation_and_replacement_win_until_dispatch_is_committed() {
        for replace in [false, true] {
            let mut s = timer(SleepAction::Shutdown);
            s.advance(61_000, false, false);
            let revision = s.revision;
            assert_eq!(
                s.advance(91_000, false, false),
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
        s.advance(61_000, false, false);
        let value = serde_json::to_value(&s).unwrap();
        assert_eq!(value["timer"]["endsAtMs"], 61_000);
        assert_eq!(value["timer"]["executeAtMs"], 91_000);
        assert_eq!(value["timer"]["action"], "shutdown");
        assert_eq!(value["revision"], 2);
    }

    #[test]
    fn wake_planning_covers_lead_time_snooze_cancellation_and_ringing() {
        assert_eq!(
            wake_plan(0, Some(300_000), false),
            WakePlan {
                arm_at_ms: Some(255_000),
                keep_awake: false
            }
        );
        assert_eq!(
            wake_plan(255_000, Some(300_000), false),
            WakePlan {
                arm_at_ms: Some(300_000),
                keep_awake: true
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
                keep_awake: true
            }
        );
        assert_eq!(
            wake_plan(0, None, false),
            WakePlan {
                arm_at_ms: None,
                keep_awake: false
            }
        );
        assert_eq!(
            wake_plan(0, None, true),
            WakePlan {
                arm_at_ms: None,
                keep_awake: true
            }
        );
    }
}
