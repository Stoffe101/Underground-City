<#
.SYNOPSIS
    Checks this PC before a playtest and prints what is ready, what to fix and how.
.DESCRIPTION
    Reads only; changes nothing. Checks: Java 17 on the PATH, Git, Python 3.10 or newer (needed for the owner's buildings and the server pack), the playtest branch and local
    changes, free disk space where the repository is, memory, whether something already listens on the server port, how the development server's authentication is set, and the
    backups made so far. Ends with OK, or with the number of problems (exit code 1) and the one line that fixes each.
.EXAMPLE
    .\scripts\windows\uc-doctor.ps1
.EXAMPLE
    .\scripts\windows\uc-doctor.ps1 -RunDir run-dev -Port 25565
#>
param([string] $RunDir = 'run-dev', [int] $Port = 25565, [switch] $NoBranchCheck)
. (Join-Path $PSScriptRoot 'uc-common.ps1')
$ErrorActionPreference = 'Continue'
$script:problems = 0
$script:warnings = 0
function Ok([string] $what) { Write-Host "  OK    $what" }
function Warn([string] $what) { $script:warnings++; Write-Host "  WARN  $what" }
function Bad([string] $what) { $script:problems++; Write-Host "  FIX   $what" }

Write-Host "Underground City doctor ($($PSVersionTable.PSVersion), $(if ($env:OS -eq 'Windows_NT') { 'Windows' } else { 'not Windows' }))"

# Java 17: Minecraft 1.20.1 with Forge 47.4.10 needs exactly that major version
$javaLine = ''
try { $javaLine = ((& java -version 2>&1) | Select-Object -First 1) -join '' } catch { $javaLine = '' }
if ($javaLine -eq '') { Bad 'Java was not found. Install JDK 17 (Temurin 17) and tick "Add to PATH"; then open a new PowerShell window.' }
elseif ($javaLine -match '"17\.') { Ok "Java 17 ($javaLine)" }
else { Bad "java -version says: $javaLine. Install JDK 17 (Temurin 17) and put it first on the PATH (or set JAVA_HOME to it)." }

# Git
$git = Get-Command git -ErrorAction SilentlyContinue
if ($null -eq $git) { Bad 'Git was not found. Install Git for Windows; uc-update.ps1 needs it.' }
else {
    Ok "Git ($((& git --version) -join ''))"
    Push-Location $script:UcRoot
    try {
        $branch = (& git rev-parse --abbrev-ref HEAD 2>$null) -join ''
        $sha = (& git rev-parse --short HEAD 2>$null) -join ''
        if ($NoBranchCheck) { Ok "checkout at $sha ($branch)" }
        elseif ($branch -eq $script:UcBranch) { Ok "on the playtest branch $branch at $sha (the other player must show the same commit)" }
        else { Warn "on $branch at $sha, not the playtest branch $($script:UcBranch). Run .\scripts\windows\uc-update.ps1 on both PCs." }
        $dirty = @(& git status --porcelain --untracked-files=no 2>$null)
        if ($dirty.Count -gt 0) { Warn "$($dirty.Count) tracked file(s) changed locally; uc-update.ps1 refuses to update until they are committed or stashed." }
    } finally { Pop-Location }
}

# Python (the owner's buildings, the server pack, the report tools)
try { $py = Get-UcPython; Ok "Python 3.10 or newer ($($py -join ' '))" }
catch { Warn 'Python 3.10 or newer was not found. Only the owner''s buildings (-C70) and the server pack need it: install it from python.org and tick "Add python.exe to PATH".' }

# Disk space where the repository is: Minecraft, Forge and the pinned mods take about 12 GB on the first run
try {
    $root = [System.IO.Path]::GetPathRoot($script:UcRoot)
    $free = (New-Object System.IO.DriveInfo($root)).AvailableFreeSpace / 1GB
    if ($free -lt 6) { Bad ("only {0:N1} GB free on {1}; the first run needs about 12 GB. Free some space or move the repository." -f $free, $root) }
    elseif ($free -lt 12) { Warn ("{0:N1} GB free on {1}; the first run needs about 12 GB." -f $free, $root) }
    else { Ok ("{0:N0} GB free on {1}" -f $free, $root) }
} catch { Warn "could not read the free disk space: $_" }

# Memory: each client is started with 4 GB and the server wants 3 GB more
if ($env:OS -eq 'Windows_NT') {
    try {
        $gb = (Get-CimInstance Win32_ComputerSystem).TotalPhysicalMemory / 1GB
        if ($gb -lt 12) { Warn ("{0:N0} GB of memory: a server and a client on one PC need about 16 GB." -f $gb) } else { Ok ("{0:N0} GB of memory" -f $gb) }
    } catch { Warn "could not read the memory: $_" }
}

# The server port
$listening = $null
if (Get-Command Get-NetTCPConnection -ErrorAction SilentlyContinue) { $listening = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue }
if ($listening) { Warn "something already listens on port $Port (a server still running?). Stop it, or the new server cannot start." }
else { Ok "port $Port is free" }

# Authentication of the development server
$props = Join-Path (Get-UcRunDir $RunDir) 'server.properties'
$online = Get-UcServerProperty $props 'online-mode'
if ($null -eq $online) { Ok 'no development server yet (uc-server.ps1 writes server.properties on its first start)' }
elseif ($online -eq 'false') { Warn 'the development server is in OFFLINE mode: your own PC or a trusted home network only, never a port forward. uc-server.ps1 asks for -LanOfflineMode on every start.' }
else { Ok "the development server checks accounts (online-mode=$online)" }

# Backups
$backups = Join-Path $script:UcRoot 'backups'
$made = @(Get-ChildItem -Path $backups -Directory -Filter "$RunDir-*" -ErrorAction SilentlyContinue)
if ($made.Count -eq 0) { Warn "no backup of $RunDir yet. Run .\scripts\windows\uc-backup.ps1 after stopping the server and the clients." }
else { Ok "$($made.Count) backup(s) of $RunDir, the latest $(($made | Sort-Object Name | Select-Object -Last 1).Name) (uc-restore.ps1 -List)" }

Write-Host ''
if ($script:problems -gt 0) { Write-Host "$($script:problems) problem(s) to fix and $($script:warnings) warning(s): see the FIX lines above."; exit 1 }
Write-Host "Ready ($($script:warnings) warning(s))."
exit 0
