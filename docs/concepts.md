# Key concepts

Start here if you are new to background jobs, streaming exports, or object storage. These terms describe the components in OmniFlux Exchange.

## Requests and identity

**Endpoint:** A URL for a service operation. A browser address-bar request uses GET to read it. Use Swagger or another HTTP client when an operation requires POST.

**API:** The HTTP interface used to submit an export, check its status, and request a download. A `202 Accepted` response means the job was accepted; the export is still running or waiting.

**Principal:** The caller's identity, including its issuer, subject, and tenant context. Job actions are scoped to this identity so one caller cannot read another caller's job.

**Allowlist:** The explicitly approved relations and columns that callers may access. A relation is a database table or view. Data outside the configured catalog is rejected.

**Idempotency key:** A client-supplied identifier for a submission. Repeating the same request with the same identity and key returns the existing job. Changing the request under that key produces a conflict.

## Database and schema changes

**Schema:** The definition of database tables, columns, indexes, and constraints. In PostgreSQL, `public` is also a named namespace grouping tables.

**Flyway and migration:** Flyway updates the schema during application startup using versioned SQL files. A migration is one recorded schema change. Flyway stores its history in `flyway_schema_history`. See [Technology and tools](TECH_STACK.md#flyway-explained) and [Data model](data-model.md#flyway-and-startup-migrations).

**psql:** PostgreSQL's interactive command-line client. Backslash commands such as `\dt` are client shortcuts; SQL statements such as `SELECT` query the database. See [inspection steps](LOCAL_DATA.md#inspect-postgresql-with-psql).

## Background work and ownership

**Job and attempt:** A job is the durable record of a requested export. An attempt is one execution of that job. A retry starts another attempt.

**Queue and worker:** The queue holds jobs waiting to run. A worker claims a queued job and performs the export. Multiple workers can compete for different jobs.

**Admission gate:** A shared PostgreSQL row used to coordinate capacity checks. Locking this row lets all replicas enforce the same queue and active-job limits.

**Lease and fencing:** A lease grants temporary ownership of a job. Fencing rejects updates from an old owner after the ownership token changes. Read the [worked example](architecture/low-level-design.md#lease-and-fencing) and its implementation limits.

**Sweeper and reconciliation:** The sweeper finds expired job leases and recovers their claims. Storage reconciliation finds abandoned uploads or objects and attempts cleanup. These are background recovery tasks.

## Reading data with bounded memory

**Source adapter:** A component that reads rows from a particular source, such as PostgreSQL through R2DBC or a REST Data API.

**Keyset pagination and high-water key:** Keyset pagination reads the next rows after the last key already processed. The high-water key is the upper bound captured when the export starts. Later inserts above that bound are excluded; updates or deletes within the range can still affect the result.

**Back-pressure:** A downstream component controls how much data it is ready to accept. When upload demand stops, the byte bridge blocks the writer, limiting how much more source data can be consumed.

**Memory budget:** The estimated memory needed for buffers, decoding, uploads, and JVM overhead. It is checked alongside the allowed number of concurrent exports.

## Files and downloads

**Object storage and bucket:** Object storage keeps a file under a key, rather than at a local filesystem path. A bucket groups those objects. OmniFlux uses an S3-compatible API; the local fixture is SeaweedFS.

**Multipart upload:** A file upload split into numbered parts. Storage combines the parts when completion succeeds. An incomplete upload must be aborted or cleaned up.

**Presigned URL:** A temporary URL signed by the service that lets a caller download one private object directly from storage. Treat the URL as a secret while it remains valid.

**Fingerprint and cache:** The fingerprint identifies the request's data selection, format, and authorization context. When caching is enabled and freshness checks pass, an equivalent request can reuse a completed object.

Continue with [Getting started](GETTING_STARTED.md) to run an export or [Overview](architecture/overview.md) to see how these components fit together.
