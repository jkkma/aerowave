---
name: station-triage
description: Diagnose why a radio station will not play in Aerowave, using the real relay outside the app rather than guessing from code.
disable-model-invocation: true
---

# Why this station will not play

Most of the wrong answers here are plausible ones. Work the sequence rather
than reasoning from the symptom — the symptom is usually misleading.

## 0. Read this before diagnosing

`README.md` records measurements taken against 24 radio-browser stations plus
SomaFM. The headline results:

- **`MEDIA_ERR_SRC_NOT_SUPPORTED` (code 4) is not a codec verdict.** Chromium
  reports it for a refused connection and for an HTTP error page too. Two
  stations that looked undecodable were simply refusing connections.
- **HE-AAC / AAC+ is not broadly unsupported** — five of six AAC+ stations
  played directly. That guess has already cost time once.
- **The cause is usually server-side gating, not the format.**

## 1. Reproduce outside the app

```bash
cd src-tauri && cargo run --example relaycheck -- <url>
```

This runs the real `relay.rs` and `stream.rs` against the real broadcaster,
with no Tauri, no WebView2 and no app. It reads the response head straight off
the socket, so it does not depend on the same HTTP client the relay uses.

Read what actually came back: the status line, the content type, whether bytes
followed. A 403 serving `text/html`, a redirect chain that ends nowhere, and a
refused connection all look identical from inside the player.

## 2. If the server refused us

Aerowave presents `Aerowave/<version>`, and that is deliberate. Do not "fix" a
refusal by sending a browser User-Agent: shipping an Edge UA in 0.5.0 broke
every Shoutcast v1 station, which sniff the other way and serve a browser
their admin console instead of the stream. An A/B over 60 stations scored
`Aerowave/<version>` 40 against Edge's 38, with no station where Edge won.

If a station really does gate on the User-Agent, that is a finding to record,
not a reason to change what every other station sees.

## 3. If it is HLS

An `.m3u8` does not go through the relay — proxying breaks HLS. It reaches the
webview through the Tauri custom protocol in `hls.rs`, played by the vendored
hls.js. Two traps:

- **Judge by `readyState >= 3` or `canplay`, never `loadedmetadata`.** HLS
  fires `loadedmetadata` and then stalls at readyState 1 for ever, so
  `loadedmetadata` reports a dead stream as working.
- **Check which player actually ran.** A `currentSrc` starting with `blob:`
  means hls.js and MSE; the `.m3u8` itself means the browser decoded it
  natively.

## 4. Do not trust the Claude Browser pane as a stand-in

It is not WebView2. Its User-Agent contains `Claude/... MSIX`, which some
stations block and which the app never sends — so a station can fail there for
a reason that does not exist in the real app. It also plays HLS natively,
which WebView2 does not, so an HLS test can pass there for entirely the wrong
reason. Autoplay is gated in the pane as well, so `play()` rejects with
AbortError and `currentTime` stays 0 even when decoding is fine. Judge on
readyState.

## 5. When you need the real front end against the real backend

This is the route that has actually found bugs. Serve `src/` with a small HTTP
server that injects a `window.__TAURI__` stub before `app.js` by rewriting the
script tag, then implement `invoke` for enough commands that `boot()` gets to
the end, plus whatever the path under test needs. app.js then boots for real
and its own `play()` and `playable()` run unmodified.

`boot()` itself reaches `get_state` (through `loadState`), `pending_alarm`, and
`config_location` (through `showConfigLocation`). `next_alarm` follows from
`refreshNextAlarm`, and `folder_info` is asked for only when a music folder is
configured — stub all five and the boot path is covered either way.

Pair it with the relay actually running:

```bash
cd src-tauri && cargo run --example relaycheck -- --serve <urls>
```

which prints a url to relay-url table that the stub answers `relay_url` from.
Only the IPC bridge is fake.

Two things that make it work:

- `new Audio()` is detached and never in the DOM, so wrap `window.Audio` in
  the stub to capture the instance and log every media event. The event trace
  — `error` and its `code`, readyState, buffered, currentTime — is what turns
  "it drops sometimes" into a diagnosis.
- Python's `HTTPServer` sets `allow_reuse_address`, so two harnesses can bind
  the same port on Windows and requests go to whichever one wins. A stale
  harness serving an old port table looks exactly like a backend bug. Kill by
  command line, not by port.

## 6. Report

Say what the server actually returned, which path the stream takes (relay,
HLS, or direct), and what would have to change. If the finding contradicts
something in `README.md`, say so explicitly — that file records measurements,
and correcting one is worth more than the fix.
