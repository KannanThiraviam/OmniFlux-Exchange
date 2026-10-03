package com.omniflux.exchange.job;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import reactor.core.publisher.Mono;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JobProgressFlusherTest {

    @Test
    void flushPersistsCurrentRowCountsForRegisteredJobs() {
        var repository = Mockito.mock(JobRepository.class);
        when(repository.updateProgress(Mockito.any(), Mockito.any(), Mockito.anyLong()))
                .thenReturn(Mono.just(true));
        var registry = new JobProgressRegistry();
        var rowsA = new AtomicLong(3);
        var rowsB = new AtomicLong();
        var jobA = UUID.randomUUID();
        var jobB = UUID.randomUUID();
        registry.register(jobA, UUID.randomUUID(), rowsA);
        registry.register(jobB, UUID.randomUUID(), rowsB);
        rowsA.set(42);
        rowsB.set(7);
        try (var flusher = new JobProgressFlusher(repository, registry)) {
            flusher.flush().block();
        }
        verify(repository).updateProgress(eq(jobA), Mockito.any(), eq(42L));
        verify(repository).updateProgress(eq(jobB), Mockito.any(), eq(7L));
    }

    @Test
    void fencedFlushRejectionsAreNotErrors() {
        var repository = Mockito.mock(JobRepository.class);
        when(repository.updateProgress(Mockito.any(), Mockito.any(), Mockito.anyLong()))
                .thenReturn(Mono.just(false));
        var registry = new JobProgressRegistry();
        registry.register(UUID.randomUUID(), UUID.randomUUID(), new AtomicLong(9));
        try (var flusher = new JobProgressFlusher(repository, registry)) {
            assertDoesNotThrow(() -> flusher.flush().block());
        }
    }

    @Test
    void clearRemovesTheJobFromTheRegistry() {
        var registry = new JobProgressRegistry();
        var jobId = UUID.randomUUID();
        registry.register(jobId, UUID.randomUUID(), new AtomicLong(1));
        assertEquals(1, registry.snapshot().size());
        registry.clear(jobId);
        assertTrue(registry.snapshot().isEmpty());
    }

    @Test
    void startAndStopCycleIsIdempotent() {
        var repository = Mockito.mock(JobRepository.class);
        when(repository.updateProgress(Mockito.any(), Mockito.any(), Mockito.anyLong()))
                .thenReturn(Mono.just(true));
        var registry = new JobProgressRegistry();
        var flusher = new JobProgressFlusher(repository, registry);
        flusher.start(java.time.Duration.ofSeconds(60));
        flusher.start(java.time.Duration.ofSeconds(60));
        flusher.stop();
        flusher.stop();
        flusher.close();
    }
}
