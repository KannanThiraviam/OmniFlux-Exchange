package com.omniflux.exchange.job;

import com.omniflux.exchange.PostgresTestBase;
import com.omniflux.exchange.security.PrincipalKey;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.core.env.Environment;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Runs the failure/cancellation race against PostgreSQL rather than asserting
 * only the shape of the SQL string.
 */
@SpringBootTest
class JobRepositoryCancellationRaceTest extends PostgresTestBase {
    private static final PrincipalKey OWNER = new PrincipalKey("race-test", "alice", "tenant-race");

    @Autowired
    private JobRepository repository;

    @Autowired
    private DatabaseClient database;

    @Autowired
    private JobQueuePoller poller;

    @Autowired
    private ExpiredLeaseSweeper sweeper;

    @Autowired
    private JobProgressFlusher progressFlusher;

    @Autowired
    private Environment environment;

    private UUID jobId;

    @Test
    void applicationReadyStartsPollingAfterFlywayInitializedTheSchema() {
        assertTrue(poller.running());
        assertTrue(sweeper.running());
        assertTrue(progressFlusher.running());
        assertEquals("graceful", environment.getProperty("server.shutdown"));
        assertEquals(java.time.Duration.ofSeconds(45), environment.getProperty(
                "spring.lifecycle.timeout-per-shutdown-phase", java.time.Duration.class));
    }

    @AfterEach
    void cleanup() {
        if (jobId == null) return;
        database.sql("DELETE FROM transfer_job_attempts WHERE job_id = :id")
                .bind("id", jobId).fetch().rowsUpdated().block();
        database.sql("DELETE FROM transfer_jobs WHERE id = :id")
                .bind("id", jobId).fetch().rowsUpdated().block();
    }

    @Test
    void persistedCancellationWinsOverATransientFailureAndClosesAttemptAsCancelled() {
        jobId = UUID.randomUUID();
        UUID claimToken = UUID.randomUUID();
        database.sql("""
                INSERT INTO transfer_jobs
                    (id, direction, status, requested_by, issuer, tenant, roles, relation_name,
                     format, attempt_count, claim_token, worker_id, lease_until,
                     cancel_requested, created_at)
                VALUES (:id, 'EXPORT', 'IN_PROGRESS', :requested_by, :issuer, :tenant,
                        ARRAY['ANALYST']::text[], 'mock_orders', 'CSV', 1,
                        :claim_token, 'race-worker', now() + interval '1 minute',
                        TRUE, :created_at)
                """)
                .bind("id", jobId)
                .bind("requested_by", OWNER.subject())
                .bind("issuer", OWNER.issuer())
                .bind("tenant", OWNER.tenant())
                .bind("claim_token", claimToken)
                .bind("created_at", Instant.now())
                .fetch().rowsUpdated().block();
        database.sql("""
                INSERT INTO transfer_job_attempts
                    (job_id, attempt_no, claim_token, worker_id, status, started_at)
                VALUES (:job_id, 1, :claim_token, 'race-worker', 'IN_PROGRESS', now())
                """)
                .bind("job_id", jobId)
                .bind("claim_token", claimToken)
                .fetch().rowsUpdated().block();

        assertTrue(repository.markFailed(jobId, claimToken, ErrorCode.QUERY_TIMEOUT,
                "transient timeout").block());

        TransferJob job = repository.find(jobId).block();
        assertEquals(JobStatus.CANCELLED, job.status());
        assertEquals(ErrorClass.DETERMINISTIC, job.errorClass());
        assertEquals(ErrorCode.CANCELLED, job.errorCode());
        assertEquals("cancelled by owner", job.errorMessage());
        assertFalse(job.cancelRequested());

        JobAttempt attempt = repository.listAttempts(jobId, OWNER).blockFirst();
        assertEquals("CANCELLED", attempt.status());
        assertEquals("CANCELLED", attempt.errorCode());
        assertEquals("cancelled by owner", attempt.errorMessage());
        assertNotNull(attempt.finishedAt());
    }

    @Test
    void completionRejectsExpiredClaimsAndReconciliationProtectsPublishedObjects() {
        UUID claim = insertClaim();
        String key = repository.find(jobId).block().attemptObjectKey("exports/", "csv");
        String legacyKey = "exports/" + jobId + "/a1/data.csv";
        assertFalse(repository.isExpiredAttemptObject(key).block());
        assertFalse(repository.isExpiredAttemptObject(legacyKey).block());
        database.sql("UPDATE transfer_jobs SET lease_until = now() - interval '1 second' WHERE id = :id")
                .bind("id", jobId).fetch().rowsUpdated().block();
        assertFalse(repository.markCompleted(jobId, claim, key, 1, 10, "sha", 1L).block());
        assertEquals(JobStatus.IN_PROGRESS, repository.find(jobId).block().status());
        assertTrue(repository.isExpiredAttemptObject(key).block());
        assertTrue(repository.isExpiredAttemptObject(legacyKey).block());
        database.sql("UPDATE transfer_jobs SET lease_until = now() + interval '1 minute' WHERE id = :id")
                .bind("id", jobId).fetch().rowsUpdated().block();
        assertTrue(repository.markCompleted(jobId, claim, key, 1, 10, "sha", 1L).block());
        assertFalse(repository.isExpiredAttemptObject(key).block(), "published object must survive reconciliation");
    }

    @Test
    void completionWaitingOnARowLockCannotPublishAfterItsLeaseExpires() throws Exception {
        UUID claim = insertClaim();
        String key = repository.find(jobId).block().attemptObjectKey("exports/", "csv");
        database.sql("UPDATE transfer_jobs SET lease_until = now() + interval '1 second' WHERE id = :id")
                .bind("id", jobId).fetch().rowsUpdated().block();
        try (var locker = java.sql.DriverManager.getConnection(
                com.omniflux.exchange.Containers.postgresJdbcUrl(),
                com.omniflux.exchange.Containers.postgresUser(),
                com.omniflux.exchange.Containers.postgresPassword());
             var statement = locker.createStatement()) {
            locker.setAutoCommit(false);
            statement.executeQuery("SELECT id FROM transfer_jobs WHERE id = '" + jobId + "' FOR UPDATE").close();
            var completion = repository.markCompleted(jobId, claim, key, 1, 10, "sha", 1L).toFuture();
            long deadline = System.nanoTime() + java.time.Duration.ofSeconds(5).toNanos();
            boolean waiting = false;
            boolean expired = false;
            while (System.nanoTime() < deadline && !(waiting && expired)) {
                statement.execute("SELECT pg_stat_clear_snapshot()");
                try (var rows = statement.executeQuery("SELECT EXISTS (SELECT 1 FROM pg_stat_activity "
                        + "WHERE pid <> pg_backend_pid() AND wait_event = 'transactionid' "
                        + "AND query LIKE '%transfer_jobs%')")) {
                    rows.next();
                    waiting |= rows.getBoolean(1);
                }
                try (var rows = statement.executeQuery("SELECT lease_until < clock_timestamp() "
                        + "FROM transfer_jobs WHERE id = '" + jobId + "'")) {
                    rows.next();
                    expired = rows.getBoolean(1);
                }
                if (!(waiting && expired)) Thread.sleep(20);
            }
            assertTrue(waiting, "completion must actually wait on the held row lock");
            assertTrue(expired, "lease must expire before the row lock is released");
            assertFalse(completion.isDone());
            locker.commit();
            assertFalse(completion.get(5, java.util.concurrent.TimeUnit.SECONDS));
        }
        assertEquals(JobStatus.IN_PROGRESS, repository.find(jobId).block().status());
    }

    @Test
    void shutdownObjectsRemainAbandonedWhenANewClaimReusesTheRetryNumber() {
        UUID claim = insertClaim();
        String oldKey = repository.find(jobId).block().attemptObjectKey("exports/", "csv");
        String legacyKey = "exports/" + jobId + "/a1/data.csv";
        assertTrue(repository.requeueForShutdown(jobId, claim).block());
        assertTrue(repository.isExpiredAttemptObject(oldKey).block());
        assertTrue(repository.isExpiredAttemptObject(legacyKey).block());
        UUID newClaim = UUID.randomUUID();
        database.sql("""
                UPDATE transfer_jobs SET status = 'IN_PROGRESS', attempt_count = 1,
                    claim_token = :claim, lease_until = now() + interval '1 minute' WHERE id = :id
                """).bind("id", jobId).bind("claim", newClaim).fetch().rowsUpdated().block();
        String newKey = repository.find(jobId).block().attemptObjectKey("exports/", "csv");
        assertNotEquals(oldKey, newKey);
        assertTrue(repository.isExpiredAttemptObject(oldKey).block());
        assertFalse(repository.isExpiredAttemptObject(newKey).block());
        assertFalse(repository.isExpiredAttemptObject(legacyKey).block(), "legacy keys conservatively protect a live attempt");
        assertFalse(repository.markCompleted(jobId, claim, oldKey, 1, 10, "sha", 1L).block());
        assertTrue(repository.markCompleted(jobId, newClaim, newKey, 1, 10, "sha", 1L).block());
        assertFalse(repository.isExpiredAttemptObject(newKey).block());
        assertTrue(repository.isExpiredAttemptObject(legacyKey).block());
    }

    private UUID insertClaim() {
        jobId = UUID.randomUUID();
        UUID claim = UUID.randomUUID();
        database.sql("""
                INSERT INTO transfer_jobs
                    (id, direction, status, requested_by, issuer, tenant, roles, relation_name,
                     format, attempt_count, claim_token, worker_id, lease_until, cancel_requested, created_at)
                VALUES (:id, 'EXPORT', 'IN_PROGRESS', :subject, :issuer, :tenant,
                    ARRAY['ANALYST']::text[], 'mock_orders', 'CSV', 1, :claim,
                    'boundary-worker', now() + interval '1 minute', FALSE, now())
                """).bind("id", jobId).bind("subject", OWNER.subject()).bind("issuer", OWNER.issuer())
                .bind("tenant", OWNER.tenant()).bind("claim", claim).fetch().rowsUpdated().block();
        database.sql("""
                INSERT INTO transfer_job_attempts (job_id, attempt_no, claim_token, worker_id, status, started_at)
                VALUES (:id, 1, :claim, 'boundary-worker', 'IN_PROGRESS', now())
                """).bind("id", jobId).bind("claim", claim).fetch().rowsUpdated().block();
        return claim;
    }

    @Test
    void shutdownReleaseIsClaimFencedClosesTheAttemptAndRestoresTheRetryBudget() {
        jobId = UUID.randomUUID();
        UUID claimToken = UUID.randomUUID();
        database.sql("""
                INSERT INTO transfer_jobs
                    (id, direction, status, requested_by, issuer, tenant, roles, relation_name,
                     format, attempt_count, claim_token, worker_id, lease_until,
                     cancel_requested, created_at)
                VALUES (:id, 'EXPORT', 'IN_PROGRESS', :requested_by, :issuer, :tenant,
                        ARRAY['ANALYST']::text[], 'mock_orders', 'CSV', 1,
                        :claim_token, 'drain-worker', now() + interval '1 minute',
                        FALSE, :created_at)
                """)
                .bind("id", jobId).bind("requested_by", OWNER.subject())
                .bind("issuer", OWNER.issuer()).bind("tenant", OWNER.tenant())
                .bind("claim_token", claimToken).bind("created_at", Instant.now())
                .fetch().rowsUpdated().block();
        database.sql("""
                INSERT INTO transfer_job_attempts
                    (job_id, attempt_no, claim_token, worker_id, status, started_at)
                VALUES (:job_id, 1, :claim_token, 'drain-worker', 'IN_PROGRESS', now())
                """)
                .bind("job_id", jobId).bind("claim_token", claimToken)
                .fetch().rowsUpdated().block();

        assertFalse(repository.requeueForShutdown(jobId, UUID.randomUUID()).block());
        assertTrue(repository.requeueForShutdown(jobId, claimToken).block());

        TransferJob queued = repository.find(jobId).block();
        assertEquals(JobStatus.QUEUED, queued.status());
        assertEquals(0, queued.attemptCount(), "the interrupted shutdown attempt does not spend retry budget");
        assertNull(queued.claimToken());
        JobAttempt attempt = repository.listAttempts(jobId, OWNER).blockFirst();
        assertEquals("REQUEUED", attempt.status());
        assertNotNull(attempt.finishedAt());
        assertEquals("worker shutdown drain timeout", attempt.errorMessage());
    }
}
