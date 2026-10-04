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

## Correctness fixes

The five correctness gaps previously listed on this page are fixed in this source tree. They are no longer open work items. The regression tests below exercise the affected boundaries; this statement does not certify every production workload or deployment.

| Previous gap | Implemented behavior | Regression coverage |
|---|---|---|
| Shutdown could reuse an export object key. | Every new object key includes the immutable claim token: `exports/<job-id>/a<attempt-count>/c<claim-token>/data.<extension>` with the configured prefix. Shutdown cancels execution before releasing the claim. Restoring the retry budget cannot reuse the old key. Reconciliation recognizes claim-specific keys and retains support for legacy keys. | `TransferJobTest`, `JobWorkerTest`, `JobRepositoryCancellationRaceTest` |
| R2DBC query timeout was not enforced. | Each high-water, XLSX count, and page query applies `omniflux.security.query-timeout` (default 30 seconds). A transaction-local PostgreSQL `statement_timeout` cancels blocked SQL; a client deadline also bounds connection acquisition and result delivery. The timeout resets for each query, not for the whole export. | `R2dbcRowSourceTimeoutTest` holds an exclusive table lock, verifies `QUERY_TIMEOUT`, checks that the query stops in PostgreSQL while the lock remains held, and checks pooled-connection settings. |
| Completion could accept an expired lease. | Publication locks the job row first, then requires the current claim token, `IN_PROGRESS` status, no cancellation request, and an unexpired lease according to the database clock. Waiting for a row lock cannot bypass expiry. | `JobRepositoryCancellationRaceTest` covers already expired leases and expiry while completion waits on a row lock. |
| Internal catalog protection was incomplete. | `transfer_jobs`, `admission_gate`, `transfer_job_attempts`, and `flyway_schema_history` are rejected even when explicitly allowlisted. | `SchemaCatalogTest` covers plain and schema-qualified names. |
| Cancellation diagnostics were incomplete. | Owner cancellation logs `Cancelled export job ... at owner request` and records `cancelled`, including cancellation persisted by the sweeper or a failure race. A lost claim records `lease_lost`; drain-timeout disposal records `shutdown`. | `JobWorkerTest` checks distinct outcome metrics. |

Tests live under [src/test/java/com/omniflux/exchange](../src/test/java/com/omniflux/exchange/). Run them using the commands in [Quality gates](QUALITY_GATES.md#integration-tests-and-testcontainers).

Demo migration isolation remains deferred as described above. JWT authentication, application-managed fine-grained entitlements, and distributed tracing also remain unimplemented. Fixing these five correctness gaps does not change those limitations.

## Verification notes

Current GitHub workflow results appear under [Actions](https://github.com/KannanThiraviam/OmniFlux-Exchange/actions). The results below distinguish verification of these fixes from the earlier clean-room setup run.

### Verification of the five fixes

On 2026-10-03, Windows 11, Docker Engine 29.8, JDK 25:

- `mvnw.cmd -B verify -Pcode-audit`: 279 tests, 0 failures, 0 errors, 2 skipped. Checkstyle, PMD unused-code checks, and the JaCoCo core-coverage gate passed. Dependency analysis produced advisory warnings and did not fail the build.
- The skipped checks are the built-image SIGTERM test (requires an application image) and an XLSX schema inspection test (the POI lite distribution lacks its schema resource). The five-fix regression tests ran without skips.
- `pwsh -File scripts/quality-gate.ps1 -RepositoryOnly`: repository hygiene, syntax, and local documentation links passed.
- `mkdocs build --strict`: documentation build and diagram source-hash checks passed.
- This fix verification used Testcontainers-managed Docker dependencies. The earlier clean-room Compose bootstrap was not repeated for these changes.

### Verification after security and hook updates

On 2026-10-03, the updated source passed `mvnw.cmd -B verify -Pcode-audit`: 289 tests, 0 failures, 0 errors, and the same 2 skips described above. This includes unit and Docker-backed integration tests, PostgreSQL metadata coverage, and full CSV/XLSX exports through the worker and S3-compatible storage.

- SonarCloud completed analysis successfully: quality gate `OK`, new-code coverage 80.0%, and 0 unresolved issues.
- Authenticated Snyk Maven and documentation dependency scans reported 0 vulnerabilities after updating both Jackson dependency families and MkDocs Material. The docs scan uses the direct requirements file with the Python environment installed from the hashed lockfile.
- All 3 current Dependabot alerts compare as `RESOLVED LOCALLY` against the updated dependencies. Remote alerts can remain open until these changes are pushed and GitHub reevaluates them.
- Snyk Code still reports 5 findings covered by the reviews in [Quality gates](QUALITY_GATES.md#static-analysis-of-source-snyk-code). They remain blocking; no ignores or blanket exclusions were added.
- OWASP dependency-check could not update NVD without a valid API key. That scan is unverified, not passed.

Both Git hooks run the same required local checks and available external checks. An unavailable external scan prints `SKIPPED` and permits contributors to continue; a completed scan with findings blocks. The hook and scanner regression scripts exercise this behavior.

### Restart recovery and download hardening

On 2026-10-04, the previous uncommitted changes were recovered and reverified after a system restart:

- Maven unit and Docker-backed integration tests passed again: 289 tests, 0 failures, 0 errors, 2 existing skips. Checkstyle, PMD, and the core-coverage gate passed.
- Repository, hook, optional-scanner, and strict documentation checks passed. Snyk dependency scans passed; the three open Dependabot alerts still compare as resolved locally.
- The dashboard now restricts download URLs to the configured public storage origin and, when enabled, its configured virtual-hosted bucket. Both hooks execute Node.js regression checks for accepted signed downloads and rejected malicious destinations. The updated checks and documentation build passed.
- SonarCloud was rerun after the dashboard change: quality gate passed, with 0 unresolved issues.
- Snyk Code still reports the same five findings after this hardening. The stream finding is reviewed as a false positive; the dashboard origin checks do not clear the scanner warning automatically. The three CSRF findings require verification of the actual gateway authentication and protection settings. No scanner exceptions were created.

### Controlled performance comparison

On 2026-10-04, the committed baseline and latest application were compared
using matching container limits, the same source/storage fixture, disabled
export caching, warm-ups, and alternating repeated blocks. All 80 samples and
128 export jobs passed integrity and resource checks. Six measured samples
per build and workload showed median worker export time changes of +8.88%
for R2DBC CSV, +8.44% for four-job R2DBC CSV, +2.14% for R2DBC XLSX, +0.79%
for REST CSV, and -1.07% for REST XLSX. Performance is not identical.

The [comparison report](evidence/2026-10-04-controlled-performance.md) contains
the method, raw evidence, memory-limit caveats, and scope. The owner explicitly
deferred the five Snyk Code findings for publication; normal hooks continue to
block on them and no ignores were added.

### Earlier verification before these fixes

Recorded on 2026-10-03, Windows 11, Docker Engine 29.8, JDK 25, against the earlier committed source:

- `mvnw -B clean verify`: 269 tests, 0 failures, 2 skipped; Checkstyle and
  the JaCoCo core-coverage gate passed. Integration tests require Docker and start their own PostgreSQL and SeaweedFS
  containers through Testcontainers. No prestarted Compose stack or manually
  supplied application environment variables are required.
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
