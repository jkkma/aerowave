//! Stage a change without exposing it to later saves until persistence succeeds.

pub fn update<T: Clone, E>(
    current: &mut T,
    change: impl FnOnce(&mut T),
    persist: impl FnOnce(&T) -> Result<(), E>,
) -> Result<(), E> {
    update_with(current, change, persist, |current, candidate| {
        *current = candidate;
    })
}

/// Publish related in-memory state only after the candidate reaches storage.
/// The caller can hold any locks needed to make that publication indivisible.
pub fn update_with<T: Clone, E>(
    current: &mut T,
    change: impl FnOnce(&mut T),
    persist: impl FnOnce(&T) -> Result<(), E>,
    commit: impl FnOnce(&mut T, T),
) -> Result<(), E> {
    let mut candidate = current.clone();
    change(&mut candidate);
    persist(&candidate)?;
    commit(current, candidate);
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[derive(Clone, Debug, PartialEq)]
    struct Data {
        stations: Vec<&'static str>,
        alarms: Vec<&'static str>,
        wake_enabled: bool,
        volume: u8,
    }

    #[test]
    fn failed_station_save_cannot_leak_into_a_later_settings_save() {
        let mut current = Data {
            stations: vec!["saved"],
            alarms: vec![],
            wake_enabled: true,
            volume: 80,
        };
        let result = update(
            &mut current,
            |data| data.stations.push("failed"),
            |_| Err("disk full"),
        );
        assert_eq!(result, Err("disk full"));
        let mut disk = None;
        update(
            &mut current,
            |data| data.volume = 50,
            |data| {
                disk = Some(data.clone());
                Ok::<_, &str>(())
            },
        )
        .unwrap();
        assert_eq!(
            disk.unwrap(),
            Data {
                stations: vec!["saved"],
                alarms: vec![],
                wake_enabled: true,
                volume: 50
            }
        );
        assert_eq!(current.stations, vec!["saved"]);
    }

    #[test]
    fn retry_commits_the_candidate_and_preserves_unrelated_data() {
        let mut current = Data {
            stations: vec!["saved"],
            alarms: vec![],
            wake_enabled: true,
            volume: 80,
        };
        assert!(update(&mut current, |data| data.stations.push("new"), |_| Err(())).is_err());
        update(
            &mut current,
            |data| data.stations.push("new"),
            |candidate| {
                assert_eq!(candidate.stations, vec!["saved", "new"]);
                assert_eq!(candidate.volume, 80);
                Ok::<_, ()>(())
            },
        )
        .unwrap();
        assert_eq!(
            current,
            Data {
                stations: vec!["saved", "new"],
                alarms: vec![],
                wake_enabled: true,
                volume: 80
            }
        );
    }

    #[test]
    fn failed_alarm_and_wake_saves_do_not_publish_scheduler_side_effects() {
        let mut current = Data {
            stations: vec!["saved"],
            alarms: vec!["morning"],
            wake_enabled: true,
            volume: 80,
        };
        let mut scheduler_updates = Vec::new();
        let failed_alarm = update_with(
            &mut current,
            |data| data.alarms.clear(),
            |_| Err("disk full"),
            |current, candidate| {
                scheduler_updates.push("cancel morning");
                *current = candidate;
            },
        );
        assert_eq!(failed_alarm, Err("disk full"));
        let failed_wake = update_with(
            &mut current,
            |data| data.wake_enabled = false,
            |_| Err("disk full"),
            |current, candidate| {
                scheduler_updates.push("disable wake");
                *current = candidate;
            },
        );
        assert_eq!(failed_wake, Err("disk full"));
        assert!(scheduler_updates.is_empty());
        assert_eq!(current.alarms, vec!["morning"]);
        assert!(current.wake_enabled);

        let mut saved = None;
        update_with(
            &mut current,
            |data| data.volume = 50,
            |candidate| {
                saved = Some(candidate.clone());
                Ok::<_, &str>(())
            },
            |current, candidate| {
                scheduler_updates.push("volume saved");
                *current = candidate;
            },
        )
        .unwrap();
        assert_eq!(saved.unwrap(), current);
        assert_eq!(current.alarms, vec!["morning"]);
        assert!(current.wake_enabled);
        assert_eq!(scheduler_updates, vec!["volume saved"]);

        update_with(
            &mut current,
            |data| data.alarms.clear(),
            |candidate| {
                assert_eq!(candidate.stations, vec!["saved"]);
                assert_eq!(candidate.volume, 50);
                Ok::<_, &str>(())
            },
            |current, candidate| {
                scheduler_updates.push("cancel morning");
                *current = candidate;
            },
        )
        .unwrap();
        update_with(
            &mut current,
            |data| data.wake_enabled = false,
            |candidate| {
                assert_eq!(candidate.stations, vec!["saved"]);
                assert!(candidate.alarms.is_empty());
                Ok::<_, &str>(())
            },
            |current, candidate| {
                scheduler_updates.push("disable wake");
                *current = candidate;
            },
        )
        .unwrap();
        assert!(!current.wake_enabled);
        assert_eq!(
            scheduler_updates,
            vec!["volume saved", "cancel morning", "disable wake"]
        );
    }
}
