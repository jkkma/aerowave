[CmdletBinding()]
param(
    [switch]$Release,
    [ValidateSet('aarch64', 'armv7', 'i686', 'x86_64')]
    [string]$Target = 'aarch64'
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path $PSScriptRoot -Parent

if (-not $env:ANDROID_HOME) {
    $env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
}
if (-not $env:JAVA_HOME) {
    $studioJava = Join-Path $env:ProgramFiles 'Android\Android Studio\jbr'
    $localJava = Get-ChildItem (Join-Path $env:LOCALAPPDATA 'Android\jdk') -Directory -ErrorAction SilentlyContinue |
        Sort-Object Name -Descending | Select-Object -First 1
    if (Test-Path (Join-Path $studioJava 'bin\java.exe')) {
        $env:JAVA_HOME = $studioJava
    } elseif ($localJava) {
        $env:JAVA_HOME = $localJava.FullName
    } else {
        throw 'Set JAVA_HOME to a JDK 21 installation, then run this script again.'
    }
}
if (-not $env:NDK_HOME) {
    $env:NDK_HOME = Join-Path $env:ANDROID_HOME 'ndk\28.2.13676358'
}
foreach ($required in @(
    (Join-Path $env:JAVA_HOME 'bin\java.exe'),
    (Join-Path $env:NDK_HOME 'source.properties'),
    (Join-Path $env:ANDROID_HOME 'platforms\android-36\android.jar')
)) {
    if (-not (Test-Path -LiteralPath $required)) {
        throw "Missing Android build prerequisite: $required. See docs/android.md."
    }
}
$env:PATH = "$(Join-Path $env:USERPROFILE '.cargo\bin');$env:JAVA_HOME\bin;$env:ANDROID_HOME\platform-tools;$env:PATH"

Push-Location $repoRoot
try {
    $buildArgs = @('tauri', 'android', 'build', '--target', $Target, '--apk', '--ci')
    if (-not $Release) { $buildArgs += '--debug' }
    & npx.cmd @buildArgs
    if ($LASTEXITCODE -ne 0) { throw "Android build failed with exit code $LASTEXITCODE" }
} finally {
    Pop-Location
}
