# Getting started

This guide starts the local development stack, submits a sample export, and explains how to inspect the result. The local stack contains PostgreSQL, PostgREST (a stand-in for a governed Data API), SeaweedFS (S3-compatible storage), and OmniFlux Exchange.

## Requirements

- Docker Desktop with Docker Compose v2 and at least 4 GB available memory.
- PowerShell 7 (`pwsh`) on Windows. Linux/macOS/Git Bash need OpenSSL, `curl`, and Python 3 for the bootstrap and export smoke.
- JDK 25 when running Maven or the application outside Docker; Bash for `proof/run-matrix.sh`.

## Start

From the repository root, run:

```powershell
pwsh -File scripts/up.ps1 -Build
```

The script checks Docker, creates `.env` if absent with random local database/storage credentials and a matching HS256 PostgREST JWT, starts Compose, waits for health, then seeds three demo rows, runs a CSV export, and downloads it. It removes inherited project credential variables so they cannot silently override this checkout's generated settings. Keep `.env` local; never commit it.

The PowerShell script accepts `-Build` to build before starting. To start again without rebuilding:

```powershell
pwsh -File scripts/up.ps1
```

The Unix-like script always builds, waits for health, and runs the same CSV export/download smoke:

```sh
./scripts/up.sh
```

If you prefer to run Compose directly, it accepts an explicit `.env` copied from `.env.example`; those checked-in values are development-only throwaways:

```sh
cp .env.example .env
docker compose up -d --build
```

Both scripts print the configured application port. Change host ports in `.env` if defaults are occupied. The setup smoke does not need `jq` or Node.

| Service | Local address |
|---|---|
| App / dashboard | `http://localhost:8080` |
| PostgreSQL | `localhost:5435`, database `omniflux`, user `omniflux` |
| S3 API (SeaweedFS) | `http://localhost:9005` |
| SeaweedFS master diagnostics | `http://localhost:9006` |
| PostgREST | `http://localhost:3001` |

Check health and logs:

```powershell
Invoke-RestMethod http://localhost:8080/actuator/health
docker compose ps
docker compose logs -f app
```

The browser dashboard is unauthenticated in the local `demo` profile and uses a fixed local development principal. This profile is for local development only.

## Submit a first export

PowerShell example: seed three customers, submit an export, wait for a terminal state, then request a short-lived download URL.

```powershell
$seed = @{ relation = 'mock_customers'; rows = 3 } | ConvertTo-Json
Invoke-RestMethod -Method Post -Uri http://localhost:8080/api/data/seed `
  -ContentType application/json -Body $seed

$request = @{
  relation = 'mock_customers'
  columns = @('id', 'full_name', 'email_address', 'country', 'membership_status')
  format = 'CSV'
  csvMode = 'SPREADSHEET_SAFE'
} | ConvertTo-Json
$key = 'getting-started-' + (Get-Date -Format yyyyMMddHHmmss)
$job = Invoke-RestMethod -Method Post -Uri http://localhost:8080/api/exports `
  -Headers @{ 'Idempotency-Key' = $key } -ContentType application/json -Body $request

do {
  Start-Sleep -Seconds 1
  $status = Invoke-RestMethod "http://localhost:8080/api/jobs/$($job.id)"
} while ($status.status -in @('QUEUED', 'IN_PROGRESS'))

$status
Invoke-RestMethod "http://localhost:8080/api/jobs/$($job.id)/download"
```

Valid job states are `QUEUED`, `IN_PROGRESS`, `COMPLETED`, `FAILED`, and `CANCELLED`. `POST /api/exports` normally returns `202 Accepted`; an eligible cache hit returns a completed response immediately. The download endpoint returns a short-lived presigned URL. See the [API reference](API.md).

## Inspect local data

Use any credentials in your local `.env`; the values are generated and are not fixed. PostgreSQL can be inspected from its container:

```powershell
docker compose exec postgres psql -U omniflux -d omniflux
```

Useful SQL:

```sql
\dt public.*
SELECT id, status, relation_name, format, row_count, byte_count
FROM public.transfer_jobs ORDER BY created_at DESC LIMIT 10;
SELECT * FROM public.transfer_job_attempts ORDER BY started_at DESC LIMIT 10;
```

Open the SeaweedFS master endpoint for local diagnostics. For S3 object inspection, use an S3 client configured with `OMNIFLUX_S3_ACCESS_KEY` and `OMNIFLUX_S3_SECRET_KEY` from `.env`. The Compose service is named `seaweedfs`.

## Development and verification

With dependencies running, run Maven verification from the host:

```powershell
.\mvnw.cmd -B verify
```

On Unix-like shells, use `./mvnw -B verify`. The integration suite starts its own PostgreSQL and SeaweedFS containers with Testcontainers; Docker Engine must be available, but the local Compose stack does not need to be running. See [Implementation status](IMPLEMENTATION_STATUS.md) and [Quality gates](QUALITY_GATES.md) for verification details. The opt-in security scan is `./mvnw -B -Psecurity-scan verify` (PowerShell: `.\mvnw.cmd -B -Psecurity-scan verify`).

The proof runner is a separate environment-dependent measurement, not part of ordinary verification. For example, `bash proof/run-matrix.sh smoke` runs the 10k selected-adapter smoke; use the [runbook](OPERATIONS.md) for interpreting proof evidence.

## Stop

```powershell
docker compose down
```

`docker compose down -v` also removes the local database and object-storage volumes. Use it only when you intend to discard that data.
