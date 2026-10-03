param([switch]$Staged, [switch]$RepositoryOnly)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
Set-Location (Split-Path $PSScriptRoot -Parent)
$root = (Get-Location).Path
function Git-Lines([string[]]$Arguments) {
    $result = @(& git -c core.quotepath=false @Arguments)
    if ($LASTEXITCODE -ne 0) { throw "Git failed: $Arguments" }
    return $result
}
try {
    $files = @(Git-Lines @('ls-files', '--cached', '--others', '--exclude-standard') | Sort-Object -Unique | Where-Object { Test-Path -LiteralPath $_ -PathType Leaf })
    if ($Staged) {
        $changed = @(Git-Lines @('diff', '--cached', '--name-only', '--diff-filter=ACMRD'))
        if ($changed.Count -eq 0) { Write-Host 'No staged changes.'; exit 0 }
        # Maven reads the working tree: fail closed instead of testing different code
        # from the commit or automatically stashing the operator's work.
        $unstaged = @(Git-Lines @('diff', '--name-only'))
        $untracked = @(Git-Lines @('ls-files', '--others', '--exclude-standard'))
        if ($unstaged.Count -or $untracked.Count) {
            throw 'Stage the complete change or move unrelated work to another checkout. The gate refuses unstaged/untracked files so Maven validates the commit contents.'
        }
        $product = @($changed | Where-Object { $_ -match '^(src/main/|deploy/|init-db/|proof/|scripts/|config/|pom\.xml$|Dockerfile$|docker-compose.*\.yml$|\.env\.example$)' })
        $docs = @($changed | Where-Object { $_ -match '^(README\.md|docs/(GETTING_STARTED|IMPLEMENTATION_STATUS|QUALITY_GATES|CHANGELOG)\.md|docs/architecture/)' })
        if ($product.Count -and -not $docs.Count) {
            throw 'Product/tooling changes require a staged current-doc update (README, getting started, status, architecture, quality gates, or docs/CHANGELOG.md with a specific no-doc-impact explanation).'
        }
    }
    Write-Host '[1/3] Repository hygiene and documentation links'
    $problems = [System.Collections.Generic.List[string]]::new()
    foreach ($file in $files) {
        if ($file -match '(^|/)(\.env($|\.(?!example$))|id_rsa|id_ed25519)$|\.(pem|p12|pfx|dump|hprof)$|(^|/)(target|node_modules|\.venv)/') {
            $problems.Add("Blocked generated/private file: $file")
        }
        if ((Get-Item -LiteralPath $file).Length -gt 1MB) { $problems.Add("File exceeds 1 MiB: $file") }
        if ($file -notmatch '\.(java|xml|md|yml|yaml|json|ps1|sh|properties|sql|html|css|js|mjs|py|example)$|(^|/)(Dockerfile|pre-commit|\.gitignore)$') { continue }
        $body = [IO.File]::ReadAllText((Join-Path $root $file))
        if ($body -match '(?m)^(<<<<<<< |=======$|>>>>>>> )') { $problems.Add("Merge conflict marker: $file") }
        if ($body -match '-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----|ghp_[A-Za-z0-9]{36}|github_pat_[A-Za-z0-9_]{50,}|AKIA[0-9A-Z]{16}') { $problems.Add("Possible credential: $file (value withheld)") }
        # Historical evidence is immutable; formatting applies to maintained code/config.
        if ($file -notmatch '^docs/' -and $body -match '(?m)[\t ]+\r?$') { $problems.Add("Trailing whitespace: $file") }
        if ($file -notmatch '^docs/(reviews|evidence|superpowers)/' -and $body.Length -and -not $body.EndsWith("`n")) { $problems.Add("Missing final newline: $file") }
        if ($file -match '\.xml$') {
            try { $null = [xml]$body } catch { $problems.Add("Invalid XML: $file") }
        }
        if ($file -match '\.json$') {
            try { $null = ConvertFrom-Json $body } catch { $problems.Add("Invalid JSON: $file") }
        }
        if ($file -match '\.ps1$') {
            $parseErrors = $null
            $null = [System.Management.Automation.Language.Parser]::ParseInput($body, [ref]$null, [ref]$parseErrors)
            if ($parseErrors.Count) { $problems.Add("PowerShell syntax errors: $file") }
        }
        if ($file -match '\.(js|mjs)$') {
            if (-not (Get-Command node -ErrorAction SilentlyContinue)) {
                throw 'Node.js (node) is required to syntax-check dashboard JS. See docs/QUALITY_GATES.md.'
            }
            & node --check (Join-Path $root $file)
            if ($LASTEXITCODE -ne 0) { $problems.Add("JavaScript syntax errors: $file") }
        }
        if ($file -match '\.md$') {
            $markdown = [regex]::Replace($body, '(?ms)^```.*?^```[^\r\n]*', '')
            foreach ($match in [regex]::Matches($markdown, '\[[^\]\r\n]*\]\(([^\s)]+)(?:\s+"[^"]*")?\)')) {
                $link = $match.Groups[1].Value.Trim('<', '>')
                if ($link -match '^(https?:|mailto:|#|app:|data:)') { continue }
                $link = [Uri]::UnescapeDataString(($link -split '[#?]', 2)[0])
                if (-not $link) { continue }
                $target = if ($link.StartsWith('/')) { Join-Path $root $link.TrimStart('/') } else { Join-Path (Split-Path (Join-Path $root $file)) $link }
                if (-not (Test-Path -LiteralPath $target)) { $problems.Add("Broken local link: $file -> $link") }
            }
        }
    }
    if ($problems.Count) { throw ($problems -join "`n") }
    if ($RepositoryOnly) { Write-Host 'Repository checks passed (Maven not requested).'; exit 0 }
    Write-Host '[2/2] Java lint, dead-code audit, clean compilation, Testcontainers suite, and 85% core coverage'
    $maven = if ($IsWindows) { '.\mvnw.cmd' } else { './mvnw' }
    & $maven -B clean verify '-Pcode-audit'
    if ($LASTEXITCODE -ne 0) { throw 'Maven quality gate failed.' }
    Write-Host 'All pre-commit quality gates passed.'
} catch {
    Write-Error $_ -ErrorAction Continue
    exit 1
}
