//! The parts of Aerowave that are pure logic: parsing what radio servers
//! send back, and deciding when an alarm is due.
//!
//! They live in their own crate for one practical reason - the app crate
//! links Tauri, WebView2 and the Win32 GUI stack, and a test binary built
//! from it will not load. Nothing here depends on any of that, so
//! `cargo test` can actually run these.

pub mod icy;
pub mod schedule;
