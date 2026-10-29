<#
.SYNOPSIS
    Packs the logs, crash reports and screenshots of a game folder into reports\uc-report-<date-time>.zip for a bug report.
.DESCRIPTION
    Worlds and saves are never included (they are large and private). Run it on every computer that took part (the server's and each client's folder).

    When the session loaded private content (the log says [UC-PRIVATE]), screenshots are left out unless -IncludeScreenshots, and every block or building id of a namespace the
    private folder holds is replaced by <private> in the copied logs (the folder is -PrivateContent, UC_PRIVATE_CONTENT_DIR or the staged default). Such a report is still for the
    owner and the people the permission covers: never attach it to a public issue.
.EXAMPLE
    .\scripts\windows\uc-logs.ps1
#>
param([string] $RunDir = 'run-dev', [switch] $IncludeScreenshots, [string] $PrivateContent = '')
. (Join-Path $PSScriptRoot 'uc-common.ps1')
$run = Get-UcRunDir $RunDir
if (-not (Test-Path $run)) { throw "$run does not exist; nothing has run in it yet." }
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$stage = Join-Path ([System.IO.Path]::GetTempPath()) "uc-report-$stamp"
New-Item -ItemType Directory -Force -Path $stage | Out-Null
$latest = Join-Path $run 'logs\latest.log'
$privateSession = (Test-Path $latest) -and [bool](Select-String -Path $latest -Pattern '[UC-PRIVATE]' -SimpleMatch -Quiet)
$parts = @('logs\latest.log', 'logs\debug.log', 'crash-reports')
if (-not $privateSession -or $IncludeScreenshots) { $parts += 'screenshots' }
foreach ($part in $parts) {
    $source = Join-Path $run $part
    if (Test-Path $source) { Copy-Item -Recurse -Force $source (Join-Path $stage ($part -replace '\\', '_')) }
}
if ($privateSession) {
    if ($PrivateContent -eq '') { $PrivateContent = if ($env:UC_PRIVATE_CONTENT_DIR) { $env:UC_PRIVATE_CONTENT_DIR } else { $script:UcDefaultPrivate } }
    $namespaces = @()
    if (Test-Path $PrivateContent) {
        $namespaces = @(Get-ChildItem -Path $PrivateContent -Directory | ForEach-Object { Get-ChildItem -Path (Join-Path $_.FullName 'data') -Directory -ErrorAction SilentlyContinue } |
            ForEach-Object { $_.Name } | Where-Object { $_ -notin @('minecraft', 'lostcities', 'underground_city', 'forge') } | Sort-Object -Unique)
    }
    foreach ($file in Get-ChildItem -Path $stage -Recurse -File) {
        $text = Get-Content -Raw -Path $file.FullName
        if ($null -eq $text) { continue }
        foreach ($ns in $namespaces) { $text = [regex]::Replace($text, [regex]::Escape($ns) + ':[a-z0-9_./-]+', '<private>') }
        Set-Content -Path $file.FullName -Value $text -NoNewline -Encoding UTF8
    }
    $shots = if ($IncludeScreenshots) { 'screenshots were kept (-IncludeScreenshots)' } else { 'screenshots were left out' }
    Write-Warning "This session loaded private content: $shots, and the ids of $($namespaces.Count) private namespace(s) were replaced by <private> in the logs. Share the report only with the owner and the people the permission covers, never on a public issue."
}
$sha = Invoke-UcNative { (git -C $script:UcRoot rev-parse --short HEAD) 2>$null }
Set-Content -Path (Join-Path $stage 'about.txt') -Value @("commit $sha", "folder $RunDir", "packed $stamp", "computer $env:COMPUTERNAME")
$reports = Join-Path $script:UcRoot 'reports'
New-Item -ItemType Directory -Force -Path $reports | Out-Null
$zip = Join-Path $reports "uc-report-$stamp.zip"
Compress-Archive -Path (Join-Path $stage '*') -DestinationPath $zip
Remove-Item -Recurse -Force $stage
Write-Host "Packed $zip. Attach it to the report with what you did, what you expected and what happened."
