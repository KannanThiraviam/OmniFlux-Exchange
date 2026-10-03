[CmdletBinding()]
param([string] $BaseUrl = 'http://localhost:8080')

$ErrorActionPreference = 'Stop'
$BaseUrl = $BaseUrl.TrimEnd('/')

$null = Invoke-RestMethod -Uri "$BaseUrl/actuator/health" -TimeoutSec 10
$seed = @{ relation = 'mock_customers'; rows = 3 } | ConvertTo-Json -Compress
$null = Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/data/seed" `
  -ContentType 'application/json' -Body $seed -TimeoutSec 15

$body = @{
  relation = 'mock_customers'
  columns = @('id', 'full_name', 'email_address', 'country', 'membership_status')
  # Bounded to three rows so the smoke stays fast on a database that already
  # holds a large fixture (the proof runs seed millions of rows).
  filters = @(@{ column = 'id'; operator = 'LTE'; value = 3 })
  format = 'CSV'
  csvMode = 'SPREADSHEET_SAFE'
} | ConvertTo-Json -Compress -Depth 4
$key = 'local-smoke-' + [guid]::NewGuid().ToString('N')
$job = Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/exports" `
  -Headers @{ 'Idempotency-Key' = $key } -ContentType 'application/json' `
  -Body $body -TimeoutSec 15

$deadline = (Get-Date).AddSeconds(90)
do {
  Start-Sleep -Seconds 1
  $status = Invoke-RestMethod -Uri "$BaseUrl/api/jobs/$($job.id)" -TimeoutSec 15
} while ($status.status -in @('QUEUED', 'IN_PROGRESS') -and (Get-Date) -lt $deadline)

if ($status.status -ne 'COMPLETED') {
  throw "CSV export smoke failed: job $($job.id) ended as $($status.status) ($($status.errorCode): $($status.errorMessage))."
}
if ($status.rowCount -ne 3) { throw "CSV export returned $($status.rowCount) rows; expected the 3 rows with id <= 3." }

$download = Invoke-RestMethod -Uri "$BaseUrl/api/jobs/$($job.id)/download" -TimeoutSec 15
$csv = Invoke-WebRequest -Uri $download.url -UseBasicParsing -TimeoutSec 30
if ([string]::IsNullOrWhiteSpace($csv.Content)) { throw 'CSV download was empty.' }

Write-Host "CSV export smoke passed: $($status.rowCount) rows, $($csv.RawContentLength) bytes."
