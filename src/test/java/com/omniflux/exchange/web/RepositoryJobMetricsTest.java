package com.omniflux.exchange.web;

import com.omniflux.exchange.TestProps;
import com.omniflux.exchange.job.JobRepository;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RepositoryJobMetricsTest {
    @Test
    void mapsTheRepositorySnapshotToTheSystemMetricsContract() {
        JobRepository repository = Mockito.mock(JobRepository.class);
        when(repository.metrics()).thenReturn(Mono.just(
                new JobRepository.JobMetricsSnapshot(2, 7, 3, 123.5, 0.25)));

        var snapshot = new RepositoryJobMetrics(repository).snapshot().block();

        assertEquals(new SystemController.JobMetrics.Snapshot(2, 7, 3, 123.5, 0.25), snapshot);
        verify(repository).metrics();
    }

    @Test
    void systemControllerPublishesTheLiveSnapshotAndConfiguredGlobalLimit() throws Exception {
        var probe = new com.omniflux.exchange.observability.ResourceProbe(
                path -> { throw new java.io.IOException("absent"); },
                java.time.Duration.ofSeconds(1));
        var source = new com.omniflux.exchange.observability.EventSource() {
            @Override public java.util.List<java.nio.file.WatchEvent<?>> poll(java.time.Duration timeout) {
                return java.util.List.of();
            }
            @Override public void close() { }
        };
        var watcher = com.omniflux.exchange.observability.FsWatcher.over(source);
        try (watcher) {
            var metrics = new SystemController.JobMetrics() {
                @Override public Mono<Snapshot> snapshot() {
                    return Mono.just(new Snapshot(2, 7, 3, 123.5, 0.25));
                }
            };
            var controller = new SystemController(probe, watcher, TestProps.defaults(), metrics);

            var response = java.util.Objects.requireNonNull(
                    controller.resourceSnapshotHttp().block());

            assertEquals(new SystemController.Jobs(2, 7, 3,
                    TestProps.defaults().queue().maxConcurrent()), response.jobs());
            assertEquals(123.5, response.rowsPerSec());
            assertEquals(0.25, response.cacheHitRate());
        }
    }
}
