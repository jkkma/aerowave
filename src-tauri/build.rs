fn main() {
    // Only Android loads the cdylib. MinGW otherwise auto-exports the desktop
    // GUI dependencies too, overflowing PE's 16-bit export ordinal table.
    if std::env::var("CARGO_CFG_TARGET_OS").as_deref() == Ok("windows")
        && std::env::var("CARGO_CFG_TARGET_ENV").as_deref() == Ok("gnu")
    {
        println!("cargo:rustc-cdylib-link-arg=-Wl,--exclude-all-symbols");
    }
    tauri_build::build()
}
