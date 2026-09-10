//! Convert instants with the same timezone rules the alarm scheduler uses.

use chrono::{Datelike, Offset, TimeZone, Timelike};

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct WallTime {
    pub at_ms: i64,
    pub year: i32,
    pub month: u32,
    pub day: u32,
    pub hour: u32,
    pub minute: u32,
    pub second: u32,
}

pub fn local_time<Tz: TimeZone>(zone: &Tz, at_ms: i64) -> Option<WallTime> {
    let instant = zone.timestamp_millis_opt(at_ms).single()?;
    // A valid UTC instant can still fall outside chrono's calendar range
    // after its local offset is applied.
    let local = instant
        .naive_utc()
        .checked_add_offset(instant.offset().fix())?;
    Some(WallTime {
        at_ms,
        year: local.year(),
        month: local.month(),
        day: local.day(),
        hour: local.hour(),
        minute: local.minute(),
        second: local.second(),
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use chrono::{DateTime, FixedOffset, Utc};
    use chrono_tz::America::New_York;

    fn utc_ms(value: &str) -> i64 {
        DateTime::parse_from_rfc3339(value)
            .unwrap()
            .timestamp_millis()
    }

    #[test]
    fn fixed_offset_preserves_the_instant_and_crosses_a_date_boundary() {
        let at_ms = utc_ms("2026-01-01T02:04:05.678Z");
        assert_eq!(
            local_time(&FixedOffset::west_opt(3 * 3600).unwrap(), at_ms),
            Some(WallTime {
                at_ms,
                year: 2025,
                month: 12,
                day: 31,
                hour: 23,
                minute: 4,
                second: 5,
            })
        );
    }

    #[test]
    fn negative_timestamps_use_the_previous_second() {
        assert_eq!(
            local_time(&Utc, -1),
            Some(WallTime {
                at_ms: -1,
                year: 1969,
                month: 12,
                day: 31,
                hour: 23,
                minute: 59,
                second: 59,
            })
        );
    }

    #[test]
    fn spring_transition_uses_the_offset_at_each_instant() {
        let before = local_time(&New_York, utc_ms("2026-03-08T06:59:59Z")).unwrap();
        let after = local_time(&New_York, utc_ms("2026-03-08T07:00:00Z")).unwrap();
        assert_eq!((before.year, before.month, before.day), (2026, 3, 8));
        assert_eq!((before.hour, before.minute, before.second), (1, 59, 59));
        assert_eq!((after.year, after.month, after.day), (2026, 3, 8));
        assert_eq!((after.hour, after.minute, after.second), (3, 0, 0));
    }

    #[test]
    fn fall_transition_keeps_repeated_wall_times_as_distinct_instants() {
        let first_ms = utc_ms("2026-11-01T05:30:00Z");
        let second_ms = utc_ms("2026-11-01T06:30:00Z");
        let first = local_time(&New_York, first_ms).unwrap();
        let second = local_time(&New_York, second_ms).unwrap();
        assert_eq!((first.year, first.month, first.day), (2026, 11, 1));
        assert_eq!((first.hour, first.minute, first.second), (1, 30, 0));
        assert_eq!(
            second,
            WallTime {
                at_ms: second_ms,
                ..first
            }
        );
        assert_eq!(second.at_ms - first.at_ms, 3_600_000);
    }

    #[test]
    fn invalid_timestamp_bounds_return_none() {
        for at_ms in [i64::MIN, i64::MAX] {
            assert_eq!(local_time(&Utc, at_ms), None);
        }
    }

    #[test]
    fn offsets_beyond_calendar_bounds_return_none() {
        let east = FixedOffset::east_opt(3600).unwrap();
        let west = FixedOffset::west_opt(3600).unwrap();
        assert_eq!(
            local_time(&east, DateTime::<Utc>::MAX_UTC.timestamp_millis()),
            None
        );
        assert_eq!(
            local_time(&west, DateTime::<Utc>::MIN_UTC.timestamp_millis()),
            None
        );
    }
}
