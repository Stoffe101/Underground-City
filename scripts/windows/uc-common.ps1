# Shared helpers for the Windows playtest scripts (dot-sourced by the others; not run on its own).
# Everything here works inside the repository folder: your normal Minecraft and its saves are never touched.

Set-StrictMode -Version 3.0
$ErrorActionPreference = 'Stop'

$script:UcRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$script:UcBranch = 'feature/twelfth-pass-playable-alpha'

# The pack sets build.gradle knows: every set of content/modpacks/dev-pack.json but the deferred ones, plus Core's own scarcity package (tests/test_windows_scripts.py keeps the two
# lists equal). legacy_travelers, the retired Traveler's Backpack kept for the save-guard test, is left out on purpose. A plain launch already loads world, firearms, vehicles and boats.
$script:UcKnownSets = @('world', 'firearms', 'vehicles', 'boats', 'vintner', 'farming', 'qol', 'survival', 'performance', 'navigation', 'furnished', 'furniture', 'scarcity', 'securitycraft')

# -C70: the owner's private buildings first (D-193). They join the furnished cities, name the furniture set's blocks and were chosen with the scarcity package in mind.
$script:UcC70Sets = @('furnished', 'scarcity', 'furniture')
# where uc-private-stage.ps1 stages them unless told otherwise: outside the repository, in the user's own folder (never commit it, never upload it)
$script:UcProfile = if ($env:USERPROFILE) { $env:USERPROFILE } else { [Environment]::GetFolderPath('UserProfile') }
$script:UcDefaultPrivate = Join-Path $script:UcProfile (Join-Path 'UndergroundCityPrivate' 'runtime')

function Get-UcGradle {
    $wrapper = Join-Path $script:UcRoot 'gradlew.bat'
    if (-not (Test-Path $wrapper)) { throw "gradlew.bat not found in $script:UcRoot; run the script from inside the repository." }
    return $wrapper
}

# Runs a native program (java, git, python, gradle) with errors not stopping the script. Windows PowerShell 5.1, the one Windows 11 ships with, turns every line such a
# program writes to its error stream into a PowerShell error once that stream is redirected or captured, and with $ErrorActionPreference = 'Stop' the first one ends the script:
# `java -version` (which prints its version there) then read as "Java was not found" (found on GitHub's Windows runner, twelfth pass). Check $LASTEXITCODE afterwards instead.
function Invoke-UcNative([scriptblock] $Block) {
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try { & $Block } finally { $ErrorActionPreference = $previous }
}

function Test-UcJava {
    $java = Get-Command java -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($null -eq $java) { throw 'Java was not found. Install JDK 17 (Temurin 17) and put it on the PATH.' }
    $version = (Invoke-UcNative { & $java.Source -version 2>&1 } | Select-Object -First 1 | ForEach-Object { "$_" }) -join ''
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
    # never into a backup that exists already (two backups in one second: a rollback backs the folder up first)
    for ($n = 2; Test-Path $target; $n++) { $target = Join-Path $backups "$RunDir-$stamp-$n" }
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

function Get-UcPython {
    # Python 3.10 or newer: the Windows launcher (py -3), then python, then python3. The Microsoft Store's python.exe stand-in prints nothing and is skipped.
    foreach ($candidate in @(@('py', '-3'), @('python'), @('python3'))) {
        $command = Get-Command $candidate[0] -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($null -eq $command) { continue }
        $rest = @($candidate | Select-Object -Skip 1)
        try { $ok = (Invoke-UcNative { & $command.Source @rest -c 'import sys; print(sys.version_info >= (3, 10))' 2>$null }) } catch { continue }
        if ("$ok".Trim() -eq 'True') { return ,(@($command.Source) + $rest) }
    }
    throw 'Python 3.10 or newer was not found. Install it from python.org (tick "Add python.exe to PATH"); the private content check and staging need it.'
}

function Invoke-UcPython([string[]] $Arguments) {
    $python = Get-UcPython
    $rest = @($python | Select-Object -Skip 1)
    Invoke-UcNative { & $python[0] @rest @Arguments }
}

function Get-UcPrivateTool { return Join-Path (Join-Path $script:UcRoot 'scripts') 'private_c70.py' }

function Resolve-UcPrivateContent([string] $Dir, [bool] $C70, [string[]] $Sets) {
    # The private folder to pass to gradle ('' for none), checked first: it exists outside the repository, holds valid packs (not a pack itself), none is an unported
    # original or staged by an older porter, and the sets include the ones the packs need. -C70 without a folder takes UC_PRIVATE_CONTENT_DIR, then the staged default.
    if ($Dir -eq '' -and $C70) {
        if ($env:UC_PRIVATE_CONTENT_DIR) { $Dir = $env:UC_PRIVATE_CONTENT_DIR; Write-Host "Private content: UC_PRIVATE_CONTENT_DIR ($Dir)." }
        elseif (Test-Path $script:UcDefaultPrivate) { $Dir = $script:UcDefaultPrivate; Write-Host "Private content: the staged folder ($Dir)." }
        else { throw "-C70 needs the staged private buildings and found none in $($script:UcDefaultPrivate). Stage them once: .\scripts\windows\uc-private-stage.ps1 -Source <the C70 folder or zip>, or pass -PrivateContent <folder>. Nothing was started." }
    }
    if ($Dir -eq '') { return '' }
    $full = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($Dir)
    Invoke-UcPython @((Get-UcPrivateTool), 'check', $full, '--sets', ($Sets -join ',')) | ForEach-Object { Write-Host $_ }
    if ($LASTEXITCODE -ne 0) { throw "The private content in $full is not ready (see above). Nothing was started." }
    return $full
}

function Add-UcSets([string[]] $Chosen, [string[]] $More) {
    $out = @($Chosen)
    foreach ($set in $More) { if ($out -notcontains $set) { $out += $set } }
    return ,$out
}
