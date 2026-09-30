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


/// A missing file is normal only on first load. Recovery retries may not turn
/// a disappeared original into permission to overwrite it with defaults.
pub fn load_json<T: serde::de::DeserializeOwned + Default>(
    read: std::io::Result<Vec<u8>>,
    allow_missing: bool,
) -> Result<T, String> {
    match read {
        Ok(bytes) => serde_json::from_slice(&bytes).map_err(|error| format!("invalid JSON: {error}")),
        Err(error) if allow_missing && error.kind() == std::io::ErrorKind::NotFound => Ok(T::default()),
        Err(error) => Err(format!("could not read file: {error}")),
    }
}

pub fn require_writable(recovery_error: Option<&str>) -> Result<(), String> {
    match recovery_error {
        Some(error) => Err(format!("Settings writes are blocked to avoid overwriting data that has not been safely loaded. {error} Fix their access or contents and retry, or restore a backup.")),
        None => Ok(()),
    }
}

/// Failure before rename leaves the destination unchanged. Failure afterwards
/// needs readback before another config save: memory can no longer establish
/// what is on disk, even though durability has not been confirmed.
#[derive(Debug, PartialEq, Eq)]
pub enum ReplaceError<E> {
    BeforeRename(E),
    AfterRename(E),
}

pub fn replace_synced<T, E>(
    stage_and_sync: impl FnOnce() -> Result<T, E>,
    rename: impl FnOnce(T) -> Result<(), E>,
    sync_directory: impl FnOnce() -> Result<(), E>,
) -> Result<(), ReplaceError<E>> {
    let staged = stage_and_sync().map_err(ReplaceError::BeforeRename)?;
    rename(staged).map_err(ReplaceError::BeforeRename)?;
    sync_directory().map_err(ReplaceError::AfterRename)
}

/// Preserve bytes before allowing a recovery replacement. In particular,
/// unreadable originals cannot be replaced by a backup of empty defaults.
pub fn preserve_original<E>(
    read: impl FnOnce() -> Result<Vec<u8>, E>,
    write_new_and_sync: impl FnOnce(&[u8]) -> Result<(), E>,
) -> Result<(), E> {
    let original = read()?;
    write_new_and_sync(&original)
}

#[cfg(test)]
mod tests {
    use super::*;


    #[test]
    fn load_failures_block_writes_without_moving_or_replacing_the_original() {
        for failure in [std::io::ErrorKind::PermissionDenied, std::io::ErrorKind::InvalidData] {
            let result = load_json::<Vec<String>>(Err(std::io::Error::from(failure)), true);
            let error = result.unwrap_err();
            assert!(require_writable(Some(&error)).is_err());
        }
        let original = b"{ damaged settings".to_vec();
        let error = load_json::<Vec<String>>(Ok(original.clone()), true).unwrap_err();
        assert!(require_writable(Some(&error)).is_err());
        assert_eq!(original, b"{ damaged settings");
        assert!(load_json::<Vec<String>>(Err(std::io::ErrorKind::NotFound.into()), true).unwrap().is_empty());
        assert!(load_json::<Vec<String>>(Err(std::io::ErrorKind::NotFound.into()), false).is_err());
    }

    #[test]
    fn recovery_keeps_exact_invalid_bytes_and_stops_on_read_or_copy_failure() {
        let original = vec![0xff, 0, b'{'];
        let mut copy = Vec::new();
        preserve_original(|| Ok::<_, &str>(original.clone()), |bytes| { copy = bytes.to_vec(); Ok(()) }).unwrap();
        assert_eq!(copy, original);
        let mut wrote = false;
        assert_eq!(preserve_original(|| Err("read denied"), |_| { wrote = true; Ok(()) }), Err("read denied"));
        assert!(!wrote);
        assert_eq!(preserve_original(|| Ok(original), |_| Err("copy failed")), Err("copy failed"));
    }

    #[test]
    fn faults_before_rename_preserve_original_and_do_not_publish_candidate() {
        use std::cell::RefCell;
        for fault in ["open", "write", "file sync", "rename", "directory sync", "none"] {
            let disk = RefCell::new("old");
            let mut current = "old";
            let result = update(&mut current, |value| *value = "new", |value| {
                replace_synced(
                    || if ["open", "write", "file sync"].contains(&fault) { Err(fault) } else { Ok(*value) },
                    |staged| if fault == "rename" { Err(fault) } else { *disk.borrow_mut() = staged; Ok(()) },
                    || if fault == "directory sync" { Err(fault) } else { Ok(()) },
                )
            });
            assert_eq!(result.is_ok(), fault == "none");
            if fault == "directory sync" {
                assert_eq!(result, Err(ReplaceError::AfterRename(fault)));
            } else if fault != "none" {
                assert_eq!(result, Err(ReplaceError::BeforeRename(fault)));
            }
            assert_eq!(current, if fault == "none" { "new" } else { "old" });
            assert_eq!(*disk.borrow(), if ["directory sync", "none"].contains(&fault) { "new" } else { "old" });
        }
    }

    #[test]
    fn post_rename_failure_requires_readback_before_later_save() {
        let mut current = vec!["old".to_string()];
        let mut disk = serde_json::to_vec(&current).unwrap();
        let result = update(&mut current, |value| value.push("new".into()), |candidate| {
            replace_synced(
                || Ok(serde_json::to_vec(candidate).unwrap()),
                |bytes| { disk = bytes; Ok(()) },
                || Err("directory sync failed"),
            )
        });
        let mut recovery = match result {
            Err(ReplaceError::AfterRename(error)) => Some(format!("Save may have applied: {error}")),
            other => panic!("expected uncertain commit, got {other:?}"),
        };
        assert_eq!(current, vec!["old"]);
        let committed_bytes = disk.clone();
        assert!(require_writable(recovery.as_deref()).is_err());
        // A later edit cannot overwrite the disk's new item with stale memory.
        let write_allowed = require_writable(recovery.as_deref());
        if write_allowed.is_ok() { disk = serde_json::to_vec(&current).unwrap(); }
        assert_eq!(disk, committed_bytes);
        current = load_json(Ok(disk), false).unwrap();
        recovery = None;
        assert_eq!(current, vec!["old", "new"]);
        assert!(require_writable(recovery.as_deref()).is_ok());
    }

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
