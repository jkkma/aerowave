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
}

#[derive(Debug, Clone, Deserialize, Serialize)]
pub struct VolumePayload {
    pub volume: f64,
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
        }
    }
}
