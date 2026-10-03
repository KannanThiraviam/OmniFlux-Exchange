# Implementation status

This page records the v1 scope visible in the current source tree. It is not a release certification; see the current CI run and verification reports for build/test results.

## Implemented

- Reactive HTTP application with export submission, job status/list/detail, timing and attempt history, retry/cancel, download URL, metadata, browse, and count endpoints.
- Allowlisted schema catalog and typed filter operators for REST Data API and R2DBC source adapters.
- Bounded streaming CSV and single-sheet XLSX writers, object-size/row/field limits, S3-compatible multipart upload, checksum, and short-lived presigned downloads.
- PostgreSQL-backed job queue with a singleton cross-replica admission gate, lease renewal, fencing tokens, retry/recovery, durable attempt records, and optional cache metadata.
- Local dashboard, demo seed/counterexample endpoints under the `demo` profile, resource snapshot at `/api/system/resources`, and health endpoint.
- Prometheus metrics at `/actuator/prometheus`, worker lifecycle counters/timing, queue gauges, and production ECS-formatted console logs.
- RFC 9457 `ProblemDetail` responses with stable `code` and `message` extensions; OpenAPI 3.1 snapshot at `api/openapi.yaml`, generated docs/Swagger UI in the local demo profile, and a route-coverage contract test.
- Readiness health includes admission-gate consistency; liveness remains independent of database readiness. OpenShift includes scrape annotations and an optional Prometheus Operator `ServiceMonitor`.
- Flyway migrations V1-V7. V7 adds `VALIDATION_ERROR` and `INTERNAL_ERROR` to persisted error-code constraints.
- Local Compose fixture uses PostgreSQL, PostgREST, and SeaweedFS 4.44 as S3-compatible storage. `scripts/up.ps1` creates a local `.env` and matching PostgREST JWT if absent.
- OpenShift deployment templates and proof/benchmark tools are present.

## Current limitations and deferred work

- JWT resource-server authentication is not implemented; `auth-mode=JWT` refuses startup. `HEADER` and `DISABLED` require `allow-non-jwt-auth=true` and must only be used behind an explicitly trusted boundary.
- Application-managed fine-grained entitlements are not implemented; the service relies on upstream Data API policy or the configured database role plus its relation/column catalog controls.
- The OpenShift manifests are templates and do not make the current auth choice for an operator.
- Demo relations are created by production Flyway migrations V1/V2. Moving them to a demo-only migration location and reconciling the duplicate Compose bootstrap schema remains an operator/repository decision.
- Distributed tracing is not implemented. `/api/system/resources` remains an application-specific snapshot alongside the Prometheus scrape endpoint.
- XLSX supports one worksheet and is limited to 1,000,000 data rows.

## Known correctness gaps

Checked against the current source on 2026-10-03. These items remain open; passing CI does not establish that they are fixed.

| Priority | Gap | Code and required follow-up |
|---|---|---|
| P1 | Shutdown can reuse an export object key. | [JobRepository](../src/main/java/com/omniflux/exchange/job/JobRepository.java) decrements the attempt count on shutdown requeue; [TransferJob](../src/main/java/com/omniflux/exchange/job/TransferJob.java) derives the key from that count. [JobWorker](../src/main/java/com/omniflux/exchange/job/JobWorker.java) releases the claim before cancelling execution. Give each claim an immutable storage identity. The upload handoff race is inferred from code and has not been reproduced. |
| P1 | R2DBC query timeout is not enforced. | [R2dbcRowSource.execute](../src/main/java/com/omniflux/exchange/adapter/r2dbc/R2dbcRowSource.java) does not apply `security.query-timeout`. A blocked query can retain an admission slot while the lease renews. Enforce a query deadline and verify database cancellation. |
| P2 | Completion does not require an unexpired lease. | [JobRepository.markCompleted](../src/main/java/com/omniflux/exchange/job/JobRepository.java) checks token, status, and cancellation, but not lease expiry. Reconciliation treats expired claims as abandoned. Align publication and reconciliation at the expiry boundary. |
| P2 | Internal catalog protection is incomplete. | [SchemaCatalog](../src/main/java/com/omniflux/exchange/meta/SchemaCatalog.java) protects `transfer_jobs` and `admission_gate`, but not `transfer_job_attempts` or `flyway_schema_history`. Protect all service-owned relations even if an operator allowlists them. The default empty production allowlist prevents exposure by default. |
| P2 | Cancellation diagnostics are incomplete. | [JobWorker](../src/main/java/com/omniflux/exchange/job/JobWorker.java) records renewal stopping as `lease_lost`, including cancellation, and does not log the successful cancellation transition. Distinguish cancellation in logs and outcome metrics. The previously reported 60-second cancellation latency has not been independently reproduced. |

Demo migration isolation remains open as described above. These gaps should be resolved before relying on unattended production exports.

## Verification notes

These are recorded runs; a documentation-only review does not rerun the Java suite or the clean-room setup. Current GitHub workflow results appear under [Actions](https://github.com/KannanThiraviam/OmniFlux-Exchange/actions).

Recorded full verification (2026-10-03, Windows 11, Docker Engine 29.8, JDK 25):

- `mvnw -B clean verify`: 269 tests, 0 failures, 2 skipped; Checkstyle and
  the JaCoCo core-coverage gate passed. Integration tests use Testcontainers,
  with no Compose stack or environment variables.
- Clean-room first run from a copy of exactly the committed files, with no
  `.env` and no `OMNIFLUX_*` variables: `scripts/up.ps1 -Build` was healthy
  with a passing export smoke in 62 s, and `scripts/up.sh` (Git Bash) in 58 s.
  On the same stack: XLSX export and download, 400 responses for an invalid
  format, unknown column, malformed UUID, malformed JSON and a non-allowlisted
  relation, Swagger UI, readiness, and the job lifecycle metrics in
  `/actuator/prometheus`.
- The smoke scripts bound their exports to the seeded rows, so they finish in
  about 5 s even against a database holding the 10M-row proof fixture.

Use [Quality gates](QUALITY_GATES.md) for the current build, test, CI, and hook commands. Use [Getting started](GETTING_STARTED.md) to bootstrap the local stack and [Operations](OPERATIONS.md) for diagnostics. Proof-matrix measurements are environment-dependent and should be interpreted with their run manifests and workload settings.
