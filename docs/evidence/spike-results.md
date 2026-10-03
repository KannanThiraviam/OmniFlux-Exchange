# Spike results — the blocking bridge and the S3 upload path

**Date:** 2026-09-05
**Code:** `spike/` — throwaway, kept for reproducibility; module retired 2026-09-19 (contents promoted — see the addendum at the end of this file)
**Original spike environment:** Java 25 (Temurin), Spring 6.2.8, Reactor 3.7.7,
AWS SDK v2 **2.31.30**, MinIO latest in Docker, `-Xmx256m`.

> Historical note: this spike used MinIO. The current local development/test
> fixture is pinned SeaweedFS; these provider-specific observations do not
> describe the current local object store.

This file preserves the original reproducible spike and its later version
re-runs. It is historical evidence rather than a claim that every row below
was rerun on the current application BOM; the current resolved versions are in
`pom.xml`.

These answer the two questions that gated the upload path in design §18.5.
Both were raised in review as assumptions the design asserted rather than
verified. **One assumption held; one was wrong and is now fixed.**

---

## Summary

| # | Question | Result |
|---|---|---|
| Q1 | Is the `outputStreamPublisher` consumer invoked on the supplied `Executor`? | ✅ **Yes** — `export-scheduler-1`, not the subscribing thread |
| Q2 | Does `toStream(1)` hold demand at 1? | ✅ **Yes — max in-flight = 1, exactly** |
| Q3 | Does cancellation unblock a consumer parked inside `write()`? | ✅ **Yes** — `IOException: Subscription has been terminated` |
| Q4 | Does cancellation unblock a consumer parked **awaiting a row**? | ❌ **No — thread leaked permanently** |
| Q4b | Does explicit `takeUntilOther` coupling fix Q4? | ✅ **Yes**, and the scheduler thread is reusable afterwards |
| Q5 | Does unknown-length `fromPublisher` work under `multipartEnabled(true)`? | ✅ **Yes** — 13,631,488 bytes, byte-exact |
| Q6 | Subscribe count on a clean upload? | ✅ **1** |
| Q7 | Does the SDK **resubscribe** to the body on a part retry? | ✅ **No** — 4 UploadPart attempts, **1 subscription**, upload completed |

---

## Q4 — the defect, and why it mattered

Design revision 8 claimed cancellation was handled because Spring documents that
cancelling the byte publisher makes `write()` throw. That is true, and it covers
only **one** of the two places the consumer can be parked.

Parked awaiting a row instead:

```
[Q4] consumer exited via: STILL-PARKED (thread leaked)
[Q4] source subscription cancelled = false
```

Cancelling the byte publisher does not touch the **separate** row subscription,
and `try (Stream<byte[]> s = ...toStream(1))` does not help — the consumer never
exits `hasNext()`, so it never reaches the close.

**Why this is worse than a leak.** The export scheduler holds one thread per
concurrent export. Every cancelled export parked awaiting a row burns one thread
**forever**. At the default of 4, four cancellations kill the export subsystem —
with no error, no metric, and a health check still reporting green.

It would also have escaped casual testing: cancelling while the *writer* is busy
(Q3) works perfectly. The failure needs a slow *source* — which is precisely the
condition under which a user cancels a stuck export.

### The fix, verified

Couple the two subscriptions with an explicit signal:

```java
Sinks.Empty<Void> cancelSignal = Sinks.empty();

Flux<byte[]> rows = rowSource.rows(job)
        .takeUntilOther(cancelSignal.asMono());        // <-- coupling

Flux<DataBuffer> bytes = Flux.from(DataBufferUtils.outputStreamPublisher(
                out -> { try (var s = rows.toStream(1)) { ... } },
                factory, exportExecutor, chunkSize))
        .doOnCancel(cancelSignal::tryEmitEmpty);       // <-- coupling
```

```
[FIX] consumer exited via: completed-normally
[FIX] source terminated   = true
[FIX] second export on the same single-thread scheduler: ran
```

The second assertion is the one that matters operationally: the scheduler thread
is **reusable**, so cancellation no longer costs capacity.

---

## Q7 — the replayability question

`AsyncRequestBody.fromPublisher` is documented as requiring a **replayable**
publisher: the SDK may resubscribe, and each subscription must reproduce the
complete content. Our publisher re-runs the query on resubscription, which for a
`MUTABLE` relation would produce **different bytes under the same upload**, plus
a corrupted digest and row counters.

Tested by injecting a `RetryableException` on the first `UploadPart` through an
`ExecutionInterceptor`:

```
[Q7] upload outcome       = COMPLETED
[Q7] UploadPart attempts  = 4        (3 parts + 1 retry)
[Q7] body SUBSCRIBE COUNT = 1
[Q7] incomplete MPUs left = 0
```

**The SDK buffers each part and retries from that buffer.** It does not
resubscribe to the source.

### What this does and does not license

- It **does** mean `fromPublisher` + `outputStreamPublisher` is viable, and that
  the bridge can stay as designed.
- It **does not** mean the contract can be ignored. This is *observed behaviour
  of one SDK version*, not an API guarantee — the javadoc still says replayable.

So the design keeps both: the observed behaviour, **and** a defence that does not
depend on it. `UploadSession.body()` **rejects a second subscription** with a
deterministic error, and a resubscribe attempt fails the attempt rather than
silently uploading inconsistent bytes. The SDK version is pinned, and this spike
becomes a regression test that runs on upgrade.

### A bonus finding

The first run used a plain `RuntimeException`, which the SDK does **not**
classify as retryable. The upload failed — and left:

```
[Q7] incomplete multipart uploads left behind = 1
```

That is the orphaned-parts scenario, reproduced empirically. It confirms two
requirements that were previously argued from first principles: the bucket needs
a lifecycle rule aborting incomplete multipart uploads, and startup
reconciliation has real work to do.

---

## Design consequences

| Finding | Change |
|---|---|
| Q4 | **`takeUntilOther` cancellation coupling is mandatory**, not an implementation detail. Two tests required: cancel while blocked in `write()`, and cancel while awaiting a row |
| Q7 | Bridge confirmed. `body()` must still **reject a second subscription**; SDK version pinned; this spike kept as a regression test |
| Q2 | `toStream(1)` confirmed as the demand-one boundary — the resource budget's prefetch term is 1, measured |
| Q1 | Thread placement confirmed; BlockHound remains the intended whole-application guard, but 1.0.17.RELEASE cannot instrument Java 25.0.4.1, so executor placement and bounded-demand tests are the active guards |
| Bonus | Bucket lifecycle rule and startup reconciliation are empirically justified, not just argued |

## Reproducing

```bash
docker run -d --name omniflux-spike-minio -p 9000:9000 \
  -e MINIO_ROOT_USER=minioadmin -e MINIO_ROOT_PASSWORD=minioadmin \
  minio/minio:latest server /data

cd spike && mvn -B test
```

`BridgeSpikeTest` (Q1–Q4) needs no MinIO. `CancellationFixSpikeTest` (Q4b) needs
no MinIO. `S3ReplaySpikeTest` (Q5–Q7) does.

---

## Spike 3 — can the abort-incomplete-multipart lifecycle rule be installed?

Spike 1 left an orphaned multipart upload behind after a non-retryable failure.
The design's mitigation was "the bucket needs a lifecycle rule aborting
incomplete multipart uploads." That was argued, never tested.

**It does not work on MinIO.**

| Route | Result |
|---|---|
| `mc ilm rule add` | **No flag exists** for `AbortIncompleteMultipartUpload` |
| `mc ilm import` with the S3 JSON | `The XML you provided was not well-formed or did not validate against our published schema` |
| **AWS SDK `putBucketLifecycleConfiguration`** | **Same rejection** — so it is MinIO, not `mc` |
| `mc ilm rule add --expire-days` (object expiry) | ✅ works |

```
[LC] AbortIncompleteMultipartUpload via SDK -> REJECTED: S3Exception /
     The XML you provided was not well-formed or did not validate against
     our published schema (Status Code: 400)
```

### Design consequence

**`StartupReconciler` is promoted from backstop to sole local mitigation.** The
design treated the lifecycle rule as the primary defence against orphaned parts
and reconciliation as belt-and-braces. Locally that is inverted: there is no
lifecycle rule, so reconciliation is the only thing that cleans up.

Two follow-ups:

1. **Verify separately on IBM COS.** COS documents lifecycle support, but after
   this result it should be measured rather than assumed. If COS also rejects
   it, reconciliation is load-bearing in production too and its correctness
   matters far more than the plan currently implies.
2. **The demo must not imply a rule exists.** `createbucket` prints the
   limitation rather than silently skipping it, so nobody later assumes orphaned
   parts are being swept by the bucket.

### Why this was worth an hour

The lifecycle rule appeared in the spec, in both appendices, and in the
deployment notes as a settled mitigation — argued from how S3 works, never
executed. It is the same failure shape as the four "guard fires too late" bugs:
a defence that is correct in principle and absent in practice.

---

## Version bump re-verification — 2026-09-05

The project moved to latest-everything. Because **Q7 is observed behaviour, not
an API guarantee**, an SDK bump normally re-opens the whole bridge decision. The
spike exists so it does not.

### AWS SDK 2.31.30 → 2.46.7 (15 minor versions)

All eight assertions re-ran unchanged:

```
[Q1] consumer thread    = export-scheduler-1     (not the subscribing thread)
[Q2] MAX IN-FLIGHT      = 1                      (exactly)
[Q3] cancel in write()  -> IOException: Subscription has been terminated
[Q4] cancel awaiting row -> STILL-PARKED          (still broken — see below)
[FIX] takeUntilOther     -> completed-normally, thread reusable
[Q5] unknown-length MPU -> 13,631,488 bytes, byte-exact
[Q6] clean subscribe    = 1
[Q7] injected retry     -> COMPLETED, 4 UploadPart attempts, SUBSCRIBE COUNT = 1
```

Two things worth noting:

- **Q7 holds on the latest SDK.** The bridge stands, and the evidence is current
  rather than historical. This is the entire reason the spike is kept as a
  regression test rather than deleted after answering the question once.
- **Q4 still reproduces.** It is a Spring/Reactor interaction, not an AWS one, so
  the `takeUntilOther` coupling is mandatory regardless of SDK version. A future
  SDK cannot fix it.

### PostgreSQL 16 → 18.6

Two findings, both from running it rather than reading about it:

| | |
|---|---|
| **The volume mount changed** | PG18 refuses to start against `/var/lib/postgresql/data` and requires a single mount at `/var/lib/postgresql`, so `pg_upgrade --link` does not cross a mount boundary. The container restart-looped until this was corrected. `card-fraud-platform` already uses the new form |
| **Server-side async I/O is available** | `io_method=worker` confirmed active. This is *separate* from the client being async — the application already uses R2DBC end to end with no JDBC on the data path. PG18 adds asynchronous I/O inside the server; `io_uring` is the Linux-only alternative |

Verified after the fix: PostgreSQL 18.6, schema created at first boot, PostgREST
serving `/mock_customers` and a keyset query with HTTP 200.

### Correction — the first "latest" re-run tested the wrong Spring

The 2.46.7 re-run above bumped the AWS SDK but left the spike on **Spring 6.2.8
/ Reactor 3.7.7**. Spring Boot 4.1.0 ships **Spring Framework 7.0.8** and Reactor
BOM 2025.0.6. Since `outputStreamPublisher` is a *Spring* API and `toStream(1)`
is a *Reactor* one, Q1–Q4 had been verified against the wrong **major** version
of the exact libraries they test — evidence for a stack the application does not
use.

Re-run on the application's real BOM:

```
spring-core   7.0.8
reactor-core  3.8.6   (Reactor BOM 2025.0.6)
aws sdk       2.46.7

[Q1] consumer thread     = export-scheduler-1
[Q2] MAX IN-FLIGHT       = 1
[Q3] cancel in write()   -> IOException: Subscription has been terminated
[Q4] cancel awaiting row -> STILL-PARKED            (unchanged on Spring 7)
[FIX] takeUntilOther     -> completed-normally, thread reusable
[Q5] unknown-length MPU  -> 13,631,488 bytes, byte-exact
[Q6] clean subscribe     = 1
[Q7] injected retry      -> COMPLETED, 4 attempts, SUBSCRIBE COUNT = 1
```

All eight hold. **Q4 reproduces on Spring 7 as well**, confirming it is not a
version-specific quirk that an upgrade might remove — the `takeUntilOther`
coupling is permanent.

The lesson is narrower than "pin your versions": *verify against the BOM the
application actually resolves*, not against versions chosen independently. A
spike on adjacent versions is evidence about a stack nobody ships.

## Current application baseline — 2026-09-06

The current application resolves Spring Boot **4.1.1**, AWS SDK **2.54.13**,
Reactor test **3.8.7**, PostgreSQL JDBC **42.7.13**, Flyway **13.5.0**, and
Testcontainers **1.21.4**. The application baseline passed the full Maven
verification suite, the dependency vulnerability scan, the compose smoke, and
the live export smoke. The Q1–Q7 measurements above have not been repeated for
AWS SDK 2.54.13, so a future SDK upgrade should rerun the spike before treating
those measurements as current.

---

## Addendum — promotion and retirement, 2026-09-19

The spike module was consolidated into the main Maven build; see the
[decision log](../architecture/decision-log.md) for rationale:

- Q3/Q4/Q4b → `com.omniflux.exchange.contract.ReactorCancellationContractTest`
- Q5/Q6/Q7 → `com.omniflux.exchange.contract.S3MultipartReplayContractTest`
  (re-pointed from the standalone spike MinIO on :9000 to the compose stack;
  byte-exact SHA-256 verification added; subscription assertions name the
  `fromPublisher` source)
- Historical MinIO lifecycle-rule verdict → rejection of multipart-abort rules
  with HTTP 400 and acceptance of object-expiry rules. This is not the current
  local-store behavior. The current SeaweedFS acceptance contract is exercised
  by `com.omniflux.exchange.contract.SeaweedFsLifecycleRuleContractTest`.
- Benchmark harness → `com.omniflux.exchange.proof` (main build's test
  sources); `proof/run-matrix.sh` launches it from `target/test-classes`
- `NaiveExportController` → `com.omniflux.exchange.web` under
  `@Profile("demo")` (previously unservable dead code in the spike package)

**Duty transfer:** this file's standing note — "rerun the spike on SDK
upgrade" — is discharged structurally: the contract tests above run on every
`./mvnw verify` against the exact dependency set production resolves. The
version-bump history above remains as the evidence for WHY the tests exist.
