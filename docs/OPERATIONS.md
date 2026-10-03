# Operations runbook

This runbook covers the current local Compose stack and the operational checks exposed by the application. Production deployments must provide authentication and platform-specific storage/secret configuration; see [Security](../SECURITY.md) and [Threat model](THREAT_MODEL.md).

## Health and routine checks

- `GET /actuator/health`: application health and readiness/liveness groups. Readiness includes database-backed admission-gate consistency; liveness intentionally excludes it.
- `GET /actuator/prometheus`: low-cardinality Micrometer counters, timers, and queue gauges. OpenShift includes an optional Prometheus Operator `ServiceMonitor`; install its CRD/operator before applying that resource.
- `GET /api/system/resources`: pod name, heap/RSS/direct memory, cgroup peak/limit/OOM counts when available, filesystem watcher counts/overflow, active/queued/global active jobs, throughput/cache-hit rate, and selected effective config.
- `GET /api/jobs?limit=50`: inspect recent jobs. `GET /api/jobs/{id}/attempts` shows each durable attempt.
- `docker compose ps` and `docker compose logs --tail 100 app postgres seaweedfs postgrest` for the local stack.

Distributed tracing is not configured. Production console logs use ECS structured JSON; avoid logging request bodies, credentials, or presigned URLs. Use the API snapshot for process/resource details and Prometheus for time-series metrics.

## Local storage diagnostics

For the default Compose ports, open the [SeaweedFS master diagnostics page](http://localhost:9006) or check [master health](http://localhost:9006/cluster/healthz). The master provides storage status; S3 object access uses a separate endpoint, `http://localhost:9005`. See [Local data and storage](LOCAL_DATA.md#open-seaweedfs-diagnostics) for browser steps, port overrides, database queries, and S3 client settings.

## Database migration diagnostics

Flyway applies pending schema migrations during application startup. If startup fails with a migration or checksum error, inspect `docker compose logs --tail 100 app`, compare the deployed migration files with the recorded history, and resolve the mismatch through a reviewed forward change. Do not delete history rows or rewrite applied migrations. See [Flyway and startup migrations](data-model.md#flyway-and-startup-migrations) for the history query and the local V1 baseline.

## Metrics and suggested alerts

| Metric (Prometheus name) | Type | Meaning |
|---|---|---|
| `omniflux_jobs_queued` | gauge | Jobs waiting in the database queue (refreshed every 15 s) |
| `omniflux_jobs_global_active` | gauge | Jobs running across all replicas; never exceeds `queue.max-concurrent` |
| `omniflux_jobs_active` | gauge | Active jobs visible to this database |
| `omniflux_jobs_worker_active` | gauge | Jobs executing on this pod |
| `omniflux_jobs_claimed_total` | counter | Jobs this pod has claimed |
| `omniflux_jobs_finished_total{outcome}` | counter | Finished attempts by emitted outcome: `completed`, `failed`, `lease_lost`; running cancellation currently also reports `lease_lost` |
| `omniflux_jobs_execution_seconds{outcome}` | histogram | Attempt duration |
| `omniflux_jobs_rows_per_second` | gauge | Five-minute completed-row throughput |
| `omniflux_jobs_cache_hit_rate` | gauge | Five-minute cache-hit ratio |

Suggested starting alerts (tune the thresholds to your workload):

- **Queue not draining:** `omniflux_jobs_queued > 0` and
  `omniflux_jobs_global_active == 0` for 5 minutes. Usually a gate mismatch or
  a database problem; see the next section.
- **Saturation:** `omniflux_jobs_global_active` at `max-concurrent` and
  `omniflux_jobs_queued` rising for 15 minutes.
- **Failures:** `rate(omniflux_jobs_finished_total{outcome="failed"}[15m])`
  above your baseline.
- **Lease loss:** any sustained
  `rate(omniflux_jobs_finished_total{outcome="lease_lost"}[15m])`, which points
  at database latency or pod CPU throttling.
- **Pod memory:** container working set near the 512 MiB limit, or any OOM
  kill. Investigate buffer limits, concurrency, JVM overhead, and workload assumptions; the configured memory estimate is not a guarantee for every workload.

## Stuck queued jobs or gate mismatch

Symptoms: jobs remain `QUEUED`, workers are not claiming work, or the poller reports repeated claim failures.

1. Check health and app logs across every replica. Look for database connectivity, claim, and admission-gate errors.
2. Compare `omniflux.queue.max-concurrent` on all replicas with `admission_gate.max_concurrent`:

   ```sql
   SELECT id, max_concurrent FROM admission_gate;
   SELECT status, count(*) FROM transfer_jobs GROUP BY status ORDER BY status;
   ```

   The gate is a singleton (`id=1`) and its configured ceiling must agree with the application configuration on every replica. Resolve a mismatch through a reviewed configuration/database change; do not delete the gate row or manually mark active jobs complete.
3. Check database connections and the `transfer_jobs` claim/lease indexes. Review recent job attempts and errors.
4. If the queue is legitimately at `max-depth`, reduce upstream load or restore worker capacity. New requests return `QUEUE_FULL`/429 until capacity is available.

## Expired leases and retries

Workers renew leases independently. Expired `IN_PROGRESS` work is handled by the lease sweeper and requeued while attempts remain; after the configured maximum, the job becomes terminal. Inspect job detail and attempt history before retrying. A manual retry restarts the export from the beginning. A replaced token rejects old worker database updates. Lease-expiry publication and shutdown object-key reuse remain open gaps; see [Implementation status](IMPLEMENTATION_STATUS.md#known-correctness-gaps).

Repeated `LEASE_LOST` errors may indicate database latency, overloaded workers, clock/lease configuration problems, or shutdowns exceeding the drain window. Compare attempt timestamps, replica logs, and configured lease-renew interval/deadline.

## Graceful shutdown and deploys

On SIGTERM, the queue poller stops accepting claims and workers drain for `omniflux.queue.drain-timeout` (default 30 seconds, maximum 40 seconds). Platform termination grace must exceed the application drain timeout plus enough time for process exit. If draining times out, the active attempt is requeued and its work may restart from row one. Plan rollout capacity accordingly; monitor queued jobs and attempts during deployment.

## Multipart upload cleanup

The startup reconciler correlates multipart uploads with job attempts and expired leases. A live lease is treated as active even if the worker appears unhealthy; do not manually abort uploads associated with live leases. Incomplete upload lifecycle cleanup is configured on the local bucket, and production storage should also have provider-side abort rules. For an orphan suspected after lease expiry:

1. Inspect the corresponding job and attempt (`object_key`, `claim_token`, start/finish time, status).
2. Verify the lease is expired and no current attempt owns that token.
3. Prefer the application reconciler/provider lifecycle rule. Use provider cleanup commands only after confirming no live owner and following the storage retention policy.
4. Verify the job was requeued or terminalized and a subsequent completed object has the expected byte count and checksum.

## Local development recovery

Start/recover the stack with `pwsh -File scripts/up.ps1`; rebuild with `pwsh -File scripts/up.ps1 -Build`. Check `.env` and Compose logs if the health wait fails. `docker compose down` preserves volumes. `docker compose down -v` deletes local database and object data and should be used only when that loss is intended.

## Capacity and evidence

The queue maximum is global across replicas via the PostgreSQL admission gate. Check `globalActive` against the configured global limit and evaluate heap/cgroup limits before raising concurrency. The proof matrix is a controlled measurement workflow, not a production load test. Preserve each timestamped evidence directory; do not compare timings without matching adapter, workload, and environment.
