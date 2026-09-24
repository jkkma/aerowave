use tauri::{
    plugin::{PluginApi, PluginHandle},
    AppHandle, Runtime,
};

use crate::models::{
    AlarmIdPayload, AlarmSettingsPayload, AlarmState, ArtworkPayload, FolderInfo,
    FolderPathPayload, MetadataEnabledPayload, PlayPayload, PlaybackState, RandomTrackPayload,
    SaveBackupFilePayload, SkipAlarmPayload, SleepTimerPayload, SleepTimerSnapshot,
    StreamTitlePayload, SyncAlarmsPayload, TestAlarmPayload, TrackPick, VolumePayload,
};
use serde::Serialize;

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct NativeAlarmSync {
    alarms_json: Option<String>,
    expected_revision: Option<u64>,
    stations_json: String,
    backup_folder: Option<String>,
}

impl TryFrom<SyncAlarmsPayload> for NativeAlarmSync {
    type Error = String;

    fn try_from(payload: SyncAlarmsPayload) -> Result<Self, Self::Error> {
        Ok(Self {
            expected_revision: payload.expected_revision,
            alarms_json: payload
                .alarms
                .map(|v| serde_json::to_string(&v))
                .transpose()
                .map_err(|e| e.to_string())?,
            stations_json: serde_json::to_string(&payload.stations).map_err(|e| e.to_string())?,
            backup_folder: payload.backup_folder,
        })
    }
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct NativeTestAlarm {
    alarm_json: String,
}

const PLUGIN_IDENTIFIER: &str = "com.aerowave.audio";

pub struct AndroidAudio<R: Runtime>(PluginHandle<R>);

pub fn init<R: Runtime>(
    _app: &AppHandle<R>,
    api: PluginApi<R, ()>,
) -> Result<AndroidAudio<R>, String> {
    api.register_android_plugin(PLUGIN_IDENTIFIER, "AndroidAudioPlugin")
        .map(AndroidAudio)
        .map_err(|error| error.to_string())
}

impl<R: Runtime> AndroidAudio<R> {
    pub fn play(&self, payload: PlayPayload) -> Result<PlaybackState, String> {
        self.0
            .run_mobile_plugin("play", payload)
            .map_err(|error| error.to_string())
    }

    pub fn pause(&self) -> Result<PlaybackState, String> {
        self.0
            .run_mobile_plugin("pause", ())
            .map_err(|error| error.to_string())
    }

    pub fn resume(&self) -> Result<PlaybackState, String> {
        self.0
            .run_mobile_plugin("resume", ())
            .map_err(|error| error.to_string())
    }

    pub fn stop(&self) -> Result<PlaybackState, String> {
        self.0
            .run_mobile_plugin("stop", ())
            .map_err(|error| error.to_string())
    }

    pub fn get_state(&self) -> Result<PlaybackState, String> {
        self.0
            .run_mobile_plugin("getState", ())
            .map_err(|error| error.to_string())
    }

    pub fn set_volume(&self, payload: VolumePayload) -> Result<PlaybackState, String> {
        self.0
            .run_mobile_plugin("setVolume", payload)
            .map_err(|error| error.to_string())
    }

    pub fn update_artwork(&self, payload: ArtworkPayload) -> Result<PlaybackState, String> {
        self.0
            .run_mobile_plugin("updateArtwork", payload)
            .map_err(|error| error.to_string())
    }

    pub fn set_metadata_enabled(
        &self,
        payload: MetadataEnabledPayload,
    ) -> Result<PlaybackState, String> {
        self.0
            .run_mobile_plugin("setMetadataEnabled", payload)
            .map_err(|error| error.to_string())
    }

    pub fn update_stream_title(&self, payload: StreamTitlePayload) -> Result<(), String> {
        self.0
            .run_mobile_plugin::<serde_json::Value>("updateStreamTitle", payload)
            .map(|_| ())
            .map_err(|error| error.to_string())
    }

    pub fn set_sleep_timer(
        &self,
        payload: SleepTimerPayload,
    ) -> Result<SleepTimerSnapshot, String> {
        self.0
            .run_mobile_plugin("setSleepTimer", payload)
            .map_err(|error| error.to_string())
    }

    pub fn cancel_sleep_timer(&self) -> Result<SleepTimerSnapshot, String> {
        self.0
            .run_mobile_plugin("cancelSleepTimer", ())
            .map_err(|error| error.to_string())
    }

    pub fn get_sleep_timer(&self) -> Result<SleepTimerSnapshot, String> {
        self.0
            .run_mobile_plugin("getSleepTimer", ())
            .map_err(|error| error.to_string())
    }

    pub fn sync_alarms(&self, payload: SyncAlarmsPayload) -> Result<AlarmState, String> {
        let native = NativeAlarmSync::try_from(payload)?;
        self.0
            .run_mobile_plugin("syncAlarms", native)
            .map_err(|e| e.to_string())
    }

    pub fn restore_alarms(&self, payload: SyncAlarmsPayload) -> Result<AlarmState, String> {
        let native = NativeAlarmSync::try_from(payload)?;
        self.0
            .run_mobile_plugin("restoreAlarms", native)
            .map_err(|e| e.to_string())
    }

    pub fn get_alarm_state(&self) -> Result<AlarmState, String> {
        self.0
            .run_mobile_plugin("getAlarmState", ())
            .map_err(|e| e.to_string())
    }

    pub fn skip_alarm(&self, payload: SkipAlarmPayload) -> Result<AlarmState, String> {
        self.0
            .run_mobile_plugin("skipAlarm", payload)
            .map_err(|e| e.to_string())
    }

    pub fn snooze_alarm(&self, payload: AlarmIdPayload) -> Result<AlarmState, String> {
        self.0
            .run_mobile_plugin("snoozeAlarm", payload)
            .map_err(|e| e.to_string())
    }

    pub fn dismiss_alarm(&self, payload: AlarmIdPayload) -> Result<AlarmState, String> {
        self.0
            .run_mobile_plugin("dismissAlarm", payload)
            .map_err(|e| e.to_string())
    }

    pub fn test_alarm(&self, payload: TestAlarmPayload) -> Result<AlarmState, String> {
        let native = NativeTestAlarm {
            alarm_json: serde_json::to_string(&payload.alarm).map_err(|e| e.to_string())?,
        };
        self.0
            .run_mobile_plugin("testAlarm", native)
            .map_err(|e| e.to_string())
    }

    pub fn open_alarm_settings(&self, payload: AlarmSettingsPayload) -> Result<AlarmState, String> {
        self.0
            .run_mobile_plugin("openAlarmSettings", payload)
            .map_err(|e| e.to_string())
    }

    pub fn pick_folder(&self) -> Result<Option<FolderInfo>, String> {
        self.0
            .run_mobile_plugin("pickFolder", ())
            .map_err(|e| e.to_string())
    }

    pub fn folder_info(&self, payload: FolderPathPayload) -> Result<FolderInfo, String> {
        self.0
            .run_mobile_plugin("folderInfo", payload)
            .map_err(|e| e.to_string())
    }

    pub fn random_track(&self, payload: RandomTrackPayload) -> Result<TrackPick, String> {
        self.0
            .run_mobile_plugin("randomTrack", payload)
            .map_err(|e| e.to_string())
    }

    pub fn read_backup_file(&self) -> Result<Option<String>, String> {
        self.0
            .run_mobile_plugin("readBackupFile", ())
            .map_err(|e| e.to_string())
    }

    pub fn save_backup_file(
        &self,
        payload: SaveBackupFilePayload,
    ) -> Result<Option<String>, String> {
        self.0
            .run_mobile_plugin("saveBackupFile", payload)
            .map_err(|e| e.to_string())
    }
}
