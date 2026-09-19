use tauri::{
    plugin::{PluginApi, PluginHandle},
    AppHandle, Runtime,
};

use crate::models::{PlayPayload, PlaybackState, VolumePayload};

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
}
