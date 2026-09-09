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

For keyboard behavior, use the existing `Aw-Press` helper. It sends
`keybd_event` with both a virtual-key code and a scan code. It supports Space,
Tab, Enter, Escape and the arrow keys, with an optional Shift modifier:

```powershell
. ./tools/drive.ps1
Aw-Press space
Aw-Press tab -Shift
```

Before sending keys, bring the intended test window forward with `Aw-Front`
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
