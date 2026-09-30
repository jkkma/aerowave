# Alarm reliability verification

These checks cover the emergency-tone, settings-recovery, one-shot completion,
and readiness changes. Automated passes do not qualify an audible unattended
wake on a particular PC or phone. Run device checks on a disposable test setup,
with a separate alarm clock available; do not edit the only copy of real settings.

## Automated checks

Run `npm test`, then
`cargo check --manifest-path src-tauri/Cargo.toml --workspace --locked`.
See [automated checks](automated-checks.md) for CI coverage and prerequisites.

The frontend tests exercise failed media and backup paths, stale callbacks,
native-tone completion/fade recovery, protected settings, editor arm revisions,
and bounded readiness checks. Core tests exercise the tone's samples/cancellation,
one-shot receipt state transitions and persistence fault ordering. Bridge-model
tests serialize Android media volume/output fields through the production Rust
model so a Kotlin-only field cannot disappear before reaching the page.

## Desktop sound and cancellation

- Schedule a near-term alarm using an unavailable station and no usable backup.
  Confirm the built-in tone is audible through the intended output, the card
  explains the fallback, and normal listening never invokes this tone
- Repeat with corrupt/undecodable backup files and a stalled network or folder
  source. Confirm the bounded media attempts eventually reach the tone
- Dismiss and snooze during connection, source scanning, and native output
  startup. Wait past the source timeout: old callbacks must not restart sound
- Reload the WebView while the emergency output is starting and while it is
  playing. Confirm only the emergency tone resumes, with the original give-up
  deadline and snooze tally
- Confirm the volume control, give-up fade, auto-snooze, manual dismissal, and
  test-alarm dismissal all control the native tone. A rejected completion must
  leave the occurrence available for manual control and restore its volume
- Try muted output, no output device, and a disconnected Bluetooth device.
  Readiness must not call these audible; device-opening failure must be visible.
  The tone uses the OS default output and does not override OS mute or routing

## Settings and one-shot durability

Use an isolated portable `data` directory and retain all original test files.

- Make `aerowave.json` unreadable or malformed, then start the app. Try ordinary
  listening and settings edits: the original bytes must remain unchanged
- Repair/unlock the original and choose Retry reading saved settings. The actual
  file must replace the temporary defaults. A missing original is still a
  recovery error, not permission to save empty defaults
- Restore a backup while recovery is needed. Confirm original settings and
  receipt bytes are preserved first. If they cannot be read/preserved, stop
  before replacement. Imported alarms must remain off
- Force the one-shot settings write to fail, while allowing its receipt write.
  Confirm the alarm does not repeat after restart and completion saves retry
- Force both writes to fail. Confirm no next-day repeat in the same running
  process and an explicit warning that restart protection is not yet durable.
  Repair storage, retry, then restart and verify the completed alarm stays off
- Open an editor before a one-shot fires. Saving its stale arm must be rejected;
  refreshing and explicitly re-enabling must create a new arm. Legitimate snoozes
  must still ring, including after an unrelated alarm/settings save

## Readiness and device qualification

- Inspect enabled, disabled, missing-station, inaccessible-folder and no-next-
  alarm cases. Repeated clicks and timed-out scans must not queue unlimited work
- On Android compare media-volume index/mute with the phone's own controls.
  Connect/disconnect wired or Bluetooth audio and recheck. The device list is
  available outputs, not a verified active route
- Change settings while a check is pending, leave/return to the app, and recover
  settings. Old results must be invalidated or replaced by fresh status
- Build and run the Android plugin's `AlarmAudioReadinessTest` in the generated
  Android Gradle project, then test on the actual phone with screen off, DND,
  battery restrictions, reboot/unlock, and source failure. The foreground Test
  sound button and granted permissions do not establish unattended wake
- Repeat relevant Windows tests in [windows-testing.md](windows-testing.md).
  Earlier Windows S3/AC results do not qualify this new audio path, Modern
  Standby, battery operation, or shutdown. A shut-down PC cannot run the alarm
