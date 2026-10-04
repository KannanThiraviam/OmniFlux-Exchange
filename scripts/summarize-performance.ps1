param([Parameter(Mandatory)][string]$EvidenceDirectory)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$folder = (Resolve-Path -LiteralPath $EvidenceDirectory).Path
if (-not (Test-Path (Join-Path $folder 'completed.json'))) { throw 'The benchmark did not complete.' }
$manifest = Get-Content -Raw (Join-Path $folder 'manifest.json') | ConvertFrom-Json
$expectedSamples = 2 * $manifest.measuredRepetitionsPerCellPerBlock
$samples = @(Get-Content (Join-Path $folder 'samples.jsonl') | ForEach-Object { $_ | ConvertFrom-Json })
if (@($samples | Where-Object { $_.exitCode -ne 0 }).Count) { throw 'Failed proof samples cannot support a performance comparison.' }
function Median($Numbers) {
    $values = @($Numbers | Sort-Object)
    $middle = [int][Math]::Floor($values.Count / 2)
    if ($values.Count % 2) { return [double]$values[$middle] }
    return ([double]$values[$middle - 1] + [double]$values[$middle]) / 2
}
$summary = @(foreach ($cell in ($samples.cellId | Sort-Object -Unique)) {
    $measured = @($samples | Where-Object { $_.cellId -eq $cell -and -not $_.warmup })
    $baseline = @($measured | Where-Object variant -eq 'baseline')
    $latest = @($measured | Where-Object variant -eq 'latest')
    if ($baseline.Count -ne $expectedSamples -or $latest.Count -ne $expectedSamples) {
        throw "Missing or unbalanced samples for $cell"
    }
    if (@($measured.keyDigest | Sort-Object -Unique).Count -ne 1) { throw "Row-key verification disagrees for $cell" }
    if (@($measured.rowCount | Sort-Object -Unique).Count -ne 1 -or
        @($measured.objectBytes | Sort-Object -Unique).Count -ne 1) {
        throw "Export workload or output size disagrees for $cell"
    }
    $baseExport = Median $baseline.exportMillis
    $newExport = Median $latest.exportMillis
    [ordered]@{
        cell = $cell; adapter = $measured[0].adapter; format = $measured[0].format
        rowsPerJob = $measured[0].rowCount; concurrency = $measured[0].concurrency
        bytesPerJob = $measured[0].objectBytes
        samplesPerBuild = $baseline.Count
        baselineExportMedianMs = $baseExport; latestExportMedianMs = $newExport
        exportChangePercent = [Math]::Round(100 * ($newExport / $baseExport - 1), 2)
        baselineExportMinMs = ($baseline.exportMillis | Measure-Object -Minimum).Minimum
        baselineExportMaxMs = ($baseline.exportMillis | Measure-Object -Maximum).Maximum
        latestExportMinMs = ($latest.exportMillis | Measure-Object -Minimum).Minimum
        latestExportMaxMs = ($latest.exportMillis | Measure-Object -Maximum).Maximum
        baselineTotalMedianMs = Median $baseline.totalMillis
        latestTotalMedianMs = Median $latest.totalMillis
        baselinePeakCgroupMaxMiB = [Math]::Round(($baseline.peakCgroupBytes | Measure-Object -Maximum).Maximum / 1MB, 2)
        latestPeakCgroupMaxMiB = [Math]::Round(($latest.peakCgroupBytes | Measure-Object -Maximum).Maximum / 1MB, 2)
        baselinePeakHeapMaxMiB = [Math]::Round(($baseline.peakHeapBytes | Measure-Object -Maximum).Maximum / 1MB, 2)
        latestPeakHeapMaxMiB = [Math]::Round(($latest.peakHeapBytes | Measure-Object -Maximum).Maximum / 1MB, 2)
        verifiedJobs = ($measured.verifiedJobs | Measure-Object -Sum).Sum
        oomKills = ($measured.oomKills | Measure-Object -Maximum).Maximum
        filesystemEvents = ($measured.filesystemEvents | Measure-Object -Sum).Sum
    }
})
$summary | ConvertTo-Json -Depth 5 | Set-Content (Join-Path $folder 'summary.json')
$summary | ForEach-Object { [PSCustomObject]$_ } |
    Format-Table cell,samplesPerBuild,baselineExportMedianMs,latestExportMedianMs,exportChangePercent,baselinePeakCgroupMaxMiB,latestPeakCgroupMaxMiB -AutoSize
