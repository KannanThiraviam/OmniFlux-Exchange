# OmniFlux Exchange

**Export millions of rows to CSV or Excel without ever holding the file in
memory or on disk.**

OmniFlux Exchange is a Spring Boot 4 / Java 25 service that streams allowlisted
relations from a governed Data API (REST) or PostgreSQL (R2DBC) into CSV or XLSX
objects in S3-compatible storage. Users then download them through short-lived
presigned URLs. Jobs are durable, globally rate-limited across replicas, and
safe through crashes and rolling deploys.

## Why it exists

The usual approach to "export a big table" builds the file in memory or in a
temp file and returns it over HTTP. At scale that OOM-kills or evicts the pod,
ties up connections for minutes, and loses work on every deploy. OmniFlux
replaces each of those weak points:

| Instead of | OmniFlux does |
|---|---|
| Whole file in memory | Fixed-size buffers and end-to-end back-pressure: memory stays flat whatever the row count (1M-row XLSX peaked at about 92 MiB heap) |
| Temp files on the pod | Zero disk: streaming CSV and hand-written streaming XLSX go straight into an S3 multipart upload |
| Download through the service | A presigned URL; object storage serves the bytes |
| In-memory or per-pod job queue | A PostgreSQL queue with a **global** concurrency gate across all replicas |
| "The worker timed out, so it must be dead" | Leases plus fencing tokens; a stale worker can never publish |
| Discovering misconfiguration in production | The app refuses to boot if the memory arithmetic or limits cannot hold |

```mermaid
flowchart LR
    U(("User")) -->|"POST /api/exports -> 202"| API["OmniFlux API<br/>(any pod)"]
    API --> Q[("PostgreSQL<br/>queue + global gate")]
    Q --> W["Worker<br/>(any pod, leased)"]
    W -->|"keyset pages"| SRC[("Data API<br/>or PostgreSQL")]
    W ==>|"stream CSV / XLSX<br/>multipart upload"| S3[("S3-compatible<br/>storage")]
    U -->|"GET /download -> presigned URL"| API
    U ==>|"download directly"| S3
```

[Open full-size diagram](docs/assets/diagrams/README-01.svg)

## Quick start

Requirements: Docker Desktop (or Docker Engine) with Compose v2. On Windows,
PowerShell 7. On Linux, macOS, or Git Bash: OpenSSL, `curl`, and Python 3.

```powershell
git clone https://github.com/KannanThiraviam/OmniFlux-Exchange.git
cd OmniFlux-Exchange
pwsh -File scripts/up.ps1 -Build
```

```sh
git clone https://github.com/KannanThiraviam/OmniFlux-Exchange.git
cd OmniFlux-Exchange
./scripts/up.sh
```

The script creates `.env` with random local credentials and a matching
PostgREST JWT, starts PostgreSQL, PostgREST (Data API stand-in), SeaweedFS
(S3), and the app, waits for health, then runs a real export and download as a
smoke test. Open <http://localhost:8080> for the dashboard and
<http://localhost:8080/swagger-ui.html> for the API.

Run the tests (Docker required; the suite starts its own containers):

```sh
./mvnw -B verify          # Windows: .\mvnw.cmd -B verify
```

Stop with `docker compose down`. Add `-v` only if you want to delete local data.

## Documentation

**[Read the documentation site](https://kannanthiraviam.github.io/OmniFlux-Exchange/)** - searchable guides, architecture, design, and operations. Start with the [documentation home](docs/index.md) when browsing this repository.

| Start here | |
|---|---|
| [Overview](docs/architecture/overview.md) | Problem, benefits, C4 diagrams, export lifecycle, FAQ (files vs object storage, SeaweedFS vs MinIO, why not Kafka) |
| [Principles and patterns](docs/architecture/principles-and-patterns.md) | First principles and design patterns, each mapped to the code |
| [Architecture](docs/architecture/architecture.md); [Low-level design](docs/architecture/low-level-design.md); [ADRs](docs/adr/README.md) | How it works and why |
| [Getting started](docs/GETTING_STARTED.md); [API](docs/API.md); [Operations](docs/OPERATIONS.md) | Run it, call it, operate it |
| [Threat model](docs/THREAT_MODEL.md); [Security](SECURITY.md) | Trust boundaries and deployment requirements |
| [All docs](docs/README.md); [Contributing](CONTRIBUTING.md); [Changelog](docs/CHANGELOG.md) | Everything else |

## Scope and limits (v1)

- **Authentication is delegated to a trusted gateway** (`HEADER` mode). Native
  JWT validation is not implemented yet, and `auth-mode=JWT` refuses to start.
  Do not expose the service except through the gateway. See
  [SECURITY.md](SECURITY.md).
- XLSX is a single worksheet capped at 1,000,000 data rows; CSV has no row cap.
- Keyset paging needs an integer unique key per relation.
- SeaweedFS is the local and test S3 fixture only. Production uses IBM COS or
  AWS S3 through configuration.
- The OpenShift manifests in `deploy/openshift` are templates
  (`oc apply -k deploy/openshift`) and need environment-specific endpoints and
  secrets.
