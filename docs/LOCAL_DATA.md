# Local data and storage

Start the stack with [Getting started](GETTING_STARTED.md) before using these addresses and commands. Run terminal commands from the repository root. All addresses below refer to the computer running Docker.

## What lives where?

| Data | Location | How to inspect it |
|---|---|---|
| Demo source rows | PostgreSQL `public.mock_customers`, `mock_orders`, and `mock_wide` | Dashboard Data tab, browse API, psql, or a database client. Setup populates customers. |
| Requests, status, result metadata | PostgreSQL `public.transfer_jobs` | Dashboard Jobs tab, jobs API, or SQL. |
| Execution history | PostgreSQL `public.transfer_job_attempts` | Job attempt details, attempts API, or SQL. |
| CSV/XLSX bytes | SeaweedFS, in the S3 bucket `omniflux` | Dashboard Download action or an S3 client. They are not stored in the job table. |
| Local credentials and host ports | Your checkout's `.env` | Open the file locally in your editor; keep it out of source control. |

PostgREST exposes database rows to the local REST source adapter. The dashboard talks to OmniFlux's API, which reads the allowlisted source. Both adapters use the same local PostgreSQL sample data.

## Local addresses

| Service | Default address | Host-port setting in `.env` |
|---|---|---|
| Dashboard | [http://localhost:8080](http://localhost:8080) | `OMNIFLUX_APP_PORT` |
| PostgreSQL database | `localhost:5435` | `OMNIFLUX_POSTGRES_PORT` |
| PostgREST source API | `http://localhost:3001` | `OMNIFLUX_POSTGREST_PORT` |
| S3 object API | `http://localhost:9005` | `OMNIFLUX_S3_PORT` |
| SeaweedFS master diagnostics | [http://localhost:9006](http://localhost:9006) | `OMNIFLUX_SEAWEEDFS_MASTER_PORT` |

For browser links, open your browser and paste the URL into its address bar. The database address is a connection setting for a database client. The S3 address is an API endpoint for an S3 client, rather than an export-file browser.

Ports are declared in [.env.example](../.env.example) and mapped in [docker-compose.yml](../docker-compose.yml). Use the values in your local `.env` if you changed them. If changing the S3 host port, update `OMNIFLUX_STORAGE_PUBLIC_ENDPOINT` too; that URL is embedded in browser download links.

To find the running ports:

```sh
docker compose port app 8080
docker compose port postgres 5432
docker compose port seaweedfs 9333
docker compose port seaweedfs 8333
```

For example, `0.0.0.0:9006` means the master is published on host port 9006; open `http://localhost:9006` on that computer.

## View backend data in the browser

The easiest route is the [dashboard](http://localhost:8080): select **Data**, choose **mock_customers**, and inspect the rows. Select **Jobs** for export status and **System** for resource information.

An **endpoint** is a URL for one service operation. Opening a read endpoint in the browser shows its JSON response. To see the underlying API responses, open these URLs in another browser tab:

| Browser URL | Response |
|---|---|
| [Allowed relations](http://localhost:8080/api/meta/relations) | JSON describing the relations available to the caller. |
| [Five customer rows](http://localhost:8080/api/data/mock_customers?limit=5) | JSON with `rows` and `nextCursor`; this reads a bounded page. |
| [Customer columns](http://localhost:8080/api/meta/relations/mock_customers/columns) | Column metadata and the key contract. |
| [Recent jobs](http://localhost:8080/api/jobs?limit=10) | JSON with job `items` and a cursor. |
| [Application health](http://localhost:8080/actuator/health) | Health JSON. |

These links work without a login only in the local demo. The API's `/download` response contains a signed URL; open that URL to obtain the file. See [API reference](API.md#export-and-job-endpoints) for job-specific paths.

## Inspect PostgreSQL with psql

**psql** is PostgreSQL's interactive command-line client. You can use the copy already installed inside the database container; a separate host installation is not required.

Open a terminal in the repository root and run:

```sh
docker compose exec postgres psql -U omniflux -d omniflux
```

You should now see a prompt such as `omniflux=#`. Type the following commands there. Commands starting with a backslash are psql shortcuts, not SQL, and do not need a semicolon.

| psql command | Meaning |
|---|---|
| `\dt public.*` | List tables in the `public` schema. `public.*` means every table name in that schema; it does not display the rows. |
| `\d public.transfer_jobs` | Show the job table's columns, indexes, and constraints. |
| `\q` | Exit psql and return to your terminal. |

These are SQL queries; finish each with a semicolon:

```sql
-- Inspect five source records.
SELECT id, full_name, email_address
FROM public.mock_customers ORDER BY id LIMIT 5;

-- Inspect the ten most recent export requests.
SELECT id, status, relation_name, format, row_count, byte_count, object_key
FROM public.transfer_jobs ORDER BY created_at DESC LIMIT 10;

-- Inspect the ten most recent worker attempts.
SELECT job_id, attempt_no, status, started_at, finished_at
FROM public.transfer_job_attempts ORDER BY started_at DESC LIMIT 10;
```

See the [PostgreSQL psql reference](https://www.postgresql.org/docs/current/app-psql.html) for more shortcuts. Use `LIMIT` when inspecting large fixtures. If output opens in a pager, press `q` to close the pager; use `\q` at the psql prompt to leave psql.

## Connect your database client

Create a PostgreSQL connection with these fields:

| Field | Value |
|---|---|
| Host | `localhost` |
| Port | `5435`, or `OMNIFLUX_POSTGRES_PORT` from `.env` |
| Database | `omniflux` |
| Username | `omniflux` |
| Password | The value of `OMNIFLUX_DB_PASSWORD` in your local `.env` |

Connect, expand the `public` schema, and open a table's data view or query editor. The password is generated on first setup, so do not assume it is `omniflux`. If connecting to an existing retained database, its initialized credentials must still match `.env`.

## Open SeaweedFS diagnostics

Open [http://localhost:9006](http://localhost:9006) in your browser. This is the **SeaweedFS master**: it tracks storage volumes and servers and exposes local topology/status information.

For a health check, open [http://localhost:9006/cluster/healthz](http://localhost:9006/cluster/healthz). HTTP `200` means the master's health check succeeded. The body may be minimal. From PowerShell:

```powershell
(Invoke-WebRequest 'http://localhost:9006/cluster/healthz').StatusCode
```

If the page cannot be reached, run `docker compose ps seaweedfs` and `docker compose logs --tail 100 seaweedfs`. Check the configured master host port. Container port `9333` is mapped to host port `9006` by default.

## Inspect stored export objects

For a completed export, use **Jobs > Download** in the dashboard. This is the simplest way to inspect CSV/XLSX content.

If you need to inspect bucket objects directly, configure your S3 client with:

| Client setting | Local value |
|---|---|
| S3 endpoint | `http://localhost:9005`, or the configured S3 host port |
| Bucket | `omniflux` |
| Access key | `OMNIFLUX_S3_ACCESS_KEY` from `.env` |
| Secret key | `OMNIFLUX_S3_SECRET_KEY` from `.env` |
| Region | `us-east-1` |
| Addressing | Path-style access for the local endpoint. |

Use the job's `object_key` to identify its object, normally under the `exports/` prefix. Master diagnostics on port 9006 and S3 object access on port 9005 serve different purposes. Storage survives `docker compose down`; volume removal discards it. Local lifecycle rules expire completed exports, so old job metadata may outlive its file.

## Add more sample rows

The demo seed endpoint takes a **target total row count**. It adds rows until the target is reached; it does not shrink a table that already has more rows. For example, ensuring at least ten customers in PowerShell:

```powershell
$body = @{ relation = 'mock_customers'; rows = 10 } | ConvertTo-Json
Invoke-RestMethod -Method Post -Uri 'http://localhost:8080/api/data/seed' `
  -ContentType application/json -Body $body
```

The response's `rows` is the requested target, not the number inserted or a fresh actual count. Refresh the dashboard's data view afterward. This endpoint exists only in the local `demo` profile.
