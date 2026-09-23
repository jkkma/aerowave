# Testing the AeroWave window on Windows

Use PowerShell 7 (`pwsh`) for the helpers in `tools/`. The window finder references
.NET Core assemblies and is not compatible with Windows PowerShell 5.1.

## Find and inspect the correct window

`tools/awwin.ps1` finds the main window by the title `AEROWAVE` and its owning
process. Do not rely on `Get-Process.MainWindowHandle`: it can identify the tiny,
invisible single-instance helper window instead.

`tools/shot.ps1` captures the window. `tools/drive.ps1` uses coordinates relative
to the window in the same space, so choose click coordinates from a fresh capture.

## Keyboard input and focus

`Aw-Key` and `Aw-Type` call `System.Windows.Forms.SendKeys.SendWait`. Previous
testing on this machine found that SendKeys could fall back to journal playback,
report "La operación se completó correctamente" and deliver a character without
a real `keydown`. The webview then saw an empty `e.code`. This made Space and
focus tests appear to fail even when the app's handlers were correct.

For keyboard behavior, send `keybd_event` with both a virtual-key code and a
scan code. The older local work contains an `Aw-Press` helper for this, but it
has not been merged into this version. A minimal Space press in PowerShell 7 is:

```powershell
Add-Type -Namespace AeroWaveTest -Name Keyboard -MemberDefinition @'
[System.Runtime.InteropServices.DllImport("user32.dll")]
public static extern void keybd_event(byte vk, byte scan, uint flags, System.IntPtr extra);
'@
[AeroWaveTest.Keyboard]::keybd_event(0x20, 0x39, 0, [IntPtr]::Zero)
Start-Sleep -Milliseconds 60
[AeroWaveTest.Keyboard]::keybd_event(0x20, 0x39, 2, [IntPtr]::Zero)
```

Dot-source `tools/drive.ps1` to load the window helpers. Before sending keys,
bring the intended test window forward with `Aw-Front`
and click a suitable point inside it with `Aw-Click`. `Aw-Front` restores and
temporarily pins the window on top, but Windows may refuse its request for
foreground focus from a background process. Mouse input through `Aw-Click`
worked in the earlier tests. Do not assume that the window being visible means
it owns keyboard focus. Call `Aw-Release` after testing to remove the temporary
topmost state, ideally from a `finally` block.

Use `Aw-Type` only with awareness of the SendKeys limitation and verify the
resulting field value. Its character delivery is not evidence that shortcut
handling works.

If all input suddenly stops reaching the app, check the interactive desktop
before diagnosing an app bug. In an earlier run, a screensaver took the input
desktop: `GetForegroundWindow()` returned zero and `SetCursorPos` could not move
the pointer. DOM checks still worked, but native interaction was unverified.

## Accessibility checks

WebView2 exposes the page through Windows UI Automation. Load
`UIAutomationClient`, resolve the app with
`System.Windows.Automation.AutomationElement::FromHandle`, and walk its
`TreeWalker::ControlViewWalker` tree. Inspect roles, accessible names, toggle
state, selection state and `HasKeyboardFocus`.

Use the deepest focused element: the window and host panes can also report
focus. For the ringing dialog, check that focus starts on DISMISS, Tab and
Shift+Tab cycle within the dialog, and focus cannot escape into a covered field.
Check that setting names separate the label and its explanatory sub-label.

## Test evidence and local data

Separate pure-logic tests, DOM checks and tests performed in the real window in
the result you report. They establish different things. A station test must
exercise real decoding as well as the HTTP/playlist checks.

Use disposable test data when changing alarms, playback folders or settings.
A `data/` folder beside the executable selects portable storage; without it,
the app uses the user's AppData settings. Check SETUP to confirm the running
copy's storage location. A portable copy under `dist/` can be older than the
source, so confirm which executable you are testing.

Sleep and shutdown checks can interrupt the entire computer. Start with the
stop-playing action and cancellation behavior; arrange an explicit user-approved
test window before actually suspending or shutting down the machine. Preserve
test evidence and follow the Recycle Bin rule when cleaning up test files.

## Closing and quitting

With disposable settings, change volume, Wake PC for alarms, or the selected
sleep action and immediately quit. Verify the saved values after restarting.
Toggle Keep running when closed and immediately close the native window; its
latest value must determine whether the app hides or exits. Repeat with the
app's own Close and Quit controls and tray Quit. Quitting releases process-owned
wake timers; hiding must preserve alarm scheduling.

The frontend tests hold settings writes and readbacks open to check ordering,
newer edits, and failed saves. A failed save must cancel the pending close and
show the error. The native request gate also has a five-second fallback when
the webview never acknowledges a request, so a loading or unresponsive page
does not disable native Quit. Once acknowledged, the request waits for the
save result; the fallback must not close the app over a reported write failure.

A 2026-09-22 disposable WebView2 check exercised production Tauri commands in a
rebuilt app. Changing Keep running when closed and immediately requesting native
close used the new value in both directions, and immediate Quit persisted the
new volume, wake setting, and selected sleep action. Suppressing the webview's
acknowledgement exercised the five-second native Quit fallback. These were
instrumented app checks; Computer Use capture/input was unavailable for the
physical Close and tray-menu controls during this check.

## Power timer checks

`cargo run --manifest-path src-tauri/Cargo.toml --example powercheck` reads the
current Windows capabilities and active AC/battery wake policy, briefly arms a
wake timer for tomorrow, then cancels it. It also acquires/releases the
system and display keep-awake requests, including a change of display state
while the system request remains active. It clears the native request without
changing the adapter's desired state, then verifies that unchanged active flags
restore it. This models a request lost on resume or a power-source change without
actually making either transition. It never requests sleep or shutdown.
Timer acceptance alone does not establish that hardware and the active policy
will resume the PC.

The probe also holds a substitute power call open on the production worker while
the caller prepares an alarm, verifies its native system/display requests and
checks the alarm deadline. It checks delivery of successful and failed results.
It verifies that final dismissal and automatic stopping release alarm requests,
and that completing an earlier power action cannot clear a newer ring's request.
Snooze retains the system request and a future wake timer, but releases the
display until the next ring's preparation window.
This covers a blocked power call without an actual suspend; it does not establish
unattended wake or speaker output on this PC.

On Windows, the probe also sends the production Modern Standby request to an
isolated message-only window that consumes display-power messages. It checks
one delivery to a responsive window, a bounded failure while the window is
not processing messages, and no late delivery when its message loop resumes.
The fixture never forwards display-power requests to Windows' default window
procedure, so this check does not turn off the display or enter standby.

The frontend alarm regressions complete the give-up fade before rejecting an
automatic snooze or dismissal. Check that sound and the watchdog recover, only
accepted snoozes consume the allowance, retries stop at their limit, and a manual
action or newer occurrence prevents obsolete work from taking over the alarm.
Duplicate or older occurrence delivery must not restart playback or its fade.
The regressions also hold an automatic completion open while a manual Dismiss
or Snooze arrives. Its final native action must match the manual choice, including
when it differs from the automatic one. Reload between snooze rounds and check
that the native used allowance survives. A newer occurrence must reject an older
occurrence's completion request.

Sleep durations and the power countdown use elapsed-clock deadlines; core and
frontend tests simulate wall-clock corrections without changing the PC's clock.
Check that replacing a track during the final fade preserves its reduced volume
and completes the fade by the original deadline. Persistence tests inject failed
alarm and wake-setting saves and verify that live settings and scheduler effects
are unchanged, including during the next successful save.

A disposable native WebView2 check on 2026-09-22 verified decoded local audio,
timer restoration after a page reload, manual dismissal overriding an accepted
automatic snooze, and failed alarm/settings writes preserving the live state.
The write failure was a temporary directory at the config's staging-file path;
a later successful save retained the original alarms and wake setting. These
checks did not suspend or shut down Windows and do not establish speaker
audibility or Android device behavior.

`cargo run --manifest-path src-tauri/Cargo.toml --example sourcecheck --locked`
exercises the production folder workers with a stalled preferred-folder fixture
and a responsive backup. It checks separate worker capacity and abandoned queued
work without touching app settings or a real network share. The core tests check
the two-second preference window and five-second deadline, including late results.
The decision limit does not cancel an OS filesystem call. With disposable native
settings, also check cancellation and replacement during resolution and reloading
the page while a scheduled or TEST alarm is resolving its folder.

The core suite covers timer expiry, the cancellable power countdown, replacement
and cancellation before dispatch, alarm precedence, short interrupted clock
gaps, and waking both before an alarm and at its actual deadline. A power timer
whose deadline passed during a clock gap longer than five seconds is cancelled;
this deliberately favors staying on when a suspend or a stalled scheduler made
its countdown unreliable. Alarm catch-up instead checks whether an occurrence
crossed between ticks, including short sleeps that span the whole alarm minute,
and retains the 15-minute grace period.

With disposable portable settings, check Stop audio expiry and recovery after
page reload. Check Sleep PC and Shut down PC selection and cancellation with a
long duration. The countdown is a modal dialog: focus starts on Cancel, Tab stays
there, Escape requests cancellation, and a rejected cancellation remains visible.
A scheduled or TEST alarm closes it and cancels the timer. Do not let a real
power countdown expire outside the approved test window.

Keep Settings open while changing an alarm and the Windows wake policy. The wake
status should reflect a completed timer update immediately and an external policy
change within twenty seconds while the page is active, or on returning to the app.
An unknown wake policy and Modern Standby must remain visibly warned even when
the timer is registered. The status must distinguish a registered wake request
from confirmed hardware/policy support, and must not imply tested S0 wake.
The Sleep PC countdown must also show its alarm-wake warning while automatic
wake is off, blocked or unknown, and clear it when support and policy are confirmed.

For an actual wake test, use a near-future alarm and a known playable local file.
Record the power source, wake policy and sleep state before suspending. Check an
ordinary wake, a snooze, and sleeping again during the 45-second early-wake window.
Repeat with Aerowave's Sleep PC timer initiating sleep: manual Windows sleep
does not exercise the application's pending power call. Verify that the alarm
overlay appears at its deadline and that snooze works after the call completes.
Leave the mouse and keyboard alone until the alarm sounds: user input turns an
unattended timer wake into a different wake path. Check that the display comes on
during the early-wake window and stays on through ringing. Check radio playback,
then an unavailable station with a valid backup folder. Record the media clock,
readiness, volume and Windows audio-session output; an advancing media clock alone
does not prove that the speakers made sound. The display request must release on
dismissal and snooze, and a sleep timer by itself must not hold the display on.
The system request must remain throughout manual and automatic snoozes when
wake-for-alarms is enabled. It must release after final dismissal or automatic
stopping without another snooze, unless another alarm is preparing, ringing or
snoozed or a sleep timer is active. Check scheduled alarms and TEST, including a
ring with no usable audio source. A snooze outside the 45-second preparation
window must retain its future wake timer and system request while releasing the
display request. Check that cancelling the last pending snooze ends the hold,
and that dismissing one alarm leaves another alarm's pending snooze protected.
An explicit Sleep PC timer must release the old snooze hold at dispatch and must
not immediately reassert it after a successful Modern Standby display-off call.
Disabling wake-for-alarms must cancel the snooze hold, preparation and wake timer,
but must not remove the requests of an alarm already ringing. Settings must
describe only the current alarm need;
an idle app must not claim it is keeping the PC awake after an alarm. Completing
or failing an older power action must not release a newer ringing alarm's request.
Perform actual power transitions only within an approved test window.
Repeat on battery only when its wake policy permits it. Modern Standby suspends
desktop applications differently from traditional sleep; report its measured
result separately rather than treating a successfully armed timer as proof.

A 2026-09-22 check of the working-tree fixes used an isolated portable profile
and a downloaded music MP3 on an AC-powered S3 PC. Stop audio expired correctly,
and Sleep PC and Shut down PC timers could be selected and cancelled. A real
Sleep PC timer completed its countdown and entered S3; Windows attributed the
subsequent wake to Aerowave's timer. The scheduled folder alarm began decoding
about 1.5 seconds after its deadline, and the user confirmed hearing the first
ring before touching the PC. Its five-minute automatic stop also completed.
This qualifies one S3 cycle with that local music file, not a radio-stream wake,
battery wake, Modern Standby, or an actual shutdown. The test alarm was cleared,
the test app exited, and the normal profile and startup entries were unchanged.

A separate awake radio check that day reached playable Radio Paradise audio in
about two seconds, and the user confirmed hearing it through the speakers. An
unavailable test station switched to the downloaded music backup and showed
advancing decoded playback. This checks radio startup and backup while awake;
it does not by itself qualify radio playback after sleep.

A subsequent 2026-09-22 radio wake check used the rebuilt app's one-minute Sleep
PC timer and its full 30-second countdown on AC power. Windows recorded actual
S3 entry and attributed the automatic wake to that Aerowave process's timer.
The scheduled Radio Paradise alarm began decoded playback about 2.3 seconds
after its deadline, without falling back to a local file. The user confirmed
that the PC woke and the radio was audible through the 3.5 mm speakers before
any mouse or keyboard input. Windows session and endpoint meters were also
unmuted with positive output. The test alarm was cleared, the process exited,
and the installed portable profile checksum was unchanged. This qualifies one
radio S3 cycle on that PC; battery wake, Modern Standby wake, and actual shutdown
remain separate checks.

Sleep PC selects its Windows method from the same capability facts used to
enable the control. Modern Standby uses a single `WM_SYSCOMMAND` /
`SC_MONITORPOWER` display-off request to the app's main window; traditional
sleep uses `SetSuspendState` with wake events enabled. The main window handle
is captured during setup on the UI thread and passed to the scheduler, so a
committed power action never waits on Tauri to look it up. Keep the main window
responsive, including when it was previously hidden in the tray, and verify
the countdown and any delivery error in the real app. A successful window
message is only request delivery: confirm the subsequent Windows power-state
records. Distinguish the Modern Standby screen-off phase from actual low-power
sleep, and record whether resume occurred at the early-wake deadline, alarm
deadline, or through user input. Do not poll the webview or inject input during
the unattended interval. A screen that turns off and an alarm that later plays
are insufficient by themselves to establish low-power entry or timer wake.

A Windows 11 S0-only check on 2026-09-21 confirmed Modern Standby entry with
Kernel-Power event 506 (`SC_MONITORPOWER`) after the Sleep PC countdown. With
AC wake timers enabled, the armed alarm timer did not resume the machine.
Event 507 instead recorded a later mouse wake, after which the missed-alarm
path decoded and played the station. This qualifies the sleep-entry fix and
catch-up playback on that machine, but not unattended alarm wake or measured
low-power residency. Keep the Modern Standby wake warning in place.
A second cycle with a one-shot Windows Task Scheduler `WakeToRun` task also
missed its requested wake deadline on that PC. Task registration alone does
not establish a working Modern Standby wake alternative.

Modern Standby alarm wake remains unqualified. Microsoft's
[wake-timer instructions](https://learn.microsoft.com/en-us/windows/win32/power/system-wake-up-events)
apply to S3/S4; [desktop apps can be paused during S0 standby](https://learn.microsoft.com/en-us/windows-hardware/design/device-experiences/integrating-apps-with-modern-standby).
A timer context string, a keep-running power request or successful Task Scheduler
registration is not evidence of unattended S0 wake and radio playback. A scheduled
Windows notification is a different delivery mechanism and does not automatically
start this app's radio engine. Qualifying S0 requires an actual compatible test
machine, no injected user input, and separate AC/battery and audible-playback
evidence. The S3-only development PC cannot establish those results. Keep the
warning until that evidence exists.
