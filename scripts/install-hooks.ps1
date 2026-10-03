$ErrorActionPreference = 'Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
git config --local core.hooksPath .githooks
if ($LASTEXITCODE -ne 0) { throw 'Could not install Git hooks.' }
Write-Host 'Installed local hooks (staged whitespace, agent push approval, agent ref guards). Run pwsh -File scripts/quality-gate.ps1 to check now.'
