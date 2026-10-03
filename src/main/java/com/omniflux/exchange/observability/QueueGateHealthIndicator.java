package com.omniflux.exchange.observability;

import com.omniflux.exchange.config.OmnifluxProperties;
import com.omniflux.exchange.job.JobRepository;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.ReactiveHealthIndicator;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/** Readiness probe for the persisted admission gate/configuration contract. */
@Component("queueGateHealthIndicator")
public final class QueueGateHealthIndicator implements ReactiveHealthIndicator {
    private final JobRepository jobs;
    private final OmnifluxProperties properties;

    public QueueGateHealthIndicator(JobRepository jobs, OmnifluxProperties properties) {
        this.jobs = jobs;
        this.properties = properties;
    }

    @Override
    public Mono<Health> health() {
        return jobs.admissionGateMatchesConfiguration()
                .map(matches -> matches
                        ? Health.up().withDetail("check", "admission gate matches configuration").build()
                        : Health.outOfService()
                                .withDetail("check", "admission gate/configuration mismatch")
                                .withDetail("configuredMaxConcurrent", properties.queue().maxConcurrent())
                                .build())
                // A transient database outage is not a process-liveness failure.
                // UNKNOWN is HTTP 200 under Boot's default status mapping and this
                // indicator belongs only to the readiness group.
                .onErrorResume(ignored -> Mono.just(Health.unknown()
                        .withDetail("check", "admission gate probe temporarily unavailable").build()));
    }
}
