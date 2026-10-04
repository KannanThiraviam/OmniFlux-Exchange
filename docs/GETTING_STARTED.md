# Getting started

This walkthrough runs OmniFlux on your own computer, previews sample customer data, and downloads a three-row export. You do not need to configure a database or install Java for the Docker walkthrough.

The documentation site explains the project. The application runs separately at `localhost` after you start it. `localhost` means your computer, so these links work only where the local stack is running.

## 1. Prepare your computer

| Tool | Why it is needed |
|---|---|
| [Git](https://git-scm.com/downloads/) | Download the repository. |
| [Docker Desktop, or Docker Engine with Compose v2](https://docs.docker.com/get-started/get-docker/) | Run the application, PostgreSQL, PostgREST, and SeaweedFS. Start Docker before continuing; allow at least 4 GB of Docker memory. |
| [PowerShell 7 on Windows](https://learn.microsoft.com/en-us/powershell/scripting/install/install-powershell) | Run the Windows setup script. Open PowerShell 7, or type `pwsh` in your existing terminal. |
| OpenSSL, curl, and Python 3 on Linux/macOS/Git Bash | Generate local credentials and run the Unix setup check. |

JDK 25 is needed only for host-side development and Maven checks. Node.js, Maven installed separately, an AWS account, and production storage are not needed for this walkthrough.

Check that Docker is running:

```sh
docker info
docker compose version
```

Both commands should succeed. If Docker is unreachable, start Docker Desktop or your Docker Engine service first.

## 2. Download and start

### Windows: PowerShell 7

```powershell
git clone https://github.com/KannanThiraviam/OmniFlux-Exchange.git
cd OmniFlux-Exchange
pwsh -File scripts/up.ps1 -Build
```

### Linux, macOS, or Git Bash

```sh
git clone https://github.com/KannanThiraviam/OmniFlux-Exchange.git
cd OmniFlux-Exchange
chmod +x scripts/up.sh scripts/smoke.sh mvnw
./scripts/up.sh
```

The executable-permission step is needed on Unix when the checkout has non-executable script files. If you already cloned the repository, enter that folder and run the setup command rather than cloning again. All later terminal commands assume you are in the repository root: the folder containing `docker-compose.yml`.

The script:

1. Creates `.env` with random local database/storage credentials and a matching PostgREST token if the file is absent.
2. Builds the app and starts PostgreSQL, PostgREST, SeaweedFS, and OmniFlux.
3. Waits for application health.
4. Ensures the sample customer table has at least three rows, exports rows with `id <= 3`, and checks the download.

Existing `.env` and database data are kept. The script clears inherited project credential variables so this checkout's `.env` supplies its settings. Keep `.env` local.

The first build downloads images and Maven dependencies, so its duration depends on your network. Successful setup ends with messages like:

```text
CSV export smoke passed: 3 rows, ... bytes.
OmniFlux is ready at http://localhost:8080
```

A smoke check is a small end-to-end check that the application can read, export, store, and download data. If setup fails, use [Troubleshooting](#troubleshooting) before continuing.

## 3. Open the application

1. Open a web browser on the computer running Docker.
2. Enter [http://localhost:8080](http://localhost:8080) in the address bar.
3. You should see **OmniFlux Exchange** with **Data**, **Jobs**, and **System** tabs.

The local demo uses a fixed development identity; there is no login step.

| Open this address | What you should see |
|---|---|
| [Dashboard](http://localhost:8080) | Browse rows, submit exports, and download jobs. |
| [Application health](http://localhost:8080/actuator/health) | JSON containing `"status":"UP"`. |
| [Swagger UI](http://localhost:8080/swagger-ui.html) | Interactive API documentation, enabled in the local demo profile. |
| [SeaweedFS master](http://localhost:9006) | Local storage topology and diagnostics. |

These are default host ports. The setup script prints the actual app URL if `.env` uses another app port. See [Local data and storage](LOCAL_DATA.md#local-addresses) for every endpoint and port override.

## 4. Preview the backend data

1. Select **Data** in the dashboard.
2. Choose **mock_customers** in the **Table** dropdown.
3. The grid shows customer rows read from the backend. Use **Next page** and **Previous page** to browse.
4. To keep your first export small, choose **Column: id**, **Operator: at most**, **Value: 3**, and click **Add filter**.
5. You should now see the three customers with IDs 1, 2, and 3 in a standard fresh setup.

The preview is paginated. An export includes all rows matching the filters, not just the currently visible page. Read [Local data and storage](LOCAL_DATA.md) to view raw JSON, inspect PostgreSQL directly, or connect a database client.

## 5. Export and download

1. Keep the `id at most 3` filter from the previous step.
2. Choose **CSV** under **Format**. Leave **Columns** empty to include all columns.
3. Click **Export 3 matching rows** once the count has loaded. If a confirmation appears, review its row count and click **Export now**.
4. Select **Open Jobs** or the **Jobs** tab.
5. Wait for **Completed**, then click **Download** in that job's row.
6. Open the downloaded CSV file. It should contain a header and three data rows.

To try Excel, return to **Data**, keep the same filter, select **XLSX**, and repeat. Excel exports support a single worksheet and at most 1,000,000 data rows.

| Dashboard status | Meaning |
|---|---|
| Queued | Accepted and waiting for a worker. |
| Running | Reading rows and uploading the file. |
| Completed | The file is ready; Download is available. |
| Failed | Open the error details before retrying. |
| Cancelled | The export was stopped; no result is available. |

Files are stored in the local `omniflux` bucket. The browser's Download action uses a temporary signed URL; the database stores job metadata, not the CSV/XLSX bytes.

## Optional: submit through PowerShell

The browser workflow above is sufficient to try the project. This example shows the same bounded request through the API. Run it in PowerShell 7 with the stack running:

```powershell
$baseUrl = 'http://localhost:8080' # Use the URL printed by setup.
$request = @{
  relation = 'mock_customers'
  columns = @('id', 'full_name', 'email_address')
  filters = @(@{ column = 'id'; operator = 'LTE'; value = 3 })
  format = 'CSV'
  csvMode = 'SPREADSHEET_SAFE'
} | ConvertTo-Json -Depth 4

$job = Invoke-RestMethod -Method Post -Uri "$baseUrl/api/exports" `
  -Headers @{ 'Idempotency-Key' = [guid]::NewGuid().ToString() } `
  -ContentType application/json -Body $request -TimeoutSec 15

$deadline = (Get-Date).AddSeconds(90)
do {
  Start-Sleep -Seconds 1
  $status = Invoke-RestMethod "$baseUrl/api/jobs/$($job.id)" -TimeoutSec 15
} while ($status.status -in @('QUEUED', 'IN_PROGRESS') -and (Get-Date) -lt $deadline)

if ($status.status -ne 'COMPLETED') {
  throw "Job $($job.id): $($status.status); $($status.errorCode): $($status.errorMessage). Check Jobs for details."
}

$download = Invoke-RestMethod "$baseUrl/api/jobs/$($job.id)/download" -TimeoutSec 15
Invoke-WebRequest $download.url -OutFile './first-export.csv' -TimeoutSec 30
```

This saves `first-export.csv` in the current folder. A polling deadline stops this example from waiting forever; it does not cancel a job still running on the server. For a Unix API example, use [Swagger UI](http://localhost:8080/swagger-ui.html) or the [API reference](API.md). Its submission example can be copied into Swagger's **Try it out** request body.

## Stop and start again

Stop the services while preserving the database and stored exports:

```sh
docker compose down
```

Start again on Windows, without rebuilding:

```powershell
pwsh -File scripts/up.ps1
```

On Unix, `./scripts/up.sh` builds and reruns the small export check. Stop a live `docker compose logs -f` session with Ctrl+C; that stops log following, not the services.

`docker compose down -v` also removes database and object-storage volumes. Use it only when you intend to discard local data. Changing the database password in `.env` does not change the password in an already-initialized database.

## Troubleshooting

| Symptom | What to do |
|---|---|
| `pwsh` is not found | Install PowerShell 7 or use the Unix instructions from Git Bash with its listed tools. Windows PowerShell 5.1 is a different program. |
| Docker is unreachable | Start Docker Desktop/Engine and wait until `docker info` succeeds. |
| `Permission denied` running a script | Run the executable-permission command in the Unix setup section. |
| Port is already allocated | Stop the conflicting service or change its host port in `.env`; then rerun setup. If changing `OMNIFLUX_S3_PORT`, also set `OMNIFLUX_STORAGE_PUBLIC_ENDPOINT` to the matching browser-facing URL. |
| App link cannot be opened | Wait for the ready message, check the printed URL, and run `docker compose ps`. `localhost` links require a local running stack. |
| A table is empty | Check that setup's sample-data/export check passed. If you are browsing another fixture, return to `mock_customers`; only that table is populated by the setup smoke. |
| Export failed or download failed | Inspect the job error, confirm the app and SeaweedFS are running, and check the browser-facing storage endpoint in `.env`. |
| Password authentication failed after editing `.env` | Use the credentials that match the existing database; rebuilding containers does not reset a retained database password. |
| Setup health wait times out | Inspect service status and logs with the commands below; preserve `.env` and volumes while diagnosing. |

```sh
docker compose ps
docker compose logs --tail 100 app postgres seaweedfs postgrest
docker compose port app 8080
```

`createbucket` is a one-time setup service; a successful exit is expected. Other services should be running.

## Development and verification

For development outside the container, install JDK 25. To run the test suite on Windows:

```powershell
.\mvnw.cmd -B verify
```

On Unix, after the executable-permission step, use `./mvnw -B verify`. The integration suite starts its own PostgreSQL and SeaweedFS containers with Testcontainers. Docker must be running; the local Compose stack does not need to be running. See [Quality gates](QUALITY_GATES.md) for audit/security scans and [Implementation status](IMPLEMENTATION_STATUS.md) for scope, completed correctness fixes, and remaining limitations.

The proof runner is a separate measured workload, not part of the first-run walkthrough. Use [Operations](OPERATIONS.md#capacity-and-evidence) to interpret proof evidence.
