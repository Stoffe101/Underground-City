<#
.SYNOPSIS
    Starts the development server (rpg-core\run-dev) with the pack sets you name, after a backup.
.DESCRIPTION
    A plain start loads the default development pack (world, firearms, vehicles, boats) and the archipelago world. -Sets adds opt-in sets; the clients must load the same sets.
    furnished and scarcity change how NEW chunks generate: use them for a new world (-World), not an old one.

    Authentication: a server reachable by other people must run with online-mode=true (see section 3b of the guide). The gradle development clients have no Minecraft account and can
    only join with online-mode=false; -LanOfflineMode sets that for one start and must be passed every time, so it is never left on by accident. In offline mode the server believes any
    name a client types: use it only on your own PC or a home network with people you trust, never behind a port forward.

    Stop the server by typing  stop  in its window; that saves the world. Start it again with the same command to test a restart.
.EXAMPLE
    .\scripts\windows\uc-server.ps1 -Sets furnished,scarcity -World UC-Alpha-1 -LanOfflineMode -AcceptEula
.EXAMPLE
    .\scripts\windows\uc-server.ps1 -Sets furnished,scarcity -World UC-Alpha-1 -LanOfflineMode
#>
param(
    [string[]] $Sets = @(),
    [string] $World = '',
    [switch] $LanOfflineMode,
    [switch] $AcceptEula,
    [switch] $NoBackup,
    [string] $RunDir = 'run-dev'
)
. (Join-Path $PSScriptRoot 'uc-common.ps1')
Test-UcJava
$gradle = Get-UcGradle
$chosen = Resolve-UcSets $Sets
if ($chosen -contains 'navigation') {
    Write-Host "Xaero's Minimap and World Map (navigation) are client mods; the server does not need them. Leaving them out of the server."
    $chosen = @($chosen | Where-Object { $_ -ne 'navigation' })
}
$run = Get-UcRunDir $RunDir
$props = Join-Path $run 'server.properties'
if (-not (Test-Path $props)) {
    # the same lines build.gradle's prepareDevRun writes for a new archipelago dev server (tests/test_windows_scripts.py keeps them equal); written here first so that the
    # offline-mode and world-name choices below can be made before the first start
    New-Item -ItemType Directory -Force -Path $run | Out-Null
    Set-Content -Path $props -Encoding ASCII -Value @('level-type=underground_city:archipelago', 'level-seed=5908', 'motd=Underground City development server', 'view-distance=8',
        'simulation-distance=6', 'difficulty=normal', 'gamemode=survival')
    Write-Host "Wrote a new $props for the archipelago."
}
if ($World -ne '') {
    if ($World -notmatch '^[A-Za-z0-9_-]{1,40}$') { throw 'A world name may use letters, digits, - and _ only.' }
    if (Test-Path (Join-Path $run $World)) { Write-Host "Opening the existing server world $World (it is never replaced)." }
    else { Write-Host "A new server world $World will be made on this start." }
    Set-UcServerProperty $props 'level-name' $World
}
$online = Get-UcServerProperty $props 'online-mode'
if ($LanOfflineMode) {
    Write-Warning 'OFFLINE MODE: the server will believe any name a client types. Your own PC or a home network with people you trust only; never a port forward or a public address.'
    Set-UcServerProperty $props 'online-mode' 'false'
} elseif ($online -eq 'false') {
    throw "server.properties says online-mode=false. Pass -LanOfflineMode to confirm this is a private network, or set online-mode=true (section 3b of the guide) for real accounts."
}
$levelName = Get-UcServerProperty $props 'level-name'
if ($null -eq $levelName) { $levelName = 'world' }
if (-not $NoBackup -and (Test-Path (Join-Path $run $levelName))) { Backup-UcRunDir $RunDir | Out-Null }
$arguments = @('runServer')
if ($chosen.Count -gt 0) { $arguments += "-PucExtraSets=$($chosen -join ',')" }
if ($RunDir -ne 'run-dev') { $arguments += "-PucRunDir=$RunDir" }
if ($AcceptEula) { $arguments += '-PucAcceptEula=true'; Write-Host 'You accept the Minecraft EULA (https://aka.ms/MinecraftEULA) by passing -AcceptEula.' }
Write-Host "Starting: gradlew.bat $($arguments -join ' ')"
Push-Location $script:UcRoot
try { & $gradle @arguments } finally { Pop-Location }
