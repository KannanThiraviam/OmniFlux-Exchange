# Changelog

Project changes before the first release are summarized here. This file is a human-readable overview, not a compatibility guarantee.

## Unreleased

- Added a beginner concepts guide, explained lease/fencing with a worked example, and removed heading permalink symbols.

- Replaced special punctuation and outdated numeric section labels in maintained documentation with plain text and descriptive links.

- Reorganized the current design into focused diagrams and a guided documentation reading path. Added a searchable GitHub Pages site, checked SVG diagram assets, and full-size viewing links.
- Scoped the Linux CI filesystem watcher to the runner's temporary directory and removed eagerly evaluated required legacy MinIO credential fallbacks from Compose.

- Established the main-only Git workflow and included the code-audit profile in CI verification.
- Consolidated open correctness gaps into Implementation status. Kept agent specs, plans, and review archives local; removed their public links and the separate publication-review page. Increased body and table text sizing.

- Implemented v1 streaming CSV/XLSX exports from allowlisted PostgreSQL or REST Data API relations to configurable S3-compatible storage.
- Added PostgreSQL-backed global job admission, durable job/attempt history, leases, fencing, retries, cache metadata, and short-lived presigned downloads.
- Added local Compose development stack using PostgreSQL, PostgREST, and SeaweedFS, plus one-command `scripts/up.ps1` / `scripts/up.sh` bootstrap with an export/download smoke.
- Added hermetic Testcontainers integration tests and a GitHub Actions verify workflow (Maven verify plus a full-stack CSV/XLSX smoke).
- Added the architecture overview, principles-and-patterns map, and nine ADRs under `docs/adr/`.
- A submit with no request body now returns `400 VALIDATION_ERROR` instead of `502`.
- Added dashboard, metadata/browse APIs, operational resource snapshot, proof runner, and OpenShift deployment templates.
- Added validation/internal error codes through Flyway V7.
- Added Prometheus metrics, structured production logs, RFC 9457 errors, OpenAPI documentation, queue-gate readiness, and graceful worker draining.
- JWT resource-server authentication, distributed tracing, and demo-fixture migration isolation remain deferred.
