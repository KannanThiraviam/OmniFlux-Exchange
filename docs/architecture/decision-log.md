# OmniFlux-Exchange — Decision Log

**Purpose:** the durable record of *why*. The spec says what to build; this says
what was considered, what was rejected, what was reversed, and what triggered
each change. Written so someone joining later — or the same people six months
on — does not relitigate settled ground or reintroduce a deleted mistake.

**Last updated:** 2026-09-06 · corresponds to design revision 16 and implementation
plan revision 10. Sections 1–8 are the design phase; **§9 onward is the
implementation-planning phase**, where the review focus moved from architecture
to arithmetic and test validity.

> Nothing in this file is deleted when it is superseded. A superseded entry is
> marked and points at what replaced it, because *what we used to believe and why
> we stopped* is the part that stops a mistake being reintroduced.

> **Current implementation amendment (2026-09-06):** `S3UploadSession` now uses
> explicit bounded multipart uploads. It buffers one configured part, waits for
> the object store to acknowledge that part, completes with the returned ETags,
> and aborts on failure or cancellation. Older entries below may describe the
> superseded SDK-managed unknown-length publisher path; they remain as design
> history and do not describe the current upload implementation.

---

## 1. Environment facts

These came from the project owner during design and are not recoverable from the
code. They drive most of the decisions below.

| Fact | Consequence |
|---|---|
| Production database is **Db2 for z/OS** | Distinct dialect from Db2 LUW: `FETCH FIRST n ROWS ONLY`, different catalogs and locking. `SqlDialect` seam exists; only Postgres implemented in v1 |
| Existing stack: **Data API** (JWT, in/out validation, row filtering) → **DB API** (filter/column validation, JDBC) → Db2 | OmniFlux reads through the Data API, not the database |
| **Only the Data API is exposed.** The DB API is internal | A separate OmniFlux cannot call the DB API. The Data API fronts the control plane; bytes bypass it |
| The org has a **dynamic single-table REST API** per table, PostgREST-shaped | Keyset paging maps onto it directly; joins do not — they need database views |
| **Views are not currently used much** in the org | Joins are deferred to v2 via JDBC, not solved through views in v1 |
| **3 pods on OpenShift**, stateless replica sets, **rolling deployment**, new pod health-checked before the old one goes | Leases + fencing are mandatory; global concurrency must be enforced in the database |
| JWT carries a **user id** | The cache fingerprint can include the caller — see §4.3 |
| Object storage is **IBM COS**; MinIO locally | S3 API, `pathStyleAccess`, HMAC credentials |
| Java 25 LTS, Maven 3.9, Docker available locally | Boot 4.1 baseline is Java 17; targeting 25 |
| **`db2-r2dbc` may not be entitled** under the existing `db2jcc` licence | Open question §6.3 — architecturally neutral either way. IBM does publish reactive/R2DBC support for Db2 for z/OS; the question is entitlement, not existence |
| Data API has **M2M tokens today**; **H2M** user tokens are being added | Drives the async credential design (§8.1) |
| **The requirement is to *prove* streaming with no disk and little memory** | Observability is a deliverable, not supporting infrastructure (§8.4) |
| The org's Data API was found to be **accidentally running Spring MVC**, not WebFlux | See §5.4 — it had both starters on the classpath |

---

## 2. The decisions that define the design

### 2.1 Dynamic engine, no ORM

**Decision.** Relation and columns arrive at runtime. No compile-time entities,
no JPA, no Spring Data repositories. R2DBC's low-level `DatabaseClient`.

**Rejected:** typed entities with a JPA `Stream<User>` and `@QueryHints`.

**Why.** Adding an exportable relation must not require a code change. And JPA's
persistence context **retains every entity it loads** — streaming 100k rows
leaks heap unless you manually `clear()` periodically. With a low-level client
there is no first-level cache, so there is nothing to leak. The same argument
carried over from `JdbcTemplate` to `DatabaseClient` when the stack went
reactive.

### 2.2 Keyset pagination, never OFFSET

**Decision.** `WHERE pk > :after AND pk <= :highWater ORDER BY pk LIMIT n`.

**Rejected:** (a) `OFFSET` paging; (b) one unbounded query with a held cursor.

**Why.** `OFFSET` is O(n) row-skipping — page 100 re-scans 99 pages. A held
cursor pins a connection and a transaction for the whole export (minutes),
which starves the pool and shows as `idle in transaction`. Decisively: **a
cursor cannot be expressed over HTTP**, and the primary read path is an HTTP
API.

**Consequence.** Each page is a separate statement, so there is no snapshot. See
§4.1 for what that does and does not guarantee.

### 2.3 The high-water bound

**Decision.** `MAX(pk)` is captured once before page 1; every page adds
`AND pk <= :highWater`.

**Why.** Without it, rows inserted during a long export keep entering the scan,
so the export has no defined end and its contents depend on timing.

**What it does *not* do — corrected during review.** An earlier draft claimed
"later inserts cannot extend it," full stop. That is only true **above** the
mark. A row inserted at `last_key < pk <= high_water` **does** appear in a later
page. So: it guarantees *termination*; it guarantees *membership* only for
monotonic relations.

### 2.4 Integer primary key only, in v1

**Decision.** A single non-null immutable integer PK. Text, UUID, DATE and
DECIMAL keys are rejected.

**Why — three problems collapse into one restriction:**

1. A typed continuation stored as text cannot faithfully round-trip decimals,
   timestamps, UUIDs or collations, and binding a string against a numeric key
   changes comparison semantics.
2. A textual key can itself be arbitrarily large and crosses the driver boundary
   during high-water capture, outside the byte guard's projection.
3. **Termination.** With a text or UUID key there are *infinitely many* values
   between `last_key` and `high_water`, so sustained below-bound inserts can
   extend a scan forever. With an integer key the interval is finite.

*(A SQLite-specific fourth reason existed while SQLite was in play — only
`INTEGER PRIMARY KEY` is type-enforced there — and disappeared with SQLite.)*

### 2.5 One upload mechanism, both formats

> **Superseded in part by §8.3.** The *rejections* below still stand and are the
> reason the alternatives are not revisited. The chosen bridge changed from
> `forBlockingOutputStream` to `DataBufferUtils.outputStreamPublisher` +
> `fromPublisher` in revision 8 — read §8.3 for why that reverses the
> `fromPublisher` rejection recorded here.

**Decision (rev 1-7).** `S3AsyncClient` with `multipartEnabled(true)` and
`AsyncRequestBody.forBlockingOutputStream(null)`.

**Rejected:** (a) `PipedInputStream`/`PipedOutputStream`; (b) a hand-rolled
multipart sink; (c) `AsyncRequestBody.fromPublisher`.

**Why not piped streams** (the original brief specified them): 1 KB default
buffer; a dead reader masks the real exception with `IOException: Read end
dead`; and they don't compose with `putObject`, which requires a known content
length you don't have.

**Why not a hand-rolled sink** (revision 1 did this): it makes *us* responsible
for the part registry, ETag tracking, the empty-final-part edge case, retryable
bodies and defensive-copy accounting. Independent review found defects in
**all four**.

**Why not `fromPublisher` (at the time):** it would require the writer to
*produce* a `Flux`, which means bridging back through a sink whose full-buffer
case has to block anyway. **This reasoning was correct but incomplete** — it
assumed we would have to build that bridge. Spring ships one
(`DataBufferUtils.outputStreamPublisher`, ≥ 6.1) whose contract is better than
what we were hand-building. See §8.3.

### 2.6 Zero-disk XLSX

**Decision.** Hand-written streaming OOXML: `ZipOutputStream` + inline strings,
sheet XML emitted row-by-row into the upload stream.

**Rejected:** Apache POI `SXSSFWorkbook`.

**Why.** POI *requires* temp disk — a shared-strings table cannot be finalised
until every row is seen, so SXSSF spills rows to temp XML and assembles at the
end. **Bounded disk is still disk**: in OpenShift, exceeding `ephemeral-storage`
**evicts the pod**, which is an OOM by another name.

Inline strings (`t="inlineStr"`) remove the shared-strings dependency, and a ZIP
writes its central directory **last**, so a spreadsheet can be emitted purely
sequentially.

**Cost accepted:** ~250 lines of OOXML, data-only output (no formulas, one
sheet, minimal styling), and larger files since inline strings don't dedupe.

**The trap that nearly shipped.** An earlier draft defined the writer's final
call as "flush, does not close" while stating the central directory is written
"on close" — meaning **the directory would never have been written and every
file would have been a corrupt archive.** Hence `finishContainer()`, contractually
required to call `ZipOutputStream.finish()`.

### 2.7 Database-backed job queue with leases and fencing

**Decision.** A `transfer_jobs` table. Atomic claim via
`UPDATE … WHERE id = (SELECT … FOR UPDATE SKIP LOCKED) RETURNING`, plus
`lease_until`, a `claim_token` fencing token, and an **independent** renewer.

**Rejected:** (a) in-memory `@Async` executor; (b) bare `claimed_at` timeout;
(c) startup-requeue of `IN_PROGRESS`.

**Why not in-memory:** it dies with the pod, breaking the `202 Accepted` promise
on every rolling deploy. Also, a synchronous export has **no admission
control** — the limit becomes `server.tomcat.threads.max` (200 by default), and
200 × per-job memory kills the pod with perfectly correct streaming code.

**Why not a bare timeout:** it cannot distinguish **slow** from **dead**. A
healthy 6-minute export exceeds a 5-minute timeout and gets requeued *while
still running* — two workers, two objects, conflicting audit.

**Why the renewer must be independent, not at page boundaries:** a worker can
exceed the lease while blocked writing into the SDK, waiting inside the upload
future, or parsing one pathological page — **none of which reach a page
boundary**.

**Why not startup-requeue:** that shortcut is only legal with a single replica
and no overlap. With 3 pods and rolling deploys, `IN_PROGRESS` rows belong to
**healthy siblings** — requeueing them at startup causes exactly the double-run
the leases prevent.

### 2.8 Global concurrency, not per-process

**Decision.** The claim query counts `IN_PROGRESS` rows across the cluster.

**Why.** Three pods each enforcing `max-concurrent: 4` gives **12** concurrent
jobs. Note the correction in §5.2: this does *not* threaten per-pod memory —
each pod bounds itself — but it does multiply load on the Data API and COS, and
it makes the configured limit meaningless.

### 2.9 No cross-attempt resume

**Decision.** A failed export restarts from row 1 into a fresh attempt key.

**Rejected:** resuming from `last_key`.

**Why.** After a failure the multipart upload is aborted, so resuming after
`last_key` yields **header + tail** — a truncated file presented as complete.
The owner later proposed tracking uploaded parts, which *is* the correct
mechanism: persist `upload_id` and `(part_number, etag, last_key)` per part,
checkpointing at **part** boundaries (not page boundaries, since only an
uploaded part is durable). It was deferred because it forces a return to manual
multipart (§2.5), and at 100k rows a re-run costs ~40 seconds.

`last_key` is retained for **progress display only**.

### 2.10 Presigned URLs, minted on click

**Decision.** Store `object_key`; sign on demand.

**Rejected:** storing the URL in the database (which the original brief implied).

**Why.** Presigned URLs expire — a stored one becomes a dead link failing with
an opaque `403`. Presigning is a **local HMAC-SHA256 computation with no network
call**, so minting per click costs microseconds; caching one buys nothing and
breaks correctness.

**Properties accepted** (discussed and judged acceptable): the URL is a bearer
credential until it expires; it is not revocable; and its *use* is not visible
in the org's logs, only its *issuance*. Authorization happens once, at mint
time, through the Data API. `presign_count` therefore counts **issuance, not
downloads**, and the UI says "access requests."

---

## 3. Reversals, and what triggered each

The most important section: these are the mistakes, so they aren't repeated.

| # | Was | Became | Trigger |
|---|---|---|---|
| R1 | Typed JPA entities | Dynamic engine | Original brief contained both; owner chose dynamic |
| R2 | `PipedInputStream` bridge | SDK multipart | Doesn't compose with a required content length |
| R3 | Hand-rolled multipart sink | SDK automatic multipart | Review found four defects in it |
| R4 | Resume from `last_key` | Restart from row 1 | Produces a truncated file presented as complete |
| R5 | Bare `claimed_at` timeout | Lease + fencing + renewer | Requeues healthy long-running jobs |
| R6 | Apache POI SXSSF | Streaming OOXML | **Owner: "this is what we are trying to avoid — no memory/no disk"** |
| R7 | Import staging table | Import deferred to v2 | **Owner: "staging moves the problem elsewhere"** — see §3.1 |
| R8 | SQLite | Postgres local / Db2 prod | **Owner: "I picked it because it's easy to run locally"** — and Docker was already required for MinIO, so its only advantage was gone |
| R9 | Single replica + `Recreate` | 3 pods + rolling | Owner corrected the deployment reality; my memory argument for single-replica was also simply wrong (§5.2) |
| R10 | Spring MVC + `JdbcTemplate` | WebFlux + R2DBC | Owner requirement |
| R11 | Export-only framing | Export/import service, import deferred | **Owner: "we are building a new export/import service"** — the distinction matters (§3.2) |
| R12 | Identity-free cache fingerprint | Fingerprint includes `userId` | Cross-user data leak found during discussion (§4.3) |
| R13 | Views rejected outright | Views allowed by explicit allowlist | Joins require them (§4.4) |

### 3.1 Why staging was wrong — the full argument

Staging existed to make import atomic: write all rows into `stg_…`, then one
`INSERT INTO target SELECT … FROM staging`, so a bad row at 50,001 doesn't leave
50,000 committed.

The owner's objection — *"staging moves the problem elsewhere"* — is correct:

- **The long write is still there**, just relocated from "spread across the
  download" to "concentrated at the promote."
- **Every row is written twice.** Double I/O, double WAL.
- **It doesn't catch the errors you'd most want caught early.** Foreign-key
  violations, unique conflicts against existing rows and trigger failures only
  surface at *promote* time, after all rows are staged.
- **The same atomicity was available for free**: `BEGIN; insert everything;
  COMMIT;` rolls back identically with no staging table.

So staging's *entire* remaining benefit was keeping download and parsing outside
the write transaction — enormous on a single-writer database, near-worthless on
Postgres MVCC.

It also generated more defects than any other feature in the design: attached
staging files, connection pinning against the pool, a cross-database atomicity
trap, page-count quotas in pages-not-bytes, journal allowance accounting, a
reservation that leaked on a lost race, and orphan-file cleanup.

**Note for v2:** the replacement is batch commit + idempotent upsert with
`rows_committed` on the job row — non-atomic but **resumable**, which staging
can never be.

### 3.2 "Export-only service" vs "export/import service, import deferred"

The owner drew this distinction explicitly, and it changes the architecture:

- An **export-only service with import bolted on later** needs reshaping when
  import arrives.
- An **export/import service with import deferred** is symmetric from day one —
  `RowSink` is declared as `RowSource`'s counterpart, the job row carries a
  `direction`, and the queue and presign machinery serve both. Import lands as
  *filling in an implementation*.

Build the second; ship only the export half.

---

## 4. Problems found during design that are easy to reintroduce

### 4.1 The consistency contract is weaker than it looks

Pages are separate statements. There is **no snapshot**. For `MUTABLE`
relations, rows below the high-water mark may be updated or deleted after being
written, and rows inserted below it *will* appear. State this in user-facing
docs rather than implying atomicity.

### 4.2 Guards must fire *before* materialisation

This failure shape appeared **four separate times** in review:

| Guard | Why it was unreachable |
|---|---|
| `RowMapper` → `List<T>` | Spring accumulates every row before returning; use a streaming consumer |
| `if (value.length() > limit)` after `getString()` | The 1 GB value is already in heap |
| Apache Commons CSV field limit | The parser accumulates each token in a `StringBuilder` first |
| `max-page-bytes` checked after decoding | The driver already fetched and materialised the page |

**Rule: a bound on untrusted input must be enforced by whatever does the
accumulating** — in the SQL (`octet_length` inside a lazy `CASE`), in the
parser's own settings, or by capping the response read. Once you hold the
object, the memory is spent.

### 4.3 The cache was a cross-user data leak

The fingerprint was `relation + columns + filters + format`. **It did not
include who was asking.** Since the Data API applies per-user row filtering, a
cache hit would have served user A's bytes to user B **without re-evaluating
authorization** — silently, with no error and no log entry.

Fixed by including `userId` from the JWT. Note the trade: correctness costs hit
rate, because users never share entries. Hashing the *authorization context*
(roles/tenant) instead would share entries between users with identical
permissions — a one-field change if hit rates disappoint.

**Related:** cache origins must be **generated** jobs only (`cache_hit_of IS
NULL`) with an immutable `generated_at`. A cache-hit row's `finished_at` is now;
if hits could be origins, every hit would refresh the TTL and staleness would be
**unbounded**, destroying the entire safety argument.

### 4.4 Joins and the keyset key

The org's data API is single-table, and PostgREST-style resource embedding
returns **nested JSON**, which doesn't flatten into CSV. The canonical answer is
a **database view**, exported like any other relation.

**The trap:** the keyset key must be unique **in the view's result set**, not
just in the underlying table. For `orders JOIN customers`, key on `orders.id` —
the *grain of the output*. Keying on the "one" side of a 1:N join silently skips
or duplicates rows, producing an incomplete export with no error.

This also forced a security-rule change: the identifier allowlist originally
rejected views outright. It now permits **explicitly named** views (R13).

### 4.5 Object publication is not atomic with the DB write

`CompleteMultipartUpload` can succeed while the database update fails. Handled
by: attempt-scoped keys, `object_key` set **only** by the fenced completion
update, and — critically — **`DeleteObject` rather than abort** on a lost final
fence, because a *completed* upload cannot be aborted.

---

## 5. Corrections to my own earlier reasoning

Recorded because the wrong versions are plausible and might be re-derived.

### 5.1 "Blocking" means two different things

| Sense | Example | Consequence |
|---|---|---|
| Holds everything in memory | `List<Row>`, POI's temp XML, `toBytes()` | **Fatal** — the thing this design prevents |
| Thread waits on synchronous I/O | `BufferedWriter` (8 KB), `ZipOutputStream` (64 KB window) | **Not a memory problem.** A placement problem: don't block the event loop |

The owner asked whether the blocking writers defeat the streaming goal. They do
not — they are pure pass-throughs and already stream perfectly. They just need
to live on `boundedElastic`, never on Netty's event loop.

### 5.2 Multi-replica does not multiply per-pod memory

I argued for a single replica partly on memory grounds. **That was wrong.** Each
pod bounds its own heap; three replicas is three pods each safely under its own
limit, not one pod at triple. What multiplies is *load* on downstream systems.

The real reason single-replica mattered was **recovery** — it was the
precondition that made "requeue `IN_PROGRESS` at startup" legal. Once the owner
confirmed 3 pods with rolling deploys, that shortcut became invalid and leases
came back.

### 5.3 Keyset makes the pgJDBC cursor configuration unnecessary

I claimed `setAutoCommit(false)` + `setFetchSize()` were "load-bearing" on
Postgres. **With keyset paging they are not** — each query already returns at
most `LIMIT` rows, so the driver has nothing to over-buffer. That configuration
matters only for **one unbounded query**, which this design never issues.

Worth keeping as a deliberate "naive endpoint" counter-example in the demo, to
*show* the OOM. But it is not part of the production path.

### 5.4 The Spring MVC / WebFlux classpath trap

From the Boot 4.1 reference: *"If both `spring-boot-starter-web` and
`spring-boot-starter-webflux` modules are present, Spring Boot auto-configures
Spring MVC, not WebFlux."* **This has been true since Boot 2.0**, is usually
introduced transitively (`springdoc-openapi-starter-webmvc-ui` is a classic),
and is silent.

Diagnosis: the startup log says **Tomcat** (servlet) or **Netty** (reactive).

The nastier variant it doesn't catch: being *on* the reactive stack while
writing blocking code. That is **worse** than MVC — MVC has ~200 Tomcat threads
to absorb blocking; Netty has roughly one event-loop thread per core, so
blocking four of them on an 8-core pod stalls HTTP for every request.

Hence **BlockHound in the test suite when the runtime supports it** (§2 of the
spec): the design deliberately keeps blocking writers, and BlockHound turns
"they're confined to the export scheduler" from an intention into a verified
property. The latest tested 1.0.17.RELEASE agent cannot instrument Java
25.0.4.1, so the executor placement and bounded-demand tests remain the active
regression guards.

### 5.5 Presigned URLs are not a governance hole

I initially framed them as bypassing the org's auth stack. The owner pushed
back, correctly: `GET /jobs/{id}/download-url` goes through the Data API with
the JWT, so **authorization does happen** — once, at mint time. What remains is
the ordinary bearer/expiry/no-download-audit properties (§2.10), which are
standard and bounded by a short TTL.

The real issue was elsewhere: the **cache** (§4.3).

---

## 6. Open questions

| # | Question | Notes |
|---|---|---|
| 6.1 | Where does `transfer_jobs` live? | Its own Postgres schema (clean separation, another datastore to run) or alongside the data in Db2 (one datastore, but this service then writes to the system of record). Matters more if the service is later merged into the DB API |
| 6.2 | Cache precision vs hit rate | `userId` in the fingerprint is obviously correct but means users never share entries. Hashing the authorization context instead is a one-field change |
| 6.3 | Db2 for z/OS driver entitlement | R2DBC if the existing `db2jcc` licence covers it, otherwise blocking JDBC on `boundedElastic`. **Architecturally neutral** — each export already owns a `boundedElastic` thread for its writer, so a blocking read adds no new cost. Confirm with IBM/DBA |
| 6.4 | Final placement | Standalone / merged into the Data API / merged into the DB API. The package layering (no Spring Web in `source`, `writer`, `upload`) keeps all three open; a hop-overhead benchmark decides |
| 6.5 | Per-relation cache policy | Should some relations be uncacheable regardless of fingerprint, e.g. anything with column masking? |

---

## 7. Process notes

Worth recording, because it shaped the outcome.

The design went through **five rounds of independent adversarial review**, which
found roughly 35 defects — several of which would have shipped as *data
corruption* rather than crashes: a truncated CSV presented as complete; a
healthy job double-run by two workers; a corrupt `.xlsx` with no central
directory; non-atomic commit across attached databases; a byte limit enforced
with a character function.

But review took the spec from ~730 lines to ~1540 while the subsequent design
discussion took it back to ~840.

**The reason is worth remembering.** Review agents answer *"is this correct?"*
extremely well and are structurally incapable of asking *"is this feature worth
its defects?"* — a reviewer's job is to find defects in what you wrote, never to
suggest deleting the feature. Every simplification in this design came from the
project owner asking **"why do we need this?"**:

- *"Why do we need another table?"* → staging deleted, import deferred
- *"Staging moves the problem elsewhere"* → the atomicity requirement examined and dropped
- *"Isn't disk what we're trying to avoid?"* → POI deleted
- *"Why did you pick SQLite?"* → six decisions of tax deleted
- *"What is this? What commit in the read path?"* → a genuine documentation defect

Run both loops. Use review for correctness; use the owner for scope.

---

## 8. Revision 8 — reactive lifecycle, identity, admission, and proof

Revision 7 moved to Spring Boot 4.1 + WebFlux + R2DBC. A sixth review round then
found that three of the *new* load-bearing claims did not hold. All were real.

### 8.1 The asynchronous credential problem

**Found in review; I had missed it entirely.** The spec said "the caller's JWT is
passed through unchanged." That is **impossible**: the worker runs minutes later
on a different pod, by which time an H2M token has expired. The job row held only
`requested_by`. There was no credential, no refresh, and no delegation.

**Resolution (D31).** Treat the token as an *authorization event*, not a
credential:

| Moment | Credential | What happens |
|---|---|---|
| Submit | caller's H2M token | validate; capture `issuer`, `subject`, `tenant`, `roles`, `authz_snapshot_hash` |
| Worker | the service's **M2M** credential | assert the captured subject on-behalf-of |

Two consequences worth remembering:

- **Identity key is `issuer + subject + tenant`**, never a bare user id —
  subjects are unique only within an issuer.
- **This makes OmniFlux able to assert any subject** to the Data API under its
  M2M identity. Standard for asynchronous job systems, and a real privilege
  concentration. It must be a deliberate platform decision. RFC 8693 token
  exchange is the documented alternative.

**And it fixed a cache defect for free (D32).** `userId` in the fingerprint stops
cross-user hits but not *stale entitlements* — if a user's roles change, cached
bytes bypass the new decision. `authz_snapshot_hash` invalidates on change by
construction, and as a side effect lets users with identical entitlements share
entries.

### 8.2 The back-pressure claim was quantitatively meaningless

Revision 7 claimed: *"a blocked `doOnNext` issues no further `request(n)`."*

True, and irrelevant. **`publishOn` inserts an async boundary whose default
prefetch is 256**, and it requests those 256 *before* the writer ever blocks:

```
200 columns x 64 KiB max-field-bytes = 12.5 MiB per row
x 256 prefetch                       = ~3.1 GiB queued
```

against a stated 32 MiB bound.

**The general lesson:** every `publishOn`, `flatMap`, `concatMap` and
`toIterable` carries a prefetch default. *"It's reactive so it's back-pressured"*
is not a memory bound — **the prefetch value is.** Reasoning at the operator
level while the buffer sits at the boundary is how this survived a full revision.

**And BlockHound cannot catch it.** BlockHound proves thread *placement*, not
demand correctness. That gap is why the **bounded-demand probe** exists: it
asserts maximum in-flight elements never exceeds the configured prefetch.

### 8.3 The bridge — `DataBufferUtils.outputStreamPublisher`

Raised by the project owner, and better than what the design had. Spring >= 6.1.
It does not replace `BufferedWriter` or `ZipOutputStream` — **the writers are
unchanged** — it replaces the bridge beneath them.

Its documented contract supplies three things the design was hand-building:

| Contract | Replaces |
|---|---|
| `write()` blocks when there is **no demand** | back-pressure emerging from the SDK's buffer |
| Cancellation makes `write()` **throw `IOException`** | a bespoke `abort()` reaching across threads to unblock a parked writer |
| Consumer exceptions **dispatch to the Subscriber** | the exact property `PipedOutputStream` lacked, which got it rejected in the first place |

It also makes byte production independently testable — subscribe and assert
without a bucket — which the proof requirement uses directly.

The prefetch knob **moves rather than disappears**: `toIterable()` defaults to
256 exactly as `publishOn` did, so it is set to `1` explicitly.

Two items deliberately left as spikes rather than asserted: unknown-length
`AsyncRequestBody.fromPublisher` under `multipartEnabled(true)` is unverified
(unlike `forBlockingOutputStream`, which is), and a non-pooled
`DefaultDataBufferFactory` is chosen to avoid `DataBuffer` release discipline.

### 8.4 Observability is the deliverable

> *"The actual project requirement is to prove end-to-end streaming without disk
> usage and little memory"* — and *"need observability to prove this."*

So the service is the experiment and the evidence is the product. But the first
draft of that section was a metrics platform, which drew the correct objection
that it was bloat. The proof is genuinely cheap — about 250 lines:

| Piece | Why it is irreducible |
|---|---|
| `/api/system/resources` | Heap **and RSS** — heap alone does not predict a cgroup OOM kill |
| Temp-dir watcher, sampling **during** the run | A file created and deleted mid-export passes an after-the-fact check. The claim is *never touched*, not *clean afterwards* |
| Scaling run, 10k to 1M rows | The actual proof. **A rising heap line falsifies the project** |
| Naive counter-example under `demo` | Highest value per line in the project — an audience that sees the obvious implementation die on the same input understands immediately |

Explicitly *not* specified: a custom metric taxonomy. Actuator publishes these
through Micrometer already; production observability can follow once the
property is established.

### 8.5 Corrections carried from review

| Claim | Reality |
|---|---|
| `count(*)` inside the claim bounds concurrency | **No.** `FOR UPDATE SKIP LOCKED` locks the *candidate row*, not the count predicate. Three pods each read `count = 3`, each pass `< 4`, each lock a different row, giving six active. Fixed by locking a singleton `admission_gate` row first, in the same transaction |
| `bodyToFlux(Object[].class)` | PostgREST returns an array of **objects**, not positional arrays — it would fail deserialization. Now `bodyToFlux(JsonNode)` plus descriptor mapping |
| `max-in-memory-size` is a "backstop" | It applies **per decoded element**, so it *is* the REST path's max row size. Set to `max-row-bytes`, not to a small number |
| A "size-capped response reader" equals the SQL guard | It does not — a cumulative page cap cannot enforce a per-field limit, and would reject a large response of entirely safe rows |
| Retry position is a policy knob | It is a **correctness constraint**. Retry below the write re-emits already-uploaded rows into the same object. Retry sits above the write, pre-first-row only |
| `octet_length` guards every column | Defined for character and binary strings only. Numeric, date and boolean columns need type-specific projections |
| `LIMIT 1000` bounds the driver | `r2dbc-postgresql` defaults `fetchSize` to **0, meaning unlimited**. Both `fetchSize` and `maxMessageSize` must be derived explicitly |
| Control store location is an open item | It could not be — it determines DDL, claim syntax, locking and migrations. **Closed: Postgres, every environment** |
| `source/` has no Spring Web dependency | `RestKeysetRowSource` requires `WebClient`. SPI split from `adapter/rest` and `adapter/r2dbc` |
| Bytes go around the Data API "in both directions" | Only the download does. **Source rows stream through it** — it is the governed access route |

### 8.6 Auth can be switched off, but not silently

> **Superseded in part by §9.5.** The environment-sniffing guard described below
> ("a storage endpoint that is not localhost/MinIO") was replaced by an explicit
> `security.allow-non-jwt-auth` opt-in. The reasoning it records is still the
> reasoning; only the mechanism changed. Kept because the rejected mechanism is
> the sort of thing that gets reinvented.

Authentication is not what this service proves, so `auth-mode: DISABLED` exists.
The hazard is not the flag but a flag that can be *silently* on in production, so
`StartupValidator` **refuses to boot** when `auth-mode != JWT` and a production
signal is present — a `prod` profile active, or a storage endpoint that is not
localhost/MinIO.

All three modes drive the same `CurrentUserProvider` seam, so the authorization
snapshot, job row and fingerprint are produced identically. The PoC exercises the
real path rather than a bypass, and enabling JWT later changes one bean.


---

# Part II — The implementation-planning phase

Six review rounds against the implementation plan, after nine against the spec.
The architecture went **unchallenged in all six**: every finding landed in
arithmetic, compile order, or test validity. That is the expected shape for a
plan approaching execution, and it is also why this part reads differently from
Part I — fewer arguments about what to build, many more about whether a stated
check can actually fail.

Detailed agent review working files are retained locally.

---

## 9. The resource budget, and three times it was wrong

The budget is the whole project. Every other decision is downstream of "a pod
running this must never die from resource pressure", so the arithmetic behind it
is load-bearing in a way that ordinary configuration is not. It was wrong three
times, in three different ways, and each way is worth remembering.

### 9.1 What each byte limit actually measures

Three different byte counts exist for the same row, and conflating them caused
two of the three errors. The definitions are now fixed:

| Count | What it is | Which limit uses it |
|---|---|---|
| **Source value bytes** | UTF-8 length of each value as the database holds it, summed across the row | `max-field-bytes` (64 KiB), `max-row-bytes` (4 MiB) |
| **Encoded output bytes** | after CSV quoting/doubling or XLSX XML escaping | `max-object-bytes` |
| **JSON wire bytes** | after property names, quotes and `\uXXXX` escaping | `source.rest.max-in-memory-size` |

This matters because the row guard fires in **three places** — pushed into SQL on
the R2DBC path, at decode on the REST path, and in the writer — and three places
measuring three different things is three different limits wearing one name.

### 9.2 Error one: the codec limit set equal to the row limit

`source.rest.max-in-memory-size` was set to `max-row-bytes`, on the reasoning
that the codec limit "is" the per-row bound because it applies per decoded
element. That reasoning is right about the *mechanism* and wrong about the
*units*: a row at exactly the source limit is **larger** than that limit as JSON,
because property names, quotes, commas and escapes all add bytes.

The consequence was not a crash. It was that **the largest legal row in the
database was unexportable through the primary adapter**, and it failed as a
Jackson decoder error rather than a typed limit — so it would have read to an
operator as a bug rather than a rule.

Fix: `max-in-memory-size = max-row-bytes × wire-envelope-factor + headroom`,
validated at startup rather than set independently.

### 9.3 Error two: an envelope factor that was not a bound

The first factor was **1.5**, chosen as a plausible average. It is not a bound:

| Input | JSON output | Ratio |
|---|---|---|
| `"` or `\` | `\"`, `\\` | 2× |
| C0 control character | `\u0007` | **6×** |
| 4-byte emoji, `\u`-escaped by the server | `\uD83D\uDE00` | **3×** |

A Data API that escapes non-ASCII — some do — turns a 4-byte character into 12.
So 8 MiB of legal source data could be 48 MiB on the wire.

Two decisions came out of this:

**Control characters other than tab, CR and LF are rejected**
(`limits.allowed-control-chars`) with `CHARACTER_NOT_REPRESENTABLE`. This is not
a new restriction — XLSX already could not represent them, and a raw `0x07` in a
CSV field is not data anyone consumes. Declaring it explicitly is what turns 3.0
into a **bound** rather than an optimistic average. Budgeting for 6× instead
would have cost 24 MiB of codec per job.

**A separate `wire-envelope-headroom: 1MB`**, because the factor covers value
escaping only. With the factor alone, a row of fully-escaped values consumes the
entire budget and the envelope itself — property names, punctuation, array
syntax, the internally-selected primary key — has nowhere to go.

### 9.4 Error three: budgeting a counter as a buffer — and the boot blocker

`export.max-page-bytes` was listed in the resource table as a *buffer* and summed
into the per-job total. It is a **cumulative counter**: it accumulates
decompressed bytes seen so far and aborts the scan past a ceiling. The bytes
themselves are released as they pass; nothing holds them.

Two consequences, both bad:

1. At 8 MiB it equalled `max-row-bytes`, so **a single legal maximum-width row
   tripped the page circuit breaker on its own**.
2. When it was raised to 64 MiB — correct for a counter — `StartupValidator`
   still summed it, computing 96.125 MiB per R2DBC job and a 448.5 MiB rollup
   against a 384 MiB heap. **The service would have refused to boot on its own
   shipped defaults**, and the failure would have looked like a genuine budget
   violation rather than a formula error.

This is the sharpest lesson in the file: *a guard and a buffer are not the same
kind of thing, and the budget only counts things that are simultaneously
resident.*

### 9.5 The numbers as they now stand

```
decodedRow = max-row-bytes x java-expansion-factor      = 4 x 2.5   = 10    MiB  (both adapters)
REST  = max-in-memory-size(13) + decodedRow(10) + bridge(0.125) + upload(16) + overhead(8) = 47.125
R2DBC = fetch-buffer-budget(8) + decodedRow(10) + bridge(0.125) + upload(16) + overhead(8) = 42.125
rollup = max(REST, R2DBC) x max-concurrent(4) + jvm-baseline(64)   = 252.5 MiB = 264,765,440 bytes
                                                                     252.5 < 384 (-Xmx)          OK
container = max-heap-budget(384) + non-heap-reserve(96)            = 480   MiB < 512             OK
```

`max-row-bytes` was **halved from 8 MiB to 4 MiB** in the process, because it
multiplies into *both* large REST terms (codec ×3.0 and decoded row ×2.5). At
8 MiB with the corrected factor the rollup was 336 MiB against a 384 MiB heap:
arithmetically valid, with no room for GC.

**The heap check is not the container check.** `-Xmx` bounds the Java heap; the
cgroup kills on RSS, which also carries direct buffers (the 16 MiB upload buffer
among them), thread stacks, metaspace and the code cache. Hence
`resources.non-heap-reserve` and a second, separate comparison — skipped, never
defaulted to zero, when no cgroup limit is readable.

### 9.6 A key deleted for having no consumer

`export.chunk-size` duplicated `source.rest.page-size` and
`source.r2dbc.page-size` while nothing read it. Page size is an adapter property
and the two adapters want different values — 5000 rows is one REST round trip;
1000 rows is a tighter R2DBC memory bound. A third key that nothing reads is a
key someone eventually tunes expecting an effect.

---

## 10. Identity: ownership and entitlement are different things

### 10.1 `PrincipalKey` must not contain roles

`PrincipalKey` originally carried `(issuer, subject, tenant, roles)`. A record's
`equals()` covers every component, so **granting someone a role changed their
ownership identity**: they stopped owning their own running jobs, and every
`GET /api/jobs/{id}` began returning 404.

Nothing would have logged. 404-for-a-foreign-job is the *designed* behaviour —
deliberately not 403, so the API does not confirm that a job exists.

Split into:

```java
record PrincipalKey(String issuer, String subject, String tenant)          // WHO OWNS IT
record AuthContext(PrincipalKey key, List<String> roles, String authzContextVersion)
                                                                          // WHAT THEY MAY SEE
```

Ownership is stable across entitlement changes; the cache fingerprint is not.
That is exactly the split the two concerns need.

### 10.2 `authzContextVersion` — where it comes from, and what is hashed

Per auth mode: `DISABLED` uses the configured constant
`security.authz-context-version` (the demo has no entitlement system, and
pretending otherwise would be a lie told by a config key); `HEADER` requires the
gateway's `X-Auth-Authz-Version`; `JWT` would use a token claim (v2). It is
captured at submit, persisted to `transfer_jobs.authz_context_version`, read back
by the worker, and **never recomputed**.

The fingerprint hashes the **version, not the role list**. Hashing both means a
cosmetic role reordering misses every cache entry; hashing only roles means an
entitlement change that does not alter the role *strings* — a changed row filter,
a revoked scope — silently serves stale bytes. Two tests hold the policy from
both sides.

### 10.3 The Data API receives the whole key, not just the subject

An early test asserted only `X-Asserted-Subject`. `dev@local` at issuer A and
`dev@local` at issuer B are different people, and a row filter evaluated on
subject alone would hand one person's data to the other — reintroducing precisely
the collision `PrincipalKey` exists to prevent. Issuer, subject, tenant, roles
and authz version all travel.

### 10.4 `auth-mode=JWT` refuses to boot in v1

There is no JWT provider. Silently accepting the mode would tell an operator
their tokens are validated when nothing validates them. Refusing to start is the
honest behaviour, and the message says so.

### 10.5 HEADER mode's boundary is a deployment property

A test once asserted that the gateway requirement was *documented*. Documentation
passes against every implementation, forged headers included — it is a comment
with an assertion around it.

The honest statement: **this service cannot distinguish a gateway-set `X-Auth-*`
header from a caller-set one, and no code in it can.** The protection is that the
gateway strips them inbound. The only thing the service can enforce is that an
operator explicitly accepted that arrangement — so the test is a **startup** test
(`allow-non-jwt-auth` must be set), not a request test.

This replaces §8.6's environment sniffing, which inspected the storage endpoint
for `localhost`/`minio`. That is not an environment signal: a production bucket
could be named `minio-prod` and pass silently.

### 10.6 Effective config is an allowlist, never a denylist

`/api/system/resources` publishes an "effective configuration" for the demo. It
was built as a redaction denylist, and the denylist omitted
`spring.flyway.password` — so that credential leaked while a test named
*"every secret is redacted"* passed.

A denylist fails silently every time a key is added. The endpoint now emits an
**allowlist** of the ~20 keys the demo displays: a new secret is invisible by
default rather than exposed by default. The endpoint is unauthenticated, which is
what makes the difference matter.

### 10.7 A CSP is enforcement; reading the HTML is inspection

Checking the served HTML for `<script src="https://…">` inspects exactly one of
the ways to reach a third party. `app.js` can `fetch()`, open a WebSocket, or
insert a `<script>` at runtime, and every one of those passes a source-text
check. `Content-Security-Policy: default-src 'self'` constrains the runtime
instead, and the test observes a `securitypolicyviolation` event against a host
that **resolves** — a request to `example.invalid` fails on DNS whether or not a
policy exists.

---

## 11. Adapters

### 11.1 PostgREST aggregates are not available, and the syntax was wrong

The high-water probe was written as `select=max(id)`. PostgREST's aggregate
syntax is `select=id.max()`, **and aggregates are disabled by default**
(`db-aggregates-enabled`), which the shipped compose stack does not turn on. The
probe would have 404'd against the very stand-in it was written for.

Replaced with an ordinary indexed read:

```
GET /{rel}?select={pk}&order={pk}.desc&limit=1     + the same filters
```

No server feature, uses the primary-key index, and an empty relation comes back
as `[]` — which maps cleanly to `OptionalLong.empty()` instead of needing a
null-versus-absent decision.

### 11.2 `collectList()` in the pagination contradicted the whole design

The REST pagination was written as `fetchPage(...).collectList()`. That buffers
an entire page — 5000 decoded rows — before the writer sees a byte. It
contradicted the incremental-decode test in the same task, contradicted the
spec's "one decoded element is retained", and added a whole decoded page to a
heap budget with no term for one.

Replaced with per-subscription state inside `Flux.defer`, appending the next page
via `concatWith(Flux.defer(...))` so page *n+1* is neither requested nor
subscribed until page *n* completes: exactly one HTTP response in flight, and the
stack does not grow with page count.

### 11.3 `after` is an `OptionalLong`, not a sentinel

`long after = 0` silently drops any row whose key is 0 or negative. Page 1 omits
the predicate entirely. The R2DBC dialect had already avoided this; the REST
adapter had reintroduced it.

### 11.4 SQL and its binds travel together

`SqlDialect` returns `SqlPlan(String sql, List<Object> binds)`. Returning a bare
string and expecting the caller to bind in the right order is how a filter value
ends up in the `LIMIT`. The tests assert the exact SQL **and** the ordered, typed
bind list — asserting "there is a `$1` and no `DROP TABLE`" passes three wrong
implementations: one that drops the filter, one that emits `$1` for something
else, and one that binds the wrong value.

### 11.5 The production path had no schema source

`PgMetadataProvider` queries `pg_attribute`. In production the relation lives in
**Db2, behind the Data API**, where there is no local catalog to query — so the
plan shipped no way for the *primary* adapter to learn a column's type or its
primary key. `DataApiMetadataProvider` reads the Data API's OpenAPI document, and
the provider is chosen **per adapter**, not by a global setting: a REST export
must not resolve its schema from the local Postgres, because the local demo
relation and the production relation can differ.

### 11.6 Driver fetch size is derived, not merely non-zero

`r2dbc-postgresql` defaults `fetchSize` to 0 = unlimited, and `LIMIT` bounds the
*result*, not the driver's fetching. `fetchSize = clamp(1, fetch-buffer-budget /
max-row-bytes, page-size)` = 2 at shipped defaults. A test asserting only
`fetchSize > 0` passes an arbitrary 1,000,000.

---

## 12. What the proof harness had to survive

The evidence is a deliverable, so the instruments needed the same scrutiny as the
code — and they had fewer people looking at them.

### 12.1 The instruments moved to Phase 0

`FsWatcher` and `ResourceProbe` are built in Task 1, not in the proof phase.
Task 8 asserts zero filesystem events long before any dashboard exists, and *an
instrument built after the experiment tends to be built to agree with it.*

### 12.2 Events, not sampling — and not only CREATE, and not only `tmpdir`

Three successively weaker versions of the disk check, each one caught:

- **A sampler misses a short-lived file.** Created and deleted between two
  samples, it leaves nothing to observe — which is exactly what a spooling
  library does.
- **CREATE alone misses a reused scratch file.** A library writing into one
  pre-existing file emits only MODIFY.
- **`java.io.tmpdir` alone misses everything else.** A library spooling to the
  working directory, to `user.home`, or to a path from its own configuration
  passes a tmpdir-only watch while writing to disk.

`WatchService` also drops events under pressure and reports `OVERFLOW`. Ignoring
it turns "zero events observed" into "zero events I still had buffer space to
notice" — a claim about the buffer. An overflow fails the run. And
`assertClean()` drains to quiescence first, because delivery is asynchronous and
a naive assertion can race past events still in flight.

### 12.3 What the disk claim actually says

**"Zero filesystem events observed across every writable mount, under a read-only
root filesystem with a 16 MiB tmpfs."** Materially stronger than sampling. Still
not a syscall-level proof that no write ever occurred. The README says exactly
this and not *"never touches disk"*.

### 12.4 `memory.peak` is cumulative, and `--rm` deletes the evidence

Two container-level facts that invalidated the harness as first written:

- **`memory.peak` is cumulative for the life of the cgroup**, and
  `resetPeakUsage()` only touches JVM memory pools. Resetting between
  repetitions inside one container leaves every later repetition reporting the
  *first* one's RSS high-water. Hence a fresh container — therefore a fresh
  cgroup — per **repetition**, not per cell.
- **`docker run --rm` deletes the container the instant it exits**, taking its
  cgroup with it. The driver has to read `memory.peak` and `memory.events`
  *before* removal, so the container is started without `--rm` and removed
  explicitly.

### 12.5 The results must not be written to disk by the thing proving it writes nothing

The harness originally bind-mounted a writable `/evidence` directory for its JSON
output. That contradicts the read-only claim the run exists to establish, and
`FsWatcher` — which watches every writable mount — would either count those
writes or have been told to ignore a directory, which is the same thing as not
measuring. Results go to **stdout**, captured by the host with `docker logs`.

### 12.6 The matrix is a stratified design, not a Cartesian product

The Cartesian version could not run at all. A wide row is 200 columns near
`max-row-bytes`; the smallest row-count cell is 10,000 rows; at the then-current
8 MiB that is **80 GiB**, already above `max-object-bytes` — so the cell had to
fail by design while the verification test demanded it complete.

Ten strata, each varying one axis against a cheap baseline for the others.
Reducing the wide-row cells from thousands to hundreds bought **S8: wide ×
concurrency 4** — the load-bearing worst case, four maximum-width rows in flight
at once, which the previous design did not contain at all because the concurrency
axis had only narrow rows.

### 12.7 Verification must come from outside the exporter

`row_count` is written **by the exporter**, so a writer that drops rows records
the number it believes it wrote. A SHA-256 the exporter computed over its own
output agrees with the downloaded bytes no matter what those bytes contain.

The object is now stream-parsed independently: data-row count, first key, last
key, **keys consecutive from one**, and a **rolling digest over every key** —
because count plus endpoints still passes an object that duplicates one middle
row and omits another. Stored `byte_count` is compared against the downloaded
length.

### 12.8 Flatness has to mean flat at every point

Comparing only the smallest and largest cells passes a defect that grows through
the middle and comes back: 10k and 10M can agree while 100k and 1M are double.
Every point is compared against the stratum's own median — and separately, the
max/min **spread** is bounded, because a ±15% band around a median permits a 35%
spread end to end.

Heap and RSS each get their own median. Comparing RSS against the *heap* median
compares two quantities that differ by the entire non-heap footprint.

### 12.9 The counter-example must fail for the right reason

Every assertion about the naive path was satisfiable by
`throw new OutOfMemoryError("Java heap space")`. Evidence now comes from outside
the process: `-XX:+HeapDumpOnOutOfMemoryError`, used heap at death above 90% of
the ceiling, and GC time fraction above 0.5 from `-Xlog:gc*` parsed by the
parent. A test also asserts the naive implementation materialises **every** row
before writing a byte, and that the naive and streaming paths share adapter,
writer factory and upload session — otherwise the comparison measures the wrong
difference.

The naive fork gets a writable mount for its heap dump. It is the
counter-example, not the subject: the zero-disk claim is about the streaming
path, and the naive path is being measured precisely because it is not that.

---

## 13. Testing decisions

### 13.1 The discipline

For every step labelled REGRESSION: write the test, implement the **broken**
version deliberately, **run it and watch it fail for the named reason**, then
implement the correct version. A regression test never observed failing against
the defect it names is a guess.

This exists because the plan once shipped a deadlock regression test whose own
fixture reproduced the deadlock — it failed identically against correct and
broken code, and would have been "fixed" by changing the implementation until the
test passed for an unrelated reason.

### 13.2 Composition over inheritance for test fixtures

At the time this decision was recorded, Java single inheritance affected a
test needing Postgres *and* MinIO. The current fixture uses SeaweedFS, but the
composition rationale is unchanged. Converting both bases to interfaces was
tried and rejected: it breaks every earlier `extends`, and
`@DynamicPropertySource` requires a **static** method, which is not inherited
from an interface.

Instead a `Containers` holder owns the containers and exposes static
`registerPostgres`/`registerObjectStore`. The bases are thin conveniences over
it, and a test needing both extends one and adds its own `@DynamicPropertySource`
— Spring collects those from the whole hierarchy, subclass included. Nothing
earlier changes.

### 13.3 Cross-pod claims need cross-process tests

"Admission is global across pods" means it lives in the database and nowhere
else. Two repository objects in one JVM does not test that — a
`static Semaphore(4)` passes. Two Spring contexts in one JVM does not either:
they share a classloader, so they share every static.

Only **separate processes** distinguish "the gate is in Postgres" from "the gate
is in this JVM". Same reasoning applies to the queue-depth cap and idempotency-key
uniqueness, which a `synchronized` block would otherwise satisfy. A compose-level
stratum (S10) runs two replicas against one database.

**Not asserted:** that both forks claimed something. A correct database gate may
legitimately let the first fork take all four before the second issues a
statement, so asserting participation would reject correct code. The invariant is
four, distinct, once.

### 13.4 Crashes are `halt()`, not `exit()`

In-process exception injection does not reproduce a pod eviction: `finally`
blocks run, shutdown hooks run, the connection closes cleanly and the lease is
released — none of which happens to a killed pod. `FailPoint.reach()` calls
`Runtime.halt(137)`. A shutdown hook that aborts the upload is exactly the code
path a real kill skips.

### 13.5 A live lease means hands off, even when we know the pod is dead

Two fault tests contradicted each other: one asserted a crashed pod's multipart
upload is aborted while its lease is still live, another asserted a live
sibling's upload is untouched. Both cannot hold — **the reconciler cannot
distinguish "crashed two seconds ago" from "busy"**. An expired lease is the only
evidence it has, and it is the only thing that authorises cleanup. Not age, not a
missing pod, not a failed health check.

### 13.6 Browser testing without npm

Reference A promises no npm and no build step, and an air-gapped namespace makes
a CDN a blank page. `org.htmlunit:htmlunit` is pure Java from Maven Central with
no browser download. The price is a real constraint: `app.js` must be a **classic
script, not an ES module**, because HtmlUnit does not implement module loading.
Its `fetch` polyfill is off by default and must be enabled explicitly, or the
tests fail on a missing global rather than on any defect.

The tests **drive** the page — Cancel actually transitions a job to `CANCELLED`
in the database — because asserting `html.contains("id=btn-cancel")` passes an
inert button, and a demo whose Cancel button does nothing is discovered during
the walkthrough.

---

## 14. Deployment

### 14.1 The manifests are deliberately non-runnable

Task 24 produces OpenShift manifests, and its tests assert they **will not boot**.

v1 has no JWT provider, so `StartupValidator` refuses `auth-mode=JWT`. The two
implemented modes require `allow-non-jwt-auth: true`, which no production
manifest may set. Therefore these manifests cannot start as shipped — **by
design**, and that is better than shipping a manifest that starts an
unauthenticated data-export service on a cluster.

Making them runnable requires one decision the plan cannot make for an operator:
implement JWT, or accept HEADER mode behind a gateway that provably strips
`X-Auth-*` — at which point the flag becomes a considered choice with a
NetworkPolicy and mTLS behind it, rather than something copied from the demo.

### 14.2 No `Route`; a `NetworkPolicy` instead

Reference A says the Data API is the only exposed entry point. A `Route`
publishes this service directly — and in HEADER mode, directly means
header-forgeable by anyone who can reach the hostname.

### 14.3 Guaranteed QoS needs CPU too

Equal, non-zero requests and limits for **both** cpu and memory, on **every**
container including init containers. Memory alone yields Burstable — and a
Burstable pod is an eviction candidate, which is the failure this entire design
exists to avoid, arriving by a different door.

### 14.4 Liveness must outlast a lease

A liveness probe that trips while a pod is merely *busy* restarts a healthy
worker mid-export, orphaning a multipart upload every time it fires. Liveness is
generous, readiness is strict.

And a 60-second termination grace period is only a manifest field: if the
application ignores SIGTERM it is force-killed at second 60 regardless, and every
rolling deploy leaves an orphan. A runtime test sends SIGTERM mid-export and
asserts the process drains, aborts its upload, and requeues the job.

### 14.5 Flyway needs its own URL inside the container

`application.yml` defaults Flyway to `localhost:5435`, which is the **host** port
mapping. From inside the app container that address is the app container.
`docker-compose.yml` sets `SPRING_FLYWAY_URL` explicitly. Flyway is JDBC and
startup-only; nothing on the request path uses it.

### 14.6 `container_name` blocked the two-replica check

A fixed container name makes `--scale app=2` fail outright, and two replicas
against one database is how cross-pod admission is proven at the compose level.
Removed from the `app` service only; `docker-compose.scale.yml` additionally
drops the port publish, since two replicas cannot both publish 8080.

---

## 15. Process notes from this phase

### 15.1 The one question that kept finding defects

> Name any test that would PASS while the defect it names is present.

It found some in **every round**, six rounds running. The catalogue is in
local review notes. This is the single most transferable thing to come out
of the review series, and it should be asked of every test written during
implementation, not just of the plan.

### 15.2 Byte arithmetic was wrong three times

The field-size calculation, the envelope factor, and the heap-gap test. Three
separate rounds, three separate errors, all in arithmetic that the guarantee
rests on. During implementation the numbers in Task 3, Task 5 and Task 11 should
be recomputed against real data rather than trusted.

### 15.3 A change summary is a claim, not evidence

In round 5 the reviewer found six fixes claimed in the round-5 prompt that had
never been applied — the summary had been written from *intended* edits rather
than from the document. The prompts and the patch scripts are both archived in
local review notes so the discrepancy stays visible. Where they disagree, the patch
script is what happened.

The mechanical fix is cheap and was adopted: re-grep each claim against the file
before asserting it.

### 15.4 What review cannot do — restated, because it held for six more rounds

Review answers *"is this correct?"*. It is structurally incapable of asking
*"is this feature worth its defects?"* Every simplification in this project came
from the user asking why something was needed. Nine rounds against the spec and
six against the plan never once proposed removing a feature.


---

## 16. Round 6 — two limits that could not be given a working value

The final review round produced one decision worth more than the fixes around
it: **two configuration keys were wrong in a way that re-tuning could not fix.**

### 16.1 `export.max-page-bytes` was deleted, not re-tuned

Three values were tried before the shape of the mistake became visible:

| Value | Treated as | What happened |
|---|---|---|
| 8 MiB | a buffer, summed into the budget | equalled `max-row-bytes`, so a single legal maximum-width row tripped the page breaker on its own |
| 64 MiB | a buffer, still summed | 96.125 MiB per R2DBC job, a 448.5 MiB rollup — **the validator refused to boot on the shipped defaults** |
| 64 MiB | a counter, correctly excluded from the sum | REST page size is 5,000 rows; sixteen 4 MiB rows already exceed it, so the S5 wide cells could never complete |

No value works. The key sits between two guards that already bound the same
quantity from both ends — `max-row-bytes` bounds each row, `max-object-bytes`
bounds the export cumulatively — and for a *streaming* decoder the page is never
resident, so a per-page cumulative ceiling protects nothing the other two do not.
Any ceiling high enough to admit a legal page of the widest rows is too high to
catch a runaway.

Deleting a limit is not usually the safe direction. It was here, because the
limit was never the thing doing the protecting. The general lesson: **a guard
that cannot be given a value that is simultaneously correct at both ends is
usually a guard in the wrong place.**

### 16.2 The wire bound is the format's worst case, not a server's habit

`wire-envelope-factor` went 1.5 → 3.0 → **6.0**. The first two were derived from
what a JSON writer *typically* emits — 2× for a quote, 3× for an escaped
surrogate pair. Both were assumptions about a server this service does not own.

**RFC 8259 §7 permits any character to be written as `\uXXXX`.** A conforming
Data API may emit one ASCII byte as six and remain conforming. So the bound is 6,
and the budget is now sized to it: 4 MiB × 6.0 + 1 MiB headroom = 25 MiB codec,
lifting the rollup to 300.5 MiB — still inside the 384 MiB heap.

This is affordable *only* because `max-row-bytes` had already been halved to
4 MiB (§9.5). At 8 MiB the same reasoning would demand a 49 MiB codec limit and a
rollup near 500 MiB, and the honest response would have been to lower
`max-concurrent` instead.

**A consequence worth stating.** With the factor at 6.0, the control-character
restriction is no longer needed for the *budget* — it survives because XLSX
cannot represent those characters and a raw `0x07` in a CSV field is not
consumable data. The spec now says which reason applies, because a rule kept for
a reason that has expired is a rule nobody can reason about later.

### 16.3 One genuine blocker, and the difference that matters

Round 6 was asked to separate what **blocks starting** from what can be fixed
during execution. It found exactly one blocker: `MinioTestBase` still constructed
its own `MinIOContainer` while `Containers` also declared one — two MinIO
instances in one JVM, disagreeing about which bucket holds what.

Everything else was sequenced: fix it before the task that needs it. That
distinction is the useful output of a review at this stage, and it is worth
asking for explicitly rather than accepting an undifferentiated list.

### 16.4 The JVM writes to disk before your code does

`-XX:-UsePerfData` is now set in the proof containers. The JVM writes
`/tmp/hsperfdata_<user>/<pid>` at startup and updates it as it runs — real
filesystem events on a watched mount, which would fail the zero-event assertion
for a bookkeeping artifact that has nothing to do with whether the exporter
touches disk.

Disabling it is more honest than adding a path exclusion. An exclusion list is
the same thing as not measuring, and it grows.

## 17. The spike module was consolidated into the main build — 2026-09-19

**Decision.** One Maven project. The spike module's experiment tests are now
maintained vendor-contract tests (`com.omniflux.exchange.contract`), its
benchmark harness lives in the main build's test sources
(`com.omniflux.exchange.proof`), and its demo controller was reinstated in
main sources under `@Profile("demo")`. `spike/pom.xml` is deleted.

**Rationale (durable).**

- One dependency graph: the spike pinned Spring/Reactor/JUnit versions
  independently of the Boot parent, so its correctness tests certified a
  stack nobody ships — the same failure the project once caught and recorded
  in `docs/evidence/spike-results.md` ("a spike on adjacent versions is
  evidence about a stack nobody ships"). Consolidation makes drift
  structurally impossible; the contract tests now run on every
  `./mvnw verify` against the resolved production stack.
- The harness needed no isolation: it imports only JDK classes and runs as a
  separately launched `main()` client (`proof/run-matrix.sh`), so test
  sources give it a home without giving it a release.
- The demo endpoint is an explicit, stated exception: packaged but inactive
  without the `demo` profile, like `SeedController`/`SeedService`/
  `DataApiConfig`, with route-registered/security-chain verification.

**Considered and rejected:** keeping a permanent `spike/` module
(independence buys nothing for in-process correctness tests and hides
version drift); a Failsafe phase (the single Surefire suite already carries
compose-bound tests; a second phase changes conventions, not behavior);
JUnit-tagged performance tests (the harness is not a JUnit test and the
performance layer is judged from recorded baselines, not per-commit gates).

**Provenance.** Cross-model review (an external model proposed the
consolidation and reviewed the draft design); every claim verified against
the repository before adoption. Provenance is recorded, not relied on —
the evidence is the repository and the verification runs.

## 18. Implementation corrections — 2026-10-03

This section is appended so earlier design decisions remain historically
readable. For current behavior, the implementation and [application configuration](../../src/main/resources/application.yml)
are authoritative.

- **CSV implementation:** the shipped implementation uses the project's
  `Rfc4180Encoder` and `CsvRowWriter`; there is no Apache Commons CSV
  dependency. Older comparison tables mentioning Commons CSV describe an
  earlier proposal, not the current implementation.
- **Identity headers:** HEADER mode consumes the configured `X-Auth-*` prefix
  (including asserted identity/authorization context fields). It does not use
  the old `X-Auth-User` name. The gateway must strip inbound identity headers
  and set trusted values.
- **Page memory setting:** `export.max-page-bytes` is not a current
  configuration property. REST rows are decoded incrementally and R2DBC row
  values are guarded; the memory validator uses the configured fetch budget,
  upload buffers, concurrency, and measured overhead. See application.yml for
  live values.
- **REST adapter name:** the current class is `RestRowSource`; references to
  `RestKeysetRowSource` in historical review/planning text are stale.
- **Local storage:** Compose and Testcontainers use SeaweedFS 4.44 as the local
  S3-compatible fixture, pinned by digest. Compose names the service
  `seaweedfs`; local credentials use generic S3 names in `.env`. Historical
  MinIO spike results describe the earlier experiment, not the current stack.
- **Shutdown:** worker shutdown drains for `omniflux.queue.drain-timeout`
  (30 seconds by default; validated maximum 40 seconds). Work that remains
  after the drain deadline is requeued and can restart from the beginning.

These corrections supersede conflicting details in earlier decision entries;
they do not erase the reasons those decisions were made at the time.
