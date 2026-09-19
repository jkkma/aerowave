use std::marker::PhantomData;

use tauri::{plugin::PluginApi, AppHandle, Runtime};

use crate::models::{PlayPayload, PlaybackState, VolumePayload};

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
}
