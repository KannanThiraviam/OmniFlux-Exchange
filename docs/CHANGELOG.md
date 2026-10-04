# Changelog

Project changes before the first release are summarized here. This file is a human-readable overview, not a compatibility guarantee.

## Unreleased

- Corrected YAML quoting for the hashed documentation dependency install command so the GitHub Pages workflow can start. Validated both workflow files with the documentation environment's YAML parser.

- Added a controlled baseline-versus-latest export benchmark and evidence report: 80 samples, 128 verified jobs, matching limits and fixtures, with measured R2DBC CSV time increases of about 9% and REST median changes of about 1%. Documented the owner's publication exception for the five deferred Snyk Code findings; normal hooks remain blocking.

- Restricted dashboard downloads to the configured public storage origin and configured virtual-hosted bucket, with regression checks in both hooks. Corrected the Snyk Code review guidance: gateway-supplied identity headers do not establish CSRF protection when the gateway authenticates browser cookies or sessions. Remaining findings stay blocking pending implementation or a scoped review.

- Added optional Snyk, SonarCloud, and Dependabot checks to both Git hooks. Unavailable scans print explicit SKIPPED reasons; completed findings block. Dependabot comparisons use current locally resolved dependencies rather than blocking fixes on stale remote alerts.
- Updated Jackson 2 and 3 dependency families and Material for MkDocs to patched versions. Locked docs dependencies with hashes and wheel-only installs. Corrected Sonar findings and added real export/metadata and credential/row-validation regression coverage.

- Made pre-commit and pre-push run the full local quality gate: repository checks, Maven unit/integration tests, Checkstyle/PMD, coverage, and strict documentation builds. Added staged/clean-checkout safeguards and documented the optional operator `git push --no-verify` shortcut after a verified commit.

- Fixed all five previously documented correctness gaps: immutable claim-specific object keys and shutdown cancellation ordering, R2DBC query deadlines with PostgreSQL cancellation, lease-expiry checks on publication, protection of all service-owned tables, and distinct cancellation logs/metrics. Added targeted regression tests and updated the status, operations, architecture, and lease ADR documentation.
- Clarified that Testcontainers requires Docker, creates suite-owned PostgreSQL/SeaweedFS containers, and does not require the Compose stack, `.env`, or manually configured application environment variables.

- Reviewed maintained guides for first-time users. Added a complete Docker/dashboard walkthrough, a bounded three-row API export with a polling deadline and saved download, explicit local endpoint/data inspection steps, and a technology/tool reference.
- Explained Flyway, migration history, the local V1 baseline, and psql shortcuts. Corrected seed target-count semantics, Unix executable-permission setup, stale storage credential aliases, and overstatements about lease safety and memory guarantees.

- Clarified psql inspection shortcuts and documented the exact SeaweedFS diagnostics, health, and S3 endpoints with configurable port mappings.

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
