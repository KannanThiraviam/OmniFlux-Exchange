package com.omniflux.exchange.job;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

/** Low-cardinality metrics for worker lifecycle events. */
@Component
public final class JobTelemetry {
    private final MeterRegistry registry;
    private final Counter claimed;
    private final AtomicInteger active = new AtomicInteger();

    // @Autowired is load-bearing: with two constructors and no marker, Spring
    // picks the private no-arg one below and production records nothing.
    @Autowired
    public JobTelemetry(MeterRegistry registry) {
        this.registry = registry;
        claimed = Counter.builder("omniflux.jobs.claimed")
                .description("Jobs accepted by this pod's worker")
                .register(registry);
        io.micrometer.core.instrument.Gauge.builder("omniflux.jobs.worker.active", active, AtomicInteger::get)
                .description("Jobs currently executing on this pod")
                .register(registry);
    }

    /** Constructor for direct unit tests which do not create a metrics registry. */
    private JobTelemetry() {
        registry = null;
        claimed = null;
    }

    public static JobTelemetry noop() {
        return new JobTelemetry();
    }

    public void started() {
        active.incrementAndGet();
        if (claimed != null) claimed.increment();
    }

    public void finished(String outcome, Duration duration) {
        active.updateAndGet(value -> Math.max(0, value - 1));
        if (registry == null) return;
        Counter.builder("omniflux.jobs.finished")
                .description("Export job outcomes")
                .tag("outcome", outcome)
                .register(registry)
                .increment();
        Timer.builder("omniflux.jobs.execution")
                .description("Export job execution time")
                .tag("outcome", outcome)
                .publishPercentileHistogram()
                .register(registry)
                .record(duration);
    }
}
