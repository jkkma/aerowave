[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$ApkPath
)

$ErrorActionPreference = 'Stop'
$resolvedApk = (Resolve-Path -LiteralPath $ApkPath -ErrorAction Stop).Path
$sdkRoot = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else {
    Join-Path $env:LOCALAPPDATA 'Android\Sdk'
}
$commandLineTools = Join-Path $sdkRoot 'cmdline-tools'
$analyzer = Join-Path $commandLineTools 'latest\bin\apkanalyzer.bat'
if (-not (Test-Path -LiteralPath $analyzer -PathType Leaf)) {
    $analyzer = Get-ChildItem -LiteralPath $commandLineTools -Directory -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -as [version] } |
        Sort-Object { [version]$_.Name } -Descending |
        ForEach-Object { Join-Path $_.FullName 'bin\apkanalyzer.bat' } |
        Where-Object { Test-Path -LiteralPath $_ -PathType Leaf } |
        Select-Object -First 1
}
if (-not $analyzer) {
    throw 'Missing apkanalyzer.bat. Install Android SDK command-line tools.'
}

# Inspect the packaged DEX, because source compilation and JVM tests cannot
# detect a JNI entry point removed or renamed by release optimization.
$signature = 'getPluginManager()Lapp/tauri/plugin/PluginManager;'
$output = & $analyzer dex code --class com.aerowave.radio.TauriActivity --method $signature $resolvedApk 2>&1
$analyzerExitCode = $LASTEXITCODE
if ($analyzerExitCode -ne 0) {
    throw "APK startup check failed: Tauri's JNI plugin-manager method is missing or unreadable.`n$($output | Out-String)"
}
$declaration = '(?m)^\.method public(?: final)? getPluginManager\(\)Lapp/tauri/plugin/PluginManager;\s*$'
if (($output | Out-String) -notmatch $declaration) {
    throw "APK startup check failed: Tauri's JNI plugin-manager method must be public, concrete and non-static."
}
Write-Output 'APK startup bridge verified in packaged DEX.'
