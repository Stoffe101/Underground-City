<#
.SYNOPSIS
    Copies a game folder (worlds, saves, configs) to backups\<folder>-<date-time>.
.DESCRIPTION
    Stop the server and close the clients first: a world copied while it is being saved can be half old, half new. The Crew ledger, homes and wallets live inside each world folder,
    so the whole folder is copied, never single files. The downloaded mods and the logs are left out (gradle downloads the mods again).
.EXAMPLE
    .\scripts\windows\uc-backup.ps1
    .\scripts\windows\uc-backup.ps1 -RunDir run-dev-2
#>
param([string] $RunDir = 'run-dev')
. (Join-Path $PSScriptRoot 'uc-common.ps1')
$java = Get-Process -Name java, javaw -ErrorAction SilentlyContinue
if ($java) { Write-Warning 'A Java process is running. If it is the server or a client of this folder, stop it first (type stop in the server window) and run the backup again.' }
Backup-UcRunDir $RunDir | Out-Null
