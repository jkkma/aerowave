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

## Power timer checks

`cargo run --manifest-path src-tauri/Cargo.toml --example powercheck` reads the
current Windows capabilities and active AC/battery wake policy, briefly arms a
wake timer for tomorrow, then cancels it. It also acquires/releases the system
keep-awake request. It never requests sleep or shutdown. Timer acceptance alone
does not establish that hardware and the active policy will resume the PC.

The core suite covers timer expiry, the cancellable power countdown, replacement
and cancellation before dispatch, alarm precedence, short interrupted clock
gaps, and waking both before an alarm and at its actual deadline. A power timer
whose deadline passed during a clock gap longer than five seconds is cancelled;
this deliberately favors staying on when a suspend or a stalled scheduler made
its countdown unreliable. The separate 90-second alarm catch-up rule is unchanged.

With disposable portable settings, check Stop audio expiry and recovery after
page reload. Check Sleep PC and Shut down PC selection and cancellation with a
long duration. The countdown is a modal dialog: focus starts on Cancel, Tab stays
there, Escape requests cancellation, and a rejected cancellation remains visible.
A scheduled or TEST alarm closes it and cancels the timer. Do not let a real
power countdown expire outside the approved test window.

For an actual wake test, use a near-future alarm and a known playable local file.
Record the power source, wake policy and sleep state before suspending. Check an
ordinary wake, a snooze, and sleeping again during the 45-second early-wake window.
Repeat on battery only when its wake policy permits it. Modern Standby suspends
desktop applications differently from traditional sleep; report its measured
result separately rather than treating a successfully armed timer as proof.
