[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [ValidateNotNullOrEmpty()]
    [string]$Serial,

    [ValidateRange(1, 10080)]
    [int]$DurationMinutes = 480,

    [ValidateRange(1, 3600)]
    [int]$IntervalSeconds = 60,

    [string]$OutputDirectory,

    [switch]$KeepComputerAwake
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$packageName = 'com.aerowave.radio'
$repoRoot = Split-Path $PSScriptRoot -Parent
$androidHome = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
$adb = Join-Path $androidHome 'platform-tools\adb.exe'
if (-not (Test-Path -LiteralPath $adb -PathType Leaf)) {
    throw "Missing adb.exe: $adb"
}
if ($KeepComputerAwake -and -not [Runtime.InteropServices.RuntimeInformation]::IsOSPlatform(
        [Runtime.InteropServices.OSPlatform]::Windows
    )) {
    throw '-KeepComputerAwake is supported only on Windows.'
}
if ($KeepComputerAwake) {
    Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;

namespace Aerowave.AndroidMonitor {
    public static class NativePower {
        [DllImport("kernel32.dll", SetLastError = true)]
        public static extern uint SetThreadExecutionState(uint executionState);
    }
}
'@
}

if (-not $OutputDirectory) {
    $safeSerial = $Serial -replace '[^A-Za-z0-9._-]', '_'
    $stamp = [DateTime]::UtcNow.ToString('yyyyMMddTHHmmssfffZ')
    $OutputDirectory = Join-Path $repoRoot "dist\evidence\android-soak\$stamp-$safeSerial"
} elseif (-not [IO.Path]::IsPathRooted($OutputDirectory)) {
    $OutputDirectory = Join-Path $repoRoot $OutputDirectory
}
$OutputDirectory = [IO.Path]::GetFullPath($OutputDirectory)
if (Test-Path -LiteralPath $OutputDirectory) {
    throw "Refusing to overwrite an existing Android soak directory: $OutputDirectory"
}
[void](New-Item -ItemType Directory -Path $OutputDirectory)
$sampleDirectory = Join-Path $OutputDirectory 'samples'
[void](New-Item -ItemType Directory -Path $sampleDirectory)
$jsonlPath = Join-Path $OutputDirectory 'samples.jsonl'
$utf8 = [Text.UTF8Encoding]::new($false)

$remoteSnapshotCommand = @'
echo __AEROWAVE_PID__
pidof com.aerowave.radio
echo __AEROWAVE_PACKAGE__
dumpsys package com.aerowave.radio
echo __AEROWAVE_MEDIA_SESSION__
dumpsys media_session
echo __AEROWAVE_AUDIO__
dumpsys audio
echo __AEROWAVE_AUDIO_FLINGER__
dumpsys media.audio_flinger
echo __AEROWAVE_POWER__
dumpsys power
echo __AEROWAVE_BATTERY__
dumpsys battery
echo __AEROWAVE_END__
'@ -replace "`r", ''

function Invoke-AdbSnapshot {
    $startInfo = [Diagnostics.ProcessStartInfo]::new()
    $startInfo.FileName = $adb
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $true
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    [void]$startInfo.ArgumentList.Add('-s')
    [void]$startInfo.ArgumentList.Add($Serial)
    [void]$startInfo.ArgumentList.Add('shell')
    [void]$startInfo.ArgumentList.Add($remoteSnapshotCommand)

    $process = [Diagnostics.Process]::new()
    $process.StartInfo = $startInfo
    try {
        if (-not $process.Start()) {
            return [pscustomobject]@{ ExitCode = -1; Output = ''; Error = 'adb did not start'; TimedOut = $false }
        }
        $stdoutTask = $process.StandardOutput.ReadToEndAsync()
        $stderrTask = $process.StandardError.ReadToEndAsync()
        if (-not $process.WaitForExit(45000)) {
            $process.Kill($true)
            $process.WaitForExit()
            return [pscustomobject]@{
                ExitCode = -1
                Output = $stdoutTask.GetAwaiter().GetResult()
                Error = 'adb snapshot timed out after 45 seconds'
                TimedOut = $true
            }
        }
        return [pscustomobject]@{
            ExitCode = $process.ExitCode
            Output = $stdoutTask.GetAwaiter().GetResult()
            Error = $stderrTask.GetAwaiter().GetResult()
            TimedOut = $false
        }
    } finally {
        $process.Dispose()
    }
}

function Split-SnapshotSections {
    param([string]$Text)

    $markers = @{
        '__AEROWAVE_PID__' = 'pid'
        '__AEROWAVE_PACKAGE__' = 'package'
        '__AEROWAVE_MEDIA_SESSION__' = 'mediaSession'
        '__AEROWAVE_AUDIO__' = 'audio'
        '__AEROWAVE_AUDIO_FLINGER__' = 'audioFlinger'
        '__AEROWAVE_POWER__' = 'power'
        '__AEROWAVE_BATTERY__' = 'battery'
        '__AEROWAVE_END__' = 'end'
    }
    $sections = @{}
    foreach ($name in $markers.Values) { $sections[$name] = [Collections.Generic.List[string]]::new() }
    $current = $null
    foreach ($line in ($Text -split "`r?`n")) {
        $trimmed = $line.Trim()
        if ($markers.ContainsKey($trimmed)) {
            $current = $markers[$trimmed]
            continue
        }
        if ($current -and $current -ne 'end') {
            $sections[$current].Add($line)
        }
    }
    return $sections
}

function First-MatchValue {
    param(
        [string[]]$Lines,
        [string]$Pattern,
        [int]$Group = 1
    )
    foreach ($line in $Lines) {
        $match = [regex]::Match($line, $Pattern)
        if ($match.Success) { return $match.Groups[$Group].Value }
    }
    return $null
}

function Get-MediaSessionEvidence {
    param(
        [string[]]$Lines,
        [string]$AppPid
    )

    $candidates = [Collections.Generic.List[object]]::new()
    for ($index = 0; $index -lt $Lines.Count; $index++) {
        if ($Lines[$index] -notmatch '^\s*package=com\.aerowave\.radio\s*$') { continue }
        $start = [Math]::Max(0, $index - 5)
        $end = [Math]::Min($Lines.Count - 1, $index + 24)
        $block = @($Lines[$start..$end])
        $ownerPid = First-MatchValue -Lines $block -Pattern '^\s*ownerPid=(\d+)'
        $active = First-MatchValue -Lines $block -Pattern '^\s*active=(true|false)'
        $stateLine = $block | Where-Object { $_ -match '^\s*state=PlaybackState \{' } | Select-Object -First 1
        $state = $null
        $position = $null
        $speed = $null
        $updatedElapsedMs = $null
        if ($stateLine) {
            $stateMatch = [regex]::Match(
                $stateLine,
                'state=PlaybackState \{state=(\d+), position=(-?\d+), buffered position=-?\d+, speed=([-0-9.]+), updated=(\d+)'
            )
            if ($stateMatch.Success) {
                $state = [int]$stateMatch.Groups[1].Value
                $position = [int64]$stateMatch.Groups[2].Value
                $speed = [double]::Parse($stateMatch.Groups[3].Value, [Globalization.CultureInfo]::InvariantCulture)
                $updatedElapsedMs = [int64]$stateMatch.Groups[4].Value
            }
        }
        $candidates.Add([pscustomobject]@{
            ownerPid = $ownerPid
            active = if ($null -eq $active) { $null } else { $active -eq 'true' }
            playbackState = $state
            reportedPositionMs = $position
            speed = $speed
            updatedElapsedMs = $updatedElapsedMs
        })
    }
    if ($AppPid) {
        $matching = $candidates | Where-Object ownerPid -eq $AppPid | Select-Object -First 1
        if ($matching) { return $matching }
    }
    return $candidates | Select-Object -First 1
}

function Get-AudioFlingerEvidence {
    param(
        [string[]]$Lines,
        [string]$AppPid
    )

    if (-not $AppPid -or ($Lines -join "`n") -match "Can't find service|Permission Denial") {
        return [pscustomobject]@{ available = $false; trackPidSeen = $false; active = $false; frames = $null; pidLines = @() }
    }
    $pidPattern = "(?<!\d)$([regex]::Escape($AppPid))(?!\d)"
    $safeLines = @($Lines | Where-Object {
        $_ -match $pidPattern -and
        $_ -notmatch '^\s*\d\d-\d\d\s+\d\d:' -and
        $_ -notmatch 'com\.aerowave\.radio'
    } | Select-Object -First 8 | ForEach-Object {
        (($_ -replace '\s+', ' ').Trim()).Substring(0, [Math]::Min(300, (($_ -replace '\s+', ' ').Trim()).Length))
    })
    $active = [bool]($safeLines | Where-Object { $_ -match '(?i)\byes\b|\bactive\b' } | Select-Object -First 1)
    return [pscustomobject]@{
        available = $true
        trackPidSeen = $safeLines.Count -gt 0
        active = $active
        # OEM AudioFlinger tables do not expose a stable, labelled per-track
        # frame field. Leave this null unless a future parser can identify one.
        frames = $null
        pidLines = $safeLines
    }
}

function Get-AudioRouteEvidence {
    param([string[]]$Lines)

    $musicIndex = -1
    for ($index = 0; $index -lt $Lines.Count; $index++) {
        if ($Lines[$index] -match '^\s*- STREAM_MUSIC:') { $musicIndex = $index; break }
    }
    if ($musicIndex -lt 0) { return [pscustomobject]@{ muted = $null; devices = $null } }
    $end = [Math]::Min($Lines.Count - 1, $musicIndex + 15)
    $block = @($Lines[$musicIndex..$end])
    $muted = First-MatchValue -Lines $block -Pattern '^\s*Muted:\s*(true|false)'
    $devices = First-MatchValue -Lines $block -Pattern '^\s*Devices:\s*(.+?)\s*$'
    return [pscustomobject]@{
        muted = if ($null -eq $muted) { $null } else { $muted -eq 'true' }
        devices = $devices
    }
}

function Get-KeyValueEvidence {
    param(
        [string[]]$Lines,
        [hashtable]$Patterns
    )
    $result = [ordered]@{}
    foreach ($name in $Patterns.Keys) {
        $result[$name] = First-MatchValue -Lines $Lines -Pattern $Patterns[$name]
    }
    return [pscustomobject]$result
}

function Write-SampleText {
    param(
        [pscustomobject]$Sample,
        [string]$Path
    )
    $lines = @(
        "UTC: $($Sample.utc)",
        "Elapsed seconds: $($Sample.elapsedSeconds)",
        "ADB connected: $($Sample.adbConnected)",
        "App PID: $($Sample.pid)",
        "App UID: $($Sample.uid)",
        "Version: $($Sample.versionName) ($($Sample.versionCode))",
        "MediaSession present: $($Sample.mediaSession.present)",
        "MediaSession state: $($Sample.mediaSession.playbackState)",
        "MediaSession reports playing: $($Sample.mediaSession.playing)",
        "MediaSession reported position ms: $($Sample.mediaSession.reportedPositionMs)",
        "MediaSession position comparison: $($Sample.mediaSession.positionComparison)",
        "Reported position advanced since comparable sample: $($Sample.mediaSession.positionAdvanced)",
        'Position is MediaSession state and may be extrapolated; it is not decoded-audio evidence.',
        "AudioFlinger available: $($Sample.audioFlinger.available)",
        "AudioFlinger track PID evidence: $($Sample.audioFlinger.trackPidSeen)",
        "AudioFlinger active-track evidence: $($Sample.audioFlinger.active)",
        "AudioFlinger labelled per-track frames: $($Sample.audioFlinger.frames)",
        "Music muted: $($Sample.audioRoute.muted)",
        "Music devices: $($Sample.audioRoute.devices)",
        "Wakefulness: $($Sample.power.wakefulness)",
        "Battery level: $($Sample.battery.level)",
        "Battery plugged: $($Sample.battery.plugged)",
        "Issues: $($Sample.issues -join ', ')"
    )
    if ($Sample.audioFlinger.pidLines.Count -gt 0) {
        $lines += 'Filtered AudioFlinger PID rows:'
        $lines += $Sample.audioFlinger.pidLines | ForEach-Object { "  $_" }
    }
    [IO.File]::WriteAllText($Path, ($lines -join [Environment]::NewLine) + [Environment]::NewLine, $utf8)
}

function Write-MonitorSummary {
    param(
        [Parameter(Mandatory)][string]$Status,
        [Parameter(Mandatory)][DateTime]$StartedUtc,
        [Parameter(Mandatory)][DateTime]$EndedUtc,
        [Parameter(Mandatory)][double]$ActualDurationSeconds,
        [Parameter(Mandatory)][Collections.IDictionary]$Counts
    )

    $summary = [ordered]@{
        schemaVersion = 1
        status = $Status
        applicationId = $packageName
        serial = $Serial
        startedUtc = $StartedUtc.ToString('o')
        endedUtc = $EndedUtc.ToString('o')
        requestedDurationMinutes = $DurationMinutes
        actualDurationSeconds = [Math]::Round($ActualDurationSeconds, 3)
        intervalSeconds = $IntervalSeconds
        counts = $Counts
        directEvidence = 'AudioFlinger PID/active-track observations when available; raw filtered rows are retained per sample.'
        inferenceOnly = 'MediaSession state and reported position can be extrapolated and do not prove decoded or audible output.'
        privacy = 'No logcat or unfiltered global dumps were persisted.'
    }
    [IO.File]::WriteAllText(
        (Join-Path $OutputDirectory 'summary.json'),
        ($summary | ConvertTo-Json -Depth 8),
        $utf8
    )
    $summaryText = @(
        "Status: $Status",
        "Samples: $($Counts.samples)",
        "ADB disconnect/failure samples: $($Counts.adbDisconnected)",
        "App-missing samples: $($Counts.appMissing)",
        "App PID changes: $($Counts.appRestarts)",
        "MediaSession not-playing samples: $($Counts.mediaNotPlaying)",
        "Comparable reported positions: $($Counts.comparablePositions)",
        "Reported positions advanced: $($Counts.positionAdvanced)",
        "AudioFlinger track-PID-evidence samples: $($Counts.audioFlingerTrackPidEvidence)",
        "AudioFlinger active-evidence samples: $($Counts.audioFlingerActiveEvidence)",
        "Playing samples missing active AudioFlinger track evidence: $($Counts.audioFlingerMissingWhilePlaying)",
        'MediaSession position is inference only; it is not decoded-audio evidence.',
        "Evidence directory: $OutputDirectory"
    ) -join [Environment]::NewLine
    [IO.File]::WriteAllText((Join-Path $OutputDirectory 'summary.txt'), $summaryText + [Environment]::NewLine, $utf8)
    return $summaryText
}

$runStartUtc = [DateTime]::UtcNow
$runManifest = [ordered]@{
    schemaVersion = 1
    applicationId = $packageName
    serial = $Serial
    requestedDurationMinutes = $DurationMinutes
    intervalSeconds = $IntervalSeconds
    keepComputerAwake = [bool]$KeepComputerAwake
    startedUtc = $runStartUtc.ToString('o')
    readOnly = $true
    telemetryBoundary = 'Filtered package/PID/UID evidence only; MediaSession position is not decoded-audio proof.'
} | ConvertTo-Json
[IO.File]::WriteAllText((Join-Path $OutputDirectory 'run.json'), $runManifest, $utf8)

$stopwatch = [Diagnostics.Stopwatch]::StartNew()
$deadlineSeconds = [double]$DurationMinutes * 60.0
$sampleNumber = 0
$completed = $false
$previousPlayingPublication = $null
$previousPid = $null
$counts = [ordered]@{
    samples = 0
    adbDisconnected = 0
    appMissing = 0
    appRestarts = 0
    mediaNotPlaying = 0
    comparablePositions = 0
    positionAdvanced = 0
    audioFlingerTrackPidEvidence = 0
    audioFlingerActiveEvidence = 0
    audioFlingerMissingWhilePlaying = 0
}
[void](Write-MonitorSummary -Status 'running' -StartedUtc $runStartUtc -EndedUtc ([DateTime]::UtcNow) `
    -ActualDurationSeconds 0 -Counts $counts)

$executionStateHeld = $false
try {
    if ($KeepComputerAwake) {
        # Prevent automatic system sleep only for this monitor thread. This
        # does not wake the display or modify any persistent power plan.
        $continuousSystemRequired = [Convert]::ToUInt32('80000001', 16)
        if ([Aerowave.AndroidMonitor.NativePower]::SetThreadExecutionState($continuousSystemRequired) -eq 0) {
            throw "SetThreadExecutionState failed with Win32 error $([Runtime.InteropServices.Marshal]::GetLastWin32Error())."
        }
        $executionStateHeld = $true
    }
    while ($stopwatch.Elapsed.TotalSeconds -lt $deadlineSeconds) {
        $sampleNumber++
        $sampleUtc = [DateTime]::UtcNow
        $snapshot = Invoke-AdbSnapshot
        $connected = $snapshot.ExitCode -eq 0 -and $snapshot.Output -match '__AEROWAVE_END__'
        $issues = [Collections.Generic.List[string]]::new()
        $appPid = $null
        $uid = $null
        $versionName = $null
        $versionCode = $null
        $media = $null
        $flinger = [pscustomobject]@{ available = $false; trackPidSeen = $false; active = $false; frames = $null; pidLines = @() }
        $route = [pscustomobject]@{ muted = $null; devices = $null }
        $power = [pscustomobject]@{ wakefulness = $null; suspendBlocker = $null }
        $battery = [pscustomobject]@{ level = $null; status = $null; plugged = $null; temperature = $null; voltage = $null }
        $positionAdvanced = $null
        $positionComparison = 'not_playing'

        if (-not $connected) {
            $counts.adbDisconnected++
            $issues.Add('adb_disconnected_or_snapshot_failed')
        } else {
            $sections = Split-SnapshotSections -Text $snapshot.Output
            $appPid = First-MatchValue -Lines $sections.pid.ToArray() -Pattern '^\s*(\d+)(?:\s|$)'
            $uid = First-MatchValue -Lines $sections.package.ToArray() -Pattern '^\s*userId=(\d+)'
            $versionName = First-MatchValue -Lines $sections.package.ToArray() -Pattern '^\s*versionName=(\S+)'
            $versionCode = First-MatchValue -Lines $sections.package.ToArray() -Pattern '^\s*versionCode=(\d+)'
            if (-not $appPid) {
                $counts.appMissing++
                $issues.Add('app_process_missing')
            } elseif ($previousPid -and $appPid -ne $previousPid) {
                $counts.appRestarts++
                $issues.Add('app_pid_changed')
            }
            if ($appPid) { $previousPid = $appPid }

            $media = Get-MediaSessionEvidence -Lines $sections.mediaSession.ToArray() -AppPid $appPid
            if (-not $media) {
                $media = [pscustomobject]@{
                    ownerPid = $null; active = $null; playbackState = $null
                    reportedPositionMs = $null; speed = $null; updatedElapsedMs = $null
                }
            }
            $playing = $media.playbackState -eq 3
            if ($appPid -and -not $playing) {
                $counts.mediaNotPlaying++
                $issues.Add('media_session_not_playing')
            }
            if ($playing) {
                if (-not $appPid) {
                    $positionComparison = 'unavailable_app_pid'
                    $previousPlayingPublication = $null
                } elseif ($null -eq $media.reportedPositionMs -or $media.reportedPositionMs -lt 0) {
                    $positionComparison = 'unavailable_position'
                    $previousPlayingPublication = $null
                } elseif ($null -eq $media.speed -or $media.speed -le 0) {
                    $positionComparison = 'unavailable_nonpositive_speed'
                    $previousPlayingPublication = $null
                } elseif ($null -eq $media.updatedElapsedMs) {
                    $positionComparison = 'unavailable_publication_time'
                    $previousPlayingPublication = $null
                } elseif ($null -eq $previousPlayingPublication -or
                    $previousPlayingPublication.pid -ne $appPid) {
                    $positionComparison = 'baseline'
                    $previousPlayingPublication = [pscustomobject]@{
                        pid = $appPid
                        positionMs = $media.reportedPositionMs
                        updatedElapsedMs = $media.updatedElapsedMs
                    }
                } elseif ($media.updatedElapsedMs -eq $previousPlayingPublication.updatedElapsedMs) {
                    # dumpsys can repeat the same published MediaSession event.
                    # Re-reading it is not an independent progress observation.
                    $positionComparison = 'unchanged_publication'
                } elseif ($media.updatedElapsedMs -lt $previousPlayingPublication.updatedElapsedMs -or
                    $media.reportedPositionMs -lt $previousPlayingPublication.positionMs) {
                    $positionComparison = 'publication_discontinuity'
                    $previousPlayingPublication = [pscustomobject]@{
                        pid = $appPid
                        positionMs = $media.reportedPositionMs
                        updatedElapsedMs = $media.updatedElapsedMs
                    }
                } else {
                    $counts.comparablePositions++
                    $positionAdvanced = $media.reportedPositionMs -gt $previousPlayingPublication.positionMs
                    $positionComparison = if ($positionAdvanced) { 'advanced' } else { 'not_advanced' }
                    if ($positionAdvanced) {
                        $counts.positionAdvanced++
                    } else {
                        $issues.Add('media_session_reported_position_not_advanced')
                    }
                    $previousPlayingPublication = [pscustomobject]@{
                        pid = $appPid
                        positionMs = $media.reportedPositionMs
                        updatedElapsedMs = $media.updatedElapsedMs
                    }
                }
            } else {
                $previousPlayingPublication = $null
            }

            $flinger = Get-AudioFlingerEvidence -Lines $sections.audioFlinger.ToArray() -AppPid $appPid
            if ($flinger.trackPidSeen) { $counts.audioFlingerTrackPidEvidence++ }
            if ($flinger.active) { $counts.audioFlingerActiveEvidence++ }
            if ($playing -and (-not $flinger.trackPidSeen -or -not $flinger.active)) {
                $counts.audioFlingerMissingWhilePlaying++
                $issues.Add('no_active_audio_flinger_track_evidence')
            }
            $route = Get-AudioRouteEvidence -Lines $sections.audio.ToArray()
            $power = Get-KeyValueEvidence -Lines $sections.power.ToArray() -Patterns @{
                wakefulness = '^\s*mWakefulness=(\S+)'
                suspendBlocker = '^\s*mHoldingWakeLockSuspendBlocker=(true|false)'
            }
            $battery = Get-KeyValueEvidence -Lines $sections.battery.ToArray() -Patterns @{
                level = '^\s*level:\s*(\d+)'
                status = '^\s*status:\s*(\d+)'
                plugged = '^\s*plugged:\s*(\d+)'
                temperature = '^\s*temperature:\s*(\d+)'
                voltage = '^\s*voltage:\s*(\d+)'
            }
        }

        $stderr = ($snapshot.Error -replace '\s+', ' ').Trim()
        if ($stderr.Length -gt 300) { $stderr = $stderr.Substring(0, 300) }
        $sample = [pscustomobject][ordered]@{
            schemaVersion = 1
            sample = $sampleNumber
            utc = $sampleUtc.ToString('o')
            elapsedSeconds = [Math]::Round($stopwatch.Elapsed.TotalSeconds, 3)
            adbConnected = $connected
            adbExitCode = $snapshot.ExitCode
            adbTimedOut = $snapshot.TimedOut
            adbError = if ($stderr) { $stderr } else { $null }
            pid = $appPid
            uid = $uid
            versionName = $versionName
            versionCode = if ($versionCode) { [int64]$versionCode } else { $null }
            mediaSession = [pscustomobject]@{
                present = $null -ne $media -and $null -ne $media.ownerPid
                ownerPid = if ($media) { $media.ownerPid } else { $null }
                active = if ($media) { $media.active } else { $null }
                playbackState = if ($media) { $media.playbackState } else { $null }
                playing = $null -ne $media -and $media.playbackState -eq 3
                reportedPositionMs = if ($media) { $media.reportedPositionMs } else { $null }
                reportedSpeed = if ($media) { $media.speed } else { $null }
                reportedUpdatedElapsedMs = if ($media) { $media.updatedElapsedMs } else { $null }
                positionComparison = $positionComparison
                positionAdvanced = $positionAdvanced
                evidenceBoundary = 'Reported state may be extrapolated and does not prove decoded audio.'
            }
            audioFlinger = $flinger
            audioRoute = $route
            power = $power
            battery = $battery
            issues = $issues.ToArray()
        }
        $counts.samples++
        [IO.File]::AppendAllText($jsonlPath, ($sample | ConvertTo-Json -Compress -Depth 8) + "`n", $utf8)
        $samplePath = Join-Path $sampleDirectory ('{0:D6}.txt' -f $sampleNumber)
        Write-SampleText -Sample $sample -Path $samplePath
        [void](Write-MonitorSummary -Status 'running' -StartedUtc $runStartUtc -EndedUtc ([DateTime]::UtcNow) `
            -ActualDurationSeconds $stopwatch.Elapsed.TotalSeconds -Counts $counts)
        Write-Output ("[{0}] sample {1}: adb={2} pid={3} playing={4} rendererPid={5} issues={6}" -f
            $sample.utc, $sampleNumber, $connected, $appPid, $sample.mediaSession.playing,
            $sample.audioFlinger.trackPidSeen, ($sample.issues -join ','))

        $nextSampleAt = [Math]::Min($deadlineSeconds, $sampleNumber * [double]$IntervalSeconds)
        while ($stopwatch.Elapsed.TotalSeconds -lt $nextSampleAt) {
            $remaining = $nextSampleAt - $stopwatch.Elapsed.TotalSeconds
            Start-Sleep -Milliseconds ([Math]::Max(1, [Math]::Min(5000, [int][Math]::Ceiling($remaining * 1000))))
        }
    }
    $completed = $true
} finally {
    try {
        $stopwatch.Stop()
        $endedUtc = [DateTime]::UtcNow
        $hasReliabilityFailure = $counts.adbDisconnected -gt 0 -or
            $counts.appMissing -gt 0 -or
            $counts.mediaNotPlaying -gt 0 -or
            $counts.audioFlingerMissingWhilePlaying -gt 0 -or
            ($counts.comparablePositions -gt 0 -and $counts.positionAdvanced -lt $counts.comparablePositions)
        $status = if (-not $completed) { 'interrupted' } elseif ($hasReliabilityFailure) { 'issues-detected' } else { 'completed-without-detected-issues' }
        $summaryText = Write-MonitorSummary -Status $status -StartedUtc $runStartUtc -EndedUtc $endedUtc `
            -ActualDurationSeconds $stopwatch.Elapsed.TotalSeconds -Counts $counts
        Write-Output $summaryText
    } finally {
        if ($executionStateHeld) {
            $continuous = [Convert]::ToUInt32('80000000', 16)
            if ([Aerowave.AndroidMonitor.NativePower]::SetThreadExecutionState($continuous) -eq 0) {
                throw "Could not release the Windows sleep-prevention request; Win32 error $([Runtime.InteropServices.Marshal]::GetLastWin32Error())."
            }
            $executionStateHeld = $false
        }
    }
}

if ($hasReliabilityFailure) {
    exit 2
}
