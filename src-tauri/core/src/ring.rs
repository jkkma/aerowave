//! What a ringing alarm holds on to between its snoozes.
//!
//! A folder alarm draws a random track when it rings. Snoozing is not a new
//! ring, though - it is the same alarm coming back - and waking to a
//! different song every nine minutes reads as a different alarm each time,
//! which is exactly the confusion an alarm should not create. So the track is
//! drawn once per occurrence and held across the snoozes that follow it.
//!
//! Everything here is bookkeeping over ids and paths, deliberately with no
//! filesystem in it: whether the held file is still there is the caller's
//! question, and the caller is the half that can answer it.

use std::collections::HashMap;

/// The trigger string that means "this alarm is coming back", as opposed to
/// arriving fresh. Kept next to the rule that reads it.
const SNOOZE: &str = "snooze";

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum RingAction {
    Dismiss,
    Snooze,
}

#[derive(Debug)]
struct Occurrence {
    id: u64,
    used: u32,
    allowance: u32,
    completed: Option<(RingAction, bool)>,
}

/// Native occurrence state survives page reloads. A manual choice may replace
/// an automatic completion whose reply is still reaching the page, but it must
/// never change a newer occurrence of the same alarm.
#[derive(Default, Debug)]
pub struct RingActions {
    occurrences: HashMap<String, Occurrence>,
}

impl RingActions {
    pub fn begin(&mut self, alarm_id: &str, id: u64, snoozed: bool, allowance: u32) {
        let used = if snoozed {
            self.occurrences.get(alarm_id).map_or(0, |ring| ring.used)
        } else {
            0
        };
        self.occurrences.insert(alarm_id.to_string(), Occurrence {
            id, used, allowance, completed: None,
        });
    }

    pub fn used(&self, alarm_id: &str, id: u64) -> u32 {
        self.occurrences.get(alarm_id).filter(|ring| ring.id == id)
            .map_or(0, |ring| ring.used)
    }

    pub fn cancel(&mut self, alarm_id: &str) {
        self.occurrences.remove(alarm_id);
    }

    /// Returns whether the caller needs to apply a scheduling change. Repeating
    /// an accepted action is harmless and does not consume another allowance.
    pub fn complete(
        &mut self,
        alarm_id: &str,
        id: u64,
        action: RingAction,
        automatic: bool,
    ) -> Result<bool, &'static str> {
        let ring = self.occurrences.get_mut(alarm_id)
            .filter(|ring| ring.id == id)
            .ok_or("that alarm occurrence is no longer current")?;
        if let Some((previous, was_automatic)) = ring.completed {
            if !automatic && was_automatic && previous == RingAction::Snooze {
                // The person answered before the automatic reply arrived.
                // Count the final manual choice, not the superseded automatic one.
                ring.used = ring.used.saturating_sub(1);
            }
            if previous == action {
                // A manual confirmation makes this decision final even when
                // the automatic reply was still pending in the page.
                if !automatic { ring.completed = Some((action, false)); }
                return Ok(false);
            }
            if automatic || !was_automatic {
                return Err("that alarm occurrence has already been completed");
            }
        }
        if automatic && action == RingAction::Snooze {
            if ring.used >= ring.allowance {
                return Err("this alarm has used its automatic snooze allowance");
            }
            ring.used += 1;
        }
        ring.completed = Some((action, automatic));
        Ok(true)
    }
}

/// Track held for each alarm's current occurrence, by alarm id.
#[derive(Default, Debug)]
pub struct RingHolds {
    held: HashMap<String, Held>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
struct Held {
    folder: String,
    track: String,
}

impl RingHolds {
    /// The track this ring should play again, if it should play one at all.
    ///
    /// `Some` only for a snooze: a scheduled ring, one caught up after the
    /// machine woke, and a test ring are all new occurrences and draw their
    /// own. `None` too when the alarm now points at a different folder than
    /// the held track came out of, since the answer to "play that one again"
    /// is no longer in the folder being asked about.
    pub fn held_for(&self, alarm_id: &str, trigger: &str, folder: &str) -> Option<&str> {
        if trigger != SNOOZE {
            return None;
        }
        let held = self.held.get(alarm_id)?;
        (held.folder == folder).then(|| held.track.as_str())
    }

    /// Hold the track a fresh draw landed on, so the snoozes after it get the
    /// same one. Only the alarm's own folder belongs here; the backup folder
    /// is the sound of something having gone wrong and is meant to be played
    /// through rather than held.
    pub fn remember(&mut self, alarm_id: &str, folder: &str, track: &str) {
        self.held.insert(
            alarm_id.to_string(),
            Held {
                folder: folder.to_string(),
                track: track.to_string(),
            },
        );
    }

    /// The occurrence is over - dismissed, or replaced by a fresh ring. The
    /// next one draws again.
    pub fn release(&mut self, alarm_id: &str) {
        self.held.remove(alarm_id);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const F: &str = "C:/Music/wake";

    #[test]
    fn manual_choice_overrides_only_the_same_automatic_occurrence() {
        for (automatic, manual) in [(RingAction::Snooze, RingAction::Dismiss),
            (RingAction::Dismiss, RingAction::Snooze)] {
            let mut actions = RingActions::default();
            actions.begin("a1", 1, false, 1);
            assert_eq!(actions.complete("a1", 1, automatic, true), Ok(true));
            assert_eq!(actions.complete("a1", 1, manual, false), Ok(true));
            assert_eq!(actions.complete("a1", 1, manual, false), Ok(false));
            assert!(actions.complete("a1", 1, automatic, false).is_err());
            actions.begin("a1", 2, false, 1);
            assert!(actions.complete("a1", 1, manual, false).is_err());
            assert_eq!(actions.complete("a1", 2, manual, false), Ok(true));
        }
    }

    #[test]
    fn automatic_budget_survives_snoozes_and_duplicate_requests() {
        let mut actions = RingActions::default();
        actions.begin("a1", 1, false, 1);
        assert_eq!(actions.complete("a1", 1, RingAction::Snooze, true), Ok(true));
        assert_eq!(actions.complete("a1", 1, RingAction::Snooze, true), Ok(false));
        assert_eq!(actions.used("a1", 1), 1);
        actions.begin("a2", 2, false, 2);
        actions.begin("a1", 3, true, 1);
        assert_eq!(actions.used("a1", 3), 1);
        assert!(actions.complete("a1", 3, RingAction::Snooze, true).is_err());
        assert_eq!(actions.complete("a1", 3, RingAction::Dismiss, true), Ok(true));
        actions.begin("a1", 4, false, 1);
        assert_eq!(actions.used("a1", 4), 0);
        assert_eq!(actions.complete("a1", 4, RingAction::Snooze, true), Ok(true));
    }

    #[test]
    fn manual_snooze_does_not_spend_the_automatic_budget() {
        let mut actions = RingActions::default();
        actions.begin("a1", 1, false, 1);
        assert_eq!(actions.complete("a1", 1, RingAction::Dismiss, true), Ok(true));
        assert_eq!(actions.complete("a1", 1, RingAction::Snooze, false), Ok(true));
        actions.begin("a1", 2, true, 1);
        assert_eq!(actions.used("a1", 2), 0);
        assert_eq!(actions.complete("a1", 2, RingAction::Snooze, true), Ok(true));
        actions.cancel("a1");
        assert!(actions.complete("a1", 2, RingAction::Dismiss, false).is_err());
    }

    #[test]
    fn manual_confirmation_refunds_an_automatic_snooze_only_once() {
        let mut actions = RingActions::default();
        actions.begin("a1", 1, false, 1);
        assert_eq!(actions.complete("a1", 1, RingAction::Snooze, true), Ok(true));
        assert_eq!(actions.used("a1", 1), 1);
        assert_eq!(actions.complete("a1", 1, RingAction::Snooze, false), Ok(false));
        assert_eq!(actions.used("a1", 1), 0);
        assert_eq!(actions.complete("a1", 1, RingAction::Snooze, false), Ok(false));
        actions.begin("a1", 2, true, 1);
        assert_eq!(actions.complete("a1", 2, RingAction::Snooze, true), Ok(true));
    }

    fn holds() -> RingHolds {
        let mut h = RingHolds::default();
        h.remember("a1", F, "C:/Music/wake/birds.mp3");
        h
    }

    #[test]
    fn a_snooze_comes_back_with_the_same_track() {
        assert_eq!(
            holds().held_for("a1", "snooze", F),
            Some("C:/Music/wake/birds.mp3")
        );
    }

    #[test]
    fn a_scheduled_ring_draws_its_own() {
        assert_eq!(holds().held_for("a1", "scheduled", F), None);
    }

    #[test]
    fn a_caught_up_ring_draws_its_own() {
        assert_eq!(holds().held_for("a1", "catchup", F), None);
    }

    #[test]
    fn a_test_ring_draws_its_own() {
        assert_eq!(holds().held_for("a1", "test", F), None);
    }

    #[test]
    fn an_alarm_with_nothing_held_draws_its_own() {
        assert_eq!(RingHolds::default().held_for("a1", "snooze", F), None);
    }

    #[test]
    fn repointing_the_alarm_at_another_folder_drops_the_hold() {
        assert_eq!(holds().held_for("a1", "snooze", "C:/Music/other"), None);
    }

    #[test]
    fn one_alarms_hold_is_not_anothers() {
        assert_eq!(holds().held_for("a2", "snooze", F), None);
    }

    #[test]
    fn a_fresh_draw_replaces_what_was_held() {
        let mut h = holds();
        h.remember("a1", F, "C:/Music/wake/rain.mp3");
        assert_eq!(
            h.held_for("a1", "snooze", F),
            Some("C:/Music/wake/rain.mp3")
        );
    }

    #[test]
    fn dismissing_ends_the_occurrence() {
        let mut h = holds();
        h.release("a1");
        assert_eq!(h.held_for("a1", "snooze", F), None);
    }

    #[test]
    fn releasing_an_alarm_that_holds_nothing_is_harmless() {
        let mut h = holds();
        h.release("a2");
        assert_eq!(
            h.held_for("a1", "snooze", F),
            Some("C:/Music/wake/birds.mp3")
        );
    }
}
