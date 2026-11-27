#Requires -RunAsAdministrator
[CmdletBinding()]
param()
$ErrorActionPreference='Stop'
$name='BattleEXNetwork'
$svc=Get-Service -Name $name -ErrorAction SilentlyContinue
if($svc){
    Stop-Service -Name $name
    $svc.WaitForStatus('Stopped',[TimeSpan]::FromSeconds(30))
    & (Join-Path $env:SystemRoot 'System32\sc.exe') delete $name
    if($LASTEXITCODE -ne 0){throw 'Could not remove the stopped service.'}
}
# Keep the protected installation and per-account identity state by default.
# Never delete user game files or routes belonging to other applications.
Write-Host 'Service removed. Protected installation files and identity state retained for administrator review.'
