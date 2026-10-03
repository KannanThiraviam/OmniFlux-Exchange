# Data model

**Flyway** is the tool that applies versioned SQL changes to the database when the application starts. A migration changes the schema: table definitions, columns, indexes, or constraints. It is separate from exporting data or filling demo tables. See [Technology and tools](TECH_STACK.md#flyway-explained) for an introduction.

Flyway migrations V1-V7 define the persistent model in PostgreSQL. The local `init-db/01-schema.sql` bootstraps the Compose fixture; Flyway remains the application migration mechanism. The demo relations are currently created by production migrations as well as used by the demo profile; they are not yet isolated into a demo-only migration location.

The diagram highlights job ownership and attempt coordination. The migration files define the complete schema.

```mermaid
erDiagram
  TRANSFER_JOBS {
    uuid id PK
    text status
    text issuer
    text tenant
    text requested_by
    int attempt_count
    uuid claim_token
    timestamptz lease_until
    text object_key
    bigint row_count
    text error_code
  }
  TRANSFER_JOB_ATTEMPTS {
    bigint id PK
    uuid job_id FK
    int attempt_no
    uuid claim_token
    text status
    timestamptz started_at
    timestamptz finished_at
  }
  TRANSFER_JOBS ||--o{ TRANSFER_JOB_ATTEMPTS : records
```

[Open full-size diagram](assets/diagrams/docs-data-model-01.svg)

`admission_gate` has exactly one row (`id=1`); its row lock serializes cluster-wide admission decisions. `transfer_jobs` is the durable job and audit record. It stores a principal snapshot, normalized projection and filters, current state, lease/fencing information, result metadata, and current error. `object_key` is persisted; presigned URLs are created on demand and never stored. Idempotency is unique over direction, issuer, tenant, requested-by, and a non-null key.

`transfer_job_attempts` keeps each claimed worker attempt after the parent is requeued or retried. It has one row per `(job_id, attempt_no)` and records the claim token, worker, timestamps, outcome, result checksum, timing, and error. `ON DELETE CASCADE` ties its retention to the parent job.

`mock_customers`, `mock_orders`, and `mock_wide` are sample/benchmark relations with a `BIGINT` identity primary key, required by v1 keyset scans. `mock_wide` contains 200 nullable text columns. Their present placement in V1/V2 means they exist in production schemas unless migration policy is changed; see [Operations](OPERATIONS.md) and [Implementation status](IMPLEMENTATION_STATUS.md).

## Flyway and startup migrations

1. On the first creation of the local database volume, the PostgreSQL container executes [init-db/01-schema.sql](../init-db/01-schema.sql). It does not rerun this file on every container restart.
2. During application startup, Spring Boot invokes Flyway using the configured JDBC connection. The export/job path uses R2DBC separately.
3. The application config uses `baseline-on-migrate: true` and baseline version `1`. For the existing Compose bootstrap schema without migration history, Flyway records a baseline at V1 and applies V2 onward. For an empty schema, it applies the versioned scripts starting with V1.
4. Flyway tracks migrations in `public.flyway_schema_history`, then applies later pending versions on future startup. A migration/validation error prevents normal startup.

A **baseline** records an existing schema's starting version. A **checksum** identifies a migration file's contents so changed applied scripts can be detected. These mechanisms are described in [Flyway's history-table reference](https://documentation.red-gate.com/flyway/flyway-concepts/migrations/flyway-schema-history-table).

To see the local history, open psql using [Local data and storage](LOCAL_DATA.md#inspect-postgresql-with-psql), then run:

```sql
SELECT installed_rank, version, description, type, script, success
FROM public.flyway_schema_history ORDER BY installed_rank;
```

On a bootstrapped Compose database, the V1 entry is a baseline rather than an execution of the V1 migration file. Keep applied migrations unchanged. Add a new migration for new schema changes; do not manually edit the history table to conceal a mismatch.

## Migration history

| Version | Change |
|---|---|
| V1 | Job table, singleton admission gate, indexes, idempotency key constraint, customers and orders fixtures |
| V2 | Order fixture columns, wide fixture relation, remove obsolete `schema_version`, notify PostgREST to reload schema |
| V3 | Ensure the admission gate singleton exists on upgraded databases |
| V4 | Normalize legacy error diagnostics and enforce valid error class |
| V5 | Persist timing snapshot on jobs |
| V6 | Add durable `transfer_job_attempts` history |
| V7 | Extend allowed job error codes with `VALIDATION_ERROR` and `INTERNAL_ERROR` |

The source migrations are authoritative. Do not edit historical migration files after they have been applied; add a forward migration for schema changes.
