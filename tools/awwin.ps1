# Finds the real Aerowave window. Get-Process's MainWindowHandle can land on
# the single-instance helper window, which is 6x6 and invisible, so match on
# the window title instead.
Add-Type -AssemblyName System.Drawing
Add-Type -Namespace AwFind -Name Win -MemberDefinition @'
public delegate bool EnumProc(System.IntPtr h, System.IntPtr l);
[DllImport("user32.dll")] public static extern bool EnumWindows(EnumProc cb, System.IntPtr l);
[DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(System.IntPtr h, out uint pid);
[DllImport("user32.dll", CharSet=CharSet.Unicode)] public static extern int GetWindowTextW(System.IntPtr h, System.Text.StringBuilder s, int n);
[DllImport("user32.dll")] public static extern bool GetWindowRect(System.IntPtr h, out System.Drawing.Rectangle r);
[DllImport("user32.dll")] public static extern bool SetProcessDPIAware();
'@ -ReferencedAssemblies System.Drawing.Primitives -ErrorAction SilentlyContinue

function Get-AerowaveWindow {
    param([string]$Title = "AEROWAVE")
    [void][AwFind.Win]::SetProcessDPIAware()
    $pids = @(Get-Process aerowave -ErrorAction SilentlyContinue | ForEach-Object { $_.Id })
    if (-not $pids) { throw "aerowave is not running" }
    $found = $null
    $cb = [AwFind.Win+EnumProc]{
        param($h, $l)
        $wpid = 0; [void][AwFind.Win]::GetWindowThreadProcessId($h, [ref]$wpid)
        if ($pids -contains $wpid) {
            $sb = New-Object System.Text.StringBuilder 256
            [void][AwFind.Win]::GetWindowTextW($h, $sb, 256)
            if ($sb.ToString() -eq $Title) { $script:found = $h; return $false }
        }
        return $true
    }
    [void][AwFind.Win]::EnumWindows($cb, [IntPtr]::Zero)
    if (-not $script:found) { throw "no window titled '$Title'" }
    $r = New-Object System.Drawing.Rectangle
    [void][AwFind.Win]::GetWindowRect($script:found, [ref]$r)
    [pscustomobject]@{
        Handle = $script:found
        Left   = $r.X
        Top    = $r.Y
        Width  = $r.Width - $r.X
        Height = $r.Height - $r.Y
    }
}
