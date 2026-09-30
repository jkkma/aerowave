//! One-shot completion has two durable paths: the settings snapshot and a
//! separate receipt. A failed write never makes a consumed arm live again in
//! this process; only a fresh arm revision can do that.

use std::collections::BTreeSet;
use serde::{Deserialize, Serialize};

#[derive(Clone, Debug, PartialEq, Eq, PartialOrd, Ord, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct Arm {
    pub alarm_id: String,
    pub revision: String,
}

pub type Receipts = BTreeSet<Arm>;

#[derive(Default)]
pub struct Completions {
    consumed: Receipts,
    pending: Receipts,
    volatile: Receipts,
}

impl Completions {
    pub fn from_receipts(receipts: Receipts) -> Self {
        Self { consumed: receipts, ..Self::default() }
    }

    /// Called at the claim boundary, before source lookup or asynchronous I/O.
    pub fn claim(&mut self, arm: Arm) {
        if self.consumed.insert(arm.clone()) {
            self.volatile.insert(arm.clone());
        }
        self.pending.insert(arm);
    }

    /// Readback after an uncertain config commit must not discard claims
    /// made while that write was in flight. Disk receipts can only add durable
    /// protection, never erase a still-pending in-memory completion.
    pub fn merge_durable_receipts(&mut self, receipts: Receipts) {
        self.volatile.retain(|arm| !receipts.contains(arm));
        self.consumed.extend(receipts);
    }

    pub fn contains(&self, arm: &Arm) -> bool { self.consumed.contains(arm) }
    pub fn has_pending(&self) -> bool { !self.pending.is_empty() }
    pub fn has_volatile(&self) -> bool { !self.volatile.is_empty() }
    pub fn receipts(&self) -> Receipts { self.consumed.clone() }

    /// A successful settings snapshot contains either the disabled arm or a
    /// newer revision. Both make the old arm safe after restart. A receipt
    /// alone protects restart but keeps retrying the settings write.
    pub fn persisted(&mut self, attempted: &Receipts, receipt_ok: bool, settings_ok: bool) {
        if receipt_ok || settings_ok {
            self.volatile.retain(|arm| !attempted.contains(arm));
        }
        if settings_ok {
            self.pending.retain(|arm| !attempted.contains(arm));
        }
    }
}

/// Readbacks use a new version when a one-shot is consumed, even before its
/// disk write finishes. A stale editor must refresh instead of undoing it.
pub fn consumed_revision(revision: &str) -> String { format!("consumed:{revision}") }

/// Re-arming a one-shot is a new occurrence even in the same wall-clock
/// minute. A cosmetic edit to a repeating alarm must not ring it twice.
pub fn same_scheduled_arm(one_shot: bool, revision: &str, fired_revision: Option<&str>) -> bool {
    !one_shot || fired_revision == Some(revision)
}

pub fn check_revision(expected: &str, current: &str) -> Result<(), &'static str> {
    if expected == current { Ok(()) }
    else { Err("An alarm changed while this list or editor was open. Refresh the alarms and try again.") }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn arm(revision: &str) -> Arm { Arm { alarm_id: "morning".into(), revision: revision.into() } }

    #[test]
    fn both_write_failures_keep_session_protection_and_pending_retry() {
        let mut state = Completions::default();
        state.claim(arm("a"));
        state.persisted(&state.receipts(), false, false);
        assert!(state.contains(&arm("a")));
        assert!(state.has_pending());
        assert!(state.has_volatile());
        // No successful storage means no restart guarantee.
        assert!(!Completions::default().contains(&arm("a")));
    }

    #[test]
    fn receipt_success_survives_restart_and_keeps_settings_retry() {
        let mut state = Completions::default();
        state.claim(arm("a"));
        let disk = state.receipts();
        state.persisted(&disk, true, false);
        assert!(state.has_pending());
        assert!(!state.has_volatile());
        let restarted = Completions::from_receipts(disk);
        assert!(restarted.contains(&arm("a")));
        assert!(!restarted.contains(&arm("edited")));
        assert!(!restarted.contains(&arm("reenabled")));
    }

    #[test]
    fn settings_success_is_sufficient_when_receipt_disk_is_unavailable() {
        let mut state = Completions::default();
        state.claim(arm("a"));
        state.persisted(&state.receipts(), false, true);
        assert!(!state.has_pending());
        assert!(!state.has_volatile());
        assert!(state.contains(&arm("a")));
    }

    #[test]
    fn retry_does_not_complete_a_newer_concurrent_claim() {
        let mut state = Completions::default();
        state.claim(arm("old"));
        let attempted = state.receipts();
        state.claim(arm("new"));
        state.persisted(&attempted, true, true);
        assert!(state.has_pending());
        assert!(state.has_volatile());
        assert!(state.contains(&arm("new")));
    }

    #[test]
    fn reenabling_and_stale_editor_saves_are_distinct() {
        let consumed = consumed_revision("a");
        assert!(check_revision("a", &consumed).is_err());
        assert!(check_revision(&consumed, &consumed).is_ok());
        let mut state = Completions::default();
        state.claim(arm("a"));
        assert!(!state.contains(&arm("new-arm")));
        // Snooze eligibility is independent of these scheduled-arm receipts.
        let mut snoozes = std::collections::HashMap::from([
            ("morning".to_string(), crate::schedule::Snooze::new(100)),
        ]);
        assert_eq!(crate::schedule::next_due_snooze_elapsed(&mut snoozes, 100, 100_000, false).0.as_deref(), Some("morning"));
        assert!(crate::schedule::alarm_may_fire_elapsed(true, false,
            snoozes.get("morning").copied(), 100, 100_000, false));
    }

    #[test]
    fn minute_deduplication_distinguishes_new_one_shot_arms_only() {
        assert!(same_scheduled_arm(true, "old", Some("old")));
        assert!(!same_scheduled_arm(true, "rearmed", Some("old")));
        assert!(!same_scheduled_arm(true, "edited", Some("old")));
        assert!(same_scheduled_arm(true, "", Some("")));
        assert!(same_scheduled_arm(false, "label-edited", Some("old")));
    }

    #[test]
    fn readback_keeps_claims_that_arrived_during_an_uncertain_save() {
        let mut state = Completions::default();
        state.claim(arm("during-save"));
        state.merge_durable_receipts(Receipts::from([arm("older-saved")]));
        assert!(state.contains(&arm("during-save")));
        assert!(state.contains(&arm("older-saved")));
        assert!(state.has_pending());
        assert!(state.has_volatile());
        state.merge_durable_receipts(Receipts::from([arm("during-save")]));
        assert!(state.has_pending());
        assert!(!state.has_volatile());
        state.persisted(&state.receipts(), false, true);
        assert!(!state.has_pending());
    }

    #[test]
    fn legacy_arms_and_receipts_round_trip() {
        let mut state = Completions::default();
        state.claim(arm(""));
        let json = serde_json::to_string(&state.receipts()).unwrap();
        let restarted = Completions::from_receipts(serde_json::from_str(&json).unwrap());
        assert!(restarted.contains(&arm("")));
        assert!(!restarted.contains(&arm("new")));
        assert!(serde_json::from_str::<Receipts>("[{}]").is_err());
    }
}
