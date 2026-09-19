[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'android-signing.ps1')

Initialize-AerowaveAndroidSigning
