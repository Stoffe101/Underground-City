# Shared helpers for the Windows playtest scripts (dot-sourced by the others; not run on its own).
# Everything here works inside the repository folder: your normal Minecraft and its saves are never touched.

Set-StrictMode -Version 3.0
$ErrorActionPreference = 'Stop'

$script:UcRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$script:UcBranch = 'feature/world-playability-overhaul'

# The pack sets build.gradle knows (content/modpacks/dev-pack.json plus Core's own data packages). A plain launch already loads world, firearms, vehicles and boats.
$script:UcKnownSets = @('world', 'firearms', 'vehicles', 'boats', 'vintner', 'farming', 'qol', 'survival', 'performance', 'navigation', 'furnished', 'scarcity', 'securitycraft')

function Get-UcGradle {
    $wrapper = Join-Path $script:UcRoot 'gradlew.bat'
    if (-not (Test-Path $wrapper)) { throw "gradlew.bat not found in $script:UcRoot; run the script from inside the repository." }
    return $wrapper
}

function Test-UcJava {
    try { $version = (& java -version 2>&1 | Select-Object -First 1) -join '' } catch { throw 'Java was not found. Install JDK 17 (Temurin 17) and put it on the PATH.' }
    if ($version -notmatch '"17\.') { Write-Warning "java -version says: $version. Minecraft 1.20.1 with Forge 47.4.10 needs JDK 17." }
}

function Resolve-UcSets([string[]] $Sets) {
    $chosen = @()
    foreach ($entry in $Sets) {
        foreach ($set in ($entry -split ',')) {
            $set = $set.Trim().ToLowerInvariant()
            if ($set -eq '') { continue }
            if ($script:UcKnownSets -notcontains $set) { throw "Unknown pack set '$set'. Known sets: $($script:UcKnownSets -join ', ')." }
            if ($chosen -notcontains $set) { $chosen += $set }
        }
    }
    return ,$chosen
}

function Get-UcRunDir([string] $RunDir = 'run-dev') { return Join-Path $script:UcRoot (Join-Path 'rpg-core' $RunDir) }

function Backup-UcRunDir([string] $RunDir = 'run-dev') {
    $source = Get-UcRunDir $RunDir
    if (-not (Test-Path $source)) { Write-Host "Nothing to back up yet ($source does not exist)."; return $null }
    $backups = Join-Path $script:UcRoot 'backups'
    New-Item -ItemType Directory -Force -Path $backups | Out-Null
    $stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
    $target = Join-Path $backups "$RunDir-$stamp"
    # the mods folder is downloaded again by gradle and the logs are not part of a world; everything else (worlds, saves, configs, the Crew ledger inside each world) is copied whole
    Write-Host "Backing up $source to $target ..."
    robocopy $source $target /E /XD mods logs crash-reports /NFL /NDL /NJH /NJS /NP | Out-Null
    if ($LASTEXITCODE -ge 8) { throw "robocopy failed with code $LASTEXITCODE; the backup in $target is incomplete." }
    $global:LASTEXITCODE = 0
    Write-Host "Backup done: $target"
    return $target
}

function Set-UcServerProperty([string] $File, [string] $Key, [string] $Value) {
    $lines = @()
    if (Test-Path $File) { $lines = @(Get-Content $File) }
    $found = $false
    for ($i = 0; $i -lt $lines.Count; $i++) {
        if ($lines[$i] -match "^\s*$([regex]::Escape($Key))\s*=") { $lines[$i] = "$Key=$Value"; $found = $true }
    }
    if (-not $found) { $lines += "$Key=$Value" }
    Set-Content -Path $File -Value $lines -Encoding ASCII
}

function Get-UcServerProperty([string] $File, [string] $Key) {
    if (-not (Test-Path $File)) { return $null }
    foreach ($line in Get-Content $File) { if ($line -match "^\s*$([regex]::Escape($Key))\s*=\s*(.*)$") { return $Matches[1].Trim() } }
    return $null
}
