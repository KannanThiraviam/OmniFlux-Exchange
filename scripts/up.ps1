[CmdletBinding()]
param([switch] $Build)
$ErrorActionPreference = 'Stop'
$repo = Split-Path $PSScriptRoot -Parent
Set-Location $repo

if (-not (Get-Command docker -ErrorAction SilentlyContinue)) { throw 'Docker CLI is required.' }
docker info *> $null
if ($LASTEXITCODE -ne 0) { throw 'Docker Engine is not reachable. Start Docker Desktop and retry.' }

# Compose gives inherited environment variables precedence over .env. Clear
# inherited project variables so this checkout's generated credentials win.
Get-ChildItem Env: | Where-Object { $_.Name -like 'OMNIFLUX_*' -or $_.Name -eq 'DATA_API_TOKEN' } |
  ForEach-Object { Remove-Item "Env:$($_.Name)" }

$envFile = Join-Path $repo '.env'
if (-not (Test-Path $envFile)) {
  $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
  try {
    $bytes = [byte[]]::new(48)
    $rng.GetBytes($bytes)
    $secret = [Convert]::ToBase64String($bytes).TrimEnd('=').Replace('+','-').Replace('/','_')
    $dbPass = [Convert]::ToBase64String($bytes[0..23]).TrimEnd('=').Replace('+','-').Replace('/','_')
    $s3Pass = [Convert]::ToBase64String($bytes[24..47]).TrimEnd('=').Replace('+','-').Replace('/','_')
  } finally { $rng.Dispose() }
  $header = 'eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9'
  $payload = 'eyJyb2xlIjoib21uaWZsdXgifQ'
  $hmac = [Security.Cryptography.HMACSHA256]::new([Text.Encoding]::UTF8.GetBytes($secret))
  try {
    $sig = [Convert]::ToBase64String($hmac.ComputeHash([Text.Encoding]::UTF8.GetBytes("$header.$payload"))).TrimEnd('=').Replace('+','-').Replace('/','_')
  } finally { $hmac.Dispose() }
  @"
OMNIFLUX_POSTGRES_PORT=5435
OMNIFLUX_S3_PORT=9005
OMNIFLUX_SEAWEEDFS_MASTER_PORT=9006
OMNIFLUX_POSTGREST_PORT=3001
OMNIFLUX_APP_PORT=8080
OMNIFLUX_POSTGREST_ADMIN_PORT=3002
OMNIFLUX_STORAGE_PUBLIC_ENDPOINT=http://localhost:9005
OMNIFLUX_SOURCE_DEFAULT_ADAPTER=rest
OMNIFLUX_DB_PASSWORD=$dbPass
OMNIFLUX_S3_ACCESS_KEY=omniflux
OMNIFLUX_S3_SECRET_KEY=$s3Pass
OMNIFLUX_PGRST_JWT_SECRET=$secret
OMNIFLUX_DATA_API_TOKEN=$header.$payload.$sig
"@ | Set-Content -Encoding ascii $envFile
  Write-Host 'Created .env with random local credentials and a matching PostgREST JWT.'
}

if ($Build) { docker compose up -d --build --remove-orphans } else { docker compose up -d --remove-orphans }
if ($LASTEXITCODE -ne 0) { throw 'docker compose up failed.' }
$deadline = (Get-Date).AddMinutes(5)
do {
  $state = docker compose ps --format json app 2>$null | ConvertFrom-Json -ErrorAction SilentlyContinue
if ($state -and $state.Health -eq 'healthy') {
    $appPort = (Get-Content $envFile | Where-Object { $_ -match '^OMNIFLUX_APP_PORT=' } |
      Select-Object -Last 1) -replace '^OMNIFLUX_APP_PORT=', ''
    if (-not $appPort) { $appPort = '8080' }
    $baseUrl = "http://localhost:$appPort"
    & (Join-Path $PSScriptRoot 'smoke.ps1') -BaseUrl $baseUrl
    if ($LASTEXITCODE -and $LASTEXITCODE -ne 0) { throw 'Local export smoke failed.' }
    Write-Host "OmniFlux is ready at $baseUrl"
    exit 0
}
  Start-Sleep -Seconds 3
} while ((Get-Date) -lt $deadline)
docker compose ps
docker compose logs --tail 80 app postgrest
throw 'Timed out waiting for the app health check. See logs above.'
