//! The parts of Aerowave that are pure logic: parsing what radio servers
//! send back, deciding when an alarm is due, and what a ringing one holds
//! on to between its snoozes.
//!
//! They live in their own crate for one practical reason - the app crate
//! links Tauri, WebView2 and the Win32 GUI stack, and a test binary built
//! from it will not load. Nothing here depends on any of that, so
//! `cargo test` can actually run these.

pub mod directory;
pub mod icy;
pub mod network;
pub mod ring;
pub mod schedule;
pub mod settings;
