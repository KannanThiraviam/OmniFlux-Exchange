param([string[]]$Scanners = @('Snyk', 'SonarCloud', 'Dependabot'))
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$root = Split-Path $PSScriptRoot -Parent
Set-Location $root
$reports = Join-Path $root ('.tmp_logs/security-' + [guid]::NewGuid().ToString('N'))
$null = New-Item -ItemType Directory -Path $reports -Force
$failures = [System.Collections.Generic.List[string]]::new()
$skipped = [System.Collections.Generic.List[string]]::new()
function Skip-Scan([string]$Name, [string]$Reason) {
    $skipped.Add($Name)
    Write-Host "SKIPPED ${Name}: $Reason"
}
function Run-Snyk([string]$Name, [string[]]$Arguments) {
    $report = Join-Path $reports ($Name.Replace(' ', '-').ToLowerInvariant() + '.json')
    $output = (& snyk @Arguments --json 2>&1 | Out-String)
    $code = $LASTEXITCODE
    [IO.File]::WriteAllText($report, $output)
    if ($code -notin @(0, 1)) {
        Skip-Scan $Name "scan could not complete (authentication, service, or project setup). Run snyk auth; diagnostic report: $report"
        return
    }
    try { $result = ConvertFrom-Json $output -AsHashtable }
    catch { Skip-Scan $Name "scanner did not return a usable report: $report"; return }
    if ($result.ContainsKey('error')) { Skip-Scan $Name "scanner returned an error; see $report"; return }
    if ($code -eq 1) {
        $failures.Add("${Name}: findings remain; see $report")
        Write-Host "FAILED ${Name}: findings remain; see $report"
    } else { Write-Host "PASSED $Name" }
}
if ('Snyk' -in $Scanners) {
    if (-not (Get-Command snyk -ErrorAction SilentlyContinue)) {
        Skip-Scan 'Snyk' 'CLI is not installed. Install Snyk CLI and run snyk auth to enable dependency and Code scans.'
    } else {
        Run-Snyk 'Snyk Maven dependencies' @('test', '--file=pom.xml')
        $python = if ($IsWindows) { Join-Path $root '.tmp_logs/docs-venv/Scripts/python.exe' }
                  else { Join-Path $root '.tmp_logs/docs-venv/bin/python' }
        if (Test-Path -LiteralPath $python) {
            $previousPath = $env:PATH
            $previousVenv = $env:VIRTUAL_ENV
            try {
                $env:VIRTUAL_ENV = Split-Path (Split-Path $python -Parent) -Parent
                $env:PATH = (Split-Path $python -Parent) + [IO.Path]::PathSeparator + $previousPath
                Run-Snyk 'Snyk documentation dependencies' @('test', '--file=docs-requirements.txt', '--package-manager=pip', '--command=python')
            } finally {
                $env:PATH = $previousPath
                $env:VIRTUAL_ENV = $previousVenv
            }
        } else { Skip-Scan 'Snyk documentation dependencies' 'install the docs environment as described in docs/DOCUMENTATION.md.' }
        Run-Snyk 'Snyk Code' @('code', 'test')
    }
}
if ('SonarCloud' -in $Scanners) {
    if (-not $env:SONAR_TOKEN) {
        Skip-Scan 'SonarCloud' 'SONAR_TOKEN is not configured. See docs/QUALITY_GATES.md. Never put tokens in the repository.'
    } else {
        $maven = if ($IsWindows) { '.\mvnw.cmd' } else { './mvnw' }
        $report = Join-Path $reports 'sonar.log'
        & $maven -B sonar:sonar -Psonar '-Dsonar.qualitygate.wait=true' *> $report
        $code = $LASTEXITCODE
        if ($code -ne 0) {
            if (Select-String -LiteralPath $report -Pattern 'QUALITY GATE STATUS: FAILED' -Quiet) {
                $failures.Add("SonarCloud: quality gate failed; see $report")
                Write-Host "FAILED SonarCloud: quality gate failed; see $report"
            } else { Skip-Scan 'SonarCloud' "analysis could not complete or the result was unavailable; see $report" }
        } else {
            try {
                $headers = @{ Authorization = 'Bearer ' + $env:SONAR_TOKEN }
                $issues = Invoke-RestMethod -Uri 'https://sonarcloud.io/api/issues/search?componentKeys=KannanThiraviam_OmniFlux-Exchange&resolved=false&ps=1' -Headers $headers
                if ($issues.total -gt 0) {
                    $failures.Add("SonarCloud: $($issues.total) unresolved issues remain")
                    Write-Host "FAILED SonarCloud: $($issues.total) unresolved issues remain"
                } else { Write-Host 'PASSED SonarCloud: quality gate passed and no unresolved issues' }
            } catch { Skip-Scan 'SonarCloud issue inventory' 'the quality gate passed but the unresolved-issue API could not be read.' }
        }
    }
}
function Test-VulnerableVersion([string]$Version, [string]$Range) {
    $actual = [version]$Version
    foreach ($condition in ($Range -split ',')) {
        if ($condition -notmatch '^\s*(>=|<=|>|<|=)?\s*(\d+(?:\.\d+){1,3})\s*$') {
            throw 'Unsupported advisory range; manual comparison is required.'
        }
        $operator = $Matches[1]
        $bound = [version]$Matches[2]
        $matchesBound = switch ($operator) {
            '>=' { $actual -ge $bound }
            '<=' { $actual -le $bound }
            '>' { $actual -gt $bound }
            '<' { $actual -lt $bound }
            default { $actual -eq $bound }
        }
        if (-not $matchesBound) { return $false }
    }
    return $true
}
if ('Dependabot' -in $Scanners) {
    if (-not (Get-Command gh -ErrorAction SilentlyContinue)) {
        Skip-Scan 'Dependabot' 'GitHub CLI is not installed. Install gh and sign in with permission to read repository security alerts.'
    } else {
        $alertReport = Join-Path $reports 'dependabot.json'
        & gh api --paginate --slurp 'repos/KannanThiraviam/OmniFlux-Exchange/dependabot/alerts?state=open&per_page=100' *> $alertReport
        if ($LASTEXITCODE -ne 0) {
            Skip-Scan 'Dependabot' "alerts could not be read (authentication, access, or service unavailable); see $alertReport"
        } else {
            try {
                $pages = Get-Content -Raw $alertReport | ConvertFrom-Json -AsHashtable
                $alerts = @(foreach ($page in $pages) { foreach ($alert in $page) { $alert } })
                $versions = @{}
                if (@($alerts | Where-Object { $_.dependency.manifest_path -eq 'pom.xml' }).Count) {
                    $treeFile = Join-Path $reports 'maven-tree.json'
                    $maven = if ($IsWindows) { '.\mvnw.cmd' } else { './mvnw' }
                    & $maven -B dependency:tree '-DoutputType=json' "-DoutputFile=$treeFile" *> (Join-Path $reports 'maven-tree.log')
                    if ($LASTEXITCODE -ne 0) { throw 'Could not resolve the current Maven dependency tree.' }
                    function Read-Dependencies($Node) {
                        $key = "$($Node.groupId):$($Node.artifactId)"
                        $versions[$key] = @($versions[$key]) + $Node.version
                        foreach ($child in $Node['children']) { Read-Dependencies $child }
                    }
                    Read-Dependencies (Get-Content -Raw $treeFile | ConvertFrom-Json -AsHashtable)
                }
                foreach ($line in Get-Content (Join-Path $root 'docs-requirements.lock')) {
                    if ($line -match '^([A-Za-z0-9_.-]+)==([^\s\\]+)') { $versions[$Matches[1]] = @($Matches[2]) }
                }
                $unchecked = 0
                foreach ($alert in $alerts) {
                    $package = $alert.dependency.package.name
                    $range = $alert.security_vulnerability.vulnerable_version_range
                    if (-not $versions.ContainsKey($package)) {
                        $unchecked++
                        Write-Host "SKIPPED Dependabot alert #$($alert.number): cannot resolve $package in the supported local manifests."
                        continue
                    }
                    try {
                        $exposed = @($versions[$package] | Where-Object { $_ -and (Test-VulnerableVersion $_ $range) })
                        if ($exposed.Count) {
                            $failures.Add("Dependabot alert #$($alert.number): $package $($exposed -join ', ') is vulnerable")
                            Write-Host "FAILED Dependabot alert #$($alert.number): $package $($exposed -join ', ') is vulnerable"
                        } else { Write-Host "RESOLVED LOCALLY Dependabot alert #$($alert.number): $package is outside $range; GitHub will reevaluate after publication." }
                    } catch {
                        $unchecked++
                        Write-Host "SKIPPED Dependabot alert #$($alert.number): advisory range cannot be compared automatically."
                    }
                }
                if ($unchecked) { Skip-Scan 'Dependabot complete local comparison' "$unchecked alerts require manual comparison; see $alertReport" }
                elseif ($alerts.Count -eq 0) { Write-Host 'PASSED Dependabot: no open alerts' }
            } catch { Skip-Scan 'Dependabot local comparison' "could not compare alerts with the current manifests; see $alertReport" }
        }
    }
}
if ($skipped.Count) { Write-Host "Security verification is incomplete: $($skipped -join ', ') skipped." }
if ($failures.Count) {
    Write-Error ($failures -join "`n") -ErrorAction Continue
    exit 1
}
Write-Host "Available security checks finished. Reports: $reports"
exit 0
