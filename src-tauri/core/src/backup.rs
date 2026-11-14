//! Versioned, bounded backup documents. The app crate owns the persisted
//! models; this module checks untrusted JSON before those models are loaded.

use std::collections::HashSet;

use serde_json::{Map, Value};
use url::Url;

pub const MAX_BYTES: usize = 2 * 1024 * 1024;
pub const FORMAT: &str = "aerowave-backup";
pub const VERSION: u64 = 1;

/// Recognize only names emitted by Store's create-new recovery writer.
pub fn recovery_name(name: &str) -> Option<(&str, u8)> {
    recovery_name_with_prefix(name, "aerowave.before-restore-")
}

pub fn raw_recovery_name(name: &str) -> Option<(&str, u8)> {
    recovery_name_with_prefix(name, "aerowave.before-restore-raw-")
}

fn recovery_name_with_prefix<'a>(name: &'a str, prefix: &str) -> Option<(&'a str, u8)> {
    let tail = name.strip_prefix(prefix)?.strip_suffix(".json")?;
    let (stamp, number) = tail.rsplit_once('-')?;
    let bytes = stamp.as_bytes();
    if !stamp.is_ascii() || bytes.len() != 15 || bytes[8] != b'-'
        || !bytes[..8].iter().chain(&bytes[9..]).all(u8::is_ascii_digit)
        || number.is_empty() || number.len() > 2 || (number.starts_with('0') && number.len() > 1)
        || !number.bytes().all(|b| b.is_ascii_digit()) {
        return None;
    }
    chrono::NaiveDateTime::parse_from_str(stamp, "%Y%m%d-%H%M%S").ok()?;
    let number: u8 = number.parse().ok()?;
    (number < 100).then_some((stamp, number))
}

/// A pointer contains one canonical basename, never a path or extra text.
pub fn recovery_pointer_target(content: &str) -> Option<&str> {
    (recovery_name(content).is_some() || raw_recovery_name(content).is_some()).then_some(content)
}

fn object<'a>(value: &'a Value, name: &str) -> Result<&'a Map<String, Value>, String> {
    value.as_object().ok_or_else(|| format!("{name} must be an object"))
}

fn array<'a>(value: &'a Value, name: &str) -> Result<&'a Vec<Value>, String> {
    value.as_array().ok_or_else(|| format!("{name} must be an array"))
}

fn field<'a>(object: &'a Map<String, Value>, key: &str, parent: &str) -> Result<&'a Value, String> {
    object.get(key).ok_or_else(|| format!("{parent}.{key} is missing"))
}

fn string<'a>(value: &'a Value, name: &str) -> Result<&'a str, String> {
    value.as_str().ok_or_else(|| format!("{name} must be text"))
}

fn id<'a>(value: &'a Value, name: &str) -> Result<&'a str, String> {
    let id = string(value, name)?;
    if id.is_empty() || id.len() > 256 || id.chars().any(char::is_control) {
        return Err(format!("{name} is invalid"));
    }
    Ok(id)
}

fn unsigned(value: &Value, name: &str, min: u64, max: u64) -> Result<u64, String> {
    let number = value.as_u64().ok_or_else(|| format!("{name} must be a whole number"))?;
    if !(min..=max).contains(&number) {
        return Err(format!("{name} is outside {min}..{max}"));
    }
    Ok(number)
}

fn volume(value: &Value, name: &str) -> Result<(), String> {
    let number = value.as_f64().ok_or_else(|| format!("{name} must be a number"))?;
    if !number.is_finite() || !(0.0..=1.0).contains(&number) {
        return Err(format!("{name} is outside 0..1"));
    }
    Ok(())
}

fn source(value: &Value, name: &str, stations: &HashSet<&str>, warnings: &mut Vec<String>) -> Result<bool, String> {
    let object = object(value, name)?;
    match string(field(object, "kind", name)?, &format!("{name}.kind"))? {
        "station" => {
            let station_id = id(field(object, "stationId", name)?, &format!("{name}.stationId"))?;
            if !stations.contains(station_id) {
                warnings.push(format!("{name} refers to a station missing from this backup"));
            }
            Ok(false)
        }
        "folder" => {
            string(field(object, "path", name)?, &format!("{name}.path"))?;
            Ok(true)
        }
        _ => Err(format!("{name}.kind is unsupported")),
    }
}

fn alarm_options(object: &Map<String, Value>, name: &str, stations: &HashSet<&str>, warnings: &mut Vec<String>) -> Result<bool, String> {
    if let Some(days) = object.get("days") {
        let mut seen = HashSet::new();
        for day in array(days, &format!("{name}.days"))? {
            let day = unsigned(day, &format!("{name}.days"), 0, 6)?;
            if !seen.insert(day) { return Err(format!("{name}.days has a duplicate")); }
        }
    }
    if let Some(v) = object.get("volume") { volume(v, &format!("{name}.volume"))?; }
    for (key, min, max) in [
        ("fadeSecs", 0, 3600),
        ("snoozeMins", 1, 1440),
        ("autoStopMins", 0, 1440),
        ("autoSnoozes", 0, 100),
    ] {
        if let Some(v) = object.get(key) { unsigned(v, &format!("{name}.{key}"), min, max)?; }
    }
    if let Some(v) = object.get("enabled") {
        if !v.is_boolean() { return Err(format!("{name}.enabled must be true or false")); }
    }
    if let Some(v) = object.get("skipDate") {
        if !v.is_null() {
            let date = string(v, &format!("{name}.skipDate"))?;
            if chrono::NaiveDate::parse_from_str(date, "%Y-%m-%d").is_err() {
                return Err(format!("{name}.skipDate must be a valid YYYY-MM-DD date"));
            }
        }
    }
    object.get("source").map(|v| source(v, &format!("{name}.source"), stations, warnings)).transpose().map(|v| v.unwrap_or(false))
}

fn validate_station(station: &Value, name: &str) -> Result<(), String> {
    let station = object(station, name)?;
    id(field(station, "id", name)?, &format!("{name}.id"))?;
    if let Some(recent_id) = station.get("recentId").filter(|value| !value.is_null()) {
        id(recent_id, &format!("{name}.recentId"))?;
    }
    string(field(station, "name", name)?, &format!("{name}.name"))?;
    let address = string(field(station, "url", name)?, &format!("{name}.url"))?;
    validate_station_url(address, &format!("{name}.url"))
}

fn validate_station_url(address: &str, name: &str) -> Result<(), String> {
    let url = Url::parse(address).map_err(|_| format!("{name} is invalid"))?;
    if !matches!(url.scheme(), "http" | "https") || url.host_str().is_none() {
        return Err(format!("{name} must be an HTTP(S) address"));
    }
    Ok(())
}

fn validate_data(data: &Value) -> Result<Vec<String>, String> {
    let data = object(data, "data")?;
    let stations = array(field(data, "stations", "data")?, "data.stations")?;
    let alarms = array(field(data, "alarms", "data")?, "data.alarms")?;
    let settings = object(field(data, "settings", "data")?, "data.settings")?;

    let mut station_ids = HashSet::new();
    for (index, station) in stations.iter().enumerate() {
        let name = format!("data.stations[{index}]");
        validate_station(station, &name)?;
        let station_id = id(field(object(station, &name)?, "id", &name)?, &format!("{name}.id"))?;
        if !station_ids.insert(station_id) { return Err(format!("duplicate station id: {station_id}")); }
    }

    let mut warnings = Vec::new();
    let mut alarm_ids = HashSet::new();
    for (index, alarm) in alarms.iter().enumerate() {
        let name = format!("data.alarms[{index}]");
        let alarm = object(alarm, &name)?;
        let alarm_id = id(field(alarm, "id", &name)?, &format!("{name}.id"))?;
        if !alarm_ids.insert(alarm_id) { return Err(format!("duplicate alarm id: {alarm_id}")); }
        unsigned(field(alarm, "hour", &name)?, &format!("{name}.hour"), 0, 23)?;
        unsigned(field(alarm, "minute", &name)?, &format!("{name}.minute"), 0, 59)?;
        alarm_options(alarm, &name, &station_ids, &mut warnings)?;
    }

    if let Some(v) = settings.get("volume") { volume(v, "data.settings.volume")?; }
    if let Some(v) = settings.get("alarmDefaults") {
        if !v.is_null() { alarm_options(object(v, "data.settings.alarmDefaults")?, "data.settings.alarmDefaults", &station_ids, &mut warnings)?; }
    }
    if let Some(v) = settings.get("recentStations") {
        let recent = array(v, "data.settings.recentStations")?;
        if recent.len() > 20 { return Err("data.settings.recentStations exceeds 20".into()); }
        for (index, station) in recent.iter().enumerate() {
            let name = format!("data.settings.recentStations[{index}]");
            // Keep the actual history snapshots. The Recent view resolves
            // them against saved stations and handles duplicate identities.
            validate_station(station, &name)?;
        }
    }
    Ok(warnings)
}

pub fn inspect(content: &str) -> Result<(Value, Vec<String>), String> {
    if content.len() > MAX_BYTES { return Err("Backup exceeds the 2 MiB limit".into()); }
    let document: Value = serde_json::from_str(content).map_err(|e| format!("Backup is not valid JSON: {e}"))?;
    let document = object(&document, "backup")?;
    if document.len() != 3 { return Err("Backup has unexpected top-level fields".into()); }
    if field(document, "format", "backup")?.as_str() != Some(FORMAT) {
        return Err("This is not an Aerowave backup".into());
    }
    if field(document, "version", "backup")?.as_u64() != Some(VERSION) {
        return Err("This backup version is unsupported".into());
    }
    let data = field(document, "data", "backup")?;
    let warnings = validate_data(data)?;
    Ok((data.clone(), warnings))
}

pub fn validate(content: &str) -> Result<Value, String> {
    inspect(content).map(|(data, _)| data)
}

pub fn prepare_restore(content: &str) -> Result<Value, String> {
    let mut data = validate(content)?;
    for alarm in data["alarms"].as_array_mut().expect("validated alarms array") {
        alarm["enabled"] = Value::Bool(false);
        alarm["skipDate"] = Value::Null;
    }
    Ok(data)
}

fn encode_document(data: &Value) -> Result<String, String> {
    serde_json::to_string_pretty(&serde_json::json!({
        "format": FORMAT,
        "version": VERSION,
        "data": data,
    })).map_err(|e| e.to_string())
}

/// Validate the size again after restore fills in model defaults and retains
/// device preferences. A valid import must still be exportable after restore.
pub fn validate_restore_size(data: &Value) -> Result<(), String> {
    if encode_document(data)?.len() > MAX_BYTES {
        return Err("The restored setup would exceed the 2 MiB backup limit.".into());
    }
    Ok(())
}

pub fn encode(data: &Value) -> Result<String, String> {
    let content = encode_document(data)?;
    validate(&content)?;
    Ok(content)
}

/// Keep new station addresses and setup growth within the backup contract.
/// An unchanged legacy URL or a shrinking oversized setup can still be edited
/// so its owner can repair the original before exporting.
pub fn validate_live_edit(before: &Value, after: &Value) -> Result<(), String> {
    let size = encode_document(after)?.len();
    if size > MAX_BYTES && size > encode_document(before)?.len() {
        return Err("This change would grow the setup beyond the 2 MiB backup limit.".into());
    }
    validate_live_station_urls(before, after)
}

pub fn validate_live_station_urls(before: &Value, after: &Value) -> Result<(), String> {
    for (pointer, label) in [
        ("/stations", "Saved station"),
        ("/settings/recentStations", "Recent station"),
    ] {
        let existing: HashSet<_> = before.pointer(pointer).and_then(Value::as_array)
            .into_iter().flatten().map(station_identity).collect();
        for station in after.pointer(pointer).and_then(Value::as_array).into_iter().flatten() {
            let identity = station_identity(station);
            if !existing.contains(&identity) {
                let address = identity.1.unwrap_or_default();
                validate_station_url(address, &format!("{label} URL"))?;
            }
        }
    }
    Ok(())
}

fn station_identity(station: &Value) -> (Option<&str>, Option<&str>) {
    (station["id"].as_str(), station["url"].as_str())
}

pub enum RecoveryCopy {
    Backup(String),
    Original(Vec<u8>),
}

pub fn recovery_copy(
    current: &Value,
    read_original: impl FnOnce() -> Result<Vec<u8>, String>,
) -> Result<RecoveryCopy, String> {
    match encode(current) {
        Ok(backup) => Ok(RecoveryCopy::Backup(backup)),
        Err(error) => read_original().map(RecoveryCopy::Original)
            .map_err(|read_error| format!("Could not preserve the original setup ({error}): {read_error}")),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn sample() -> Value {
        serde_json::json!({
            "stations": [{"id":"news", "name":"News", "url":"https://example.com/live"}],
            "alarms": [{"id":"morning", "hour":7, "minute":30,
                "source":{"kind":"station", "stationId":"news"}, "enabled":true}],
            "settings":{"volume":0.8, "recentStations":[{"id":"news", "name":"News", "url":"https://example.com/live"}]}
        })
    }

    #[test]
    fn live_edits_reject_malformed_station_urls_and_allow_legacy_repairs() {
        let before = sample();
        let mut invalid = before.clone();
        invalid["stations"][0]["url"] = Value::from("https://[abc]");
        assert!(validate_live_edit(&before, &invalid).is_err());
        assert!(validate_live_station_urls(&before, &invalid).is_err());
        let mut settings_edit = invalid.clone();
        settings_edit["settings"]["volume"] = Value::from(0.4);
        assert!(validate_live_edit(&invalid, &settings_edit).is_ok());
        assert!(validate_live_edit(&invalid, &before).is_ok());
    }

    #[test]
    fn live_edits_cannot_grow_beyond_the_backup_limit() {
        let before = sample();
        let mut oversized = before.clone();
        oversized["stations"][0]["url"] =
            Value::from(format!("https://example.com/{}", "x".repeat(MAX_BYTES)));
        assert!(validate_live_edit(&before, &oversized).is_err());
        assert!(validate_live_edit(&oversized, &before).is_ok());
    }

    #[test]
    fn restore_cannot_expand_an_at_limit_backup_into_an_unexportable_setup() {
        let mut data = sample();
        data["alarms"][0]["skipDate"] = Value::Null;
        data["alarms"][0]["armingRevision"] = Value::from("0".repeat(32));
        data["settings"]["wakeForAlarms"] = Value::Bool(true);
        data["alarms"][0]["label"] = Value::from("");
        let empty_label = encode(&data).unwrap().len();
        let normalize = |content: &str| {
            let mut restored = prepare_restore(content).unwrap();
            restored["alarms"][0]["armingRevision"] = Value::from("1".repeat(32));
            restored["settings"]["wakeForAlarms"] = Value::Bool(true);
            restored
        };
        data["alarms"][0]["label"] = Value::from("x".repeat(MAX_BYTES - empty_label - 1));
        let restored = normalize(&encode(&data).unwrap());
        assert!(validate_restore_size(&restored).is_ok());
        assert_eq!(encode(&restored).unwrap().len(), MAX_BYTES);
        data["alarms"][0]["label"] = Value::from("x".repeat(MAX_BYTES - empty_label));
        let incoming = encode(&data).unwrap();
        assert_eq!(incoming.len(), MAX_BYTES);
        let restored = normalize(&incoming);
        assert!(validate_restore_size(&restored).is_err());
    }

    #[test]
    fn compact_legacy_backup_can_shrink_below_the_limit_after_normalization() {
        let mut incoming = serde_json::json!({
            "format": FORMAT,
            "version": VERSION,
            "data": sample(),
        });
        incoming["data"]["ignored"] = Value::from("");
        let base_size = serde_json::to_string(&incoming).unwrap().len();
        incoming["data"]["ignored"] = Value::from("x".repeat(MAX_BYTES - base_size));
        let content = serde_json::to_string(&incoming).unwrap();
        assert_eq!(content.len(), MAX_BYTES);
        let mut restored = prepare_restore(&content).unwrap();
        assert!(validate_restore_size(&restored).is_err());
        restored.as_object_mut().unwrap().remove("ignored");
        restored["alarms"][0]["armingRevision"] = Value::from("1".repeat(32));
        restored["settings"]["wakeForAlarms"] = Value::Bool(true);
        assert!(validate_restore_size(&restored).is_ok());
    }

    #[test]
    fn restore_recovery_keeps_original_bytes_if_the_old_setup_cannot_be_exported() {
        let mut invalid = sample();
        invalid["stations"][0]["url"] = Value::from("https://");
        let original = b"{\r\n  \"oldField\": \"preserved verbatim\"\r\n}";
        let copy = recovery_copy(&invalid, || Ok(original.to_vec())).unwrap();
        assert!(matches!(copy, RecoveryCopy::Original(bytes) if bytes == original));
        assert!(recovery_copy(&invalid, || Err("cannot read".into())).is_err());
        assert!(matches!(
            recovery_copy(&sample(), || panic!("valid setup does not need raw bytes")),
            Ok(RecoveryCopy::Backup(_))
        ));
    }

    #[test]
    fn roundtrip_and_bounds() {
        let encoded = encode(&sample()).unwrap();
        assert_eq!(validate(&encoded).unwrap(), sample());
        assert!(validate(&format!("{}{}", encoded, " ".repeat(MAX_BYTES))).is_err());
    }

    #[test]
    fn recent_history_with_default_port_variants_still_exports_and_restores() {
        let mut data = sample();
        data["settings"]["recentStations"].as_array_mut().unwrap().push(serde_json::json!({
            "id": "other", "name": "Other", "url": "https://example.com:443/live"
        }));
        let restored = prepare_restore(&encode(&data).unwrap()).unwrap();
        assert_eq!(restored["settings"]["recentStations"], data["settings"]["recentStations"]);
    }

    #[test]
    fn promoted_station_preserves_its_history_link_in_a_backup() {
        let mut data = sample();
        data["stations"][0]["recentId"] = Value::from("discovery");
        data["settings"]["recentStations"][0]["id"] = Value::from("discovery");
        let restored = prepare_restore(&encode(&data).unwrap()).unwrap();
        assert_eq!(restored["stations"][0]["id"], "news");
        assert_eq!(restored["stations"][0]["recentId"], "discovery");
        assert_eq!(restored["alarms"][0]["source"]["stationId"], "news");
        data["stations"][0]["recentId"] = Value::from(42);
        assert!(encode(&data).unwrap_err().contains("recentId must be text"));
    }

    #[test]
    fn rejects_wrong_format_and_version() {
        let encoded = encode(&sample()).unwrap();
        assert!(validate(&encoded.replace(FORMAT, "other-format")).is_err());
        assert!(validate(&encoded.replace("\"version\": 1", "\"version\": 2")).is_err());
    }

    #[test]
    fn restore_disables_alarms_and_clears_skips() {
        let mut data = sample();
        data["alarms"][0]["skipDate"] = Value::from("2026-09-25");
        let restored = prepare_restore(&encode(&data).unwrap()).unwrap();
        assert_eq!(restored["alarms"][0]["enabled"], Value::Bool(false));
        assert!(restored["alarms"][0]["skipDate"].is_null());
        assert_eq!(restored["stations"], data["stations"]);
    }

    #[test]
    fn recovery_reader_accepts_only_our_numbered_names() {
        assert_eq!(recovery_name("aerowave.before-restore-20260924-142301-9.json"), Some(("20260924-142301", 9)));
        for name in [
            "aerowave.before-restore-20260924-142301-100.json",
            "aerowave.before-restore-20260924-142301-00.json",
            "aerowave.before-restore-20260924-142301-0.json.tmp",
            "aerowave.before-restore-20260924-142301-0.json.exe",
            "aerowave.before-restore-20260924-142301-0.txt",
            "aerowave.before-restore-20260924-142301-../0.json",
            "aerowave.before-restore-20260924-142301-x.json",
        ] {
            assert_eq!(recovery_name(name), None, "{name}");
        }
    }

    #[test]
    fn recovery_pointer_cannot_select_another_path() {
        let name = "aerowave.before-restore-20260924-142301-9.json";
        assert_eq!(recovery_pointer_target(name), Some(name));
        let raw = "aerowave.before-restore-raw-20260924-142301-9.json";
        assert_eq!(recovery_pointer_target(raw), Some(raw));
        assert_eq!(raw_recovery_name(raw), Some(("20260924-142301", 9)));
        for content in [
            "../aerowave.before-restore-20260924-142301-9.json",
            "subdir/aerowave.before-restore-20260924-142301-9.json",
            "aerowave.before-restore-20260924-142301-9.json\n",
            "aerowave.before-restore-20260924-142301-9.json\0",
            "aerowave.before-restore-20260924-142301-09.json",
            "../aerowave.before-restore-raw-20260924-142301-9.json",
        ] {
            assert_eq!(recovery_pointer_target(content), None, "{content:?}");
        }
    }

    #[test]
    fn rejects_duplicate_ids_bad_ranges_urls_and_references() {
        let mut data = sample();
        let station = data["stations"][0].clone();
        data["stations"].as_array_mut().unwrap().push(station);
        assert!(encode(&data).unwrap_err().contains("duplicate station id"));
        let mut data = sample();
        data["alarms"][0]["hour"] = Value::from(24);
        assert!(encode(&data).unwrap_err().contains("hour"));
        let mut data = sample();
        data["stations"][0]["url"] = Value::from("file:///tmp/music");
        assert!(encode(&data).unwrap_err().contains("HTTP(S)"));
        let mut data = sample();
        data["alarms"][0]["source"]["stationId"] = Value::from("missing");
        let encoded = encode(&data).unwrap();
        assert!(inspect(&encoded).unwrap().1[0].contains("missing"));
    }
}
