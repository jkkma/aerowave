use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PlayPayload {
    pub url: String,
    pub source_url: String,
    pub title: String,
    pub station_id: Option<String>,
    pub volume: f64,
    pub generation: i64,
    pub is_hls: bool,
    #[serde(default = "enabled_by_default")]
    pub show_metadata: bool,
    /// A normalized, bounded PNG data URL. Native code validates this again
    /// before exposing it to Media3 or persisting it for service restoration.
    #[serde(default)]
    pub artwork_data_url: Option<String>,
    #[serde(default)]
    pub sleep_revision: Option<u64>,
    /// A persisted Android document-tree URI. When present, Media3 advances
    /// to another track from the tree when this one ends.
    #[serde(default)]
    pub source_folder: Option<String>,
    /// A persisted document-tree URI used if a radio source cannot play.
    #[serde(default)]
    pub backup_folder: Option<String>,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ArtworkPayload {
    pub generation: i64,
    pub source_url: String,
    #[serde(default)]
    pub artwork_data_url: Option<String>,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct MetadataEnabledPayload {
    pub generation: i64,
    pub source_url: String,
    pub enabled: bool,
}

#[cfg(target_os = "android")]
#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct StreamTitlePayload {
    pub source_url: String,
    pub title: String,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
pub struct VolumePayload {
    pub volume: f64,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
pub struct SleepTimerPayload {
    pub minutes: u32,
}

#[derive(Debug, Clone, Default, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SleepTimer {
    pub minutes: u32,
    pub action: String,
    pub ends_at_ms: i64,
    pub execute_at_ms: Option<i64>,
    pub remaining_ms: i64,
}

#[derive(Debug, Clone, Default, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SleepTimerSnapshot {
    pub revision: u64,
    pub timer: Option<SleepTimer>,
    pub outcome: Option<String>,
    pub error: Option<String>,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PlaybackState {
    pub status: String,
    pub generation: i64,
    pub source_url: String,
    pub title: String,
    pub station_id: Option<String>,
    pub position_ms: i64,
    pub volume: f64,
    pub error: Option<String>,
    pub track_title: Option<String>,
    pub source_folder: Option<String>,
    pub is_hls: bool,
    pub show_metadata: bool,
    pub sleep_timer: SleepTimerSnapshot,
}

impl Default for PlaybackState {
    fn default() -> Self {
        Self {
            status: "idle".into(),
            generation: 0,
            source_url: String::new(),
            title: String::new(),
            station_id: None,
            position_ms: 0,
            volume: 1.0,
            error: None,
            track_title: None,
            source_folder: None,
            is_hls: false,
            show_metadata: true,
            sleep_timer: SleepTimerSnapshot::default(),
        }
    }
}

#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(
    tag = "kind",
    rename_all = "camelCase",
    rename_all_fields = "camelCase"
)]
pub enum AlarmSource {
    Station { station_id: String },
    Folder { path: String },
}

#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct AlarmDefinition {
    pub id: String,
    #[serde(default)]
    pub label: String,
    pub hour: u32,
    pub minute: u32,
    #[serde(default)]
    pub days: Vec<u32>,
    #[serde(default = "enabled_by_default")]
    pub enabled: bool,
    pub source: AlarmSource,
    pub volume: f64,
    pub fade_secs: u32,
    pub snooze_mins: u32,
    pub auto_stop_mins: u32,
    #[serde(default)]
    pub auto_snoozes: u32,
}

fn enabled_by_default() -> bool {
    true
}

#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct AlarmStation {
    pub id: String,
    pub name: String,
    pub url: String,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SyncAlarmsPayload {
    /// Omitted for a station/settings update. Native alarm state is then left
    /// canonical, including an automatically disabled one-shot.
    #[serde(default)]
    pub alarms: Option<Vec<AlarmDefinition>>,
    #[serde(default)]
    pub expected_revision: Option<u64>,
    #[serde(default)]
    pub stations: Vec<AlarmStation>,
    #[serde(default)]
    pub backup_folder: Option<String>,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct AlarmIdPayload {
    pub id: String,
    #[serde(default)]
    pub occurrence_id: Option<String>,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
pub struct TestAlarmPayload {
    pub alarm: AlarmDefinition,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
pub struct AlarmSettingsPayload {
    pub setting: String,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
pub struct FolderPathPayload {
    pub path: String,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
pub struct RandomTrackPayload {
    pub path: String,
    #[serde(default)]
    pub exclude: Option<String>,
}

#[derive(Debug, Clone, Default, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct FolderInfo {
    pub path: String,
    pub name: String,
    pub count: usize,
}

#[derive(Debug, Clone, Default, Deserialize, Serialize)]
pub struct TrackPick {
    pub path: String,
    pub name: String,
    pub total: usize,
}

#[derive(Debug, Clone, Default, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct AlarmPermissions {
    pub exact: String,
    pub notifications: String,
    pub full_screen: String,
    pub battery_optimized: bool,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct NextAlarm {
    pub alarm_id: String,
    pub label: String,
    pub at_ms: i64,
    pub snoozed: bool,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct RingingAlarm {
    pub alarm_id: String,
    pub occurrence_id: String,
    pub label: String,
    pub trigger: String,
    pub started_at_ms: i64,
    pub hour: u32,
    pub minute: u32,
    pub snooze_mins: u32,
    pub volume: f64,
    pub can_snooze: bool,
    pub auto_snoozes_remaining: u32,
    pub source_kind: String,
    pub title: Option<String>,
    pub note: Option<String>,
}

#[derive(Debug, Clone, Default, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct AlarmState {
    pub initialized: bool,
    pub revision: u64,
    pub alarms: Vec<AlarmDefinition>,
    pub next: Option<NextAlarm>,
    pub ringing: Option<RingingAlarm>,
    pub permissions: AlarmPermissions,
    pub error: Option<String>,
}
