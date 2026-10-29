<#
.SYNOPSIS
    Lists the backups of a game folder, or puts one back (a rollback).
.DESCRIPTION
    -List shows the backups uc-backup.ps1 and uc-server.ps1 made, newest last. -From <name> puts that backup back into the game folder: the folder as it is now is backed up first
    (so a rollback can itself be undone), then the backup's worlds, saves and configs replace the folder's. The downloaded mods and the logs are left as they are.
    Stop the server and close the clients first; the script refuses while a Java process is running unless -Force.
.EXAMPLE
    .\scripts\windows\uc-restore.ps1 -List
.EXAMPLE
    .\scripts\windows\uc-restore.ps1 -From run-dev-20261029-201500
#>
param([string] $RunDir = 'run-dev', [switch] $List, [string] $From = '', [switch] $Force)
. (Join-Path $PSScriptRoot 'uc-common.ps1')
$backups = Join-Path $script:UcRoot 'backups'
$made = @(Get-ChildItem -Path $backups -Directory -Filter "$RunDir-*" -ErrorAction SilentlyContinue | Sort-Object Name)
if ($List -or $From -eq '') {
    if ($made.Count -eq 0) { Write-Host "No backups of $RunDir in $backups."; exit 0 }
    Write-Host "Backups of $RunDir (newest last):"
    foreach ($b in $made) {
        $worlds = @(Get-ChildItem -Path $b.FullName -Directory -ErrorAction SilentlyContinue | Where-Object { Test-Path (Join-Path $_.FullName 'level.dat') } | ForEach-Object { $_.Name })
        $saves = @(Get-ChildItem -Path (Join-Path $b.FullName 'saves') -Directory -ErrorAction SilentlyContinue | ForEach-Object { $_.Name })
        Write-Host ("  {0}   server worlds: {1}   single-player saves: {2}" -f $b.Name, ($(if ($worlds.Count) { $worlds -join ', ' } else { '-' })), ($(if ($saves.Count) { $saves -join ', ' } else { '-' })))
    }
    if ($From -eq '') { exit 0 }
}
if ($From -notmatch '^[A-Za-z0-9_.-]+$') { throw 'Give the backup by its folder name, as -List shows it.' }
$source = Join-Path $backups $From
if (-not (Test-Path $source)) { throw "No backup called $From in $backups (uc-restore.ps1 -List shows them). Nothing was changed." }
if (-not $Force -and (Get-Process -Name java, javaw -ErrorAction SilentlyContinue)) { throw 'A Java process is running. Stop the server (type stop) and close the clients, then run this again. Nothing was changed.' }
$target = Get-UcRunDir $RunDir
$safety = Backup-UcRunDir $RunDir
if ($null -ne $safety) { Write-Host "The folder as it was is kept in $safety." }
New-Item -ItemType Directory -Force -Path $target | Out-Null
Write-Host "Putting $From back into $target ..."
# /MIR makes the folder match the backup; mods, logs and crash reports are excluded on both sides, so they are neither copied nor deleted
robocopy $source $target /MIR /XD mods logs crash-reports /NFL /NDL /NJH /NJS /NP | Out-Null
if ($LASTEXITCODE -ge 8) { throw "robocopy failed with code $LASTEXITCODE; the folder may be half restored. The state before is in $safety." }
$global:LASTEXITCODE = 0
Write-Host "Restored $From. Start the server or the client as usual."
