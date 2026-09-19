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
    PlayPayload, PlaybackState, SleepTimer, SleepTimerPayload, SleepTimerSnapshot, VolumePayload,
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

pub fn init<R: Runtime>() -> TauriPlugin<R> {
    Builder::new("android-audio")
        .invoke_handler(tauri::generate_handler![
            play,
            pause,
            resume,
            stop,
            get_state,
            set_volume,
            set_sleep_timer,
            cancel_sleep_timer,
            get_sleep_timer
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
