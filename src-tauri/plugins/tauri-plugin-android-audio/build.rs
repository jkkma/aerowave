const COMMANDS: &[&str] = &[
    "play",
    "pause",
    "resume",
    "stop",
    "get_state",
    "set_volume",
    "update_artwork",
    "set_metadata_enabled",
    "set_sleep_timer",
    "cancel_sleep_timer",
    "get_sleep_timer",
    "sync_alarms",
    "get_alarm_state",
    "snooze_alarm",
    "dismiss_alarm",
    "test_alarm",
    "open_alarm_settings",
    "pick_folder",
    "folder_info",
    "random_track",
];

fn main() {
    tauri_plugin::Builder::new(COMMANDS)
        .android_path("android")
        .try_build()
        .expect("failed to build the android-audio Tauri plugin");
}
