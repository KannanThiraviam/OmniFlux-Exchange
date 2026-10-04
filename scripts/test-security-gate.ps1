$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$root = Split-Path $PSScriptRoot -Parent
$suite = Join-Path $root ('.tmp_logs/security-gate-tests-' + [guid]::NewGuid().ToString('N'))
$null = New-Item -ItemType Directory -Path (Join-Path $suite 'scripts') -Force
Copy-Item (Join-Path $PSScriptRoot 'security-gate.ps1') (Join-Path $suite 'scripts/security-gate.ps1')
$null = New-Item -ItemType Directory -Path (Join-Path $suite '.tmp_logs') -Force
[IO.File]::WriteAllText((Join-Path $suite 'docs-requirements.lock'), "mkdocs-material==9.7.7`n")
$runner = @'
param([string]$Case)
$env:SONAR_TOKEN = ''
function global:Get-Command {
    param([string]$Name, [string]$ErrorAction)
    if (($Case -eq 'snyk-cli-missing' -and $Name -eq 'snyk') -or
        ($Case -eq 'dependabot-cli-missing' -and $Name -eq 'gh')) { return }
    Microsoft.PowerShell.Core\Get-Command -Name $Name -ErrorAction SilentlyContinue
}
function global:snyk {
    if ($Case -eq 'snyk-auth-missing') {
        Write-Output '{"ok":false,"error":"Use snyk auth to authenticate"}'
        $global:LASTEXITCODE = 2
    } elseif ($Case -eq 'snyk-findings') {
        Write-Output '{"ok":false,"vulnerabilities":[{"id":"fixture-finding"}]}'
        $global:LASTEXITCODE = 1
    } else {
        Write-Output '{"ok":true}'
        $global:LASTEXITCODE = 0
    }
}
function global:gh {
    if ($Case -eq 'dependabot-access-missing') {
        Write-Output 'HTTP 403: alerts are unavailable'
        $global:LASTEXITCODE = 1
    } elseif ($Case -in @('dependabot-exposed', 'dependabot-resolved', 'dependabot-unsupported')) {
        $range = switch ($Case) {
            'dependabot-exposed' { '>= 9.0, < 9.8' }
            'dependabot-resolved' { '>= 7.2, < 9.7.7' }
            default { '~9.7' }
        }
        Write-Output (ConvertTo-Json -Depth 6 -Compress -InputObject @(@(@{
            number = 1
            dependency = @{ manifest_path = 'docs-requirements.txt'; package = @{ name = 'mkdocs-material' } }
            security_vulnerability = @{ vulnerable_version_range = $range }
        })))
        $global:LASTEXITCODE = 0
    } else {
        Write-Output '[[]]'
        $global:LASTEXITCODE = 0
    }
}
$scanners = switch -Wildcard ($Case) {
    'snyk-*' { @('Snyk') }
    'dependabot-*' { @('Dependabot') }
    default { @('SonarCloud') }
}
& (Join-Path $PSScriptRoot 'scripts/security-gate.ps1') -Scanners $scanners
exit $LASTEXITCODE
'@ + "`n"
[IO.File]::WriteAllText((Join-Path $suite 'runner.ps1'), $runner)
$cases = @(
    @('sonar-token-missing', 0, 'SKIPPED SonarCloud: SONAR_TOKEN'),
    @('snyk-cli-missing', 0, 'SKIPPED Snyk: CLI is not installed'),
    @('snyk-auth-missing', 0, 'SKIPPED Snyk Maven dependencies'),
    @('snyk-findings', 1, 'FAILED Snyk Maven dependencies'),
    @('snyk-clean', 0, 'PASSED Snyk Maven dependencies'),
    @('dependabot-access-missing', 0, 'SKIPPED Dependabot: alerts could not be read'),
    @('dependabot-cli-missing', 0, 'SKIPPED Dependabot: GitHub CLI is not installed'),
    @('dependabot-clean', 0, 'PASSED Dependabot: no open alerts'),
    @('dependabot-exposed', 1, 'FAILED Dependabot alert #1'),
    @('dependabot-resolved', 0, 'RESOLVED LOCALLY Dependabot alert #1'),
    @('dependabot-unsupported', 0, 'SKIPPED Dependabot alert #1')
)
foreach ($case in $cases) {
    $output = (& pwsh -NoProfile -File (Join-Path $suite 'runner.ps1') -Case $case[0] 2>&1 | Out-String)
    $code = $LASTEXITCODE
    if ($code -ne $case[1] -or $output -notmatch $case[2]) { throw "Case $($case[0]) failed (exit $code): $output" }
    Write-Host "PASS $($case[0])"
}
Write-Host "All security gate regression checks passed. Fixtures: $suite"
