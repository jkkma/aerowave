# Drives the Aerowave window with real mouse and keyboard input, for
# end-to-end testing. Coordinates are given in window pixels, the same space
# tools/shot.ps1 captures in, so you can read them straight off a screenshot.
#
#   . ./tools/drive.ps1
#   Aw-Click 700 900
#   Aw-Type "https://example.com/stream"
#   Aw-Key "{ENTER}"

Add-Type -AssemblyName System.Windows.Forms
. "$PSScriptRoot/awwin.ps1"
Add-Type -Namespace AwIn -Name Win -MemberDefinition @'
[DllImport("user32.dll")] public static extern bool SetCursorPos(int x, int y);
[DllImport("user32.dll")] public static extern void mouse_event(uint f, uint x, uint y, uint d, System.IntPtr e);
[DllImport("user32.dll")] public static extern bool SetForegroundWindow(System.IntPtr h);
[DllImport("user32.dll")] public static extern bool SetWindowPos(System.IntPtr h, System.IntPtr after, int x, int y, int cx, int cy, uint flags);
[DllImport("user32.dll")] public static extern bool ShowWindow(System.IntPtr h, int cmd);
'@ -ErrorAction SilentlyContinue

function Aw-Window { Get-AerowaveWindow }

# SetForegroundWindow is refused when the caller is not already in front, so
# clicks would land on whatever window happens to be on top. Pin the window
# above everything for the duration of a test run instead.
function Aw-Front {
    $w = Aw-Window
    [void][AwIn.Win]::ShowWindow($w.Handle, 9)                              # SW_RESTORE
    [void][AwIn.Win]::SetWindowPos($w.Handle, [IntPtr]-1, 0, 0, 0, 0, 0x0043)  # HWND_TOPMOST
    [void][AwIn.Win]::SetForegroundWindow($w.Handle)
    Start-Sleep -Milliseconds 500
}

function Aw-Release {
    $w = Aw-Window
    [void][AwIn.Win]::SetWindowPos($w.Handle, [IntPtr]-2, 0, 0, 0, 0, 0x0043)  # HWND_NOTOPMOST
}

function Aw-Focus { Aw-Front }

function Aw-Click {
    param([int]$X, [int]$Y, [int]$SettleMs = 500)
    $w = Aw-Window
    [void][AwIn.Win]::SetCursorPos($w.Left + $X, $w.Top + $Y)
    Start-Sleep -Milliseconds 140
    [AwIn.Win]::mouse_event(0x0002, 0, 0, 0, [IntPtr]::Zero)   # left down
    Start-Sleep -Milliseconds 70
    [AwIn.Win]::mouse_event(0x0004, 0, 0, 0, [IntPtr]::Zero)   # left up
    Start-Sleep -Milliseconds $SettleMs
}

function Aw-Type {
    param([string]$Text, [int]$SettleMs = 250)
    [System.Windows.Forms.SendKeys]::SendWait($Text)
    Start-Sleep -Milliseconds $SettleMs
}

function Aw-Key {
    param([string]$Keys, [int]$SettleMs = 400)
    [System.Windows.Forms.SendKeys]::SendWait($Keys)
    Start-Sleep -Milliseconds $SettleMs
}
