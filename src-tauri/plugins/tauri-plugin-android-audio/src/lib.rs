mod models;

#[cfg(not(target_os = "android"))]
mod desktop;
#[cfg(target_os = "android")]
mod mobile;

#[cfg(not(target_os = "android"))]
use desktop::AndroidAudio;
#[cfg(target_os = "android")]
use mobile::AndroidAudio;

pub use models::{
    AlarmDefinition, AlarmIdPayload, AlarmSettingsPayload, AlarmSource, AlarmState, AlarmStation,
    ArtworkPayload, FolderInfo, FolderPathPayload, MetadataEnabledPayload, PlayPayload,
    PlaybackState, RandomTrackPayload, SaveBackupFilePayload, SkipAlarmPayload, SleepTimer,
    SleepTimerPayload, SleepTimerSnapshot, SyncAlarmsPayload, TestAlarmPayload, TrackPick,
    VolumePayload,
};

use tauri::{
    plugin::{Builder, TauriPlugin},
    AppHandle, Manager, Runtime,
};

#[tauri::command]
fn play<R: Runtime>(app: AppHandle<R>, payload: PlayPayload) -> Result<PlaybackState, String> {
    app.state::<AndroidAudio<R>>().play(payload)
}

#[tauri::command]
fn pause<R: Runtime>(app: AppHandle<R>) -> Result<PlaybackState, String> {
    app.state::<AndroidAudio<R>>().pause()
}

#[tauri::command]
fn resume<R: Runtime>(app: AppHandle<R>) -> Result<PlaybackState, String> {
    app.state::<AndroidAudio<R>>().resume()
}

#[tauri::command]
fn stop<R: Runtime>(app: AppHandle<R>) -> Result<PlaybackState, String> {
    app.state::<AndroidAudio<R>>().stop()
}

#[tauri::command]
fn get_state<R: Runtime>(app: AppHandle<R>) -> Result<PlaybackState, String> {
    app.state::<AndroidAudio<R>>().get_state()
}

#[tauri::command]
fn set_volume<R: Runtime>(
    app: AppHandle<R>,
    payload: VolumePayload,
) -> Result<PlaybackState, String> {
    app.state::<AndroidAudio<R>>().set_volume(payload)
}

#[tauri::command]
fn update_artwork<R: Runtime>(
    app: AppHandle<R>,
    payload: ArtworkPayload,
) -> Result<PlaybackState, String> {
    app.state::<AndroidAudio<R>>().update_artwork(payload)
}

#[tauri::command]
fn set_metadata_enabled<R: Runtime>(
    app: AppHandle<R>,
    payload: MetadataEnabledPayload,
) -> Result<PlaybackState, String> {
    app.state::<AndroidAudio<R>>().set_metadata_enabled(payload)
}

/// Publishes relay metadata directly to Android's media session. The relay can
/// keep calling this when the WebView is suspended because JNI work is moved
/// off its streaming task and stale source URLs are rejected on the native side.
pub fn update_stream_title<R: Runtime>(app: &AppHandle<R>, source_url: String, title: String) {
    #[cfg(target_os = "android")]
    {
        let app = app.clone();
        tauri::async_runtime::spawn_blocking(move || {
            if let Err(error) = app
                .state::<AndroidAudio<R>>()
                .update_stream_title(models::StreamTitlePayload { source_url, title })
            {
                eprintln!("Android stream-title update failed: {error}");
            }
        });
    }
    #[cfg(not(target_os = "android"))]
    let _ = (app, source_url, title);
}

#[tauri::command]
fn set_sleep_timer<R: Runtime>(
    app: AppHandle<R>,
    payload: SleepTimerPayload,
) -> Result<SleepTimerSnapshot, String> {
    app.state::<AndroidAudio<R>>().set_sleep_timer(payload)
}

#[tauri::command]
fn cancel_sleep_timer<R: Runtime>(app: AppHandle<R>) -> Result<SleepTimerSnapshot, String> {
    app.state::<AndroidAudio<R>>().cancel_sleep_timer()
}

#[tauri::command]
fn get_sleep_timer<R: Runtime>(app: AppHandle<R>) -> Result<SleepTimerSnapshot, String> {
    app.state::<AndroidAudio<R>>().get_sleep_timer()
}

#[tauri::command]
fn sync_alarms<R: Runtime>(
    app: AppHandle<R>,
    payload: SyncAlarmsPayload,
) -> Result<AlarmState, String> {
    app.state::<AndroidAudio<R>>().sync_alarms(payload)
}
#[tauri::command]
fn get_alarm_state<R: Runtime>(app: AppHandle<R>) -> Result<AlarmState, String> {
    android_alarm_state(&app)
}

pub fn android_alarm_state<R: Runtime>(app: &AppHandle<R>) -> Result<AlarmState, String> {
    app.state::<AndroidAudio<R>>().get_alarm_state()
}
#[tauri::command]
fn restore_alarms<R: Runtime>(
    app: AppHandle<R>,
    payload: SyncAlarmsPayload,
) -> Result<AlarmState, String> {
    restore_android_alarms(&app, payload)
}

pub fn restore_android_alarms<R: Runtime>(
    app: &AppHandle<R>,
    payload: SyncAlarmsPayload,
) -> Result<AlarmState, String> {
    app.state::<AndroidAudio<R>>().restore_alarms(payload)
}
#[tauri::command]
fn skip_alarm<R: Runtime>(
    app: AppHandle<R>,
    payload: SkipAlarmPayload,
) -> Result<AlarmState, String> {
    app.state::<AndroidAudio<R>>().skip_alarm(payload)
}
#[tauri::command]
fn snooze_alarm<R: Runtime>(
    app: AppHandle<R>,
    payload: AlarmIdPayload,
) -> Result<AlarmState, String> {
    app.state::<AndroidAudio<R>>().snooze_alarm(payload)
}
#[tauri::command]
fn dismiss_alarm<R: Runtime>(
    app: AppHandle<R>,
    payload: AlarmIdPayload,
) -> Result<AlarmState, String> {
    app.state::<AndroidAudio<R>>().dismiss_alarm(payload)
}
#[tauri::command]
fn test_alarm<R: Runtime>(
    app: AppHandle<R>,
    payload: TestAlarmPayload,
) -> Result<AlarmState, String> {
    app.state::<AndroidAudio<R>>().test_alarm(payload)
}
#[tauri::command]
fn open_alarm_settings<R: Runtime>(
    app: AppHandle<R>,
    payload: AlarmSettingsPayload,
) -> Result<AlarmState, String> {
    app.state::<AndroidAudio<R>>().open_alarm_settings(payload)
}
#[tauri::command]
fn pick_folder<R: Runtime>(app: AppHandle<R>) -> Result<Option<FolderInfo>, String> {
    app.state::<AndroidAudio<R>>().pick_folder()
}
#[tauri::command]
fn folder_info<R: Runtime>(
    app: AppHandle<R>,
    payload: FolderPathPayload,
) -> Result<FolderInfo, String> {
    app.state::<AndroidAudio<R>>().folder_info(payload)
}
#[tauri::command]
fn random_track<R: Runtime>(
    app: AppHandle<R>,
    payload: RandomTrackPayload,
) -> Result<TrackPick, String> {
    app.state::<AndroidAudio<R>>().random_track(payload)
}

#[tauri::command]
fn read_backup_file<R: Runtime>(app: AppHandle<R>) -> Result<Option<String>, String> {
    read_android_backup_file(&app)
}

pub fn read_android_backup_file<R: Runtime>(app: &AppHandle<R>) -> Result<Option<String>, String> {
    app.state::<AndroidAudio<R>>().read_backup_file()
}

#[tauri::command]
fn save_backup_file<R: Runtime>(
    app: AppHandle<R>,
    payload: SaveBackupFilePayload,
) -> Result<Option<String>, String> {
    save_android_backup_file(&app, payload)
}

pub fn save_android_backup_file<R: Runtime>(
    app: &AppHandle<R>,
    payload: SaveBackupFilePayload,
) -> Result<Option<String>, String> {
    app.state::<AndroidAudio<R>>().save_backup_file(payload)
}

pub fn init<R: Runtime>() -> TauriPlugin<R> {
    Builder::new("android-audio")
        .invoke_handler(tauri::generate_handler![
            play,
            pause,
            resume,
            stop,
            get_state,
            set_volume,
            update_artwork,
            set_metadata_enabled,
            set_sleep_timer,
            cancel_sleep_timer,
            get_sleep_timer,
            sync_alarms,
            get_alarm_state,
            restore_alarms,
            skip_alarm,
            snooze_alarm,
            dismiss_alarm,
            test_alarm,
            open_alarm_settings,
            pick_folder,
            folder_info,
            random_track,
            read_backup_file,
            save_backup_file
        ])
        .setup(|app, api| {
            #[cfg(target_os = "android")]
            let audio = mobile::init(app, api).map_err(std::io::Error::other)?;
            #[cfg(not(target_os = "android"))]
            let audio = desktop::init(app, api).map_err(std::io::Error::other)?;
            app.manage(audio);
            Ok(())
        })
        .build()
}
