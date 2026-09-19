const COMMANDS: &[&str] = &[
    "play",
    "pause",
    "resume",
    "stop",
    "get_state",
    "set_volume",
    "set_sleep_timer",
    "cancel_sleep_timer",
    "get_sleep_timer",
];

fn main() {
    tauri_plugin::Builder::new(COMMANDS)
        .android_path("android")
        .try_build()
        .expect("failed to build the android-audio Tauri plugin");
}
