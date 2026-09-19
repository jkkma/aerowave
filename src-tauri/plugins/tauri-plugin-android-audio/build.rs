const COMMANDS: &[&str] = &["play", "pause", "resume", "stop", "get_state", "set_volume"];

fn main() {
    tauri_plugin::Builder::new(COMMANDS)
        .android_path("android")
        .try_build()
        .expect("failed to build the android-audio Tauri plugin");
}
