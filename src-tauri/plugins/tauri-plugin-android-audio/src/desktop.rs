use std::marker::PhantomData;

use tauri::{plugin::PluginApi, AppHandle, Runtime};

use crate::models::{
    AlarmIdPayload, AlarmSettingsPayload, AlarmState, FolderInfo, FolderPathPayload, PlayPayload,
    PlaybackState, RandomTrackPayload, SleepTimerPayload, SleepTimerSnapshot, SyncAlarmsPayload,
    TestAlarmPayload, TrackPick, VolumePayload,
};

pub struct AndroidAudio<R: Runtime>(PhantomData<fn() -> R>);

pub fn init<R: Runtime>(
    _app: &AppHandle<R>,
    _api: PluginApi<R, ()>,
) -> Result<AndroidAudio<R>, String> {
    Ok(AndroidAudio(PhantomData))
}

impl<R: Runtime> AndroidAudio<R> {
    pub fn play(&self, _payload: PlayPayload) -> Result<PlaybackState, String> {
        Err("Android background audio is only available on Android".into())
    }

    pub fn pause(&self) -> Result<PlaybackState, String> {
        Ok(PlaybackState::default())
    }

    pub fn resume(&self) -> Result<PlaybackState, String> {
        Err("Android background audio is only available on Android".into())
    }

    pub fn stop(&self) -> Result<PlaybackState, String> {
        Ok(PlaybackState::default())
    }

    pub fn get_state(&self) -> Result<PlaybackState, String> {
        Ok(PlaybackState::default())
    }

    pub fn set_volume(&self, _payload: VolumePayload) -> Result<PlaybackState, String> {
        Ok(PlaybackState::default())
    }

    pub fn set_sleep_timer(
        &self,
        _payload: SleepTimerPayload,
    ) -> Result<SleepTimerSnapshot, String> {
        Err("Android background audio is only available on Android".into())
    }

    pub fn cancel_sleep_timer(&self) -> Result<SleepTimerSnapshot, String> {
        Ok(SleepTimerSnapshot::default())
    }

    pub fn get_sleep_timer(&self) -> Result<SleepTimerSnapshot, String> {
        Ok(SleepTimerSnapshot::default())
    }

    pub fn sync_alarms(&self, _payload: SyncAlarmsPayload) -> Result<AlarmState, String> {
        Err("Android alarms are only available on Android".into())
    }

    pub fn get_alarm_state(&self) -> Result<AlarmState, String> {
        Ok(AlarmState::default())
    }
    pub fn snooze_alarm(&self, _payload: AlarmIdPayload) -> Result<AlarmState, String> {
        Err("Android alarms are only available on Android".into())
    }
    pub fn dismiss_alarm(&self, _payload: AlarmIdPayload) -> Result<AlarmState, String> {
        Err("Android alarms are only available on Android".into())
    }
    pub fn test_alarm(&self, _payload: TestAlarmPayload) -> Result<AlarmState, String> {
        Err("Android alarms are only available on Android".into())
    }
    pub fn open_alarm_settings(
        &self,
        _payload: AlarmSettingsPayload,
    ) -> Result<AlarmState, String> {
        Err("Android alarms are only available on Android".into())
    }
    pub fn pick_folder(&self) -> Result<Option<FolderInfo>, String> {
        Err("Android folders are only available on Android".into())
    }
    pub fn folder_info(&self, _payload: FolderPathPayload) -> Result<FolderInfo, String> {
        Err("Android folders are only available on Android".into())
    }
    pub fn random_track(&self, _payload: RandomTrackPayload) -> Result<TrackPick, String> {
        Err("Android folders are only available on Android".into())
    }
}
