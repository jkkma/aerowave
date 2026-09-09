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
