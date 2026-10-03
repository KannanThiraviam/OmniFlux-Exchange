# Architecture decision records

Short records of the decisions that shape OmniFlux Exchange, in
Context / Decision / Consequences form. The full reasoning, including what was
reversed and why, lives in the append-only
[decision log](../architecture/decision-log.md).

| ADR | Decision | Status |
|---|---|---|
| [0001](0001-bounded-streaming-zero-disk.md) | Bounded streaming with zero local disk | Accepted |
| [0002](0002-postgresql-job-queue-with-admission-gate.md) | PostgreSQL job queue with a global admission gate | Accepted |
| [0003](0003-leases-and-fencing-tokens.md) | Leases and fencing tokens for job ownership | Accepted |
| [0004](0004-object-storage-and-presigned-downloads.md) | Object storage with presigned downloads instead of files | Accepted |
| [0005](0005-governed-rest-source-and-r2dbc-adapter.md) | Governed REST source, with R2DBC as a direct adapter | Accepted |
| [0006](0006-streaming-ooxml-instead-of-poi.md) | Hand-written streaming OOXML instead of Apache POI | Accepted |
| [0007](0007-webflux-with-dedicated-blocking-executor.md) | WebFlux, with blocking writers on a dedicated executor | Accepted |
| [0008](0008-seaweedfs-local-s3-fixture.md) | SeaweedFS as the local and test S3 fixture | Accepted |
| [0009](0009-gateway-identity-jwt-deferred.md) | Gateway-supplied identity in v1; JWT validation deferred | Accepted for v1 |

## Adding a record

Copy the shape of an existing ADR, take the next number, and link it here.
Never rewrite an accepted ADR. Supersede it with a new one and update the old
one's status line.

## Map into the decision log

| Topic | Decision-log section |
|---|---|
| Environment facts, defining decisions, reversals | section 1-3 |
| Problems easy to reintroduce; corrections to earlier reasoning | section 4-5 |
| Reactive lifecycle, identity, admission, proof (revision 8) | section 8 |
| Resource budget and the three times it was wrong | section 9 |
| Identity: ownership vs entitlement | section 10 |
| Adapters | section 11 |
| Proof harness, testing, deployment decisions | section 12-14 |
| Limits that could not be given a working value | section 16 |
| Spike module consolidation; implementation corrections | section 17 and appended notes |
