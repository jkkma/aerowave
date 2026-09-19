[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'android-signing.ps1')

$paths = Get-AerowaveAndroidSigningPaths
foreach ($required in @($paths.Root, $paths.KeyStore, $paths.Manifest)) {
    if (-not (Test-Path -LiteralPath $required)) {
        throw "Recovery requires the original keystore and manifest at $($paths.Root)."
    }
}
if (Test-Path -LiteralPath $paths.Credential) {
    throw 'The protected credential already exists. Recovery will not replace it.'
}

try {
    $manifest = [IO.File]::ReadAllText($paths.Manifest) | ConvertFrom-Json -ErrorAction Stop
} catch {
    throw "The restored signing manifest is corrupt. Nothing was changed. $($_.Exception.Message)"
}
if ($manifest.schemaVersion -ne $script:AerowaveSigningSchemaVersion -or
    $manifest.applicationId -ne $script:AerowaveSigningApplicationId -or
    $manifest.alias -ne $script:AerowaveSigningAlias -or
    $manifest.keyStoreFile -ne $script:AerowaveSigningKeyStoreName -or
    $manifest.protectedCredentialFile -ne $script:AerowaveSigningCredentialName -or
    $manifest.certificateSha256 -notmatch '^[0-9A-F]{64}$') {
    throw 'The restored Android signing manifest has unexpected values. Nothing was changed.'
}

$securePassword = Read-Host 'Release-key password' -AsSecureString
$pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($securePassword)
try {
    $password = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)
    $fingerprint = Get-AerowaveCertificateSha256 -KeyStore $paths.KeyStore -Alias $manifest.alias -Password $password
    if ($fingerprint -ne $manifest.certificateSha256) {
        throw 'The restored keystore certificate does not match the signing manifest. Nothing was changed.'
    }
    Protect-AerowaveSigningDirectory -Path $paths.Root
    $protected = ConvertFrom-SecureString -SecureString $securePassword
    [IO.File]::WriteAllText($paths.Credential, $protected, [Text.UTF8Encoding]::new($false))
    Test-AerowaveSigningDirectoryAcl -Path $paths.Root
    Write-Output "Recovered the DPAPI credential at $($paths.Root)"
    Write-Output "Certificate SHA-256: $fingerprint"
} finally {
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer)
    $password = $null
    $securePassword.Dispose()
}
