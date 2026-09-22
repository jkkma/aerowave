//! Deadline and preference rules for an alarm whose folders are scanned in parallel.
//! Filesystem work belongs to the app; this state only decides when to use its answers.

#[derive(Debug, PartialEq, Eq)]
pub enum Decision<T> {
    Preferred(T),
    Backup(T),
    Unavailable,
}

/// Saving a disabled/deleted alarm cancels only a ring still waiting for its
/// source. Once published, the user must dismiss that already audible ring.
/// A TEST preview is independent of the saved record with the same id.
pub fn cancel_unresolved_ring(
    ringing_id: Option<&str>,
    changed_id: &str,
    preview: bool,
    accepted: bool,
) -> bool {
    ringing_id == Some(changed_id) && !preview && !accepted
}

/// A slow result may only publish into the exact occurrence that requested it.
pub fn accepts_result(
    ringing_id: Option<&str>,
    expected_id: &str,
    current_generation: u64,
    expected_generation: u64,
) -> bool {
    ringing_id == Some(expected_id) && current_generation == expected_generation
}

pub struct Resolution<T> {
    fallback_at_ms: u64,
    deadline_ms: u64,
    preferred: Option<Option<T>>,
    backup: Option<Option<T>>,
}

impl<T> Resolution<T> {
    pub fn new(start_ms: u64) -> Self {
        Self {
            fallback_at_ms: start_ms.saturating_add(2_000),
            deadline_ms: start_ms.saturating_add(5_000),
            preferred: None,
            backup: None,
        }
    }

    pub fn preferred(&mut self, result: Option<T>, completed_ms: u64) {
        if completed_ms <= self.deadline_ms {
            self.preferred = Some(result);
        }
    }

    pub fn backup(&mut self, result: Option<T>, completed_ms: u64) {
        if completed_ms <= self.deadline_ms {
            self.backup = Some(result);
        }
    }

    pub fn decide(&mut self, now_ms: u64) -> Option<Decision<T>> {
        if let Some(track) = self.preferred.as_mut().and_then(Option::take) {
            return Some(Decision::Preferred(track));
        }
        let preferred_failed = self.preferred.is_some();
        if preferred_failed || now_ms >= self.fallback_at_ms {
            if let Some(track) = self.backup.as_mut().and_then(Option::take) {
                return Some(Decision::Backup(track));
            }
        }
        if preferred_failed && self.backup.is_some() || now_ms >= self.deadline_ms {
            return Some(Decision::Unavailable);
        }
        None
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn preferred_wins_before_fallback_deadline() {
        let mut r = Resolution::new(10);
        r.backup(Some("backup"), 100);
        assert_eq!(r.decide(2_009), None);
        r.preferred(Some("preferred"), 1_000);
        assert_eq!(r.decide(2_009), Some(Decision::Preferred("preferred")));
    }

    #[test]
    fn stalled_preferred_does_not_trap_ready_backup() {
        let mut r = Resolution::new(10);
        r.backup(Some("backup"), 100);
        assert_eq!(r.decide(2_010), Some(Decision::Backup("backup")));
    }

    #[test]
    fn failed_preferred_uses_backup_without_delay() {
        let mut r = Resolution::new(10);
        r.preferred(None::<&str>, 11);
        r.backup(Some("backup"), 11);
        assert_eq!(r.decide(11), Some(Decision::Backup("backup")));
    }

    #[test]
    fn total_deadline_is_finite_even_with_no_worker_answers() {
        let mut r = Resolution::<&str>::new(10);
        assert_eq!(r.decide(5_009), None);
        assert_eq!(r.decide(5_010), Some(Decision::Unavailable));
    }

    #[test]
    fn late_completions_cannot_replace_the_deadline_answer() {
        let mut r = Resolution::new(10);
        r.preferred(Some("late"), 5_011);
        r.backup(Some("also late"), 5_012);
        assert_eq!(r.decide(6_000), Some(Decision::Unavailable));
    }

    #[test]
    fn timely_result_survives_a_delayed_scheduler_poll() {
        let mut r = Resolution::new(10);
        r.backup(Some("ready backup"), 4_999);
        assert_eq!(r.decide(6_000), Some(Decision::Backup("ready backup")));
    }

    #[test]
    fn cancellation_and_replacement_reject_pending_result_but_keep_accepted_and_preview_rings() {
        assert!(cancel_unresolved_ring(Some("alarm"), "alarm", false, false));
        assert!(!cancel_unresolved_ring(Some("alarm"), "alarm", false, true));
        assert!(!cancel_unresolved_ring(Some("alarm"), "alarm", true, false));
        assert!(!cancel_unresolved_ring(
            Some("other"),
            "alarm",
            false,
            false
        ));
        assert!(accepts_result(Some("alarm"), "alarm", 7, 7));
        assert!(!accepts_result(None, "alarm", 8, 7));
        assert!(!accepts_result(Some("alarm"), "alarm", 8, 7));
        assert!(!accepts_result(Some("replacement"), "alarm", 7, 7));
    }
}
