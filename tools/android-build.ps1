[CmdletBinding()]
param(
    [switch]$Release,
    [ValidateSet('aarch64', 'armv7', 'i686', 'x86_64')]
    [string]$Target = 'aarch64'
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path $PSScriptRoot -Parent
. (Join-Path $PSScriptRoot 'android-signing.ps1')

function Get-ApkSigner {
    $buildToolsRoot = Join-Path $env:ANDROID_HOME 'build-tools'
    $candidate = Get-ChildItem -LiteralPath $buildToolsRoot -Directory -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -as [version] } |
        Sort-Object { [version]$_.Name } -Descending |
        ForEach-Object { Join-Path $_.FullName 'apksigner.bat' } |
        Where-Object { Test-Path -LiteralPath $_ -PathType Leaf } |
        Select-Object -First 1
    if (-not $candidate) {
        throw "Missing apksigner.bat below $buildToolsRoot. Install Android SDK build-tools 36.0.0."
    }
    return $candidate
}

function Confirm-ApkTargetAbi {
    param(
        [Parameter(Mandatory)][string]$ApkPath,
        [Parameter(Mandatory)][string]$Target
    )

    $targetAbis = @{
        aarch64 = 'arm64-v8a'
        armv7 = 'armeabi-v7a'
        i686 = 'x86'
        x86_64 = 'x86_64'
    }
    $expectedAbi = $targetAbis[$Target]
    if (-not $expectedAbi) {
        throw "No Android ABI mapping is defined for Tauri target $Target."
    }

    $archive = [IO.Compression.ZipFile]::OpenRead($ApkPath)
    try {
        $nativeEntries = @($archive.Entries | Where-Object {
            $_.FullName -match '^lib/([^/]+)/[^/]+\.so$'
        })
        $packagedAbis = @($nativeEntries |
            ForEach-Object { [regex]::Match($_.FullName, '^lib/([^/]+)/').Groups[1].Value } |
            Sort-Object -Unique)
        if ($packagedAbis.Count -ne 1 -or $packagedAbis[0] -ne $expectedAbi) {
            $actual = if ($packagedAbis.Count) { $packagedAbis -join ', ' } else { '<none>' }
            throw "The release APK for $Target must contain only ABI $expectedAbi; found $actual."
        }
        if (-not ($nativeEntries.FullName -contains "lib/$expectedAbi/libaerowave_lib.so")) {
            throw "The release APK is missing lib/$expectedAbi/libaerowave_lib.so."
        }
    } finally {
        $archive.Dispose()
    }
    return $expectedAbi
}

function Publish-VerifiedReleaseApk {
    param(
        [Parameter(Mandatory)][string]$Target,
        [Parameter(Mandatory)][string]$ExpectedCertificateSha256
    )

    $outputRoot = Join-Path $repoRoot 'src-tauri\gen\android\app\build\outputs\apk'
    $metadataFiles = @(Get-ChildItem -LiteralPath $outputRoot -Filter 'output-metadata.json' -Recurse -File |
        Where-Object { $_.Directory.Name -eq 'release' })
    if ($metadataFiles.Count -ne 1) {
        throw "Expected one release APK metadata file below $outputRoot, found $($metadataFiles.Count)."
    }
    $metadata = [IO.File]::ReadAllText($metadataFiles[0].FullName) | ConvertFrom-Json -ErrorAction Stop
    if ($metadata.applicationId -ne 'com.aerowave.radio' -or $metadata.elements.Count -ne 1) {
        throw 'Android release output metadata has an unexpected application ID or artifact count.'
    }
    $element = $metadata.elements[0]
    $sourceApk = Join-Path $metadataFiles[0].Directory.FullName $element.outputFile
    if (-not (Test-Path -LiteralPath $sourceApk -PathType Leaf)) {
        throw "The release APK recorded by Gradle is missing: $sourceApk"
    }
    $androidAbi = Confirm-ApkTargetAbi -ApkPath $sourceApk -Target $Target
    & (Join-Path $PSScriptRoot 'android-check-startup.ps1') -ApkPath $sourceApk

    $packageVersion = ([IO.File]::ReadAllText((Join-Path $repoRoot 'package.json')) | ConvertFrom-Json).version
    if ($element.versionName -ne $packageVersion) {
        throw "The APK version $($element.versionName) does not match package.json version $packageVersion."
    }
    $versionMatch = [regex]::Match($packageVersion, '^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$')
    if (-not $versionMatch.Success) {
        throw "Android releases require a stable major.minor.patch version; found $packageVersion."
    }
    $expectedVersionCode = [int64]$versionMatch.Groups[1].Value * 1000000L +
        [int64]$versionMatch.Groups[2].Value * 1000L +
        [int64]$versionMatch.Groups[3].Value
    if ([int64]$element.versionCode -ne $expectedVersionCode) {
        throw "The APK version code $($element.versionCode) does not match derived code $expectedVersionCode."
    }

    $timestamp = [DateTime]::UtcNow.ToString('yyyyMMddTHHmmssfffZ')
    $retainedRoot = Join-Path $repoRoot 'dist\android-release'
    $retainedDirectory = Join-Path $retainedRoot "$timestamp-v$packageVersion-$Target"
    if (Test-Path -LiteralPath $retainedDirectory) {
        throw "Refusing to overwrite retained Android release directory: $retainedDirectory"
    }
    [void](New-Item -ItemType Directory -Path $retainedDirectory)
    $retainedApk = Join-Path $retainedDirectory "Aerowave-$packageVersion-android-$Target.apk"
    if (Test-Path -LiteralPath $retainedApk) {
        throw "Refusing to overwrite retained Android release APK: $retainedApk"
    }
    Copy-Item -LiteralPath $sourceApk -Destination $retainedApk

    $apkSigner = Get-ApkSigner
    $verificationOutput = & $apkSigner verify --verbose --print-certs $retainedApk 2>&1
    $verifyExitCode = $LASTEXITCODE
    if ($verifyExitCode -ne 0) {
        throw "apksigner verification failed with exit code $verifyExitCode.`n$($verificationOutput | Out-String)"
    }
    $certificateMatch = [regex]::Match(
        ($verificationOutput | Out-String),
        '(?im)^(?:Signer #1 certificate|V[0-9]+(?:\.[0-9]+)? Signer: certificate) SHA-256 digest:\s*([0-9a-f]+)\s*$'
    )
    if (-not $certificateMatch.Success) {
        throw 'apksigner did not report a SHA-256 signing-certificate digest.'
    }
    $certificateSha256 = $certificateMatch.Groups[1].Value.ToUpperInvariant()
    if ($certificateSha256 -ne $ExpectedCertificateSha256) {
        throw 'The retained APK certificate does not match the persistent Aerowave release key.'
    }
    $apkSha256 = (Get-FileHash -LiteralPath $retainedApk -Algorithm SHA256).Hash.ToUpperInvariant()
    $gitCommit = (& git -C $repoRoot rev-parse HEAD).Trim()
    if ($LASTEXITCODE -ne 0) { throw 'Could not record the source Git commit for the Android artifact.' }
    $sourceDirty = [bool]((& git -C $repoRoot status --porcelain --untracked-files=normal) | Select-Object -First 1)
    if ($LASTEXITCODE -ne 0) { throw 'Could not record whether the Android artifact source tree was dirty.' }
    $artifactManifest = [ordered]@{
        schemaVersion = 1
        applicationId = [string]$metadata.applicationId
        versionName = [string]$element.versionName
        versionCode = [int64]$element.versionCode
        target = $Target
        androidAbi = $androidAbi
        apkFile = [IO.Path]::GetFileName($retainedApk)
        apkSha256 = $apkSha256
        certificateSha256 = $certificateSha256
        verifiedBy = 'apksigner verify --verbose --print-certs'
        sourceCommit = $gitCommit
        sourceDirty = $sourceDirty
        createdUtc = [DateTime]::UtcNow.ToString('o')
    } | ConvertTo-Json
    $artifactManifestPath = Join-Path $retainedDirectory 'manifest.json'
    [IO.File]::WriteAllText($artifactManifestPath, $artifactManifest, [Text.UTF8Encoding]::new($false))

    Write-Output "Retained signed APK: $retainedApk"
    Write-Output "APK SHA-256: $apkSha256"
    Write-Output "Certificate SHA-256: $certificateSha256"
    Write-Output "Artifact manifest: $artifactManifestPath"
}

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
    if (-not $Release) {
        $buildArgs += '--debug'
        & npx.cmd @buildArgs
        if ($LASTEXITCODE -ne 0) { throw "Android build failed with exit code $LASTEXITCODE" }
    } else {
        $signing = Get-AerowaveAndroidSigningMaterial
        try {
            # A single-use Gradle daemon keeps the decrypted credentials from
            # surviving in a reusable background process after this build.
            $gradleOptions = @($env:GRADLE_OPTS, '-Dorg.gradle.daemon=false') |
                Where-Object { $_ } |
                Join-String -Separator ' '
            Invoke-AerowaveWithEnvironment -Variables @{
                AEROWAVE_ANDROID_KEYSTORE_FILE = $signing.KeyStore
                AEROWAVE_ANDROID_STORE_PASSWORD = $signing.Password
                AEROWAVE_ANDROID_KEY_ALIAS = $signing.Alias
                AEROWAVE_ANDROID_KEY_PASSWORD = $signing.Password
                GRADLE_OPTS = $gradleOptions
            } -Action {
                & npx.cmd @buildArgs
                if ($LASTEXITCODE -ne 0) { throw "Android release build failed with exit code $LASTEXITCODE" }
            }
            Publish-VerifiedReleaseApk -Target $Target -ExpectedCertificateSha256 $signing.CertificateSha256
        } finally {
            $signing = $null
        }
    }
} finally {
    Pop-Location
}
