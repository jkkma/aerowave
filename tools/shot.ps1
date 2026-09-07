# Capture the Aerowave window with PrintWindow(PW_RENDERFULLCONTENT).
# CopyFromScreen does not work from a non-interactive shell, and this renders
# the WebView2 content correctly without needing the window in the foreground.
param([string]$Out = "")

. "$PSScriptRoot/awwin.ps1"
Add-Type -AssemblyName System.Drawing.Common
Add-Type -Namespace AwShot -Name Win -MemberDefinition @'
[DllImport("user32.dll")] public static extern bool PrintWindow(System.IntPtr h, System.IntPtr dc, uint flags);
'@ -ErrorAction SilentlyContinue

if (-not $Out) { $Out = Join-Path $env:TEMP "aerowave-shot.png" }

$win = Get-AerowaveWindow
$bmp = New-Object System.Drawing.Bitmap $win.Width, $win.Height
$gfx = [System.Drawing.Graphics]::FromImage($bmp)
$dc = $gfx.GetHdc()
$ok = [AwShot.Win]::PrintWindow($win.Handle, $dc, 2)   # PW_RENDERFULLCONTENT
$gfx.ReleaseHdc($dc)
$bmp.Save($Out, [System.Drawing.Imaging.ImageFormat]::Png)
$gfx.Dispose(); $bmp.Dispose()
Write-Output "$Out  $($win.Width)x$($win.Height)  PrintWindow=$ok"
