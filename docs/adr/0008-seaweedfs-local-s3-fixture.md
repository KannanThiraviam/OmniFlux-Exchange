# ADR-0008: SeaweedFS as the local and test S3 fixture

- **Status:** Accepted (supersedes the original MinIO fixture)
- **Detail:** [decision log, implementation corrections](../architecture/decision-log.md)

## Context

The application only needs the S3 API: multipart upload, abort, list
multipart uploads, presigned GET, and bucket lifecycle. Local development and
Testcontainers need a reproducible, pinned image. MinIO changed how it
distributes its community edition in 2025, and the original compose file used
an unpinned `minio/minio:latest`.

## Decision

Compose and the integration tests run `chrislusf/seaweedfs:4.44` in `mini`
mode, pinned by digest. An `amazon/aws-cli` one-shot creates the bucket and
installs lifecycle rules (7-day expiry and incomplete multipart abort for
`exports/`). The older `OMNIFLUX_MINIO_*` variable names are still accepted as
aliases for `OMNIFLUX_S3_*`.

## Consequences

- No application code depends on the fixture. Production uses IBM COS or AWS
  S3 through the same configuration keys.
- SeaweedFS accepts the abort-incomplete-multipart lifecycle rule, which
  MinIO rejected (see `docs/evidence/spike-results.md`), so the local stack is
  now closer to production behaviour. `StartupReconciler` remains the
  application-side safety net.
- Historical documents (spec, plans, reviews, evidence) still say MinIO; they
  describe the system at the time they were written.
- Switching back to MinIO, or to another S3 implementation, is a compose and
  Testcontainers change only.
