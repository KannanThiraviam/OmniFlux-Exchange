# ADR-0001: Bounded streaming with zero local disk

- **Status:** Accepted
- **Detail:** [decision log](../architecture/decision-log.md); [architecture](../architecture/architecture.md)

## Context

Exports range from a few rows to tens of millions. Pods run with a 512 MiB
memory limit and minimal ephemeral storage. Building a file in memory causes
OOM kills. Building it on disk (including Apache POI's streaming writer, which
spools XML) causes ephemeral-storage evictions. Either failure takes down every
other user on the pod.

## Decision

Every stage of the export path holds a fixed-size buffer, and demand flows end
to end:

- Sources read keyset pages and decode them incrementally.
- Writers emit bytes row by row. CSV is a project RFC 4180 encoder; XLSX is
  hand-written streaming OOXML with inline strings.
- A demand-gated `outputStreamPublisher` bridge blocks the writer when the
  uploader has no demand.
- The uploader uses bounded buffers and sends 8 MiB parts by default.

The container runs with a read-only root filesystem and a 16 MiB tmpfs, to constrain
accidental spooling. `StartupValidator` checks the memory
arithmetic (`max-concurrent * per-job budget + baseline <= heap`, heap plus
non-heap reserve <= cgroup limit) at boot.

## Consequences

- Peak memory is independent of row count. A 1M-row XLSX export peaked at
  about 92 MiB of heap in the recorded proof run.
- We maintain our own XLSX writer: one worksheet, inline strings, capped at
  1,000,000 data rows.
- Changing heap, concurrency, part size, or row limits requires the validator's
  arithmetic to still hold, or the app refuses to start.
