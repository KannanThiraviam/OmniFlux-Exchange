# HTTP API reference

Base URL for the local stack: `http://localhost:8080`. Requests and responses use JSON unless noted. The committed [OpenAPI 3.1 snapshot](../api/openapi.yaml) is the consumer contract. With the local `demo` profile, SpringDoc serves the generated document at `/v3/api-docs` and Swagger UI at `/swagger-ui.html`; both are disabled outside the demo profile. A contract test checks the snapshot against controller routes.

## Caller identity

The default local `demo` profile uses a fixed development principal. Outside that profile, `DISABLED` uses the configured development principal and `HEADER` resolves `X-Auth-Issuer`, `X-Auth-Subject`, `X-Auth-Tenant`, `X-Auth-Roles`, and `X-Auth-Authz-Version` by default (the prefix is configurable). Both non-JWT modes require the explicit `security.allow-non-jwt-auth=true` opt-in. JWT mode is not implemented and prevents startup. See [Security](../SECURITY.md) before exposing an instance.

## Export and job endpoints

| Method and path | Purpose | Success |
|---|---|---|
| `POST /api/exports` | Submit export; requires relation, optional ordered columns/filters/format/csvMode; optional `Idempotency-Key` header | `202` queued, or `200` for a cache hit |
| `GET /api/jobs?limit=&after=` | List caller-owned jobs with cursor pagination | `200` `{items, nextCursor}` |
| `GET /api/jobs/{id}` | Read job state and output metadata | `200` job view |
| `GET /api/jobs/{id}/timing` | Read persisted export phase timings | `200` timing snapshot |
| `GET /api/jobs/{id}/attempts` | Read durable attempt history | `200` array |
| `GET /api/jobs/{id}/download` | Mint a short-lived presigned URL for a completed job | `200` `{url, expiresAt, contentType, filename}` |
| `GET /api/jobs/{id}/download-url` | Alias for `/download` | same |
| `POST /api/jobs/{id}/retry` | Requeue an eligible failed/cancelled job | `200` job view |
| `POST /api/jobs/{id}/cancel` | Cancel a queued or in-progress job | `200` job view |

Example submission:

```json
{
  "relation": "mock_customers",
  "columns": ["id", "full_name", "email_address"],
  "filters": [{"column": "id", "operator": "GTE", "value": 1}],
  "format": "CSV",
  "csvMode": "SPREADSHEET_SAFE"
}
```

`format` is `CSV` or `XLSX`; `csvMode` is `SPREADSHEET_SAFE` or `RAW`. Omitted format/mode use configured defaults. Filter operators are `EQ`, `NE`, `GT`, `GTE`, `LT`, `LTE`, and `IN`. Relation and column identifiers are resolved through the schema catalog and allowlist. Invalid fields fail before work is queued. XLSX is capped at 1,000,000 data rows.

Job status values are `QUEUED`, `IN_PROGRESS`, `COMPLETED`, `FAILED`, and `CANCELLED`. Terminal states are completed, failed, or cancelled. Job views include `id`, `status`, `relation`, `format`, `rowCount`, `byteCount`, `contentSha256`, `objectKey`, `attemptCount`, timestamps, error fields, cursor, and `cacheHit`. Presigned URLs are generated at download time and are not stored as job data.

## Metadata and browse

| Method and path | Purpose |
|---|---|
| `GET /api/meta/relations` | List allowlisted catalog relations and best-effort row counts |
| `GET /api/meta/relations/{relation}/columns` | Describe the relation's columns and key contract |
| `GET /api/data/{relation}?limit=&after=&filters=` | Browse rows using keyset cursor and optional URL-encoded JSON filters |
| `GET /api/data/{relation}/count?filters=` | Count rows matching optional JSON filters |

Browse `limit` defaults to 50 and is capped by `omniflux.ui.page-size`. The `after` value is an opaque cursor from `nextCursor`. Browse responses have `{rows, nextCursor}`. Count responses have `{count}`.

## Local demo and operator endpoints

These routes are profile/operator endpoints, not a general consumer API:

| Method and path | Availability | Purpose |
|---|---|---|
| `POST /api/data/seed` | `demo` profile | Add fixture rows: `{ "relation": "mock_customers", "rows": 3 }` |
| `POST /api/exports/naive` | `demo` profile | Deliberate materialization counterexample; not an export path |
| `GET /api/system/resources` | Application | In-memory resource, filesystem-event, job-count, throughput, and config snapshot |
| `GET /actuator/health` | Actuator | Health status plus readiness/liveness probes |
| `GET /actuator/prometheus` | Actuator | Prometheus scrape endpoint |

## Errors

Errors use RFC 9457 `application/problem+json` with the standard `type`, `title`, `status`, and `detail` members. The existing top-level `code` and `message` fields remain as extension members for clients.

| HTTP | Common codes | Meaning |
|---:|---|---|
| 400 | `VALIDATION_ERROR`, `UNKNOWN_RELATION`, `UNKNOWN_COLUMN`, `DUPLICATE_COLUMN`, `RELATION_NOT_ALLOWED`, `UNSUPPORTED_PRIMARY_KEY`, `UNSUPPORTED_COLUMN_TYPE`, `TOO_MANY_COLUMNS`, `TOO_MANY_IN_VALUES`, `FIELD_TOO_LARGE`, `ROW_TOO_LARGE`, `EXPORT_TOO_LARGE`, `XLSX_ROW_LIMIT`, `CHARACTER_NOT_REPRESENTABLE` | Invalid request or export constraints |
| 401 | `PRINCIPAL_UNRESOLVED` | Caller identity could not be resolved |
| 404 | `JOB_NOT_FOUND`, `NOT_FOUND` | Job is not visible to caller or route does not exist |
| 409 | `IDEMPOTENCY_KEY_CONFLICT`, `CANCELLED` | State or idempotency conflict |
| 429 | `QUEUE_FULL` | Configured queued-job capacity reached |
| 500 | `INTERNAL_ERROR`, `LEASE_LOST` | Internal or worker lease failure |
| 502 | `UPSTREAM_CLIENT_ERROR` | Upstream Data API rejected the request |
| 503 | `UPSTREAM_UNAVAILABLE`, `STORAGE_UNAVAILABLE` | Upstream or object storage unavailable |
| 504 | `QUERY_TIMEOUT` | Source query timed out |

For an asynchronous job failure, read `errorCode` and `errorMessage` from the job detail. The request that queued it has already completed successfully.
