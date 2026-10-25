<#
.SYNOPSIS
    Packs the logs, crash reports and screenshots of a game folder into reports\uc-report-<date-time>.zip for a bug report.
.DESCRIPTION
    Worlds and saves are never included (they are large and private). Run it on every computer that took part (the server's and each client's folder).
.EXAMPLE
    .\scripts\windows\uc-logs.ps1
#>
param([string] $RunDir = 'run-dev')
. (Join-Path $PSScriptRoot 'uc-common.ps1')
$run = Get-UcRunDir $RunDir
if (-not (Test-Path $run)) { throw "$run does not exist; nothing has run in it yet." }
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$stage = Join-Path ([System.IO.Path]::GetTempPath()) "uc-report-$stamp"
New-Item -ItemType Directory -Force -Path $stage | Out-Null
foreach ($part in @('logs\latest.log', 'logs\debug.log', 'crash-reports', 'screenshots')) {
    $source = Join-Path $run $part
    if (Test-Path $source) { Copy-Item -Recurse -Force $source (Join-Path $stage ($part -replace '\\', '_')) }
}
$sha = (git -C $script:UcRoot rev-parse --short HEAD) 2>$null
Set-Content -Path (Join-Path $stage 'about.txt') -Value @("commit $sha", "folder $RunDir", "packed $stamp", "computer $env:COMPUTERNAME")
$reports = Join-Path $script:UcRoot 'reports'
New-Item -ItemType Directory -Force -Path $reports | Out-Null
$zip = Join-Path $reports "uc-report-$stamp.zip"
Compress-Archive -Path (Join-Path $stage '*') -DestinationPath $zip
Remove-Item -Recurse -Force $stage
Write-Host "Packed $zip. Attach it to the report with what you did, what you expected and what happened."
