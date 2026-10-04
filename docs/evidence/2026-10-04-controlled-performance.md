# Controlled performance comparison, 2026-10-04

The latest code passed all export-integrity and resource checks in this comparison.
Performance is not identical: median R2DBC CSV worker time increased by about
9%, while REST medians changed by about 1%. These measurements do not certify
production capacity or every workload.

## Method

Compared committed baseline `a1fb3bf` with the latest working-tree application,
including the five correctness fixes, dependency updates, and dashboard download
hardening. The exact JAR hashes, baseline commit, runtime image ID, and settings
are in the [manifest](runs/20261004-controlled-performance-v3/manifest.json).
Both builds used the same latest `StreamingRunner` and existing 10-million-row
`mock_customers` fixture; no source rows were seeded or changed during measurement.

- Same PostgreSQL, PostgREST, and SeaweedFS services; only one application worker
  build active at a time. The original Compose application was paused and restored.
- Same Temurin 25 container image, two CPUs, 512 MiB container memory, 320 MiB
  maximum heap, 64 MiB direct-memory limit, read-only root, and 16 MiB tmpfs.
- Export caching disabled. Same default page sizes: 1,000 for R2DBC and 5,000
  for REST. Polling, leasing, storage, and format settings matched.
- For each adapter, fresh application blocks ran in baseline/latest/latest/baseline
  order. Each block warmed each workload once and measured it three times:
  six measured samples per build and workload.
- One-million-row single CSV, 100,000-row XLSX, and a four-job R2DBC CSV case
  with one million rows per job. Sustained concurrency is needed to observe
  overlap with the normal two-second worker polling interval.

The primary metric is worker `exportMillis`, excluding queue wait and client
download verification. For the four-job case it is the slowest job's export
duration, not aggregate per-job time. End-to-end medians and sample ranges
appear separately in the [summary](runs/20261004-controlled-performance-v3/summary.json).
All [raw samples](runs/20261004-controlled-performance-v3/samples.jsonl) include
warm-up labels, timings, checksum evidence, resource telemetry, and exit status.
Reproduction commands are in [Quality gates](../QUALITY_GATES.md#controlled-performance-comparison).

## Results

Positive changes below mean increased export time. Warm-ups are excluded.

| Workload | Baseline median | Latest median | Change | Measured samples per build |
|---|---:|---:|---:|---:|
| R2DBC CSV, 1 million rows | 11.379 s | 12.389 s | +8.88% | 6 |
| R2DBC XLSX, 100,000 rows | 5.072 s | 5.180 s | +2.14% | 6 |
| R2DBC CSV, four jobs of 1 million rows | 25.407 s | 27.551 s | +8.44% | 6 |
| REST CSV, 1 million rows | 7.002 s | 7.058 s | +0.79% | 6 |
| REST XLSX, 100,000 rows | 5.187 s | 5.132 s | -1.07% | 6 |

Single-job R2DBC CSV samples ranged from 11.040-11.688 s at baseline and
12.252-12.785 s in the latest build. Four-job ranges were 24.261-26.256 s
and 26.973-28.504 s respectively. The increase was consistent across both
application blocks. Added per-query transactions and deadline enforcement are
a plausible contributor; this comparison does not isolate their cost from
the other changes. Those safeguards remain enabled.

REST ranges overlap: CSV 6.588-7.382 s versus 6.540-8.014 s, and XLSX
5.112-5.488 s versus 5.066-5.266 s. Small median differences on this shared
Docker Desktop host do not establish a performance improvement or exact parity.

## Integrity and resource evidence

- 80 successful samples: 20 warm-ups and 60 measured. All 128 submitted jobs
  completed and independently verified, including 96 jobs in measured samples.
- Matching row counts, row-key digests, and output byte counts between builds:
  CSV 67,360,034 bytes per job; XLSX 2,483,982 bytes per job. Each download's
  complete checksum also matched its worker-persisted checksum.
- Every concurrency sample observed four active workers and respected the
  global admission ceiling of four. Zero OOM kills, filesystem events, and
  filesystem-watcher overflows were recorded.
- Heap high-water marks were approximately 210-213 MiB across both builds.
  Container high-water marks reached 512.06-512.64 MiB, within the harness's
  existing 1 MiB kernel-accounting tolerance above the 512 MiB limit. These
  peaks include startup, warm-ups, and file/page cache; they are not per-export
  allocations. No additional container memory headroom is established.

This is a warm-fixture comparison of local single and four-job workloads on
a shared host. It does not cover cold source caches, wide-row exports, multiple
application replicas, large XLSX files, or production network/storage latency.
Do not claim unchanged performance based on the passing correctness tests.

## Publication verification and exception

The five existing Snyk Code findings are deferred by the repository owner's
explicit instruction on 2026-10-04. They remain visible and blocking in the
normal hooks; no scanner ignores or blanket exclusions were added. Publication
uses the explicit Git hook bypass only after the other checks are verified.
This exception does not turn the combined security gate into a passing result.

See [Implementation status](../IMPLEMENTATION_STATUS.md) for the correctness
fixes, verification results, and remaining limitations. OWASP dependency-check
remains a separate unverified scan, as documented in [Quality gates](../QUALITY_GATES.md#dependency-vulnerability-scanning-owasp-dependency-check-and-snyk).
