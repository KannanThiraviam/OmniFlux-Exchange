# ADR-0002: PostgreSQL job queue with a global admission gate

- **Status:** Accepted
- **Detail:** [decision log](../architecture/decision-log.md); [low-level design](../architecture/low-level-design.md)

## Context

Exports must survive deploys (a `202 Accepted` is a promise), and the load on
the Data API and storage must be capped *across all replicas*, not per pod.
`FOR UPDATE SKIP LOCKED` alone does not enforce a global count: three pods can
each read `count = 3`, each pass `< 4`, and each claim a different row.

## Decision

- Jobs are rows in `transfer_jobs`; attempts are rows in
  `transfer_job_attempts`.
- A singleton `admission_gate` row is locked `FOR UPDATE` in the same
  transaction as every submit (queue depth check) and every claim (active
  count check), followed by `SKIP LOCKED` selection of the oldest queued job.
- If the gate's `max_concurrent` differs from the configured value, claims
  fail loudly (ERROR log, readiness DOWN) instead of running with an unknown
  ceiling.

## Alternatives considered

- **Kafka or RabbitMQ:** they add a second stateful system and still need a
  database for status, ownership, history and fencing. They also don't provide
  a global in-flight ceiling.
- **In-memory queue:** loses accepted work on every deploy and has no global
  limit.

## Consequences

- One database is the single source of truth for job state.
- Submits and claims serialize on one row. That is fine at export rates
  (jobs per minute) but would not suit messages-per-second workloads.
- Operators must change `admission_gate.max_concurrent` and the config value
  together (see the [runbook](../OPERATIONS.md)).
