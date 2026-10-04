$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$root = Split-Path $PSScriptRoot -Parent
$suite = Join-Path $root ('.tmp_logs/hook-tests-' + [guid]::NewGuid().ToString('N'))
$fixture = Join-Path $suite 'fixture'
$null = New-Item -ItemType Directory -Path (Join-Path $fixture '.githooks') -Force
$null = New-Item -ItemType Directory -Path (Join-Path $fixture 'scripts') -Force
foreach ($name in @('pre-commit', 'pre-push')) {
    Copy-Item (Join-Path $root ".githooks/$name") (Join-Path $fixture ".githooks/$name")
}
# The stub isolates hook dispatch and exit-code behavior from Maven execution.
# The real quality gate is verified separately in the active checkout.
$stub = @'
param([switch]$Staged, [switch]$ForPush)
if ($Staged) { Write-Output 'GATE_MODE=staged' }
elseif ($ForPush) { Write-Output 'GATE_MODE=push' }
else { Write-Error 'Hook did not select a verification mode'; exit 19 }
exit [int]$env:OFX_HOOK_TEST_EXIT
'@ + "`n"
[IO.File]::WriteAllText((Join-Path $fixture 'scripts/quality-gate.ps1'), $stub)
[IO.File]::WriteAllText((Join-Path $fixture 'README.md'), "# Hook fixture`n")
& git -C $fixture init --quiet --initial-branch=hook-fixture
if ($LASTEXITCODE) { throw 'Fixture initialization failed' }
# Do not install the copied hooks in this fixture; invoke them directly below.
& git -C $fixture config core.hooksPath not-installed
& git -C $fixture add .
& git -C $fixture -c user.name=GateTest -c user.email=gate@example.invalid commit --quiet -m fixture
if ($LASTEXITCODE) { throw 'Fixture commit failed' }
$head = (& git -C $fixture rev-parse HEAD).Trim()
$zero = '0' * 40
$pushInput = "refs/heads/hook-fixture $head refs/heads/hook-fixture $zero"
$saved = @{}
foreach ($name in @('OFX_GIT_AGENT_CONTEXT', 'OFX_ALLOW_GIT_COMMIT', 'OFX_ALLOW_GIT_PUSH', 'OFX_HOOK_TEST_EXIT')) {
    $saved[$name] = [Environment]::GetEnvironmentVariable($name)
}
function Check-Hook([string]$Name, [string]$Hook, [int]$ExpectedExit, [string]$ExpectedOutput) {
    $output = ($pushInput | & bash (Join-Path $fixture ".githooks/$Hook") origin unused 2>&1 | Out-String)
    $code = $LASTEXITCODE
    if ($code -ne $ExpectedExit -or $output -notmatch $ExpectedOutput) {
        throw "Case $Name failed (exit $code): $output"
    }
    Write-Host "PASS $Name"
}
Push-Location $fixture
try {
    $env:OFX_GIT_AGENT_CONTEXT = '1'
    $env:OFX_ALLOW_GIT_COMMIT = '0'
    $env:OFX_ALLOW_GIT_PUSH = '0'
    $env:OFX_HOOK_TEST_EXIT = '0'
    Check-Hook 'commit-approval-preserved' 'pre-commit' 1 'BLOCKED: agent git commit'
    Check-Hook 'push-approval-preserved' 'pre-push' 1 'BLOCKED: agent git push'
    $env:OFX_ALLOW_GIT_COMMIT = '1'
    $env:OFX_ALLOW_GIT_PUSH = '1'
    Check-Hook 'commit-dispatches-staged-gate' 'pre-commit' 0 'GATE_MODE=staged'
    Check-Hook 'push-dispatches-full-gate' 'pre-push' 0 'GATE_MODE=push'
    $env:OFX_HOOK_TEST_EXIT = '17'
    Check-Hook 'commit-propagates-gate-failure' 'pre-commit' 17 'GATE_MODE=staged'
    Check-Hook 'push-propagates-gate-failure' 'pre-push' 17 'GATE_MODE=push'
    $env:OFX_HOOK_TEST_EXIT = '0'
    $previousHead = $head
    [IO.File]::AppendAllText((Join-Path $fixture 'README.md'), "Second revision`n")
    & git add README.md
    & git -c user.name=GateTest -c user.email=gate@example.invalid commit --quiet -m second
    if ($LASTEXITCODE) { throw 'Second fixture commit failed' }
    $pushInput = "refs/heads/other $previousHead refs/heads/other $zero"
    Check-Hook 'push-rejects-untested-revision' 'pre-push' 1 'pushed revision to match HEAD'
} finally {
    Pop-Location
    foreach ($name in $saved.Keys) { [Environment]::SetEnvironmentVariable($name, $saved[$name]) }
}
Write-Host "All hook regression checks passed. Fixtures: $suite"
