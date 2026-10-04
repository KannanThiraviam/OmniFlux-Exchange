# ADR-0004: Object storage with presigned downloads instead of files

- **Status:** Accepted
- **Detail:** [object-storage rationale](../architecture/overview.md#why-object-storage); [architecture](../architecture/architecture.md)

## Context

Writing rows to a file is as streamable as writing to S3, so streaming alone
does not settle this. What matters is what happens after the export finishes.
Several pods produce files, and users download them minutes or hours later,
from any pod, sometimes several gigabytes at a time.

## Decision

Exports are written as S3 multipart uploads to claim-specific keys
(`exports/<job-id>/a<attempt-count>/c<claim-token>/data.csv` or `data.xlsx`,
using the configured export prefix) in
an S3-compatible bucket (IBM COS or AWS S3 in production, SeaweedFS locally).
The claim token is part of the immutable storage identity. Shutdown can restore
the attempt counter, but a new claim cannot reuse an earlier claim's key.
Reconciliation also recognizes legacy `a<attempt-count>/data.<extension>` keys;
it conservatively protects them while that attempt number has a live lease.
Downloads are presigned GET URLs, signed locally with HMAC on each request and
valid for 15 minutes. URLs are never stored.

## Alternatives considered

| Option | Rejected because |
|---|---|
| Local file on the pod | Invisible to other pods, lost on restart, fills ephemeral storage and gets the pod evicted |
| Shared filesystem (ReadWriteMany PVC, NFS) | The service would have to serve every download byte (holding connections and threads), and build per-file access tokens, expiry, cleanup and capacity management itself |
| Streaming the export directly in the HTTP response | Ties a connection to a minutes-long job; gateway timeouts; deploys kill downloads; retries regenerate the whole export |

## Consequences

- Download bandwidth and duration never touch a pod.
- Expiry and incomplete-upload cleanup are bucket lifecycle rules, backed by
  `StartupReconciler`.
- Presigned URLs are bearer capabilities: treat them as secrets, keep the TTL
  short, and never log them.
- The bucket's public endpoint must be reachable from users' browsers
  (`storage.public-endpoint`).
