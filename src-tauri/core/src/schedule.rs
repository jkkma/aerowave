//! When is an alarm due?
//!
//! Days are 0 = Monday .. 6 = Sunday, matching
//! `chrono::Weekday::num_days_from_monday`. An empty day list means "once,
//! at the next occurrence" and so matches every day.

use chrono::{DateTime, Datelike, Duration, TimeZone, Timelike};

/// How late an alarm may be and still ring after the machine wakes from
/// sleep. Later than this and ringing would only be confusing.
pub const CATCHUP_GRACE_SECS: i64 = 15 * 60;

/// A gap between ticks longer than this means the machine was asleep.
pub const SLEEP_GAP_SECS: i64 = 90;

pub fn day_matches(days: &[u32], weekday_from_monday: u32) -> bool {
    days.is_empty() || days.contains(&weekday_from_monday)
}

/// Is this alarm due at exactly this minute?
pub fn due_now<Tz: TimeZone>(hour: u32, minute: u32, days: &[u32], now: &DateTime<Tz>) -> bool {
    now.hour() == hour
        && now.minute() == minute
        && day_matches(days, now.weekday().num_days_from_monday())
}

/// The next moment this alarm is due, as a unix timestamp, strictly after
/// `from`. None if the day list somehow never comes round.
pub fn next_occurrence<Tz: TimeZone>(
    hour: u32,
    minute: u32,
    days: &[u32],
    from: &DateTime<Tz>,
) -> Option<i64> {
    let tz = from.timezone();
    for ahead in 0..8 {
        // Calendar days, not 86_400-second ones: adding a fixed span to an
        // instant skips or repeats a local date across a DST transition, and
        // the skipped date is the one an alarm would have rung on.
        let date = from.date_naive() + Duration::days(ahead);
        if !day_matches(days, date.weekday().num_days_from_monday()) {
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

/// The most recent moment this alarm was due, at or before `now`. Used to
/// work out whether its turn passed while the machine was asleep.
pub fn last_occurrence_before<Tz: TimeZone>(
    hour: u32,
    minute: u32,
    days: &[u32],
    now: &DateTime<Tz>,
) -> Option<i64> {
    let tz = now.timezone();
    for back in 0..2 {
        let date = now.date_naive() - Duration::days(back);
        if !day_matches(days, date.weekday().num_days_from_monday()) {
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
    let now_secs = now.timestamp();
    if last_tick <= 0 || now_secs - last_tick <= SLEEP_GAP_SECS {
        return false;
    }
    match last_occurrence_before(hour, minute, days, now) {
        Some(t) => t > last_tick && t <= now_secs && now_secs - t <= CATCHUP_GRACE_SECS,
        None => false,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use chrono::{FixedOffset, NaiveDate};

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
    fn a_missed_alarm_inside_the_grace_window_is_caught_up() {
        let now = at(7, 7, 35); // woke at 07:35
        let last_tick = at(7, 7, 20).timestamp(); // asleep since 07:20
        assert!(missed_while_asleep(7, 30, &[], &now, last_tick));
    }

    #[test]
    fn an_alarm_missed_by_hours_is_left_alone() {
        let now = at(7, 12, 0);
        let last_tick = at(7, 6, 0).timestamp();
        assert!(!missed_while_asleep(7, 30, &[], &now, last_tick));
    }

    #[test]
    fn a_short_gap_is_not_treated_as_sleep() {
        let now = at(7, 7, 31);
        let last_tick = at(7, 7, 30).timestamp() + 15;
        assert!(!missed_while_asleep(7, 30, &[], &now, last_tick));
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
