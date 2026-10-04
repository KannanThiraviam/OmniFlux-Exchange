$ErrorActionPreference = 'Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
if (-not $IsWindows) {
    & chmod +x .githooks/pre-commit .githooks/pre-push .githooks/reference-transaction
    if ($LASTEXITCODE -ne 0) { throw 'Could not make Git hooks executable.' }
}
git config --local core.hooksPath .githooks
if ($LASTEXITCODE -ne 0) { throw 'Could not install Git hooks.' }
Write-Host 'Installed local hooks: pre-commit and pre-push run repository checks, Maven unit/integration tests, code audit, coverage, the strict docs build, and available Snyk/SonarCloud/Dependabot checks. Unavailable external scans print SKIPPED with a reason. Agent approval and trunk ref guards remain active. See docs/QUALITY_GATES.md for prerequisites.'
