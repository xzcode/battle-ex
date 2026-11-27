#Requires -RunAsAdministrator
[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][string]$AgentPath,
    [Parameter(Mandatory=$true)][ValidatePattern('^[a-fA-F0-9]{64}$')][string]$AgentSha256,
    [Parameter(Mandatory=$true)][string]$WintunPath,
    [Parameter(Mandatory=$true)][ValidatePattern('^[a-fA-F0-9]{64}$')][string]$WintunSha256,
    [Parameter(Mandatory=$true)][string]$Origin,
    [Parameter(Mandatory=$true)][ValidatePattern('^[a-f0-9]{64}$')][string]$Issuer,
    [Parameter(Mandatory=$true)][string]$AllowedUserSid
)
$ErrorActionPreference = 'Stop'
if(-not [Environment]::Is64BitProcess){throw 'Use 64-bit Windows PowerShell for the x64 service kit.'}
if([Environment]::GetEnvironmentVariable('PROCESSOR_ARCHITECTURE') -ne 'AMD64'){throw 'This first kit targets Windows x64.'}
$name='BattleEXNetwork'
$uri=[Uri]$Origin
if($uri.Scheme -ne 'https' -or $uri.UserInfo -or $uri.AbsolutePath -ne '/' -or $uri.Query -or $uri.Fragment){
    throw 'Provide only the trusted HTTPS platform origin (no path, credentials, query or fragment).'
}
$Origin=$uri.GetLeftPart([UriPartial]::Authority)
if($Issuer -eq ('0'*64)){throw 'Provide the genuine platform signing public key.'}
$sid=[System.Security.Principal.SecurityIdentifier]::new($AllowedUserSid)
if($sid.Value -notmatch '^S-1-(5-21-[0-9]+-[0-9]+-[0-9]+-[0-9]+|12-1-[0-9]+-[0-9]+-[0-9]+-[0-9]+)$'){throw 'Select a specific local/domain user SID, not a broad group.'}
# The service also resolves this SID and requires SidTypeUser.
$null=$sid.Translate([System.Security.Principal.NTAccount])
$agent=(Resolve-Path -LiteralPath $AgentPath).ProviderPath
$driver=(Resolve-Path -LiteralPath $WintunPath).ProviderPath
if((Get-FileHash -LiteralPath $agent -Algorithm SHA256).Hash -ne $AgentSha256){throw 'Agent SHA-256 mismatch.'}
if((Get-FileHash -LiteralPath $driver -Algorithm SHA256).Hash -ne $WintunSha256){throw 'Wintun SHA-256 mismatch.'}
if((Get-AuthenticodeSignature -LiteralPath $driver).Status -ne 'Valid'){throw 'Use a valid Authenticode-signed Wintun DLL from a trusted official distribution.'}
$root=Join-Path ([Environment]::GetFolderPath('ProgramFiles')) 'Battle EX Network Service'
$exe=Join-Path $root 'battle-agent.exe'
if((Get-Service -Name $name -ErrorAction SilentlyContinue) -or (Test-Path -LiteralPath $root)){
    throw 'An installation or retained state already exists. This is a first-install kit; no silent upgrade or overwrite.'
}
$created=$false
try {
    $null=New-Item -ItemType Directory -Path $root
    $created=$true
    $acl=New-Object System.Security.AccessControl.DirectorySecurity
    $acl.SetAccessRuleProtection($true,$false)
    foreach($principal in @('S-1-5-18','S-1-5-32-544')){
        $identity=[System.Security.Principal.SecurityIdentifier]::new($principal)
        $rule=[System.Security.AccessControl.FileSystemAccessRule]::new($identity,[System.Security.AccessControl.FileSystemRights]::FullControl,([System.Security.AccessControl.InheritanceFlags]::ContainerInherit -bor [System.Security.AccessControl.InheritanceFlags]::ObjectInherit),[System.Security.AccessControl.PropagationFlags]::None,[System.Security.AccessControl.AccessControlType]::Allow)
        $acl.AddAccessRule($rule)
    }
    $adminSid=[System.Security.Principal.SecurityIdentifier]::new('S-1-5-32-544')
    $acl.SetOwner($adminSid)
    Set-Acl -LiteralPath $root -AclObject $acl
    Copy-Item -LiteralPath $agent -Destination $exe
    Copy-Item -LiteralPath $driver -Destination (Join-Path $root 'wintun.dll')
    # Recheck the actual copied bytes (source might have changed during copying).
    if((Get-FileHash -LiteralPath $exe -Algorithm SHA256).Hash -ne $AgentSha256){throw 'Copied agent hash mismatch.'}
    if((Get-FileHash -LiteralPath (Join-Path $root 'wintun.dll') -Algorithm SHA256).Hash -ne $WintunSha256){throw 'Copied Wintun hash mismatch.'}
    @{origin=$Origin;issuer=$Issuer;allowedSid=$sid.Value} | ConvertTo-Json -Compress |
        Set-Content -LiteralPath (Join-Path $root 'service-policy.json') -Encoding ASCII
    foreach($file in @($exe,(Join-Path $root 'wintun.dll'),(Join-Path $root 'service-policy.json'))){
        $fileAcl=[System.Security.AccessControl.FileSecurity]::new()
        $fileAcl.SetAccessRuleProtection($true,$false)
        foreach($principal in @('S-1-5-18','S-1-5-32-544')){
            $identity=[System.Security.Principal.SecurityIdentifier]::new($principal)
            $fileAcl.AddAccessRule([System.Security.AccessControl.FileSystemAccessRule]::new($identity,'FullControl','Allow'))
        }
        $fileAcl.SetOwner($adminSid)
        Set-Acl -LiteralPath $file -AclObject $fileAcl
    }
    $null=New-Service -Name $name -DisplayName 'Battle EX Network Service' -BinaryPathName ('"{0}" --windows-service' -f $exe) -StartupType Automatic
    Start-Service -Name $name
    $svc=Get-Service -Name $name
    $svc.WaitForStatus('Running',[TimeSpan]::FromSeconds(20))
    Write-Host 'Installed. The ordinary desktop client connects through the protected local named pipe.'
} catch {
    # Do not remove files while a service process might still own them.
    $svc=Get-Service -Name $name -ErrorAction SilentlyContinue
    if($svc){
        Stop-Service -Name $name -ErrorAction SilentlyContinue
        try{$svc.WaitForStatus('Stopped',[TimeSpan]::FromSeconds(20))}catch{}
        if($svc.Status -eq 'Stopped'){
            & (Join-Path $env:SystemRoot 'System32\sc.exe') delete $name | Out-Null
        }
    }
    if($created -and -not (Get-Service -Name $name -ErrorAction SilentlyContinue)){
        Remove-Item -LiteralPath $root -Recurse -Force
    }
    throw
}
