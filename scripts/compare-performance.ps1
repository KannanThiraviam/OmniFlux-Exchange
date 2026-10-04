param(
    [Parameter(Mandatory)][string]$BaselineJar,
    [Parameter(Mandatory)][string]$LatestJar,
    [Parameter(Mandatory)][string]$OutputDirectory,
    [string]$BaselineRef = 'HEAD',
    [string]$AppContainer = 'omniflux-app-1',
    [string]$RuntimeImage = 'eclipse-temurin:25-jre-alpine',
    [int]$Port = 18080,
    [ValidateRange(3, 20)][int]$Repetitions = 3
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$root = Split-Path $PSScriptRoot -Parent
Set-Location $root
$baseline = (Resolve-Path -LiteralPath $BaselineJar).Path
$latest = (Resolve-Path -LiteralPath $LatestJar).Path
$output = [IO.Path]::GetFullPath((Join-Path $root $OutputDirectory))
if (-not $output.StartsWith($root + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Evidence must stay inside this checkout.'
}
if (Test-Path -LiteralPath $output) { throw 'Use a fresh evidence directory; previous runs are never overwritten.' }
$null = New-Item -ItemType Directory -Path $output
$runtime = Join-Path $root ('.tmp_logs/performance-' + [guid]::NewGuid().ToString('N'))
$null = New-Item -ItemType Directory -Path $runtime
$inspect = (& docker inspect $AppContainer | ConvertFrom-Json)[0]
if ($LASTEXITCODE -ne 0 -or -not $inspect.State.Running) { throw 'The local Compose application must be running.' }
$network = @($inspect.NetworkSettings.Networks.PSObject.Properties.Name)[0]
$environmentFile = Join-Path $runtime 'runtime.env'
# Credentials are reused locally and never copied into published evidence.
[IO.File]::WriteAllLines($environmentFile, [string[]]$inspect.Config.Env)
$imageId = & docker image inspect $RuntimeImage --format '{{.Id}}'
if ($LASTEXITCODE -ne 0) { throw 'Pull the shared Java runtime image first.' }
$javaVersion = & java -version 2>&1 | Out-String
$sourceDiff = & git diff --binary
$sourceHash = [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData(
    [Text.Encoding]::UTF8.GetBytes(($sourceDiff -join "`n")))).ToLowerInvariant()
$manifest = [ordered]@{
    startedAt = [DateTime]::UtcNow.ToString('o')
    baselineCommit = (& git rev-parse "$BaselineRef^{commit}")
    baselineJarSha256 = (Get-FileHash -LiteralPath $baseline -Algorithm SHA256).Hash.ToLowerInvariant()
    latestJarSha256 = (Get-FileHash -LiteralPath $latest -Algorithm SHA256).Hash.ToLowerInvariant()
    trackedSourceDiffSha256 = $sourceHash
    runtimeImageId = $imageId
    runnerJava = $javaVersion.Trim()
    dockerVersion = (& docker version --format '{{.Server.Version}}')
    cpus = 2; memoryBytes = 536870912; heap = '320m'; directMemory = '64m'
    cacheEnabled = $false; seed = $false; relation = 'mock_customers'
    readOnly = $true; tmpfs = '16m'; runner = 'same latest StreamingRunner for both builds'
    blockOrder = @('baseline', 'latest', 'latest', 'baseline')
    warmupsPerCellPerBlock = 1; measuredRepetitionsPerCellPerBlock = $Repetitions
    primaryMetric = 'worker exportMillis, excluding queue wait and client download'
    secondaryMetrics = @('totalMillis', 'peakHeapBytes', 'peakRssBytes', 'peakCgroupBytes')
    limits = 'Warm local fixture; fixed small sample on a shared Docker Desktop host; not a production load test.'
}
$manifest | ConvertTo-Json -Depth 5 | Set-Content (Join-Path $output 'manifest.json')
$resultFile = Join-Path $output 'samples.jsonl'
$activeContainer = $null
$stoppedOriginal = $false
function Run-Cell([string]$Cell, [string]$Adapter, [string]$Variant, [int]$Block, [int]$Iteration) {
    $runnerArgs = @('-cp', (Join-Path $root 'target/test-classes'),
        'com.omniflux.exchange.proof.StreamingRunner', '--base-url', "http://127.0.0.1:$Port",
        '--cell', $Cell, '--adapter', $Adapter, '--skip-seed')
    if ($Cell -eq 'rest-xlsx-100k') { $runnerArgs += @('--rows', '100000', '--format', 'XLSX') }
    $stderr = Join-Path $runtime "$Adapter-$Variant-$Block-$Cell-$Iteration.stderr.log"
    $json = (& java @runnerArgs 2> $stderr | Out-String).Trim()
    $code = $LASTEXITCODE
    if (-not $json) { throw "Runner failed; see $stderr" }
    $sample = $json | ConvertFrom-Json -AsHashtable
    $sample['variant'] = $Variant
    $sample['block'] = $Block
    $sample['iteration'] = $Iteration
    $sample['warmup'] = $Iteration -eq 0
    $sample['recordedAt'] = [DateTime]::UtcNow.ToString('o')
    $sample | ConvertTo-Json -Depth 8 -Compress | Add-Content $resultFile
    if ($code -ne 0 -or $sample.exitCode -ne 0 -or $sample.verifiedJobs -ne $sample.concurrency) {
        throw "Proof failed for $Adapter/$Variant/$Cell; see $resultFile and $stderr"
    }
    Write-Host "$Adapter $Variant block=$Block cell=$Cell iteration=$Iteration export=$($sample.exportMillis)ms total=$($sample.totalMillis)ms verified=$($sample.verifiedJobs)"
}
try {
    $null = & docker stop $AppContainer
    if ($LASTEXITCODE -ne 0) { throw 'Could not pause the Compose worker.' }
    $stoppedOriginal = $true
    foreach ($adapter in @('r2dbc', 'rest')) {
        $cells = if ($adapter -eq 'r2dbc') { @('S1-1m', 'S3-100k', 'S7-1m') }
                 else { @('S2-1m', 'rest-xlsx-100k') }
        $block = 0
        foreach ($variant in @('baseline', 'latest', 'latest', 'baseline')) {
            $block++
            $jar = if ($variant -eq 'baseline') { $baseline } else { $latest }
            $name = 'ofx-perf-' + $adapter + '-' + $variant + '-' + [guid]::NewGuid().ToString('N').Substring(0, 8)
            $arguments = @('run', '-d', '--name', $name, '--network', $network,
                '--cpus', '2', '--memory', '512m', '--read-only', '--tmpfs', '/tmp:size=16m',
                '--user', '1000:1000', '-p', "127.0.0.1:${Port}:8080", '--env-file', $environmentFile,
                '-e', "OMNIFLUX_SOURCE_DEFAULT_ADAPTER=$adapter", '-e', 'OMNIFLUX_CACHE_ENABLED=false',
                '-e', 'JAVA_TOOL_OPTIONS=-Xmx320m -XX:MaxDirectMemorySize=64m -XX:+ExitOnOutOfMemoryError -XX:NativeMemoryTracking=summary',
                '--mount', "type=bind,source=$jar,target=/benchmark/app.jar,readonly",
                $RuntimeImage, 'java', '-jar', '/benchmark/app.jar')
            $null = & docker @arguments
            if ($LASTEXITCODE -ne 0) { throw 'Could not start benchmark application.' }
            $activeContainer = $name
            $deadline = [DateTime]::UtcNow.AddMinutes(2)
            $healthy = $false
            $lastHealthError = 'No health response'
            while ([DateTime]::UtcNow -lt $deadline) {
                try {
                    $health = Invoke-RestMethod "http://127.0.0.1:$Port/actuator/health" -TimeoutSec 5
                    if ($health.status -eq 'UP') { $healthy = $true; break }
                } catch { $lastHealthError = $_.Exception.Message }
                Start-Sleep -Seconds 2
            }
            if (-not $healthy) { throw "Benchmark container $name failed startup: $lastHealthError" }
            $configuration = Invoke-RestMethod "http://127.0.0.1:$Port/api/system/resources" -TimeoutSec 10
            if ($configuration.effectiveConfig.'omniflux.source.default-adapter' -ne $adapter) {
                throw 'Adapter configuration mismatch.'
            }
            foreach ($cell in $cells) { Run-Cell $cell $adapter $variant $block 0 }
            for ($iteration = 1; $iteration -le $Repetitions; $iteration++) {
                foreach ($cell in $cells) { Run-Cell $cell $adapter $variant $block $iteration }
            }
            & docker logs $name *> (Join-Path $runtime "$name.log")
            $null = & docker stop $name
            if ($LASTEXITCODE -ne 0) { throw 'Could not stop benchmark worker.' }
            $activeContainer = $null
        }
    }
    @{completedAt = [DateTime]::UtcNow.ToString('o'); status = 'PASSED'; samples = $resultFile} |
        ConvertTo-Json | Set-Content (Join-Path $output 'completed.json')
} finally {
    if ($activeContainer) { $null = & docker stop $activeContainer }
    if ($stoppedOriginal) {
        $null = & docker start $AppContainer
        if ($LASTEXITCODE -ne 0) { Write-Warning 'The original Compose application could not be restarted.' }
    }
}
