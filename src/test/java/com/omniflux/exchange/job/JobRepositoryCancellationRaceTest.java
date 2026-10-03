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
