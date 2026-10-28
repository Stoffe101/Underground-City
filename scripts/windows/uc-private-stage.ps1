<#
.SYNOPSIS
    Ports, wires and stages the owner's private C70 buildings into a private folder OUTSIDE the repository, ready for -C70.
.DESCRIPTION
    Runs scripts\private_c70.py stage: it finds the Lost Cities data in -Source (the original folder, or its zip, whose paths are checked first), copies it unchanged to a work
    folder, dumps the block registry of the furniture set from a disposable dedicated server the first time (a few minutes; reused while the pinned mods stay the same), ports it,
    chooses the buildings (automatically, or your -Selection file), wires them into the three cities, seals the result and installs it as <Out>\c70-port. The pack it replaces is
    kept in <Out>-work\previous.

    The default -Out is $HOME\UndergroundCityPrivate\runtime, which -C70 finds by itself. Never put it, its work folder or its screenshots in the repository or on GitHub; the
    git hooks (python scripts\install_git_hooks.py) refuse a commit that would carry it.

    Where a home ends is read from each building's rooms (D-206); a building whose rooms leave that uncertain is not let, and runtime-work\wiring-report.json says why.
    -Dwellings <file> names the dwellings of such buildings yourself ({"<building id>": [dwellings] or "none"}).

    The first staging of a download without a uc-private-pack.json needs -Holder and -Summary: who gave the permission and what it covers, in your words. Later stagings reuse it.
    Stage again after every update (uc-update.ps1): a launch refuses a pack staged by an older porter or wiring than the checkout needs.
.EXAMPLE
    .\scripts\windows\uc-private-stage.ps1 -Source "$HOME\Downloads\c70-original.zip" -Holder "C70 and team" -Summary "Private two-player use and modifications permitted"
.EXAMPLE
    .\scripts\windows\uc-private-stage.ps1 -Source D:\Private\c70-original -Selection D:\Private\my-selection.json
#>
param(
    [Parameter(Mandatory = $true)] [string] $Source,
    [string] $Out = '',
    [string] $Registry = '',
    [string] $Selection = '',
    [string] $Holder = '',
    [string] $Summary = '',
    [string] $Dwellings = ''
)
. (Join-Path $PSScriptRoot 'uc-common.ps1')
if ($Out -eq '') { $Out = $script:UcDefaultPrivate }
$resolve = { param($p) $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($p) }
$arguments = @((Get-UcPrivateTool), 'stage', '--source', (& $resolve $Source), '--out', (& $resolve $Out))
if ($Registry -ne '') { $arguments += @('--registry', (& $resolve $Registry)) } else { Test-UcJava }
if ($Selection -ne '') { $arguments += @('--selection', (& $resolve $Selection)) }
if ($Holder -ne '') { $arguments += @('--holder', $Holder) }
if ($Summary -ne '') { $arguments += @('--summary', $Summary) }
if ($Dwellings -ne '') { $arguments += @('--dwellings', (& $resolve $Dwellings)) }
Invoke-UcPython $arguments
if ($LASTEXITCODE -ne 0) { throw 'Staging stopped (see above); the private folder was not changed.' }
Write-Host "Next: .\scripts\windows\uc-new-world.ps1 -Name <you> -World <new name> -C70   or   .\scripts\windows\uc-server.ps1 -C70 -World <new name> -LanOfflineMode"
