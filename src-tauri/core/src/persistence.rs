//! Stage a change without exposing it to later saves until persistence succeeds.

pub fn update<T: Clone, E>(
    current: &mut T,
    change: impl FnOnce(&mut T),
    persist: impl FnOnce(&T) -> Result<(), E>,
) -> Result<(), E> {
    let mut candidate = current.clone();
    change(&mut candidate);
    persist(&candidate)?;
    *current = candidate;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[derive(Clone, Debug, PartialEq)]
    struct Data {
        stations: Vec<&'static str>,
        volume: u8,
    }

    #[test]
    fn failed_station_save_cannot_leak_into_a_later_settings_save() {
        let mut current = Data {
            stations: vec!["saved"],
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
                volume: 50
            }
        );
        assert_eq!(current.stations, vec!["saved"]);
    }

    #[test]
    fn retry_commits_the_candidate_and_preserves_unrelated_data() {
        let mut current = Data {
            stations: vec!["saved"],
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
                volume: 80
            }
        );
    }
}
