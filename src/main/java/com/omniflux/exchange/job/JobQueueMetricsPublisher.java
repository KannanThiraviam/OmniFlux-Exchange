package com.omniflux.exchange.job;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

/** Periodically refreshes database-backed queue gauges without querying on scrape. */
@Component
public final class JobQueueMetricsPublisher {
    private static final Logger LOG = LoggerFactory.getLogger(JobQueueMetricsPublisher.class);
    private static final Duration REFRESH_INTERVAL = Duration.ofSeconds(15);

    private final JobRepository jobs;
    private final AtomicLong active = new AtomicLong();
    private final AtomicLong queued = new AtomicLong();
    private final AtomicLong globalActive = new AtomicLong();
    private final AtomicLong rowsPerSecond = new AtomicLong();
    private final AtomicLong cacheHitRatePpm = new AtomicLong();
    private volatile Disposable refresh;

    public JobQueueMetricsPublisher(JobRepository jobs, MeterRegistry registry) {
        this.jobs = jobs;
        register(registry, "omniflux.jobs.active", active, "Active export jobs visible to this database");
        register(registry, "omniflux.jobs.queued", queued, "Queued export jobs");
        register(registry, "omniflux.jobs.global.active", globalActive, "Cluster-wide active export jobs");
        register(registry, "omniflux.jobs.rows.per.second", rowsPerSecond,
                "Five-minute completed-row throughput estimate");
        Gauge.builder("omniflux.jobs.cache.hit.rate", cacheHitRatePpm, value -> value.get() / 1_000_000.0)
                .description("Five-minute export cache-hit ratio")
                .register(registry);
    }

    @EventListener(ApplicationReadyEvent.class)
    public synchronized void start() {
        if (refresh != null) return;
        refresh = Flux.interval(Duration.ZERO, REFRESH_INTERVAL)
                .concatMap(ignored -> jobs.metrics()
                        .doOnNext(this::update)
                        .doOnError(error -> LOG.debug("Queue metrics refresh failed"))
                        .onErrorResume(ignoredError -> reactor.core.publisher.Mono.empty()))
                .subscribe();
    }

    private void update(JobRepository.JobMetricsSnapshot snapshot) {
        active.set(snapshot.active());
        queued.set(snapshot.queued());
        globalActive.set(snapshot.globalActive());
        rowsPerSecond.set(Math.round(snapshot.rowsPerSec()));
        cacheHitRatePpm.set(Math.round(snapshot.cacheHitRate() * 1_000_000));
    }

    private static void register(MeterRegistry registry, String name, AtomicLong value, String description) {
        Gauge.builder(name, value, AtomicLong::get).description(description).register(registry);
    }

    @PreDestroy
    public synchronized void close() {
        if (refresh != null) {
            refresh.dispose();
            refresh = null;
        }
    }
}
