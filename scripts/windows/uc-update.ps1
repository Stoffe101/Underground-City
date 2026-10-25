<#
.SYNOPSIS
    Fetches the playtest branch and fast-forwards your checkout to it.
.DESCRIPTION
    Refuses to run when you have local changes (it never throws your work away), checks the branch out, pulls with --ff-only and prints the commit, which both players should compare:
    the server and every client must run the same commit.
.EXAMPLE
    .\scripts\windows\uc-update.ps1
#>
param([string] $Branch = '')
. (Join-Path $PSScriptRoot 'uc-common.ps1')
if ($Branch -eq '') { $Branch = $script:UcBranch }
Push-Location $script:UcRoot
try {
    $dirty = git status --porcelain --untracked-files=no
    if ($dirty) { throw "You have local changes in tracked files; commit or stash them first (git status shows them). Nothing was changed." }
    git fetch origin $Branch
    if ($LASTEXITCODE -ne 0) { throw 'git fetch failed (network, or the branch name).' }
    git checkout $Branch
    if ($LASTEXITCODE -ne 0) { throw "git checkout $Branch failed." }
    git pull --ff-only origin $Branch
    if ($LASTEXITCODE -ne 0) { throw 'git pull --ff-only failed: your branch has commits the remote does not. Ask before doing anything else.' }
    $sha = git rev-parse --short HEAD
    Write-Host "On $Branch at $sha. The other player should see the same commit."
} finally { Pop-Location }
