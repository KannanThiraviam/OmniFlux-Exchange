$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$suite = Join-Path $root ('.tmp_logs/gate-tests-' + [guid]::NewGuid().ToString('N'))
function Check-Case([string]$Name, [hashtable]$Files, [bool]$Pass, [string]$Expected, [switch]$Unstaged) {
    $folder = Join-Path $suite $Name
    $null = New-Item -ItemType Directory -Path (Join-Path $folder 'scripts') -Force
    Copy-Item (Join-Path $PSScriptRoot 'quality-gate.ps1') (Join-Path $folder 'scripts/quality-gate.ps1')
    foreach ($path in $Files.Keys) {
        $destination = Join-Path $folder $path
        $null = New-Item -ItemType Directory -Path (Split-Path $destination) -Force
        [IO.File]::WriteAllText($destination, $Files[$path], [Text.UTF8Encoding]::new($false))
    }
    & git -C $folder init --quiet
    if ($LASTEXITCODE) { throw 'Fixture init failed' }
    & git -C $folder add .
    if ($LASTEXITCODE) { throw 'Fixture staging failed' }
    if ($Unstaged) { Add-Content (Join-Path $folder 'README.md') 'Unstaged edit' }
    $output = (& pwsh -NoProfile -File (Join-Path $folder 'scripts/quality-gate.ps1') -Staged -RepositoryOnly 2>&1 | Out-String)
    $code = $LASTEXITCODE
    if (($Pass -and $code -ne 0) -or (-not $Pass -and $code -eq 0) -or $output -notmatch $Expected) {
        throw "Case $Name failed (exit $code): $output"
    }
    Write-Host "PASS $Name"
}
Check-Case 'valid' @{'README.md' = "# Fixture`n"} $true 'Repository checks passed'
Check-Case 'docs-required' @{'src/main/example.txt' = "change`n"} $false 'require a staged current-doc update'
Check-Case 'broken-link' @{'README.md' = "[missing](missing.md)`n"} $false 'Broken local link'
Check-Case 'secret' @{'README.md' = "# Fixture`n"; 'config/example.properties' = ('token=ghp_' + ('a' * 36) + "`n")} $false 'Possible credential'
Check-Case 'private-file' @{'README.md' = "# Fixture`n"; '.env' = "PASSWORD=example`n"} $false 'Blocked generated/private file'
Check-Case 'invalid-json' @{'README.md' = "# Fixture`n"; 'config/example.json' = "{invalid`n"} $false 'Invalid JSON'
Check-Case 'broken-js' @{'README.md' = "# Fixture`n"; 'app.js' = "if (x {`n"} $false 'JavaScript syntax errors'
Check-Case 'partial-stage' @{'README.md' = "# Fixture`n"} $false 'refuses unstaged/untracked files' -Unstaged
Write-Host "All gate regression checks passed. Fixtures: $suite"
