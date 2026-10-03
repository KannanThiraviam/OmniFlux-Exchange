package com.omniflux.exchange.job;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * The bean is built by Spring, not by {@code new}: JobTelemetry also has a
 * private no-arg constructor for {@link JobTelemetry#noop()}, and without an
 * explicit autowiring marker Spring chooses it, leaving production with a
 * no-op that records nothing. The assertions read the Prometheus exposition
 * text, because a name collision would also drop a meter silently.
 */
class JobTelemetryPrometheusTest {

    @Test
    void springBuiltTelemetryIsScrapedAlongsideQueueGauges() {
        try (var context = new AnnotationConfigApplicationContext()) {
            PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
            context.registerBean(MeterRegistry.class, () -> registry);
            context.registerBean(JobRepository.class, () -> mock(JobRepository.class));
            context.register(JobTelemetry.class, JobQueueMetricsPublisher.class);
            context.refresh();

            JobTelemetry telemetry = context.getBean(JobTelemetry.class);
            telemetry.started();
            telemetry.finished("completed", Duration.ofMillis(250));
            String scrape = registry.scrape();

            assertTrue(scrape.contains("omniflux_jobs_queued "), scrape);
            assertTrue(scrape.contains("omniflux_jobs_claimed_total "), scrape);
            assertTrue(scrape.contains("omniflux_jobs_finished_total{outcome=\"completed\"}"), scrape);
            assertTrue(scrape.contains("omniflux_jobs_execution_seconds_count{outcome=\"completed\"}"), scrape);
            assertTrue(scrape.contains("omniflux_jobs_worker_active "), scrape);
        }
    }
}
