<#
.SYNOPSIS
    Makes a second copy of the repository folder for a second player on the same PC (the cloud tests gave every client its own copy).
.DESCRIPTION
    Copies the source to a sibling folder (default ..\Underground-City-P2) without build output, game folders or gradle caches, so the copy builds and downloads on its own.
    An existing target is refused: run uc-update.ps1 inside it instead to bring it to the same commit.
.EXAMPLE
    .\scripts\windows\uc-second-copy.ps1
    cd ..\Underground-City-P2; .\scripts\windows\uc-client.ps1 -Name Bob -Sets furnished,scarcity -Join
#>
param([string] $Target = '')
. (Join-Path $PSScriptRoot 'uc-common.ps1')
if ($Target -eq '') { $Target = Join-Path (Split-Path $script:UcRoot -Parent) 'Underground-City-P2' }
if (Test-Path $Target) { throw "$Target already exists. Run uc-update.ps1 inside it, or name another -Target." }
robocopy $script:UcRoot $Target /E /XD build .gradle run run-dev backups reports /NFL /NDL /NJH /NJS /NP | Out-Null
if ($LASTEXITCODE -ge 8) { throw "robocopy failed with code $LASTEXITCODE." }
$global:LASTEXITCODE = 0
Get-ChildItem -Path (Join-Path $Target 'rpg-core') -Directory -Filter 'run*' -ErrorAction SilentlyContinue | Remove-Item -Recurse -Force
Write-Host "Copied to $Target. Start the second player there with uc-client.ps1 and a different -Name."
