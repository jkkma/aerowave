---
name: station-triage
description: Diagnose AeroWave playback failures using this checkout's probe_stream command, actual media events and native WebView2 validation.
---

# Diagnose station playback

Read README.md, AGENTS.md and the player code before diagnosing. This checkout
plays streams directly through its media element, resolves playlists through
probe_stream, and reports HLS as unsupported. It has no relay.rs, hls.rs or
relaycheck example. Do not run commands or assume measurements from a newer
version of the application.

## 1. Capture the failure

Record the station URL without exposing private tokens, the expected behavior,
and whether the test used the installed app, a portable copy or a browser.
Observe probe_stream's result and the actual media element's error,
readyState, currentSrc, playing/canplay events and play() rejection. Inspect
play(), canDecode() and reconnect handling in src/app.js, and probe()/resolve()
in src-tauri/src/stream.rs.

An HTTP success is not proof of decoding. A Chromium media error alone does
not distinguish a rejected request, an HTML response or an unsupported codec.
Separate the server response from what the native decoder actually did.

## 2. Trace the current path

Check redirects and .pls/.m3u playlist resolution, response status and content
type, whether media bytes arrive, and whether the resolved URL changes during
retry. Preserve query strings. Treat HLS detection according to the code in
this checkout; do not invent a local proxy or silently add a new playback
engine as part of diagnosis.

Compare the server and native player evidence before changing headers or
codec handling. README measurements are historical context and may need
reproduction against the current station.

## 3. Check fallback and alarms

Verify bounded reconnects and fallback to the configured backup folder when
the station gives up. Check missing stations, unsupported media and folder
failures separately. Preserve the visible alarm failure when no backup can
play; there is no synthesized fallback tone.

Do not alter the user's real stations or alarms for a test. Use an isolated
portable copy with its own data folder, following docs/windows-testing.md.
An installed single-instance copy can intercept a dev launch; identify the
running copy before concluding that the changed build started.

## 4. Validate and report

Use the actual native WebView2 app for the final playback check. A browser
preview may differ in autoplay, cross-origin access and decoding support.
Record what each environment established.

Run the applicable pure logic checks after a code fix:

```powershell
cargo test --manifest-path src-tauri/Cargo.toml -p aerowave-core
python -B tools/hooks/check_seam.py
```

Report the server response, selected URL/path, media events, fallback outcome
and any remaining uncertainty. A readiness event, a successful build or a
passing pure test alone does not establish sustained playback or alarm sound.
