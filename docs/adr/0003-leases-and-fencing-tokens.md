# ADR-0003: Leases and fencing tokens for job ownership

- **Status:** Accepted
- **Detail:** [decision log](../architecture/decision-log.md); [low-level design](../architecture/low-level-design.md)

## Context

During rolling deploys, healthy sibling pods hold `IN_PROGRESS` jobs. A paused
JVM (GC, CPU throttling, a slow database) looks exactly like a dead one. A
timeout alone cannot tell them apart, so timeouts alone can let two pods write
the same export.

## Terms

A **lease** is a worker's permission to run a job until a recorded deadline; renewal extends that deadline. **Fencing** rejects database updates whose ownership token no longer matches the job's current claim. See [Lease and fencing](../architecture/low-level-design.md#lease-and-fencing) for an example and the current implementation limits.

## Decision

- Each claim gets a random `claim_token` and a `lease_until`.
- `LeaseRenewer` renews the lease on its own scheduler every 15 s, conditional
  on the token. If renewal cannot be proven before a deadline, the worker
  cancels its own export and aborts the partial upload.
- Every worker-side transition (`progress`, `timing`, `COMPLETED`, `FAILED`,
  `CANCELLED`) is an `UPDATE ... WHERE claim_token = :token`. A stale worker's
  write affects zero rows.
- `ExpiredLeaseSweeper` requeues expired leases (or fails them once attempts
  are exhausted). `StartupReconciler` aborts multipart uploads only when their
  lease has expired, never by age alone.
- On shutdown, workers drain in-flight jobs for up to `queue.drain-timeout`,
  then release the remainder to `QUEUED` without using up an attempt.

## Consequences

- At most one owner can *publish* a result, even if two briefly run.
- A failed or expired attempt restarts from the first row; there is no
  mid-file resume.
- The termination grace period must exceed the drain timeout (60 s vs 30 s by
  default).

## Publication and storage identity

Completion requires a matching token, `IN_PROGRESS` status, no cancellation request, and an unexpired lease measured by the database clock at the update. Each new storage key includes its immutable claim token. Shutdown cancels execution before releasing the claim and restores the retry budget; a later claim can reuse the attempt number but cannot reuse the storage key. Reconciliation recognizes both claim-specific keys and legacy attempt-only keys. See [Implementation status](../IMPLEMENTATION_STATUS.md#correctness-fixes) for regression coverage.
