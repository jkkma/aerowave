//! When is an alarm due?
//!
//! Days are 0 = Monday .. 6 = Sunday, matching
//! `chrono::Weekday::num_days_from_monday`. An empty day list means "once,
//! at the next occurrence" and so matches every day.

use chrono::{DateTime, Datelike, Duration, NaiveDate, TimeZone, Timelike};
use std::collections::HashMap;

/// How late an alarm may be and still ring after the machine wakes from
/// sleep. Later than this and ringing would only be confusing.
pub const CATCHUP_GRACE_SECS: i64 = 15 * 60;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Snooze {
    pub at: i64,
    ready: bool,
}

impl Snooze {
    pub fn new(at: i64) -> Self {
        Self { at, ready: false }
    }
}

/// Leave due snoozes queued until a ring actually claims one. A slow source
/// resolution can overlap an edit that cancels the occurrence.
pub fn next_due_snooze(
    pending: &mut HashMap<String, Snooze>,
    now: i64,
    busy: bool,
) -> (Option<String>, Vec<String>) {
    let mut expired = Vec::new();
    pending.retain(|id, snooze| {
        // Catch-up grace applies when first observing a deadline after sleep.
        // Once eligible, waiting behind another ring must not expire it.
        if snooze.ready {
            true
        } else if now.saturating_sub(snooze.at) > CATCHUP_GRACE_SECS {
            expired.push(id.clone());
            false
        } else {
            snooze.ready = snooze.at <= now;
            true
        }
    });
    let next = if busy {
        None
    } else {
        pending
            .iter()
            .filter(|(_, snooze)| snooze.ready && snooze.at <= now)
            .min_by(|(id_a, a), (id_b, b)| a.at.cmp(&b.at).then_with(|| id_a.cmp(id_b)))
            .map(|(id, _)| id.clone())
    };
    (next, expired)
}

/// An automatically disabled one-shot may still have a legitimate snooze.
/// Only an explicit enabled-to-disabled transition or removal cancels it.
pub fn cancelled_alarms(previous: &[(&str, bool)], current: &[(&str, bool)]) -> Vec<String> {
    previous
        .iter()
        .filter_map(
            |(id, was_enabled)| match current.iter().find(|(new_id, _)| new_id == id) {
                None => Some((*id).to_string()),
                Some((_, enabled)) if *was_enabled && !enabled => Some((*id).to_string()),
                _ => None,
            },
        )
        .collect()
}

pub fn alarm_may_fire(
    is_snooze: bool,
    enabled: bool,
    snooze: Option<Snooze>,
    now: i64,
    busy: bool,
) -> bool {
    !busy
        && if is_snooze {
            snooze.is_some_and(|s| s.ready && s.at <= now)
        } else {
            enabled
        }
}

pub fn day_matches(days: &[u32], weekday_from_monday: u32) -> bool {
    days.is_empty() || days.contains(&weekday_from_monday)
}

/// A skip belongs to one local calendar date, never to a weekday or an
/// elapsed 24-hour period. Reject noncanonical persisted values harmlessly.
pub fn skip_matches_date(skip_date: Option<&str>, date: NaiveDate) -> bool {
    skip_date.is_some_and(|value| {
        NaiveDate::parse_from_str(value, "%Y-%m-%d")
            .ok()
            .is_some_and(|parsed| parsed == date && parsed.format("%Y-%m-%d").to_string() == value)
    })
}

/// Ordinary alarm saves carry editor data, but skip state belongs to the
/// scheduler. Keep it only when the recurrence itself is unchanged.
pub fn skip_survives_edit(
    old_enabled: bool, old_hour: u32, old_minute: u32, old_days: &[u32],
    new_enabled: bool, new_hour: u32, new_minute: u32, new_days: &[u32],
) -> bool {
    old_enabled && new_enabled && !old_days.is_empty() && !new_days.is_empty()
        && old_hour == new_hour && old_minute == new_minute
        && old_days.iter().all(|day| new_days.contains(day))
        && new_days.iter().all(|day| old_days.contains(day))
}

/// Resolve a requested skip against the regular future occurrence, or an
/// undo against the still-future saved exception. The expected timestamp
/// prevents a stale webview from changing a different week's alarm.
pub fn skip_change<Tz: TimeZone>(
    hour: u32,
    minute: u32,
    days: &[u32],
    current_skip: Option<&str>,
    now: &DateTime<Tz>,
    skip: bool,
    expected_at_ms: i64,
    fired_at_key: impl FnOnce(&str) -> bool,
) -> Result<Option<String>, &'static str> {
    if days.is_empty() {
        return Err("Only repeating alarms can skip an occurrence");
    }
    let target = if skip {
        if future_skipped_occurrence(hour, minute, days, current_skip, now).is_some() {
            return Err("That alarm already has a skipped occurrence");
        }
        next_occurrence(hour, minute, days, now)
    } else {
        future_skipped_occurrence(hour, minute, days, current_skip, now)
    }.ok_or("That occurrence is no longer available")?;
    if target.checked_mul(1000) != Some(expected_at_ms) {
        return Err("That occurrence has changed; refresh the alarm and try again");
    }
    let local = now.timezone().timestamp_opt(target, 0).single()
        .ok_or("That occurrence is outside the supported date range")?;
    if skip && fired_at_key(&local.naive_local().format("%Y-%m-%d %H:%M").to_string()) {
        return Err("That occurrence has already rung");
    }
    Ok(skip.then(|| local.date_naive().format("%Y-%m-%d").to_string()))
}

/// Is this alarm due at exactly this minute?
pub fn due_now<Tz: TimeZone>(hour: u32, minute: u32, days: &[u32], now: &DateTime<Tz>) -> bool {
    due_now_except(hour, minute, days, None, now)
}

pub fn due_now_except<Tz: TimeZone>(hour: u32, minute: u32, days: &[u32], skip_date: Option<&str>, now: &DateTime<Tz>) -> bool {
    now.hour() == hour
        && now.minute() == minute
        && day_matches(days, now.weekday().num_days_from_monday())
        && (days.is_empty() || !skip_matches_date(skip_date, now.date_naive()))
}

/// The next moment this alarm is due, as a unix timestamp, strictly after
/// `from`. None if the day list somehow never comes round.
pub fn next_occurrence<Tz: TimeZone>(
    hour: u32,
    minute: u32,
    days: &[u32],
    from: &DateTime<Tz>,
) -> Option<i64> {
    next_occurrence_except(hour, minute, days, None, from)
}

pub fn next_occurrence_except<Tz: TimeZone>(
    hour: u32,
    minute: u32,
    days: &[u32],
    skip_date: Option<&str>,
    from: &DateTime<Tz>,
) -> Option<i64> {
    let tz = from.timezone();
    // A skipped weekly date may be followed by a missing DST hour. Today may
    // already have passed, so include a third weekly occurrence on day 21.
    for ahead in 0..22 {
        // Calendar days, not 86_400-second ones: adding a fixed span to an
        // instant skips or repeats a local date across a DST transition, and
        // the skipped date is the one an alarm would have rung on.
        let date = from.date_naive() + Duration::days(ahead);
        if !day_matches(days, date.weekday().num_days_from_monday())
            || (!days.is_empty() && skip_matches_date(skip_date, date)) {
            continue;
        }
        let Some(naive) = date.and_hms_opt(hour, minute, 0) else {
            continue;
        };
        // A local time can be missing (the hour a clock springs forward) or
        // doubled (the hour it falls back). Skip the first, take the earlier
        // of the second so the alarm rings once.
        let Some(when) = tz.from_local_datetime(&naive).earliest() else {
            continue;
        };
        if when.timestamp() > from.timestamp() {
            return Some(when.timestamp());
        }
    }
    None
}

/// The future scheduled instant represented by a stored skip, if it still
/// exists and the alarm's selected weekdays still include that date.
pub fn future_skipped_occurrence<Tz: TimeZone>(
    hour: u32,
    minute: u32,
    days: &[u32],
    skip_date: Option<&str>,
    from: &DateTime<Tz>,
) -> Option<i64> {
    if days.is_empty() { return None; }
    let value = skip_date?;
    let date = NaiveDate::parse_from_str(value, "%Y-%m-%d").ok()?;
    if !skip_matches_date(Some(value), date)
        || !day_matches(days, date.weekday().num_days_from_monday()) { return None; }
    let naive = date.and_hms_opt(hour, minute, 0)?;
    let at = from.timezone().from_local_datetime(&naive).earliest()?;
    (at.timestamp() > from.timestamp()).then_some(at.timestamp())
}

/// The most recent moment this alarm was due, at or before `now`. Used to
/// work out whether its turn passed while the machine was asleep.
pub fn last_occurrence_before<Tz: TimeZone>(
    hour: u32,
    minute: u32,
    days: &[u32],
    now: &DateTime<Tz>,
) -> Option<i64> {
    last_occurrence_before_except(hour, minute, days, None, now)
}

pub fn last_occurrence_before_except<Tz: TimeZone>(
    hour: u32,
    minute: u32,
    days: &[u32],
    skip_date: Option<&str>,
    now: &DateTime<Tz>,
) -> Option<i64> {
    let tz = now.timezone();
    for back in 0..2 {
        let date = now.date_naive() - Duration::days(back);
        if !day_matches(days, date.weekday().num_days_from_monday())
            || (!days.is_empty() && skip_matches_date(skip_date, date)) {
            continue;
        }
        let Some(naive) = date.and_hms_opt(hour, minute, 0) else {
            continue;
        };
        let Some(when) = tz.from_local_datetime(&naive).earliest() else {
            continue;
        };
        if when.timestamp() <= now.timestamp() {
            return Some(when.timestamp());
        }
    }
    None
}

/// Did this alarm's moment pass unnoticed while the machine was asleep?
pub fn missed_while_asleep<Tz: TimeZone>(
    hour: u32,
    minute: u32,
    days: &[u32],
    now: &DateTime<Tz>,
    last_tick: i64,
) -> bool {
    missed_while_asleep_except(hour, minute, days, None, now, last_tick)
}

pub fn missed_while_asleep_except<Tz: TimeZone>(
    hour: u32,
    minute: u32,
    days: &[u32],
    skip_date: Option<&str>,
    now: &DateTime<Tz>,
    last_tick: i64,
) -> bool {
    let now_secs = now.timestamp();
    // Even a short suspension can skip the whole alarm minute. Crossing an
    // unobserved occurrence, rather than the gap's length, proves it was missed.
    if last_tick <= 0 || now_secs <= last_tick {
        return false;
    }
    match last_occurrence_before_except(hour, minute, days, skip_date, now) {
        Some(t) => t > last_tick && t <= now_secs && now_secs - t <= CATCHUP_GRACE_SECS,
        None => false,
    }
}

/// Recheck alarm priority immediately before a PC power action commits. The
/// wall clock or saved alarms may change after the ordinary scheduler tick;
/// `next_occurrence` skips the current minute and cannot answer this question.
pub fn unclaimed_due_alarm<Tz: TimeZone>(
    hour: u32,
    minute: u32,
    days: &[u32],
    now: &DateTime<Tz>,
    last_tick: i64,
    last_fired_key: Option<&str>,
) -> bool {
    unclaimed_due_alarm_except(hour, minute, days, None, now, last_tick, last_fired_key)
}

pub fn unclaimed_due_alarm_except<Tz: TimeZone>(
    hour: u32,
    minute: u32,
    days: &[u32],
    skip_date: Option<&str>,
    now: &DateTime<Tz>,
    last_tick: i64,
    last_fired_key: Option<&str>,
) -> bool {
    let current_key = now.naive_local().format("%Y-%m-%d %H:%M").to_string();
    if last_fired_key == Some(current_key.as_str()) {
        return false;
    }
    due_now_except(hour, minute, days, skip_date, now)
        || missed_while_asleep_except(hour, minute, days, skip_date, now, last_tick)
}

#[cfg(test)]
mod tests {
    use super::*;
    use chrono::{FixedOffset, NaiveDate};

    #[test]
    fn overdue_snoozes_wait_for_each_other_and_keep_their_deadlines() {
        let mut pending = HashMap::from([
            ("first".into(), Snooze::new(100)),
            ("second".into(), Snooze::new(110)),
        ]);
        assert_eq!(
            next_due_snooze(&mut pending, 120, false).0.as_deref(),
            Some("first")
        );
        assert_eq!(pending.len(), 2);
        pending.remove("first");
        assert_eq!(next_due_snooze(&mut pending, 121, true).0, None);
        assert_eq!(pending.get("second").unwrap().at, 110);
        // The first alarm can ring longer than the catch-up grace period.
        assert_eq!(next_due_snooze(&mut pending, 2000, true).0, None);
        assert_eq!(
            next_due_snooze(&mut pending, 2001, false).0.as_deref(),
            Some("second")
        );
        assert!(alarm_may_fire(
            true,
            false,
            pending.get("second").copied(),
            2001,
            false
        ));
    }

    #[test]
    fn snoozes_first_observed_after_the_grace_period_expire() {
        let mut pending = HashMap::from([
            ("old".into(), Snooze::new(1)),
            ("future".into(), Snooze::new(2000)),
        ]);
        assert_eq!(
            next_due_snooze(&mut pending, 1000, true),
            (None, vec!["old".into()])
        );
        assert_eq!(pending.len(), 1);
    }

    #[test]
    fn explicit_disable_and_removal_cancel_but_one_shot_autodisable_does_not() {
        let old = [("repeat", true), ("one-shot", false), ("deleted", false)];
        let new = [("repeat", false), ("one-shot", false)];
        assert_eq!(cancelled_alarms(&old, &new), vec!["repeat", "deleted"]);
    }

    #[test]
    fn a_cancelled_or_postponed_snooze_cannot_fire_after_source_resolution() {
        assert!(!alarm_may_fire(true, true, None, 100, false));
        assert!(!alarm_may_fire(
            true,
            true,
            Some(Snooze::new(110)),
            100,
            false
        ));
        assert!(!alarm_may_fire(false, false, None, 100, false));
        let ready = Snooze {
            at: 90,
            ready: true,
        };
        assert!(alarm_may_fire(true, false, Some(ready), 100, false));
        assert!(!alarm_may_fire(true, false, Some(ready), 100, true));
    }

    /// The local calendar date an instant falls on, in a given zone.
    fn next_local_date<Tz: TimeZone>(tz: &Tz, unix: i64) -> chrono::NaiveDate {
        tz.timestamp_opt(unix, 0).unwrap().date_naive()
    }

    /// 2026-09-07 is a Monday.
    fn at(day: u32, hour: u32, minute: u32) -> DateTime<FixedOffset> {
        let tz = FixedOffset::east_opt(0).unwrap();
        tz.from_local_datetime(
            &NaiveDate::from_ymd_opt(2026, 9, day)
                .unwrap()
                .and_hms_opt(hour, minute, 0)
                .unwrap(),
        )
        .unwrap()
    }

    #[test]
    fn an_empty_day_list_matches_every_day() {
        for d in 0..7 {
            assert!(day_matches(&[], d));
        }
    }

    #[test]
    fn weekday_alarms_skip_the_weekend() {
        let weekdays = [0, 1, 2, 3, 4];
        assert!(day_matches(&weekdays, 0));
        assert!(day_matches(&weekdays, 4));
        assert!(!day_matches(&weekdays, 5));
        assert!(!day_matches(&weekdays, 6));
    }

    #[test]
    fn due_only_in_its_own_minute() {
        let now = at(7, 7, 30); // Monday 07:30
        assert!(due_now(7, 30, &[], &now));
        assert!(!due_now(7, 31, &[], &now));
        assert!(!due_now(8, 30, &[], &now));
    }

    #[test]
    fn due_respects_the_day_list() {
        let monday = at(7, 7, 30);
        assert!(due_now(7, 30, &[0], &monday));
        assert!(!due_now(7, 30, &[1], &monday));
    }

    #[test]
    fn the_next_occurrence_is_tomorrow_once_today_has_passed() {
        let now = at(7, 8, 0); // Monday 08:00, alarm was at 07:30
        let next = next_occurrence(7, 30, &[], &now).unwrap();
        assert_eq!(next - now.timestamp(), 23 * 3600 + 30 * 60);
    }

    #[test]
    fn the_next_occurrence_is_later_today_when_it_is_still_ahead() {
        let now = at(7, 6, 0);
        let next = next_occurrence(7, 30, &[], &now).unwrap();
        assert_eq!(next - now.timestamp(), 90 * 60);
    }

    #[test]
    fn the_current_minute_does_not_count_as_next() {
        let now = at(7, 7, 30);
        let next = next_occurrence(7, 30, &[], &now).unwrap();
        assert_eq!(next - now.timestamp(), 24 * 3600);
    }

    #[test]
    fn a_weekend_alarm_from_monday_lands_on_saturday() {
        let now = at(7, 9, 0); // Monday
        let next = next_occurrence(9, 0, &[5, 6], &now).unwrap();
        assert_eq!(next - now.timestamp(), 5 * 24 * 3600); // Saturday 09:00
    }

    #[test]
    fn a_weekly_alarm_after_its_time_recurs_the_following_week() {
        let now = at(7, 9, 0); // Monday
        let next = next_occurrence(7, 30, &[0], &now).unwrap();
        assert_eq!(next, at(14, 7, 30).timestamp());
    }

    #[test]
    fn a_missed_alarm_inside_the_grace_window_is_caught_up() {
        let now = at(7, 7, 35); // woke at 07:35
        let last_tick = at(7, 7, 20).timestamp(); // asleep since 07:20
        assert!(missed_while_asleep(7, 30, &[], &now, last_tick));
    }

    #[test]
    fn a_short_suspend_that_skips_the_alarm_minute_is_caught_up() {
        let now = at(7, 7, 31);
        for seconds_asleep in [61, 75, 90] {
            let last_tick = now.timestamp() - seconds_asleep;
            assert!(missed_while_asleep(7, 30, &[], &now, last_tick));
        }
    }

    #[test]
    fn an_alarm_missed_by_hours_is_left_alone() {
        let now = at(7, 12, 0);
        let last_tick = at(7, 6, 0).timestamp();
        assert!(!missed_while_asleep(7, 30, &[], &now, last_tick));
    }

    #[test]
    fn an_alarm_observed_before_a_gap_is_not_replayed() {
        let now = at(7, 7, 31);
        for seconds_after_alarm in [0, 15] {
            let last_tick = at(7, 7, 30).timestamp() + seconds_after_alarm;
            assert!(!missed_while_asleep(7, 30, &[], &now, last_tick));
        }
    }

    #[test]
    fn catchup_requires_a_previous_tick_and_forward_clock_progress() {
        let now = at(7, 7, 31);
        for last_tick in [0, now.timestamp(), now.timestamp() + 60] {
            assert!(!missed_while_asleep(7, 30, &[], &now, last_tick));
        }
    }

    #[test]
    fn final_power_check_blocks_an_unclaimed_alarm_in_its_minute() {
        let now = at(7, 7, 30) + Duration::seconds(10);
        assert!(unclaimed_due_alarm(
            7,
            30,
            &[],
            &now,
            now.timestamp() - 1,
            None
        ));
        assert!(!unclaimed_due_alarm(
            7,
            30,
            &[],
            &now,
            now.timestamp() - 1,
            Some("2026-09-07 07:30")
        ));
        assert!(!unclaimed_due_alarm(
            7,
            30,
            &[1],
            &now,
            now.timestamp() - 1,
            None
        ));
    }

    #[test]
    fn final_power_check_catches_a_crossed_minute_within_grace() {
        let before = at(7, 7, 29) + Duration::seconds(50);
        let after = at(7, 7, 31) + Duration::seconds(10);
        assert!(unclaimed_due_alarm(
            7,
            30,
            &[],
            &after,
            before.timestamp(),
            None
        ));
        assert!(!unclaimed_due_alarm(
            7,
            30,
            &[],
            &after,
            before.timestamp(),
            Some("2026-09-07 07:31")
        ));
        assert!(!unclaimed_due_alarm(
            7,
            30,
            &[1],
            &after,
            before.timestamp(),
            None
        ));
        let too_late = at(7, 7, 46);
        assert!(!unclaimed_due_alarm(
            7,
            30,
            &[],
            &too_late,
            before.timestamp(),
            None
        ));
    }

    #[test]
    fn catchup_stops_at_the_grace_boundary() {
        let last_tick = at(7, 7, 29).timestamp();
        let boundary = at(7, 7, 45);
        assert!(missed_while_asleep(7, 30, &[], &boundary, last_tick));
        assert!(!missed_while_asleep(
            7,
            30,
            &[],
            &(boundary + Duration::seconds(1)),
            last_tick
        ));
    }

    #[test]
    fn skip_suppresses_due_catchup_and_final_power_claim_on_its_local_date() {
        let skipped = Some("2026-09-07");
        let due = at(7, 7, 30);
        let woke = at(7, 7, 35);
        let last_tick = at(7, 7, 20).timestamp();
        assert!(!due_now_except(7, 30, &[0], skipped, &due));
        assert!(!missed_while_asleep_except(7, 30, &[0], skipped, &woke, last_tick));
        assert!(!unclaimed_due_alarm_except(7, 30, &[0], skipped, &due, last_tick, None));
        assert!(!unclaimed_due_alarm_except(7, 30, &[0], skipped, &woke, last_tick, None));
        assert!(due_now_except(7, 30, &[0], skipped, &at(14, 7, 30)));
        // A one-shot never consumes a persisted recurring-only exception.
        assert!(due_now_except(7, 30, &[], skipped, &due));
    }

    #[test]
    fn skip_omits_one_week_then_expires_without_affecting_later_weeks() {
        let before = at(6, 12, 0);
        assert_eq!(next_occurrence_except(7, 30, &[0], Some("2026-09-07"), &before),
            Some(at(14, 7, 30).timestamp()));
        assert_eq!(future_skipped_occurrence(7, 30, &[0], Some("2026-09-07"), &before),
            Some(at(7, 7, 30).timestamp()));
        let after = at(7, 8, 0);
        assert_eq!(future_skipped_occurrence(7, 30, &[0], Some("2026-09-07"), &after), None);
        assert_eq!(next_occurrence_except(7, 30, &[0], Some("2026-09-07"), &after),
            Some(at(14, 7, 30).timestamp()));
        assert!(!skip_matches_date(Some("2026-9-07"), at(7, 7, 30).date_naive()));
        assert!(!skip_matches_date(Some("2026-09-31"), at(7, 7, 30).date_naive()));
    }

    #[test]
    fn skip_and_undo_require_the_expected_future_instant() {
        let now = at(6, 12, 0);
        let target = at(7, 7, 30).timestamp_millis();
        assert!(skip_change(7, 30, &[0], None, &now, true, target + 60_000, |_| false).is_err());
        assert_eq!(skip_change(7, 30, &[0], None, &now, true, target, |_| false),
            Ok(Some("2026-09-07".into())));
        assert_eq!(skip_change(7, 30, &[0], None, &now, true, target, |_| true),
            Err("That occurrence has already rung"));
        let later_target = at(7, 20, 0).timestamp_millis();
        assert_eq!(skip_change(20, 0, &[0], None, &now, true, later_target,
            |key| key == "2026-09-07 07:00"), Ok(Some("2026-09-07".into())));
        assert_eq!(skip_change(20, 0, &[0], None, &now, true, later_target,
            |key| key == "2026-09-07 20:00"), Err("That occurrence has already rung"));
        assert!(skip_change(7, 30, &[0], Some("2026-09-07"), &now, true, target, |_| false).is_err());
        assert_eq!(skip_change(7, 30, &[0], Some("2026-09-07"), &now, false, target, |_| false), Ok(None));
        assert!(skip_change(7, 30, &[0], Some("2026-09-07"), &at(7, 8, 0), false, target, |_| false).is_err());
        assert!(skip_change(7, 30, &[], None, &now, true, target, |_| false).is_err());
    }

    #[test]
    fn editor_save_keeps_skip_only_for_the_same_enabled_recurrence() {
        assert!(skip_survives_edit(true, 7, 30, &[0, 2], true, 7, 30, &[2, 0]));
        assert!(!skip_survives_edit(true, 7, 30, &[0, 2], true, 7, 31, &[2, 0]));
        assert!(!skip_survives_edit(true, 7, 30, &[0, 2], true, 7, 30, &[0]));
        assert!(!skip_survives_edit(true, 7, 30, &[0, 2], false, 7, 30, &[0, 2]));
        assert!(!skip_survives_edit(false, 7, 30, &[0, 2], true, 7, 30, &[0, 2]));
        assert!(!skip_survives_edit(true, 7, 30, &[], true, 7, 30, &[]));
    }

    #[test]
    fn skip_targets_the_earlier_fall_dst_instant_and_respects_a_missing_spring_hour() {
        use chrono_tz::Europe::Paris;
        let before_fall = Paris.with_ymd_and_hms(2026, 10, 24, 12, 0, 0).unwrap();
        let repeated = Paris.with_ymd_and_hms(2026, 10, 25, 2, 30, 0).earliest().unwrap();
        assert_eq!(skip_change(2, 30, &[6], None, &before_fall, true,
            repeated.timestamp_millis(), |_| false), Ok(Some("2026-10-25".into())));
        assert_eq!(next_occurrence_except(2, 30, &[6], Some("2026-10-25"), &before_fall),
            Some(Paris.with_ymd_and_hms(2026, 11, 1, 2, 30, 0).unwrap().timestamp()));
        let before_spring = Paris.with_ymd_and_hms(2026, 3, 28, 23, 30, 0).unwrap();
        assert_eq!(future_skipped_occurrence(2, 30, &[6], Some("2026-03-29"), &before_spring), None);
        assert_eq!(next_occurrence_except(2, 30, &[6], Some("2026-03-29"), &before_spring),
            Some(Paris.with_ymd_and_hms(2026, 4, 5, 2, 30, 0).unwrap().timestamp()));
    }

    #[test]
    fn skipped_weekly_date_followed_by_a_dst_gap_still_finds_the_third_week() {
        use chrono_tz::Europe::Paris;
        let from = Paris.with_ymd_and_hms(2026, 3, 16, 12, 0, 0).unwrap();
        let skipped = Paris.with_ymd_and_hms(2026, 3, 22, 2, 30, 0).unwrap();
        let following = Paris.with_ymd_and_hms(2026, 4, 5, 2, 30, 0).unwrap();
        assert_eq!(skip_change(2, 30, &[6], None, &from, true,
            skipped.timestamp_millis(), |_| false), Ok(Some("2026-03-22".into())));
        assert_eq!(next_occurrence_except(2, 30, &[6], Some("2026-03-22"), &from),
            Some(following.timestamp()));
    }

    /// Europe/Paris springs forward on the last Sunday in March: 02:00 local
    /// jumps straight to 03:00. Walking instants rather than dates used to
    /// skip a whole calendar day around a transition.
    #[test]
    fn a_transition_day_is_not_skipped() {
        use chrono_tz::Europe::Paris;
        // Saturday 2026-03-28 23:30, the evening before the spring forward.
        let saturday = Paris
            .from_local_datetime(
                &NaiveDate::from_ymd_opt(2026, 3, 28)
                    .unwrap()
                    .and_hms_opt(23, 30, 0)
                    .unwrap(),
            )
            .unwrap();
        // A 07:00 alarm must land on Sunday the 29th, the transition day.
        let next = next_occurrence(7, 0, &[], &saturday).expect("daily alarm recurs");
        let landed = next_local_date(&Paris, next);
        assert_eq!(landed, NaiveDate::from_ymd_opt(2026, 3, 29).unwrap());
    }

    /// An alarm inside the vanished hour has no instant to ring at, so the
    /// next real one is the day after - never "no alarm at all".
    #[test]
    fn an_alarm_in_the_vanished_hour_still_resolves() {
        use chrono_tz::Europe::Paris;
        let saturday = Paris
            .from_local_datetime(
                &NaiveDate::from_ymd_opt(2026, 3, 28)
                    .unwrap()
                    .and_hms_opt(23, 30, 0)
                    .unwrap(),
            )
            .unwrap();
        // 02:30 does not exist on the 29th in Paris.
        let next = next_occurrence(2, 30, &[], &saturday).expect("must still find one");
        assert_eq!(
            next_local_date(&Paris, next),
            NaiveDate::from_ymd_opt(2026, 3, 30).unwrap()
        );
    }

    #[test]
    fn a_weekly_alarm_skips_a_vanished_hour_to_the_next_matching_day() {
        use chrono_tz::Europe::Paris;
        let expected = Paris.with_ymd_and_hms(2026, 4, 5, 2, 30, 0).unwrap();
        for from in [
            // The next eligible Sunday is missing its alarm hour, even when
            // searching immediately after the previous week's occurrence.
            Paris.with_ymd_and_hms(2026, 3, 22, 2, 30, 0).unwrap(),
            Paris.with_ymd_and_hms(2026, 3, 28, 23, 30, 0).unwrap(),
            Paris.with_ymd_and_hms(2026, 3, 29, 1, 30, 0).unwrap(),
        ] {
            assert_eq!(
                next_occurrence(2, 30, &[6], &from),
                Some(expected.timestamp())
            );
        }
    }

    #[test]
    fn a_vanished_alarm_hour_still_uses_the_first_valid_selected_day() {
        use chrono_tz::Europe::Paris;
        let from = Paris.with_ymd_and_hms(2026, 3, 28, 23, 30, 0).unwrap();
        let monday = Paris.with_ymd_and_hms(2026, 3, 30, 2, 30, 0).unwrap();
        assert_eq!(
            next_occurrence(2, 30, &[0, 6], &from),
            Some(monday.timestamp())
        );
    }

    #[test]
    fn a_weekly_alarm_in_a_repeated_hour_uses_only_the_earlier_instant() {
        use chrono_tz::Europe::Paris;
        let repeated = Paris.with_ymd_and_hms(2026, 10, 25, 2, 30, 0);
        let earlier = repeated.earliest().unwrap();
        let later = repeated.latest().unwrap();
        assert!(earlier < later);
        let saturday = Paris.with_ymd_and_hms(2026, 10, 24, 23, 30, 0).unwrap();
        assert_eq!(
            next_occurrence(2, 30, &[6], &saturday),
            Some(earlier.timestamp())
        );
        let following = Paris.with_ymd_and_hms(2026, 11, 1, 2, 30, 0).unwrap();
        assert_eq!(
            next_occurrence(2, 30, &[6], &earlier),
            Some(following.timestamp())
        );
    }

    /// Just before midnight, evaluated just after it: the alarm that has only
    /// recently passed is on yesterday's date.
    #[test]
    fn a_late_alarm_is_caught_up_across_midnight() {
        let now = at(8, 0, 5); // 00:05 on the 8th
        let last_tick = at(7, 23, 50).timestamp(); // asleep since 23:50 on the 7th
        assert!(missed_while_asleep(23, 55, &[], &now, last_tick));
    }

    #[test]
    fn nothing_is_missed_when_the_alarm_was_not_due() {
        let now = at(7, 7, 35);
        let last_tick = at(7, 7, 20).timestamp();
        // Tuesday-only alarm, and today is Monday.
        assert!(!missed_while_asleep(7, 30, &[1], &now, last_tick));
    }
}
