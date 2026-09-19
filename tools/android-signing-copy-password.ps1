[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'android-signing.ps1')

$material = Get-AerowaveAndroidSigningMaterial
$password = $material.Password
try {
    Set-Clipboard -Value $password
    Write-Output 'The release-key password is on the clipboard. Save it in a password manager now.'
    Write-Output 'Press Enter after saving it; this script will clear the clipboard if it is unchanged.'
    [void](Read-Host)
} finally {
    if ((Get-Clipboard -Raw) -eq $password) {
        Set-Clipboard -Value ''
    }
    $password = $null
    $material = $null
}
