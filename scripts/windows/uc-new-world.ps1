<#
.SYNOPSIS
    Starts a single-player client in a NEW world (refuses a name that already exists, so nothing is ever replaced).
.DESCRIPTION
    The usual choice is the furnished cities and the scarcity package: -Sets furnished,scarcity (the default here). -C70 adds the furniture set and the owner's staged private
    buildings (uc-private-stage.ps1), which is what a new world is for.
.EXAMPLE
    .\scripts\windows\uc-new-world.ps1 -Name Alice -World UC-Session1
.EXAMPLE
    .\scripts\windows\uc-new-world.ps1 -Name Alice -World UC-C70-Solo -C70
#>
param(
    [Parameter(Mandatory = $true)] [string] $Name,
    [Parameter(Mandatory = $true)] [string] $World,
    [string[]] $Sets = @('furnished', 'scarcity'),
    [switch] $Xaero,
    [switch] $Pregen,
    [switch] $C70,
    [string] $PrivateContent = '',
    [string] $RunDir = 'run-dev'
)
. (Join-Path $PSScriptRoot 'uc-common.ps1')
$saves = Join-Path (Get-UcRunDir $RunDir) (Join-Path 'saves' $World)
if (Test-Path $saves) { throw "A world called $World already exists ($saves). Pick another name; nothing was changed." }
$client = Join-Path $PSScriptRoot 'uc-client.ps1'
& $client -Name $Name -Sets $Sets -World $World -RunDir $RunDir -Xaero:$Xaero -Pregen:$Pregen -C70:$C70 -PrivateContent $PrivateContent
