//! Power capability interpretation and the clock conversion shared with the
//! native adapter. Raw operating-system calls stay in the desktop crate.

use serde::Serialize;

#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PowerCapabilities {
    pub sleep_supported: bool,
    pub shutdown_supported: bool,
    pub wake_supported: bool,
    pub wake_allowed: Option<bool>,
    pub on_battery: Option<bool>,
    pub message: String,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, PartialOrd, Ord)]
pub enum SleepState {
    Sleep1,
    Sleep2,
    Sleep3,
    Hibernate,
    PowerOff,
}

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub enum WakePolicy {
    #[default]
    Unknown,
    Disabled,
    Enabled,
    ImportantOnly,
}

#[derive(Clone, Copy, Debug, Default)]
pub struct PowerFacts {
    pub known: bool,
    pub sleep_s1: bool,
    pub sleep_s2: bool,
    pub sleep_s3: bool,
    pub hibernate: bool,
    pub rtc_wake: Option<SleepState>,
    pub modern_standby: bool,
    pub wake_alarm_present: bool,
    pub on_battery: Option<bool>,
    pub policy: WakePolicy,
}

/// Windows absolute timer deadlines are positive 100 ns ticks since 1601.
/// Zero and negative values would instead describe an immediate/relative timer.
pub fn unix_ms_to_filetime(at_ms: i64) -> Option<i64> {
    at_ms
        .checked_add(11_644_473_600_000)
        .and_then(|value| value.checked_mul(10_000))
        .filter(|value| *value > 0)
}

pub fn classify_capabilities(facts: PowerFacts) -> PowerCapabilities {
    let sleep_supported =
        facts.known && (facts.sleep_s1 || facts.sleep_s2 || facts.sleep_s3 || facts.modern_standby);
    // A machine capable of S3 normally sleeps there. RTC support for a
    // shallower state alone must not be presented as support for that sleep.
    let deepest = if facts.sleep_s3 {
        Some(SleepState::Sleep3)
    } else if facts.sleep_s2 {
        Some(SleepState::Sleep2)
    } else if facts.sleep_s1 {
        Some(SleepState::Sleep1)
    } else if facts.hibernate {
        Some(SleepState::Hibernate)
    } else {
        None
    };
    let rtc_wake = match (deepest, facts.rtc_wake) {
        (Some(sleep), Some(wake)) => wake >= sleep,
        _ => false,
    };
    let wake_supported =
        facts.known && (rtc_wake || (facts.modern_standby && facts.wake_alarm_present));
    let supply = match facts.on_battery {
        Some(true) => "on battery",
        Some(false) => "while plugged in",
        None => "for the current power source",
    };
    let (wake_allowed, mut message) = if !facts.known {
        (
            None,
            "Windows could not report this PC's sleep and wake capabilities.".to_string(),
        )
    } else if !wake_supported {
        (
            Some(false),
            "This PC does not report support for waking from sleep with an alarm timer."
                .to_string(),
        )
    } else {
        match facts.policy {
            WakePolicy::Disabled => (Some(false), format!("Windows wake timers are disabled {supply}. Choose Enable under Sleep > Allow wake timers for Aerowave alarms.")),
            WakePolicy::ImportantOnly => (Some(false), format!("Windows allows only important wake timers {supply}. Choose Enable under Sleep > Allow wake timers for Aerowave alarms.")),
            WakePolicy::Enabled if facts.modern_standby => (None, format!("Windows wake timers are enabled {supply}.")),
            WakePolicy::Enabled => (Some(true), format!("Windows wake timers are enabled {supply}. Keep Aerowave running, including in the tray, for alarms to wake this PC.")),
            WakePolicy::Unknown => (None, format!("Windows could not confirm whether alarm wake timers are allowed {supply}. Check Sleep > Allow wake timers in your power plan.")),
        }
    };
    if facts.modern_standby {
        message.push_str(" This PC uses Modern Standby, which can pause desktop apps; automatic alarm wake is not guaranteed.");
    }
    message.push_str(" Alarms cannot turn on a shut-down PC.");
    PowerCapabilities {
        sleep_supported,
        shutdown_supported: true,
        wake_supported,
        wake_allowed,
        on_battery: facts.on_battery,
        message,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn traditional() -> PowerFacts {
        PowerFacts {
            known: true,
            sleep_s3: true,
            rtc_wake: Some(SleepState::Hibernate),
            on_battery: Some(false),
            policy: WakePolicy::Enabled,
            ..PowerFacts::default()
        }
    }

    #[test]
    fn filetime_epoch_and_millisecond_precision() {
        assert_eq!(unix_ms_to_filetime(0), Some(116_444_736_000_000_000));
        assert_eq!(unix_ms_to_filetime(1), Some(116_444_736_000_010_000));
        assert_eq!(unix_ms_to_filetime(-1), Some(116_444_735_999_990_000));
        assert_eq!(unix_ms_to_filetime(-11_644_473_599_999), Some(10_000));
    }

    #[test]
    fn rejects_relative_or_overflowing_filetime_deadlines() {
        for deadline in [-11_644_473_600_000, -11_644_473_600_001, i64::MIN, i64::MAX] {
            assert_eq!(unix_ms_to_filetime(deadline), None);
        }
        let largest = i64::MAX / 10_000 - 11_644_473_600_000;
        assert!(unix_ms_to_filetime(largest).is_some());
        assert_eq!(unix_ms_to_filetime(largest + 1), None);
    }

    #[test]
    fn wake_policy_distinguishes_disabled_important_and_unknown() {
        for (policy, allowed, phrase) in [
            (WakePolicy::Disabled, Some(false), "disabled"),
            (WakePolicy::ImportantOnly, Some(false), "only important"),
            (WakePolicy::Unknown, None, "could not confirm"),
            (WakePolicy::Enabled, Some(true), "enabled"),
        ] {
            let result = classify_capabilities(PowerFacts {
                policy,
                ..traditional()
            });
            assert!(result.wake_supported);
            assert_eq!(result.wake_allowed, allowed);
            assert!(result.message.contains(phrase));
        }
    }

    #[test]
    fn modern_standby_warning_survives_every_policy() {
        for policy in [
            WakePolicy::Disabled,
            WakePolicy::ImportantOnly,
            WakePolicy::Unknown,
            WakePolicy::Enabled,
        ] {
            let result = classify_capabilities(PowerFacts {
                known: true,
                modern_standby: true,
                wake_alarm_present: true,
                policy,
                ..PowerFacts::default()
            });
            assert!(result.sleep_supported && result.wake_supported);
            assert_ne!(result.wake_allowed, Some(true));
            assert!(result.message.contains("Modern Standby"));
            assert!(result.message.contains("not guaranteed"));
        }
    }

    #[test]
    fn unknown_and_unsupported_hardware_do_not_claim_wake() {
        let unknown = classify_capabilities(PowerFacts {
            known: false,
            ..traditional()
        });
        assert!(!unknown.sleep_supported && !unknown.wake_supported);
        assert_eq!(unknown.wake_allowed, None);
        let no_rtc = classify_capabilities(PowerFacts {
            rtc_wake: None,
            ..traditional()
        });
        assert!(no_rtc.sleep_supported);
        assert!(!no_rtc.wake_supported);
        assert_eq!(no_rtc.wake_allowed, Some(false));
        let modern_no_alarm = classify_capabilities(PowerFacts {
            known: true,
            modern_standby: true,
            policy: WakePolicy::Enabled,
            ..PowerFacts::default()
        });
        assert!(!modern_no_alarm.wake_supported);
        assert!(modern_no_alarm.message.contains("Modern Standby"));
    }

    #[test]
    fn rtc_must_reach_the_sleep_state_the_machine_uses() {
        for wake in [SleepState::Sleep1, SleepState::Sleep2] {
            assert!(
                !classify_capabilities(PowerFacts {
                    sleep_s1: true,
                    rtc_wake: Some(wake),
                    ..traditional()
                })
                .wake_supported
            );
        }
        for wake in [
            SleepState::Sleep3,
            SleepState::Hibernate,
            SleepState::PowerOff,
        ] {
            assert!(
                classify_capabilities(PowerFacts {
                    rtc_wake: Some(wake),
                    ..traditional()
                })
                .wake_supported
            );
        }
        let hibernate_only = PowerFacts {
            sleep_s3: false,
            hibernate: true,
            ..traditional()
        };
        let result = classify_capabilities(hibernate_only);
        assert!(!result.sleep_supported);
        assert!(result.wake_supported);
        assert!(
            !classify_capabilities(PowerFacts {
                rtc_wake: Some(SleepState::Sleep3),
                ..hibernate_only
            })
            .wake_supported
        );
        let no_sleep = classify_capabilities(PowerFacts {
            sleep_s3: false,
            ..traditional()
        });
        assert!(!no_sleep.wake_supported);
    }

    #[test]
    fn supply_and_serialized_contract_are_preserved() {
        let result = classify_capabilities(PowerFacts {
            on_battery: Some(true),
            ..traditional()
        });
        assert!(result.message.contains("on battery"));
        assert!(result.message.contains("cannot turn on a shut-down PC"));
        let json = serde_json::to_value(result).unwrap();
        assert_eq!(json.as_object().unwrap().len(), 6);
        assert_eq!(json["sleepSupported"], true);
        assert_eq!(json["shutdownSupported"], true);
        assert_eq!(json["wakeSupported"], true);
        assert_eq!(json["wakeAllowed"], true);
        assert_eq!(json["onBattery"], true);
    }
}
