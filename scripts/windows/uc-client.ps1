<#
.SYNOPSIS
    Starts a development client with a player name, the pack sets you name and, optionally, Xaero's Minimap.
.DESCRIPTION
    -Join opens the title screen, for Multiplayer > Direct Connection to a server (address localhost on the server's PC). Without it the client opens a single-player dev world:
    UC-Archipelago, or the one -World names (made new beside the others when it does not exist; an existing world is opened, never replaced).
    -Sets must match the server's sets when joining. -Xaero adds the navigation set (Xaero's Minimap and World Map); it is a client mod, the server does not need it.
    A second player on the same PC needs a second copy of the repository folder (uc-second-copy.ps1): the cloud tests always gave each client its own copy.
    -Pregen (single player only) generates the three islands once in the background in the world it opens (about 40 minutes on four cores); a server does it with its own -Pregen.
.EXAMPLE
    .\scripts\windows\uc-client.ps1 -Name Alice -Sets furnished,scarcity -Join -Xaero
.EXAMPLE
    .\scripts\windows\uc-client.ps1 -Name Alice -Sets furnished,scarcity -World UC-Solo-1
#>
param(
    [Parameter(Mandatory = $true)] [string] $Name,
    [string[]] $Sets = @(),
    [switch] $Xaero,
    [switch] $Join,
    [string] $World = '',
    [switch] $Pregen,
    [string] $RunDir = 'run-dev'
)
. (Join-Path $PSScriptRoot 'uc-common.ps1')
Test-UcJava
$gradle = Get-UcGradle
if ($Name -notmatch '^[A-Za-z0-9_]{3,16}$') { throw 'A player name is 3 to 16 letters, digits or _.' }
$chosen = Resolve-UcSets $Sets
if ($Xaero -and $chosen -notcontains 'navigation') { $chosen += 'navigation' }
$arguments = @('runClient', "-PucUsername=$Name")
if ($chosen.Count -gt 0) { $arguments += "-PucExtraSets=$($chosen -join ',')" }
if ($RunDir -ne 'run-dev') { $arguments += "-PucRunDir=$RunDir" }
if ($Join) {
    if ($World -ne '') { throw '-Join and -World do not go together: a joining client plays the server''s world.' }
    if ($Pregen) { throw '-Join and -Pregen do not go together: the server generates its own world (uc-server.ps1 -Pregen).' }
    $arguments += '-PucAutoWorld=false'
} elseif ($World -ne '') {
    if ($World -notmatch '^[A-Za-z0-9_-]{1,40}$') { throw 'A world name may use letters, digits, - and _ only.' }
    $saves = Join-Path (Get-UcRunDir $RunDir) (Join-Path 'saves' $World)
    if (Test-Path $saves) { Write-Host "Opening the existing single-player world $World." } else { Write-Host "Making a new single-player world $World." }
    $arguments += "-PucDevWorldName=$World"
}
if ($Pregen) { $arguments += '-PucPregen=islands' }
Write-Host "Starting: gradlew.bat $($arguments -join ' ')"
Push-Location $script:UcRoot
try { & $gradle @arguments } finally { Pop-Location }
