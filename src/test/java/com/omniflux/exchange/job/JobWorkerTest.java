package com.omniflux.exchange.job;

import com.omniflux.exchange.TestProps;
import com.omniflux.exchange.config.OmnifluxProperties;
import com.omniflux.exchange.security.PrincipalKey;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JobWorkerTest {
    private static final PrincipalKey OWNER = new PrincipalKey("issuer", "alice", "tenant");
    private static final OmnifluxProperties PROPERTIES = TestProps.with(
            "omniflux.queue.lease-renew-interval", "1s");

    @Test
    void successfulExecutionIsFencedAndMarkedComplete() {
        var repository = Mockito.mock(JobRepository.class);
        var job = inProgress();
        var result = new JobResult("exports/data.csv", 2, 20, "sha", null, null);
        when(repository.markCompleted(eq(job.id()), eq(job.claimToken()), any(JobResult.class)))
                .thenReturn(Mono.just(true));
        var execution = (JobExecution) ignored -> Mono.just(result);
        var worker = new JobWorker(repository, execution, PROPERTIES);
        try (worker) {
            worker.run(job).block();
            assertTrue(worker.accepting());
        }
        assertFalse(worker.accepting());
        verify(repository).markCompleted(job.id(), job.claimToken(), result);
        verify(repository, Mockito.never()).requeueForShutdown(any(), any());
    }

    @Test
    void transientExecutionFailureIsPersistedWithItsErrorCode() {
        var repository = Mockito.mock(JobRepository.class);
        var job = inProgress();
        when(repository.markFailed(eq(job.id()), eq(job.claimToken()),
                eq(ErrorCode.QUERY_TIMEOUT), anyString())).thenReturn(Mono.just(true));
        try (var worker = new JobWorker(repository,
                ignored -> Mono.error(new ExportException(ErrorCode.QUERY_TIMEOUT, "timeout")),
                PROPERTIES)) {
            worker.run(job).block();
        }
        verify(repository).markFailed(eq(job.id()), eq(job.claimToken()),
                eq(ErrorCode.QUERY_TIMEOUT), eq("QUERY_TIMEOUT: timeout"));
    }

    @Test
    void unexpectedExecutionFailureIsNotClassifiedAsATransientStorageOutage() {
        var repository = Mockito.mock(JobRepository.class);
        var job = inProgress();
        when(repository.markFailed(eq(job.id()), eq(job.claimToken()),
                eq(ErrorCode.INTERNAL_ERROR), anyString())).thenReturn(Mono.just(true));
        try (var worker = new JobWorker(repository,
                ignored -> Mono.error(new NullPointerException("bug")), PROPERTIES)) {
            worker.run(job).block();
        }
        verify(repository).markFailed(eq(job.id()), eq(job.claimToken()),
                eq(ErrorCode.INTERNAL_ERROR), contains("bug"));
    }

    @Test
    void sdkClientFailuresAreClassifiedAsTransientStorageFailures() {
        var repository = Mockito.mock(JobRepository.class);
        var job = inProgress();
        when(repository.markFailed(eq(job.id()), eq(job.claimToken()),
                eq(ErrorCode.STORAGE_UNAVAILABLE), anyString())).thenReturn(Mono.just(true));
        try (var worker = new JobWorker(repository,
                ignored -> Mono.error(software.amazon.awssdk.core.exception.SdkClientException
                        .create("storage connection failed")), PROPERTIES)) {
            worker.run(job).block();
        }
        verify(repository).markFailed(eq(job.id()), eq(job.claimToken()),
                eq(ErrorCode.STORAGE_UNAVAILABLE), contains("storage connection failed"));
    }

    @Test
    void drainTimeoutFencedRequeuesAndCancelsTheActiveExecution() {
        var repository = Mockito.mock(JobRepository.class);
        var job = inProgress();
        when(repository.requeueForShutdown(job.id(), job.claimToken())).thenReturn(Mono.just(true));
        var cancelled = new AtomicBoolean();
        var properties = TestProps.with(java.util.Map.of(
                "omniflux.queue.lease-renew-interval", "1s",
                "omniflux.queue.drain-timeout", "20ms"));
        var worker = new JobWorker(repository,
                ignored -> Mono.<JobResult>never().doOnCancel(() -> cancelled.set(true)), properties);

        worker.run(job).subscribe();
        worker.stopAccepting();

        assertTrue(cancelled.get(), "timed-out execution must be cancelled for upload cleanup");
        verify(repository).requeueForShutdown(job.id(), job.claimToken());
        worker.close();
    }

    @Test
    void invalidClaimsAreRejectedAndStoppedWorkersDoNotStartNewWork() {
        var repository = Mockito.mock(JobRepository.class);
        var execution = (JobExecution) ignored -> Mono.empty();
        var worker = new JobWorker(repository, execution, PROPERTIES);
        assertThrows(IllegalArgumentException.class, () -> worker.run(
                TransferJob.queued(OWNER, "mock_orders", "CSV")).block());
        worker.stopAccepting();
        assertTrue(worker.run(inProgress()).blockOptional().isEmpty());
        worker.close();
    }

    @Test
    void runOnceClaimsAtMostOneJob() {
        var repository = Mockito.mock(JobRepository.class);
        when(repository.claim("worker-1")).thenReturn(Mono.empty());
        try (var worker = new JobWorker(repository, ignored -> Mono.empty(), PROPERTIES)) {
            worker.runOnce("worker-1").block();
        }
        verify(repository).claim("worker-1");
    }

    private static TransferJob inProgress() {
        return TransferJob.builder().id(UUID.randomUUID()).status(JobStatus.IN_PROGRESS)
                .owner(OWNER).relationName("mock_orders").format("CSV")
                .claimToken(UUID.randomUUID()).createdAt(Instant.now()).build();
    }
}
