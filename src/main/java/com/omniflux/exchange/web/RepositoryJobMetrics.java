package com.omniflux.exchange.web;

import com.omniflux.exchange.job.JobRepository;
import reactor.core.publisher.Mono;

/** Reads operator metrics from the same database that owns job admission. */
public final class RepositoryJobMetrics implements SystemController.JobMetrics {
    private final JobRepository jobs;

    public RepositoryJobMetrics(JobRepository jobs) {
        this.jobs = jobs;
    }

    @Override
    public Mono<Snapshot> snapshot() {
        return jobs.metrics().map(value -> new Snapshot(value.active(), value.queued(),
                value.globalActive(), value.rowsPerSec(), value.cacheHitRate()));
    }
}
