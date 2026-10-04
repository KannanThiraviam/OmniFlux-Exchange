package com.omniflux.exchange.job;

import com.omniflux.exchange.config.OmnifluxProperties;
import com.omniflux.exchange.security.PrincipalKey;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.ReactiveTransactionManager;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Database-backed job state machine.
 *
 * <p>Admission and claim deliberately share one transaction and lock the
 * singleton {@code admission_gate} row before reading either count. The local
 * process therefore owns no concurrency truth that can drift from a sibling
 * pod.</p>
 */
@Repository
public class JobRepository {
    private static final String NEW_JOBS_STATUS_ERROR = "new jobs must be QUEUED or COMPLETED";
    private static final String WORKER_ID_BLANK_ERROR = "workerId must not be blank";
    private static final String GATE_MISMATCH_ERROR = "admission_gate.max_concurrent=%d disagrees with omniflux.queue.max-concurrent=%d";
    private static final Pattern ATTEMPT_KEY = Pattern.compile(
            "(?:^|/)([0-9a-fA-F]{8}-[0-9a-fA-F-]{27,})/a(\\d+)(?:/c([0-9a-fA-F-]{36}))?/data\\.[^/]+$");

    // Column and parameter names shared between the SQL text and the bind/row
    // mapping calls. Keeping them in constants keeps each .bind(...) and
    // row.get(...) call typo-safe against the SQL it mirrors.
    private static final String COL_CLAIM_TOKEN = "claim_token";
    private static final String COL_WORKER_ID = "worker_id";
    private static final String COL_OBJECT_KEY = "object_key";
    private static final String COL_ROW_COUNT = "row_count";
    private static final String COL_BYTE_COUNT = "byte_count";
    private static final String COL_MAX_CONCURRENT = "max_concurrent";
    private static final String JOB_ID_REQUIRED = "jobId";
    private static final String COL_CONTENT_SHA256 = "content_sha256";
    private static final String COL_HIGH_WATER_KEY = "high_water_key";
    private static final String COL_TIMING_JSON = "timing_json";
    private static final String COL_ERROR_CLASS = "error_class";
    private static final String COL_ERROR_CODE = "error_code";
    private static final String COL_ERROR_MESSAGE = "error_message";
    private static final String COL_ISSUER = "issuer";
    private static final String COL_SUBJECT = "subject";
    private static final String COL_TENANT = "tenant";
    private static final String COL_CLIENT_IP = "client_ip";
    private static final String COL_DIRECTION = "direction";
    private static final String COL_IDEMPOTENCY_KEY = "idempotency_key";
    private static final String COL_STATUS = "status";
    private static final String COL_FINISHED_AT = "finished_at";
    private static final String COL_COUNT = "count";
    private static final String COL_JOB_ID = "job_id";
    private static final String OWNER_REQUIRED = "owner";

    /** Claims the oldest queued job while holding the admission-gate lock. */
    private static final String CLAIM_NEXT_JOB_SQL = """
            UPDATE transfer_jobs
               SET status = 'IN_PROGRESS',
                   worker_id = :worker_id,
                   claim_token = :claim_token,
                   lease_until = now() + (:lease_seconds * interval '1 second'),
                   attempt_count = attempt_count + 1,
                   row_count = 0,
                   byte_count = 0,
                   timing_json = NULL,
                   last_key = NULL,
                   cancel_requested = FALSE,
                   finished_at = NULL
             WHERE id = (
                   SELECT id FROM transfer_jobs
                    WHERE status = 'QUEUED'
                    ORDER BY created_at, id
                    FOR UPDATE SKIP LOCKED
                    LIMIT 1)
               AND status = 'QUEUED'
            RETURNING *
            """;

    /** Owner-scoped requeue of a terminal attempt; clears the previous result. */
    private static final String RETRY_OWNED_SQL = """
        UPDATE transfer_jobs
           SET status = 'QUEUED',
               cancel_requested = FALSE, object_key = NULL, row_count = 0,
               byte_count = 0, content_sha256 = NULL, last_key = NULL,
               timing_json = NULL,
               high_water_key = NULL, worker_id = NULL, claim_token = NULL,
               lease_until = NULL, error_class = NULL, error_code = NULL,
               error_message = NULL, finished_at = NULL
         WHERE id = :id AND issuer = :issuer AND requested_by = :subject
           AND tenant = :tenant AND status IN ('FAILED', 'CANCELLED')
        RETURNING *
        """;

    private final DatabaseClient database;
    private final TransactionalOperator transactions;
    private final OmnifluxProperties properties;
    private final Clock clock;

    @Autowired
    public JobRepository(DatabaseClient database, ReactiveTransactionManager transactionManager,
                         OmnifluxProperties properties) {
        this(database, TransactionalOperator.create(transactionManager), properties, Clock.systemUTC());
    }

    public JobRepository(DatabaseClient database, TransactionalOperator transactions,
                         OmnifluxProperties properties) {
        this(database, transactions, properties, Clock.systemUTC());
    }

    public JobRepository(DatabaseClient database, TransactionalOperator transactions,
                         OmnifluxProperties properties, Clock clock) {
        this.database = Objects.requireNonNull(database, "database");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Enqueues a job, replays an identical idempotency request, or rejects it. */
    public Mono<TransferJob> submit(TransferJob candidate) {
        Objects.requireNonNull(candidate, "candidate");
        if (candidate.status() != JobStatus.QUEUED && candidate.status() != JobStatus.COMPLETED) {
            return Mono.error(new IllegalArgumentException(NEW_JOBS_STATUS_ERROR));
        }
        TransferJob normalized = candidate.toBuilder()
                .id(candidate.id() == null ? UUID.randomUUID() : candidate.id())
                .createdAt(candidate.createdAt() == null ? clock.instant() : candidate.createdAt())
                .build();

        Mono<TransferJob> operation = lockAdmissionGate()
                .then(existingIdempotency(normalized))
                .flatMap(existing -> existing
                        .map(found -> replayOrConflict(found, normalized))
                        .orElseGet(() -> admitAndInsert(normalized)));
        return transactions.transactional(operation);
    }

    /**
     * Claims one queued job if the database gate has spare capacity. Empty means
     * there is currently no admissible work and is not an error.
     */
    public Mono<TransferJob> claim(String workerId) {
        if (workerId == null || workerId.isBlank()) {
            return Mono.error(new IllegalArgumentException(WORKER_ID_BLANK_ERROR));
        }
        UUID token = UUID.randomUUID();
        long leaseSeconds = Math.max(1L, properties.queue().leaseDuration().toSeconds());
        Mono<TransferJob> operation = lockAdmissionGate()
                .then(database.sql("SELECT max_concurrent FROM admission_gate WHERE id = 1")
                        .map((row, metadata) -> Objects.requireNonNull(
                                row.get(COL_MAX_CONCURRENT, Integer.class), COL_MAX_CONCURRENT))
                        .one())
                .flatMap(databaseLimit -> inProgressCount().flatMap(active -> {
                    int configuredLimit = properties.queue().maxConcurrent();
                    if (databaseLimit != configuredLimit) {
                        return Mono.error(new IllegalStateException(
                                String.format(GATE_MISMATCH_ERROR, databaseLimit, configuredLimit)));
                    }
                    if (active >= databaseLimit) {
                        return Mono.empty();
                    }
                    return database.sql(CLAIM_NEXT_JOB_SQL)
                            .bind(COL_WORKER_ID, workerId)
                            .bind(COL_CLAIM_TOKEN, token)
                            .bind("lease_seconds", leaseSeconds)
                            .map(JobRepository::mapJob)
                            .one();
                }))
                .flatMap(job -> insertAttempt(job).thenReturn(job));
        return transactions.transactional(operation);
    }

    public Mono<Boolean> renewLease(UUID jobId, UUID claimToken, Duration leaseDuration) {
        Objects.requireNonNull(jobId, JOB_ID_REQUIRED);
        Objects.requireNonNull(claimToken, "claimToken");
        Objects.requireNonNull(leaseDuration, "leaseDuration");
        long seconds = Math.max(1L, leaseDuration.toSeconds());
        return database.sql("""
                UPDATE transfer_jobs
                   SET lease_until = now() + (:lease_seconds * interval '1 second')
                 WHERE id = :id
                   AND claim_token = :claim_token
                   AND status = 'IN_PROGRESS'
                   AND cancel_requested = FALSE
                   AND lease_until >= now()
                """)
                .bind("id", jobId)
                .bind(COL_CLAIM_TOKEN, claimToken)
                .bind("lease_seconds", seconds)
                .fetch().rowsUpdated().map(rows -> rows == 1);
    }

    /**
     * Live rows-so-far from the worker, mid-attempt. Fenced exactly like
     * renewLease: a flush from a worker that lost its lease must never land on
     * another pod's job. Completion overwrites this with the final count.
     */
    public Mono<Boolean> updateProgress(UUID jobId, UUID claimToken, long rowsSoFar) {
        Objects.requireNonNull(jobId, JOB_ID_REQUIRED);
        Objects.requireNonNull(claimToken, "claimToken");
        return database.sql("""
                UPDATE transfer_jobs
                   SET row_count = :row_count
                 WHERE id = :id
                   AND claim_token = :claim_token
                   AND status = 'IN_PROGRESS'
                """)
                .bind("id", jobId)
                .bind(COL_CLAIM_TOKEN, claimToken)
                .bind(COL_ROW_COUNT, Math.max(0, rowsSoFar))
                .fetch().rowsUpdated().map(rows -> rows == 1);
    }

    /** The row count is supplied by the scan, never copied from byte_count. */
    public Mono<Boolean> markCompleted(UUID jobId, UUID claimToken, String objectKey,
                                       long rowCount, long byteCount, String contentSha256,
                                       Long highWaterKey) {
        DatabaseClient.GenericExecuteSpec spec = database.sql("""
                UPDATE transfer_jobs
                   SET status = 'COMPLETED', object_key = :object_key,
                       row_count = :row_count, byte_count = :byte_count,
                       content_sha256 = :content_sha256, generated_at = now(),
                       high_water_key = :high_water_key,
                       finished_at = now(), worker_id = NULL, claim_token = NULL,
                       lease_until = NULL, cancel_requested = FALSE,
                       error_class = NULL, error_code = NULL, error_message = NULL
                 WHERE id = :id AND claim_token = :claim_token AND status = 'IN_PROGRESS'
                   AND cancel_requested = FALSE
                   AND lease_until >= clock_timestamp()
                """)
                .bind("id", jobId)
                .bind(COL_CLAIM_TOKEN, claimToken)
                .bind(COL_OBJECT_KEY, requireText(objectKey))
                .bind(COL_ROW_COUNT, nonNegative(rowCount, "rowCount"))
                .bind(COL_BYTE_COUNT, nonNegative(byteCount, "byteCount"));
        spec = bindNullable(spec, COL_CONTENT_SHA256, contentSha256, String.class);
        spec = bindNullable(spec, COL_HIGH_WATER_KEY, highWaterKey, Long.class);
        Mono<Boolean> operation = spec.fetch().rowsUpdated()
                .flatMap(rows -> rows == 1
                        ? closeAttempt(jobId, claimToken, "COMPLETED", null,
                                new AttemptOutcome(objectKey, rowCount, byteCount,
                                        contentSha256)).thenReturn(true)
                        : Mono.just(false));
        // Acquire the row lock before evaluating the wall-clock expiry guard.
        // An UPDATE can otherwise qualify a row, wait on its lock, and publish
        // after the lease expires without reevaluating an unchanged row.
        Mono<Boolean> locked = database.sql("SELECT id FROM transfer_jobs WHERE id = :id FOR UPDATE")
                .bind("id", jobId).fetch().one().then(operation);
        return transactions.transactional(locked);
    }

    public Mono<Boolean> markCompleted(UUID jobId, UUID claimToken, JobResult result) {
        Objects.requireNonNull(result, "result");
        return markCompleted(jobId, claimToken, result.objectKey(), result.rowCount(),
                result.byteCount(), result.contentSha256(), result.highWaterKey());
    }

    /** Persists attempt timings before the final fence clears the claim token. */
    public Mono<Boolean> recordTiming(UUID jobId, UUID claimToken, String timingJson) {
        DatabaseClient.GenericExecuteSpec spec = database.sql("""
                UPDATE transfer_jobs SET timing_json = :timing_json
                 WHERE id = :id AND claim_token = :claim_token AND status = 'IN_PROGRESS'
                """)
                .bind("id", jobId).bind(COL_CLAIM_TOKEN, claimToken);
        spec = bindNullable(spec, COL_TIMING_JSON, timingJson, String.class);
        Mono<Boolean> operation = spec.fetch().rowsUpdated()
                .flatMap(rows -> rows == 1
                        ? updateAttemptTiming(jobId, claimToken, timingJson).thenReturn(true)
                        : Mono.just(false));
        return transactions.transactional(operation);
    }

    /** Fenced worker failure transition; transient failures requeue until attempts are exhausted. */
    public Mono<Boolean> markFailed(UUID jobId, UUID claimToken, ErrorCode code, String message) {
        Objects.requireNonNull(code, "code");
        String safeMessage = message == null ? code.name() : message;
        int maxAttempts = Math.max(1, properties.queue().maxAttempts());
        DatabaseClient.GenericExecuteSpec spec = database.sql("""
                UPDATE transfer_jobs
                   SET status = CASE
                         WHEN cancel_requested THEN 'CANCELLED'
                         WHEN :error_class = 'TRANSIENT' AND attempt_count < :max_attempts
                           THEN 'QUEUED' ELSE 'FAILED' END,
                       error_class = CASE WHEN cancel_requested THEN 'DETERMINISTIC'
                                          ELSE :error_class END,
                       error_code = CASE WHEN cancel_requested THEN 'CANCELLED'
                                        ELSE :error_code END,
                       error_message = CASE WHEN cancel_requested THEN 'cancelled by owner'
                                            ELSE :error_message END,
                       finished_at = CASE
                         WHEN cancel_requested THEN now()
                         WHEN :error_class = 'TRANSIENT' AND attempt_count < :max_attempts
                           THEN NULL ELSE now() END,
                       worker_id = NULL, claim_token = NULL, lease_until = NULL,
                       cancel_requested = FALSE
                 WHERE id = :id AND claim_token = :claim_token AND status = 'IN_PROGRESS'
                """)
                .bind(COL_ERROR_CLASS, code.errorClass().name())
                .bind(COL_ERROR_CODE, code.name())
                .bind(COL_ERROR_MESSAGE, safeMessage)
                .bind("max_attempts", maxAttempts)
                .bind("id", jobId)
                .bind(COL_CLAIM_TOKEN, claimToken);
        Mono<Boolean> operation = spec.fetch().rowsUpdated()
                .flatMap(rows -> rows == 1
                        // The job row now owns the authoritative error fields.
                        // Passing null makes the attempt copy the cancellation
                        // override when a request raced with the failure.
                        ? closeAttempt(jobId, claimToken, null, null,
                                null).thenReturn(true)
                        : Mono.just(false));
        return transactions.transactional(operation);
    }

    public Mono<Boolean> markCancelled(UUID jobId, UUID claimToken) {
        Mono<Boolean> operation = database.sql("""
                UPDATE transfer_jobs
                   SET status = 'CANCELLED', finished_at = now(),
                       worker_id = NULL, claim_token = NULL, lease_until = NULL,
                       cancel_requested = FALSE, error_class = 'DETERMINISTIC',
                       error_code = 'CANCELLED', error_message = 'cancelled by owner'
                 WHERE id = :id AND claim_token = :claim_token AND status = 'IN_PROGRESS'
                   AND cancel_requested = TRUE
                """)
                .bind("id", jobId).bind(COL_CLAIM_TOKEN, claimToken)
                .fetch().rowsUpdated()
                .flatMap(rows -> rows == 1
                        ? closeAttempt(jobId, claimToken, "CANCELLED",
                        "cancelled by owner", null).thenReturn(true)
                        : Mono.just(false));
        return transactions.transactional(operation);
    }

    /** Requeues a draining worker's current claim without using an attempt budget slot. */
    public Mono<Boolean> requeueForShutdown(UUID jobId, UUID claimToken) {
        Mono<Boolean> operation = database.sql("""
                UPDATE transfer_jobs
                   SET status = 'QUEUED', attempt_count = GREATEST(attempt_count - 1, 0),
                       finished_at = NULL, worker_id = NULL, claim_token = NULL, lease_until = NULL,
                       cancel_requested = FALSE, error_class = NULL, error_code = NULL, error_message = NULL
                 WHERE id = :id AND claim_token = :claim_token AND status = 'IN_PROGRESS'
                   AND cancel_requested = FALSE
                """)
                .bind("id", jobId).bind(COL_CLAIM_TOKEN, claimToken)
                .fetch().rowsUpdated()
                .flatMap(rows -> rows == 1
                        ? closeAttempt(jobId, claimToken, "REQUEUED", "worker shutdown drain timeout", null)
                                .thenReturn(true)
                        : Mono.just(false));
        return transactions.transactional(operation);
    }

    /** Queued cancellation is owner-scoped and does not use a claim token. */
    public Mono<Boolean> cancelQueued(UUID jobId, PrincipalKey owner) {
        return ownerUpdate(jobId, owner, """
                UPDATE transfer_jobs SET status = 'CANCELLED', finished_at = now()
                 WHERE id = :id AND status = 'QUEUED'
                   AND issuer = :issuer AND requested_by = :subject AND tenant = :tenant
                """);
    }

    /** In-progress cancellation is an owner-scoped request observed by the worker. */
    public Mono<Boolean> requestCancellation(UUID jobId, PrincipalKey owner) {
        return ownerUpdate(jobId, owner, """
                UPDATE transfer_jobs SET cancel_requested = TRUE
                 WHERE id = :id AND status = 'IN_PROGRESS'
                   AND issuer = :issuer AND requested_by = :subject AND tenant = :tenant
                """);
    }

    public Mono<Boolean> cancel(UUID jobId, PrincipalKey owner) {
        return cancelQueued(jobId, owner)
                .flatMap(cancelled -> Boolean.TRUE.equals(cancelled)
                        ? Mono.just(cancelled)
                        : requestCancellation(jobId, owner));
    }

    /** Owner-scoped presign issuance; a completed job has no claim token. */
    public Mono<TransferJob> recordPresign(UUID jobId, PrincipalKey owner, String clientIp) {
        Objects.requireNonNull(owner, OWNER_REQUIRED);
        DatabaseClient.GenericExecuteSpec spec = database.sql("""
                UPDATE transfer_jobs
                   SET presign_count = presign_count + 1,
                       last_presign_at = now(), last_presign_ip = :client_ip
                 WHERE id = :id AND status = 'COMPLETED' AND object_key IS NOT NULL
                   AND issuer = :issuer AND requested_by = :subject AND tenant = :tenant
                RETURNING *
                """)
                .bind("id", jobId)
                .bind(COL_ISSUER, owner.issuer())
                .bind(COL_SUBJECT, owner.subject())
                .bind(COL_TENANT, owner.tenant());
        spec = bindNullable(spec, COL_CLIENT_IP, clientIp, String.class);
        return spec.map(JobRepository::mapJob)
                .one()
                .switchIfEmpty(Mono.error(new ExportException(ErrorCode.JOB_NOT_FOUND,
                        "owned completed job not found: " + jobId)));
    }

    public Mono<TransferJob> find(UUID jobId) {
        return database.sql("SELECT * FROM transfer_jobs WHERE id = :id")
                .bind("id", jobId).map(JobRepository::mapJob).one();
    }

    public Mono<TransferJob> findOwned(UUID jobId, PrincipalKey owner) {
        return database.sql("""
                SELECT * FROM transfer_jobs
                 WHERE id = :id AND issuer = :issuer AND requested_by = :subject AND tenant = :tenant
                """)
                .bind("id", jobId).bind(COL_ISSUER, owner.issuer())
                .bind(COL_SUBJECT, owner.subject()).bind(COL_TENANT, owner.tenant())
                .map(JobRepository::mapJob).one()
                .switchIfEmpty(Mono.error(new ExportException(ErrorCode.JOB_NOT_FOUND,
                        "owned job not found: " + jobId)));
    }

    /** Finds only origin rows; cache-hit rows can never extend an object's TTL. */
    public Mono<Optional<TransferJob>> findCacheOrigin(String fingerprint,
                                                                  Instant generatedAfter) {
        return database.sql("""
                SELECT * FROM transfer_jobs
                 WHERE request_fingerprint = :fingerprint
                   AND status = 'COMPLETED' AND cache_hit_of IS NULL
                   AND generated_at > :generated_after AND object_key IS NOT NULL
                 ORDER BY generated_at DESC, id DESC LIMIT 1
                """)
                .bind("fingerprint", fingerprint)
                .bind("generated_after", generatedAfter)
                .map(JobRepository::mapJob).one()
                .map(Optional::of)
                .defaultIfEmpty(Optional.empty());
    }

    /** Inserts the audit row for a cache hit while preserving the origin bytes. */
    public Mono<TransferJob> insertCacheHit(TransferJob request, TransferJob origin) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(origin, "origin");
        TransferJob hit = request.toBuilder()
                .status(JobStatus.COMPLETED).objectKey(origin.objectKey())
                .rowCount(origin.rowCount()).byteCount(origin.byteCount())
                .contentSha256(origin.contentSha256()).highWaterKey(origin.highWaterKey())
                .generatedAt(origin.generatedAt()).cacheHitOf(origin.id())
                .finishedAt(clock.instant()).build();
        return insert(hit);
    }

    /** Owner-scoped keyset page for the HTTP job list. */
    public Flux<TransferJob> listOwned(PrincipalKey owner, Instant afterCreatedAt,
                                       UUID afterId, int limit) {
        if (limit < 1) return Flux.error(new IllegalArgumentException("limit must be positive"));
        String sql = "SELECT * FROM transfer_jobs WHERE direction = 'EXPORT' "
                + "AND issuer = :issuer AND requested_by = :subject AND tenant = :tenant";
        if (afterCreatedAt != null || afterId != null) {
            if (afterCreatedAt == null || afterId == null) {
                return Flux.error(new IllegalArgumentException("cursor must contain both position fields"));
            }
            sql += " AND (created_at, id) < (:after_created_at, :after_id)";
        }
        sql += " ORDER BY created_at DESC, id DESC LIMIT :limit";
        DatabaseClient.GenericExecuteSpec query = database.sql(sql)
                .bind(COL_ISSUER, owner.issuer()).bind(COL_SUBJECT, owner.subject())
                .bind(COL_TENANT, owner.tenant()).bind("limit", limit);
        if (afterCreatedAt != null) {
            query = query.bind("after_created_at", afterCreatedAt).bind("after_id", afterId);
        }
        return query.map(JobRepository::mapJob).all();
    }

    /** Requeues a terminal owned attempt and clears its previous result. */
    public Mono<TransferJob> retryOwned(UUID jobId, PrincipalKey owner) {
        Mono<TransferJob> operation = lockAdmissionGate().then(queuedCount().flatMap(depth -> {
            if (depth >= properties.queue().maxDepth()) {
                return Mono.error(new ExportException(ErrorCode.QUEUE_FULL,
                        "queued job depth has reached " + properties.queue().maxDepth()));
            }
            return database.sql(RETRY_OWNED_SQL)
                .bind("id", jobId).bind(COL_ISSUER, owner.issuer())
                .bind(COL_SUBJECT, owner.subject()).bind(COL_TENANT, owner.tenant())
                .map(JobRepository::mapJob).one();
        }));
        return transactions.transactional(operation);
    }

    /** Requeues expired attempts, but terminally fails attempts at the limit. */
    public Mono<Long> sweepExpired() {
        Mono<Long> operation = database.sql("""
                UPDATE transfer_jobs
                   SET status = CASE WHEN cancel_requested THEN 'CANCELLED'
                                     WHEN attempt_count >= :max_attempts THEN 'FAILED'
                                     ELSE 'QUEUED' END,
                       error_class = CASE WHEN cancel_requested THEN 'DETERMINISTIC' ELSE 'TRANSIENT' END,
                       error_code = CASE WHEN cancel_requested THEN 'CANCELLED' ELSE 'LEASE_LOST' END,
                       error_message = CASE WHEN cancel_requested THEN 'cancelled by owner'
                                            ELSE 'lease expired' END,
                       finished_at = CASE WHEN cancel_requested OR attempt_count >= :max_attempts
                                           THEN now() ELSE NULL END,
                       worker_id = NULL, claim_token = NULL, lease_until = NULL,
                       object_key = NULL, row_count = 0, byte_count = 0,
                       content_sha256 = NULL, timing_json = NULL,
                       cancel_requested = FALSE
                 WHERE status = 'IN_PROGRESS' AND lease_until < now()
                """)
                .bind("max_attempts", Math.max(1, properties.queue().maxAttempts()))
                .fetch().rowsUpdated()
                .flatMap(rows -> closeSweptAttempts().thenReturn(rows));
        return transactions.transactional(operation);
    }

    /** Owner-scoped durable attempt history for the operator and user views. */
    public Flux<JobAttempt> listAttempts(UUID jobId, PrincipalKey owner) {
        Objects.requireNonNull(jobId, JOB_ID_REQUIRED);
        Objects.requireNonNull(owner, OWNER_REQUIRED);
        return database.sql("""
                SELECT a.*
                  FROM transfer_job_attempts a
                  JOIN transfer_jobs j ON j.id = a.job_id
                 WHERE a.job_id = :id AND j.issuer = :issuer
                   AND j.requested_by = :subject AND j.tenant = :tenant
                 ORDER BY a.attempt_no DESC
                """)
                .bind("id", jobId).bind(COL_ISSUER, owner.issuer())
                .bind(COL_SUBJECT, owner.subject()).bind(COL_TENANT, owner.tenant())
                .map(JobRepository::mapAttempt).all();
    }

    /** Determines whether an attempt object is abandoned or still published. */
    public Mono<Boolean> isExpiredAttemptObject(String objectKey) {
        if (objectKey == null) return Mono.just(false);
        Matcher matcher = ATTEMPT_KEY.matcher(objectKey);
        if (!matcher.find()) return Mono.just(false);
        UUID jobId;
        int attempt;
        try {
            jobId = UUID.fromString(matcher.group(1));
            attempt = Integer.parseInt(matcher.group(2));
        } catch (IllegalArgumentException _) {
            return Mono.just(false);
        }
        // New keys identify a claim independently of the retry budget. Shutdown
        // can restore that budget without making old uploads belong to a new claim.
        if (matcher.group(3) != null) {
            UUID storageClaim;
            try {
                storageClaim = UUID.fromString(matcher.group(3));
            } catch (IllegalArgumentException _) {
                return Mono.just(false);
            }
            return database.sql("""
                    SELECT EXISTS (
                        SELECT 1 FROM transfer_jobs
                         WHERE id = :id AND object_key IS DISTINCT FROM :object_key
                           AND (claim_token IS DISTINCT FROM :storage_claim
                                OR status <> 'IN_PROGRESS' OR lease_until < now())
                    ) AS expired
                    """)
                    .bind("id", jobId).bind("storage_claim", storageClaim).bind(COL_OBJECT_KEY, objectKey)
                    .map((row, metadata) -> Boolean.TRUE.equals(row.get("expired", Boolean.class)))
                    .one().defaultIfEmpty(false);
        }
        // Retain support for objects written before claim-specific keys.
        return database.sql("""
                SELECT EXISTS (
                    SELECT 1 FROM transfer_jobs
                     WHERE id = :id
                       AND object_key IS DISTINCT FROM :object_key
                       AND (
                            attempt_count > :attempt
                            OR status <> 'IN_PROGRESS'
                            OR (attempt_count = :attempt AND status = 'IN_PROGRESS'
                                AND lease_until < now())
                       )
                ) AS expired
                """)
                .bind("id", jobId).bind("attempt", attempt).bind(COL_OBJECT_KEY, objectKey)
                .map((row, metadata) -> Boolean.TRUE.equals(row.get("expired", Boolean.class)))
                .one().defaultIfEmpty(false);
    }

    public Mono<Boolean> isObjectReferenced(String objectKey) {
        return database.sql("SELECT EXISTS (SELECT 1 FROM transfer_jobs "
                        + "WHERE object_key = :key AND status = 'COMPLETED') AS referenced")
                .bind("key", objectKey)
                .map((row, metadata) -> Boolean.TRUE.equals(row.get("referenced", Boolean.class)))
                .one().defaultIfEmpty(false);
    }

    public Flux<String> referencedObjectKeys(String prefix) {
        DatabaseClient.GenericExecuteSpec query = database.sql(
                "SELECT object_key FROM transfer_jobs WHERE object_key IS NOT NULL AND object_key LIKE :prefix")
                .bind("prefix", (prefix == null ? "" : prefix) + "%");
        return query.map((row, metadata) -> row.get(COL_OBJECT_KEY, String.class))
                .all().filter(Objects::nonNull);
    }

    private Mono<TransferJob> admitAndInsert(TransferJob candidate) {
        if (candidate.status() == JobStatus.COMPLETED) {
            return insert(candidate);
        }
        return queuedCount().flatMap(depth -> {
            if (depth >= properties.queue().maxDepth()) {
                return Mono.error(new ExportException(ErrorCode.QUEUE_FULL,
                        "queued job depth has reached " + properties.queue().maxDepth()));
            }
            return insert(candidate);
        });
    }

    private Mono<TransferJob> replayOrConflict(TransferJob existing, TransferJob candidate) {
        if (Objects.equals(existing.requestFingerprint(), candidate.requestFingerprint())) {
            return Mono.just(existing);
        }
        return Mono.error(new ExportException(ErrorCode.IDEMPOTENCY_KEY_CONFLICT,
                "idempotency key already belongs to a different request"));
    }

    private Mono<Optional<TransferJob>> existingIdempotency(TransferJob candidate) {
        if (candidate.idempotencyKey() == null || candidate.idempotencyKey().isBlank()) {
            return Mono.just(Optional.empty());
        }
        return database.sql("""
                SELECT * FROM transfer_jobs
                 WHERE direction = :direction AND issuer = :issuer
                   AND tenant = :tenant AND requested_by = :subject
                   AND idempotency_key = :idempotency_key
                """)
                .bind(COL_DIRECTION, candidate.direction())
                .bind(COL_ISSUER, candidate.owner().issuer())
                .bind(COL_TENANT, candidate.owner().tenant())
                .bind(COL_SUBJECT, candidate.owner().subject())
                .bind(COL_IDEMPOTENCY_KEY, candidate.idempotencyKey())
                .map(JobRepository::mapJob).one()
                .map(Optional::of)
                .defaultIfEmpty(Optional.empty());
    }

    private Mono<TransferJob> insert(TransferJob job) {
        DatabaseClient.GenericExecuteSpec spec = database.sql("""
                INSERT INTO transfer_jobs (
                    id, direction, status, requested_by, issuer, tenant, roles,
                    authz_context_version, client_ip, idempotency_key, relation_name,
                    columns_json, filters_json, format, csv_mode, csv_dialect_version,
                     object_key, row_count, byte_count, content_sha256, last_key,
                     timing_json,
                    high_water_key, attempt_count, worker_id, claim_token, lease_until,
                    cancel_requested, request_fingerprint, generated_at, cache_hit_of,
                    presign_count, last_presign_at, last_presign_ip, error_class,
                    error_code, error_message, created_at, finished_at
                ) VALUES (
                    :id, :direction, :status, :requested_by, :issuer, :tenant, :roles,
                    :authz_context_version, :client_ip, :idempotency_key, :relation_name,
                    :columns_json, :filters_json, :format, :csv_mode, :csv_dialect_version,
                     :object_key, :row_count, :byte_count, :content_sha256, :last_key,
                     :timing_json,
                    :high_water_key, :attempt_count, :worker_id, :claim_token, :lease_until,
                    :cancel_requested, :request_fingerprint, :generated_at, :cache_hit_of,
                    :presign_count, :last_presign_at, :last_presign_ip, :error_class,
                    :error_code, :error_message, :created_at, :finished_at
                ) RETURNING *
                """);
        spec = spec.bind("id", job.id()).bind(COL_DIRECTION, job.direction())
                .bind(COL_STATUS, job.status().name()).bind("requested_by", job.owner().subject())
                .bind(COL_ISSUER, job.owner().issuer()).bind(COL_TENANT, job.owner().tenant())
                .bind("roles", job.roles().toArray(String[]::new))
                .bind("cancel_requested", job.cancelRequested())
                .bind(COL_ROW_COUNT, job.rowCount()).bind(COL_BYTE_COUNT, job.byteCount())
                .bind("attempt_count", job.attemptCount()).bind("presign_count", job.presignCount())
                .bind("created_at", job.createdAt() == null ? clock.instant() : job.createdAt());
        spec = bindNullable(spec, "authz_context_version", job.authzContextVersion(), String.class);
        spec = bindNullable(spec, COL_CLIENT_IP, job.clientIp(), String.class);
        spec = bindNullable(spec, COL_IDEMPOTENCY_KEY, job.idempotencyKey(), String.class);
        spec = bindNullable(spec, "relation_name", job.relationName(), String.class);
        spec = bindNullable(spec, "columns_json", job.columnsJson(), String.class);
        spec = bindNullable(spec, "filters_json", job.filtersJson(), String.class);
        spec = bindNullable(spec, "format", job.format(), String.class);
        spec = bindNullable(spec, "csv_mode", job.csvMode(), String.class);
        spec = bindNullable(spec, "csv_dialect_version", job.csvDialectVersion(), Integer.class);
        spec = bindNullable(spec, COL_OBJECT_KEY, job.objectKey(), String.class);
        spec = bindNullable(spec, COL_CONTENT_SHA256, job.contentSha256(), String.class);
        spec = bindNullable(spec, COL_TIMING_JSON, job.timingJson(), String.class);
        spec = bindNullable(spec, "last_key", job.lastKey(), Long.class);
        spec = bindNullable(spec, COL_HIGH_WATER_KEY, job.highWaterKey(), Long.class);
        spec = bindNullable(spec, COL_WORKER_ID, job.workerId(), String.class);
        spec = bindNullable(spec, COL_CLAIM_TOKEN, job.claimToken(), UUID.class);
        spec = bindNullable(spec, "lease_until", job.leaseUntil(), Instant.class);
        spec = bindNullable(spec, "request_fingerprint", job.requestFingerprint(), String.class);
        spec = bindNullable(spec, "generated_at", job.generatedAt(), Instant.class);
        spec = bindNullable(spec, "cache_hit_of", job.cacheHitOf(), UUID.class);
        spec = bindNullable(spec, "last_presign_at", job.lastPresignAt(), Instant.class);
        spec = bindNullable(spec, "last_presign_ip", job.lastPresignIp(), String.class);
        spec = bindNullable(spec, COL_ERROR_CLASS,
                job.errorClass() == null ? null : job.errorClass().name(), String.class);
        spec = bindNullable(spec, COL_ERROR_CODE,
                job.errorCode() == null ? null : job.errorCode().name(), String.class);
        spec = bindNullable(spec, COL_ERROR_MESSAGE, job.errorMessage(), String.class);
        spec = bindNullable(spec, COL_FINISHED_AT, job.finishedAt(), Instant.class);
        return spec.map(JobRepository::mapJob).one();
    }

    private Mono<Void> lockAdmissionGate() {
        return database.sql("SELECT id FROM admission_gate WHERE id = 1 FOR UPDATE")
                .map((row, metadata) -> row.get("id", Integer.class))
                .one()
                .flatMap(id -> id == 1
                        ? Mono.just(Boolean.TRUE)
                        : Mono.error(new IllegalStateException("admission_gate row id=1 is missing")))
                .switchIfEmpty(Mono.error(new IllegalStateException("admission_gate row id=1 is missing")))
                .then();
    }

    private Mono<Long> queuedCount() {
        return database.sql("SELECT count(*) AS count FROM transfer_jobs WHERE status = 'QUEUED'")
                .map((row, metadata) -> Objects.requireNonNull(row.get(COL_COUNT, Long.class), COL_COUNT))
                .one().defaultIfEmpty(0L);
    }

    private Mono<Long> inProgressCount() {
        return database.sql("SELECT count(*) AS count FROM transfer_jobs WHERE status = 'IN_PROGRESS'")
                .map((row, metadata) -> Objects.requireNonNull(row.get(COL_COUNT, Long.class), COL_COUNT))
                .one().defaultIfEmpty(0L);
    }

    /** A short-window database snapshot for the operator UI and health view. */
    public Mono<JobMetricsSnapshot> metrics() {
        return database.sql("""
                SELECT
                  count(*) FILTER (WHERE status = 'IN_PROGRESS') AS active,
                  count(*) FILTER (WHERE status = 'QUEUED') AS queued,
                  coalesce(sum(row_count) FILTER (
                    WHERE status = 'COMPLETED'
                      AND finished_at >= now() - interval '5 minutes'), 0) AS recent_rows,
                  count(*) FILTER (
                    WHERE status = 'COMPLETED'
                      AND finished_at >= now() - interval '5 minutes') AS recent_completed,
                  count(*) FILTER (
                    WHERE status = 'COMPLETED' AND cache_hit_of IS NOT NULL
                      AND finished_at >= now() - interval '5 minutes') AS recent_cache_hits
                FROM transfer_jobs
                WHERE direction = 'EXPORT'
                """)
                .map((row, metadata) -> {
                    long active = number(row.get("active"));
                    long queued = number(row.get("queued"));
                    long rows = number(row.get("recent_rows"));
                    long completed = number(row.get("recent_completed"));
                    long cacheHits = number(row.get("recent_cache_hits"));
                    return new JobMetricsSnapshot(active, queued, active,
                            rows / 300.0, completed == 0 ? 0.0 : (double) cacheHits / completed);
                })
                .one()
                .defaultIfEmpty(new JobMetricsSnapshot(0, 0, 0, 0.0, 0.0));
    }

    /**
     * Readiness-only consistency probe for the cluster-wide admission gate.
     * Database errors are allowed to propagate so the health contributor can
     * report an unknown probe result without affecting the liveness group.
     */
    public Mono<Boolean> admissionGateMatchesConfiguration() {
        return database.sql("SELECT max_concurrent FROM admission_gate WHERE id = 1")
                .map((row, metadata) -> Objects.requireNonNull(
                        row.get(COL_MAX_CONCURRENT, Integer.class), COL_MAX_CONCURRENT))
                .one()
                .map(databaseLimit -> databaseLimit == properties.queue().maxConcurrent())
                .defaultIfEmpty(false);
    }

    public record JobMetricsSnapshot(long active, long queued, long globalActive,
                                     double rowsPerSec, double cacheHitRate) { }

    private Mono<Boolean> ownerUpdate(UUID jobId, PrincipalKey owner, String sql) {
        Objects.requireNonNull(owner, OWNER_REQUIRED);
        return database.sql(sql).bind("id", jobId).bind(COL_ISSUER, owner.issuer())
                .bind(COL_SUBJECT, owner.subject()).bind(COL_TENANT, owner.tenant())
                .fetch().rowsUpdated().map(rows -> rows == 1);
    }

    private Mono<Void> insertAttempt(TransferJob job) {
        return database.sql("""
                INSERT INTO transfer_job_attempts
                    (job_id, attempt_no, claim_token, worker_id, status, started_at)
                SELECT :job_id, COALESCE(MAX(attempt_no), 0) + 1, :claim_token, :worker_id,
                       'IN_PROGRESS', now()
                  FROM transfer_job_attempts WHERE job_id = :job_id
                """)
                .bind(COL_JOB_ID, job.id())
                .bind(COL_CLAIM_TOKEN, job.claimToken()).bind(COL_WORKER_ID, job.workerId())
                .fetch().rowsUpdated().then();
    }

    private Mono<Void> updateAttemptTiming(UUID jobId, UUID claimToken, String timingJson) {
        DatabaseClient.GenericExecuteSpec spec = database.sql("""
                UPDATE transfer_job_attempts
                   SET timing_json = :timing_json
                 WHERE job_id = :job_id AND claim_token = :claim_token
                   AND finished_at IS NULL
                """)
                .bind(COL_JOB_ID, jobId).bind(COL_CLAIM_TOKEN, claimToken);
        spec = bindNullable(spec, COL_TIMING_JSON, timingJson, String.class);
        return spec.fetch().rowsUpdated().then();
    }

    private Mono<Void> closeAttempt(UUID jobId, UUID claimToken, String statusOverride,
                                    String errorMessage, AttemptOutcome outcome) {
        long rowCount = outcome == null ? 0 : outcome.rowCount();
        long byteCount = outcome == null ? 0 : outcome.byteCount();
        DatabaseClient.GenericExecuteSpec spec = database.sql("""
                UPDATE transfer_job_attempts a
                   SET status = CASE
                         WHEN :status_override IS NOT NULL THEN :status_override
                         WHEN j.status = 'QUEUED' THEN 'REQUEUED'
                         ELSE j.status END,
                       finished_at = now(),
                       object_key = CASE WHEN :has_result THEN :object_key ELSE j.object_key END,
                       row_count = CASE WHEN :has_result THEN :row_count ELSE j.row_count END,
                       byte_count = CASE WHEN :has_result THEN :byte_count ELSE j.byte_count END,
                       content_sha256 = CASE WHEN :has_result THEN :content_sha256 ELSE j.content_sha256 END,
                       error_code = j.error_code,
                       error_message = COALESCE(:error_message, j.error_message)
                  FROM transfer_jobs j
                 WHERE a.job_id = j.id AND a.job_id = :job_id
                   AND a.claim_token = :claim_token AND a.finished_at IS NULL
                """)
                .bind(COL_JOB_ID, jobId).bind(COL_CLAIM_TOKEN, claimToken)
                .bind("has_result", "COMPLETED".equals(statusOverride))
                .bind(COL_ROW_COUNT, Math.max(0, rowCount)).bind(COL_BYTE_COUNT, Math.max(0, byteCount));
        spec = bindNullable(spec, "status_override", statusOverride, String.class);
        spec = bindNullable(spec, COL_OBJECT_KEY, outcome == null ? null : outcome.objectKey(), String.class);
        spec = bindNullable(spec, COL_CONTENT_SHA256,
                outcome == null ? null : outcome.contentSha256(), String.class);
        spec = bindNullable(spec, COL_ERROR_MESSAGE, errorMessage, String.class);
        return spec.fetch().rowsUpdated().then();
    }

    /** Result fields copied onto the attempt row; null closes without a result. */
    private record AttemptOutcome(String objectKey, long rowCount, long byteCount,
                                  String contentSha256) { }

    private Mono<Void> closeSweptAttempts() {
        return database.sql("""
                UPDATE transfer_job_attempts a
                   SET status = CASE WHEN j.status = 'CANCELLED' THEN 'CANCELLED'
                                     WHEN j.status = 'FAILED' THEN 'FAILED'
                                     ELSE 'LEASE_LOST' END,
                       finished_at = now(),
                       error_code = CASE WHEN j.status = 'CANCELLED' THEN 'CANCELLED'
                                         ELSE 'LEASE_LOST' END,
                       error_message = CASE WHEN j.status = 'CANCELLED'
                                            THEN 'cancelled by owner' ELSE 'lease expired' END
                  FROM transfer_jobs j
                 WHERE a.job_id = j.id AND a.finished_at IS NULL
                   AND j.claim_token IS NULL AND j.status IN ('QUEUED', 'FAILED', 'CANCELLED')
                   AND a.id = (SELECT latest.id FROM transfer_job_attempts latest
                                WHERE latest.job_id = j.id ORDER BY latest.attempt_no DESC LIMIT 1)
                """).fetch().rowsUpdated().then();
    }

    private static DatabaseClient.GenericExecuteSpec bindNullable(
            DatabaseClient.GenericExecuteSpec spec, String name, Object value, Class<?> type) {
        return value == null ? spec.bindNull(name, type) : spec.bind(name, value);
    }

    private static TransferJob mapJob(io.r2dbc.spi.Row row, io.r2dbc.spi.RowMetadata metadata) {
        String issuer = row.get(COL_ISSUER, String.class);
        String subject = row.get("requested_by", String.class);
        String tenant = row.get(COL_TENANT, String.class);
        List<String> roles = toStringList(row.get("roles"));
        return new TransferJob(
                row.get("id", UUID.class),
                row.get(COL_DIRECTION, String.class),
                JobStatus.parse(row.get(COL_STATUS, String.class)),
                new PrincipalKey(issuer, subject, tenant),
                roles,
                row.get("authz_context_version", String.class),
                row.get(COL_CLIENT_IP, String.class),
                row.get(COL_IDEMPOTENCY_KEY, String.class),
                row.get("relation_name", String.class),
                row.get("columns_json", String.class),
                row.get("filters_json", String.class),
                row.get("format", String.class),
                row.get("csv_mode", String.class),
                row.get("csv_dialect_version", Integer.class),
                row.get(COL_OBJECT_KEY, String.class),
                number(row.get(COL_ROW_COUNT)), number(row.get(COL_BYTE_COUNT)),
                 row.get(COL_CONTENT_SHA256, String.class),
                 row.get(COL_TIMING_JSON, String.class),
                 nullableLong(row.get("last_key")), nullableLong(row.get(COL_HIGH_WATER_KEY)),
                (int) number(row.get("attempt_count")), row.get(COL_WORKER_ID, String.class),
                row.get(COL_CLAIM_TOKEN, UUID.class), instant(row.get("lease_until")),
                Boolean.TRUE.equals(row.get("cancel_requested", Boolean.class)),
                row.get("request_fingerprint", String.class), instant(row.get("generated_at")),
                row.get("cache_hit_of", UUID.class), (int) number(row.get("presign_count")),
                instant(row.get("last_presign_at")), row.get("last_presign_ip", String.class),
                enumValue(ErrorClass.class, row.get(COL_ERROR_CLASS, String.class)),
                enumValue(ErrorCode.class, row.get(COL_ERROR_CODE, String.class)),
                row.get(COL_ERROR_MESSAGE, String.class), instant(row.get("created_at")),
                instant(row.get(COL_FINISHED_AT)));
    }

    private static JobAttempt mapAttempt(io.r2dbc.spi.Row row, io.r2dbc.spi.RowMetadata metadata) {
        return new JobAttempt(
                (int) number(row.get("attempt_no")), row.get(COL_CLAIM_TOKEN, UUID.class),
                row.get(COL_WORKER_ID, String.class), row.get(COL_STATUS, String.class),
                instant(row.get("started_at")), instant(row.get(COL_FINISHED_AT)),
                row.get(COL_OBJECT_KEY, String.class), number(row.get(COL_ROW_COUNT)),
                number(row.get(COL_BYTE_COUNT)), row.get(COL_CONTENT_SHA256, String.class),
                row.get(COL_TIMING_JSON, String.class), row.get(COL_ERROR_CODE, String.class),
                row.get(COL_ERROR_MESSAGE, String.class));
    }

    /** The roles column arrives as a Postgres text array or a driver collection. */
    private static List<String> toStringList(Object roleValue) {
        if (roleValue instanceof String[] values) return List.of(values);
        if (roleValue instanceof Collection<?> values) {
            return values.stream().map(String::valueOf).toList();
        }
        return List.of();
    }

    private static long number(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private static Long nullableLong(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }

    private static Instant instant(Object value) {
        if (value instanceof Instant instant) return instant;
        if (value instanceof java.time.OffsetDateTime valueAsOffset) return valueAsOffset.toInstant();
        if (value instanceof java.time.LocalDateTime valueAsLocal)
            return valueAsLocal.toInstant(java.time.ZoneOffset.UTC);
        return null;
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, String value) {
        if (value == null) return null;
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException _) {
            // Rows created by older releases may contain values that are no
            // longer part of the Java enum. A stale diagnostic must not make
            // the entire jobs list unreadable; the original text remains in
            // the database for migration/audit inspection.
            return null;
        }
    }

    private static String requireText(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("objectKey" + " must not be blank");
        return value;
    }

    private static long nonNegative(long value, String name) {
        if (value < 0) throw new IllegalArgumentException(name + " must not be negative");
        return value;
    }
}
