Set-StrictMode -Version Latest

$script:AerowaveSigningSchemaVersion = 1
$script:AerowaveSigningApplicationId = 'com.aerowave.radio'
$script:AerowaveSigningAlias = 'aerowave-release'
$script:AerowaveSigningKeyStoreName = 'aerowave-release.p12'
$script:AerowaveSigningCredentialName = 'credentials.dpapi'
$script:AerowaveSigningManifestName = 'signing-manifest.json'

function Get-AerowaveAndroidSigningRoot {
    if ($env:AEROWAVE_ANDROID_SIGNING_ROOT) {
        return [IO.Path]::GetFullPath($env:AEROWAVE_ANDROID_SIGNING_ROOT)
    }
    if (-not $env:LOCALAPPDATA) {
        throw 'LOCALAPPDATA is unavailable. Set AEROWAVE_ANDROID_SIGNING_ROOT to a private local directory.'
    }
    return [IO.Path]::GetFullPath((Join-Path $env:LOCALAPPDATA 'Aerowave\signing'))
}

function Get-AerowaveAndroidSigningPaths {
    $root = Get-AerowaveAndroidSigningRoot
    return [pscustomobject]@{
        Root = $root
        KeyStore = Join-Path $root $script:AerowaveSigningKeyStoreName
        Credential = Join-Path $root $script:AerowaveSigningCredentialName
        Manifest = Join-Path $root $script:AerowaveSigningManifestName
    }
}

function Get-AerowaveKeytool {
    $candidates = [Collections.Generic.List[string]]::new()
    if ($env:JAVA_HOME) {
        $candidates.Add((Join-Path $env:JAVA_HOME 'bin\keytool.exe'))
    }
    if ($env:ProgramFiles) {
        $candidates.Add((Join-Path $env:ProgramFiles 'Android\Android Studio\jbr\bin\keytool.exe'))
    }
    if ($env:LOCALAPPDATA) {
        $jdkRoot = Join-Path $env:LOCALAPPDATA 'Android\jdk'
        Get-ChildItem -LiteralPath $jdkRoot -Directory -ErrorAction SilentlyContinue |
            Sort-Object Name -Descending |
            ForEach-Object { $candidates.Add((Join-Path $_.FullName 'bin\keytool.exe')) }
    }
    foreach ($candidate in $candidates) {
        if (Test-Path -LiteralPath $candidate -PathType Leaf) {
            return $candidate
        }
    }
    throw 'Could not find keytool.exe. Set JAVA_HOME to a JDK 21 installation.'
}

function Invoke-AerowaveWithEnvironment {
    param(
        [Parameter(Mandatory)]
        [hashtable]$Variables,
        [Parameter(Mandatory)]
        [scriptblock]$Action
    )

    $saved = @{}
    foreach ($name in $Variables.Keys) {
        $saved[$name] = [pscustomobject]@{
            Present = Test-Path -LiteralPath "Env:$name"
            Value = [Environment]::GetEnvironmentVariable($name, 'Process')
        }
    }
    try {
        foreach ($name in $Variables.Keys) {
            [Environment]::SetEnvironmentVariable($name, [string]$Variables[$name], 'Process')
        }
        & $Action
    } finally {
        foreach ($name in $Variables.Keys) {
            if ($saved[$name].Present) {
                [Environment]::SetEnvironmentVariable($name, $saved[$name].Value, 'Process')
            } else {
                Remove-Item -LiteralPath "Env:$name" -ErrorAction SilentlyContinue
            }
        }
    }
}

function Get-AerowaveRandomPassword {
    $bytes = [byte[]]::new(32)
    [Security.Cryptography.RandomNumberGenerator]::Fill($bytes)
    try {
        return [Convert]::ToBase64String($bytes)
    } finally {
        [Array]::Clear($bytes, 0, $bytes.Length)
    }
}

function Protect-AerowaveSigningDirectory {
    param([Parameter(Mandatory)][string]$Path)

    $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
    if (-not $identity.User) {
        throw 'Could not determine the current Windows user SID for the signing directory ACL.'
    }
    $acl = Get-Acl -LiteralPath $Path
    $acl.SetAccessRuleProtection($true, $false)
    foreach ($rule in @($acl.Access)) {
        [void]$acl.RemoveAccessRuleSpecific($rule)
    }
    $rule = [Security.AccessControl.FileSystemAccessRule]::new(
        $identity.User,
        [Security.AccessControl.FileSystemRights]::FullControl,
        [Security.AccessControl.InheritanceFlags]'ContainerInherit, ObjectInherit',
        [Security.AccessControl.PropagationFlags]::None,
        [Security.AccessControl.AccessControlType]::Allow
    )
    $acl.AddAccessRule($rule)
    Set-Acl -LiteralPath $Path -AclObject $acl
}

function Test-AerowaveSigningDirectoryAcl {
    param([Parameter(Mandatory)][string]$Path)

    $currentSid = [Security.Principal.WindowsIdentity]::GetCurrent().User.Value
    $acl = Get-Acl -LiteralPath $Path
    $unexpected = @($acl.Access | Where-Object {
        $_.AccessControlType -eq [Security.AccessControl.AccessControlType]::Allow -and
        $_.IdentityReference.Translate([Security.Principal.SecurityIdentifier]).Value -ne $currentSid
    })
    if (-not $acl.AreAccessRulesProtected -or $unexpected.Count -gt 0) {
        throw "The signing directory ACL is not restricted to the current Windows user: $Path"
    }
}

function Invoke-AerowaveKeytool {
    param(
        [Parameter(Mandatory)][string]$Password,
        [Parameter(Mandatory)][string[]]$Arguments
    )

    $keytool = Get-AerowaveKeytool
    $result = Invoke-AerowaveWithEnvironment -Variables @{ AEROWAVE_KEYTOOL_PASSWORD = $Password } -Action {
        $text = & $keytool '-J-Duser.language=en' '-J-Duser.country=US' @Arguments 2>&1
        return [pscustomobject]@{
            ExitCode = $LASTEXITCODE
            Output = ($text | Out-String)
        }
    }
    if ($result.ExitCode -ne 0) {
        throw "keytool failed with exit code $($result.ExitCode). Its output did not contain signing credentials.`n$($result.Output)"
    }
    return $result.Output
}

function Get-AerowaveCertificateSha256 {
    param(
        [Parameter(Mandatory)][string]$KeyStore,
        [Parameter(Mandatory)][string]$Alias,
        [Parameter(Mandatory)][string]$Password
    )

    $output = Invoke-AerowaveKeytool -Password $Password -Arguments @(
        '-list', '-v',
        '-keystore', $KeyStore,
        '-storetype', 'PKCS12',
        '-alias', $Alias,
        '-storepass:env', 'AEROWAVE_KEYTOOL_PASSWORD'
    )
    $match = [regex]::Match($output, '(?im)^\s*SHA256:\s*([0-9A-F:]+)\s*$')
    if (-not $match.Success) {
        throw 'keytool did not report the release certificate SHA-256 fingerprint.'
    }
    return $match.Groups[1].Value.Replace(':', '').ToUpperInvariant()
}

function Unprotect-AerowaveSigningPassword {
    param([Parameter(Mandatory)][string]$CredentialPath)

    try {
        $protected = [IO.File]::ReadAllText($CredentialPath).Trim()
        if (-not $protected) { throw 'The protected credential file is empty.' }
        $secure = ConvertTo-SecureString -String $protected
        $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
        try {
            return [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)
        } finally {
            [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer)
            $secure.Dispose()
        }
    } catch {
        throw "The Android signing credential could not be decrypted for this Windows user. The existing signing material was not changed. $($_.Exception.Message)"
    }
}

function Get-AerowaveAndroidSigningMaterial {
    $paths = Get-AerowaveAndroidSigningPaths
    foreach ($required in @($paths.Root, $paths.KeyStore, $paths.Credential, $paths.Manifest)) {
        if (-not (Test-Path -LiteralPath $required)) {
            throw "Android signing material is incomplete at $($paths.Root). Nothing was regenerated. Restore the original signing directory from backup."
        }
    }
    Test-AerowaveSigningDirectoryAcl -Path $paths.Root
    try {
        $manifest = [IO.File]::ReadAllText($paths.Manifest) | ConvertFrom-Json -ErrorAction Stop
    } catch {
        throw "The Android signing manifest is corrupt. Nothing was regenerated. Restore the original signing directory from backup. $($_.Exception.Message)"
    }
    if ($manifest.schemaVersion -ne $script:AerowaveSigningSchemaVersion -or
        $manifest.applicationId -ne $script:AerowaveSigningApplicationId -or
        $manifest.alias -ne $script:AerowaveSigningAlias -or
        $manifest.keyStoreFile -ne $script:AerowaveSigningKeyStoreName -or
        $manifest.protectedCredentialFile -ne $script:AerowaveSigningCredentialName -or
        $manifest.certificateSha256 -notmatch '^[0-9A-F]{64}$') {
        throw 'The Android signing manifest has unexpected values. Nothing was regenerated or replaced.'
    }
    $password = Unprotect-AerowaveSigningPassword -CredentialPath $paths.Credential
    $actualFingerprint = Get-AerowaveCertificateSha256 -KeyStore $paths.KeyStore -Alias $manifest.alias -Password $password
    if ($actualFingerprint -ne $manifest.certificateSha256) {
        throw 'The Android signing key does not match its manifest certificate. Nothing was regenerated or replaced.'
    }
    return [pscustomobject]@{
        Root = $paths.Root
        KeyStore = $paths.KeyStore
        Alias = [string]$manifest.alias
        Password = $password
        CertificateSha256 = $actualFingerprint
        CreatedUtc = [string]$manifest.createdUtc
    }
}

function Initialize-AerowaveAndroidSigning {
    if (-not $IsWindows) {
        throw 'Aerowave Android signing initialization uses Windows DPAPI and must run on Windows.'
    }
    $paths = Get-AerowaveAndroidSigningPaths
    if (Test-Path -LiteralPath $paths.Root) {
        $material = Get-AerowaveAndroidSigningMaterial
        Write-Output "Android release signing already exists at $($material.Root)"
        Write-Output "Certificate SHA-256: $($material.CertificateSha256)"
        return
    }

    [void](New-Item -ItemType Directory -Path $paths.Root)
    Protect-AerowaveSigningDirectory -Path $paths.Root
    Test-AerowaveSigningDirectoryAcl -Path $paths.Root

    $password = Get-AerowaveRandomPassword
    try {
        $secure = ConvertTo-SecureString -String $password -AsPlainText -Force
        try {
            $protected = ConvertFrom-SecureString -SecureString $secure
            [IO.File]::WriteAllText($paths.Credential, $protected, [Text.UTF8Encoding]::new($false))
        } finally {
            $secure.Dispose()
        }
        [void](Invoke-AerowaveKeytool -Password $password -Arguments @(
            '-genkeypair',
            '-keystore', $paths.KeyStore,
            '-storetype', 'PKCS12',
            '-alias', $script:AerowaveSigningAlias,
            '-keyalg', 'RSA',
            '-keysize', '4096',
            '-validity', '10000',
            '-dname', 'CN=Aerowave Android Release, O=Aerowave',
            '-storepass:env', 'AEROWAVE_KEYTOOL_PASSWORD',
            '-keypass:env', 'AEROWAVE_KEYTOOL_PASSWORD'
        ))
        $fingerprint = Get-AerowaveCertificateSha256 -KeyStore $paths.KeyStore -Alias $script:AerowaveSigningAlias -Password $password
        $manifest = [ordered]@{
            schemaVersion = $script:AerowaveSigningSchemaVersion
            applicationId = $script:AerowaveSigningApplicationId
            alias = $script:AerowaveSigningAlias
            keyStoreFile = $script:AerowaveSigningKeyStoreName
            protectedCredentialFile = $script:AerowaveSigningCredentialName
            certificateSha256 = $fingerprint
            createdUtc = [DateTime]::UtcNow.ToString('o')
        } | ConvertTo-Json
        [IO.File]::WriteAllText($paths.Manifest, $manifest, [Text.UTF8Encoding]::new($false))
        Test-AerowaveSigningDirectoryAcl -Path $paths.Root
        Write-Output "Created Android release signing at $($paths.Root)"
        Write-Output "Certificate SHA-256: $fingerprint"
    } finally {
        $password = $null
    }
}
