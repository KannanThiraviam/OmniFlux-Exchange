package com.omniflux.exchange.job;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Persists each executing job's rows-so-far so the jobs list shows live progress.
 *
 * <p>The interval is a constant rather than configuration on purpose: the
 * configuration schema in the design spec (§12) is authoritative, and progress
 * cadence is an operational detail, not a deployment knob. Five seconds is well
 * inside the UI's 1.5s poll cadence cost model: one small indexed UPDATE per
 * running job per interval.</p>
 */
public final class JobProgressFlusher implements AutoCloseable {
    public static final Duration DEFAULT_INTERVAL = Duration.ofSeconds(5);

    private final JobRepository repository;
    private final JobProgressRegistry registry;
    private final ScheduledExecutorService scheduler;
    private final boolean ownsScheduler;
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicReference<ScheduledFuture<?>> task = new AtomicReference<>();

    public JobProgressFlusher(JobRepository repository, JobProgressRegistry registry) {
        this(repository, registry, Executors.newSingleThreadScheduledExecutor(namedFactory()), true);
    }

    public JobProgressFlusher(JobRepository repository, JobProgressRegistry registry,
                              ScheduledExecutorService scheduler) {
        this(repository, registry, scheduler, false);
    }

    private JobProgressFlusher(JobRepository repository, JobProgressRegistry registry,
                               ScheduledExecutorService scheduler, boolean ownsScheduler) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.ownsScheduler = ownsScheduler;
    }

    public void start(Duration interval) {
        if (interval == null || interval.isZero() || interval.isNegative()) {
            throw new IllegalArgumentException("interval must be positive");
        }
        if (!running.compareAndSet(false, true)) return;
        task.set(scheduler.scheduleWithFixedDelay(this::flushSafely, interval.toMillis(),
                interval.toMillis(), TimeUnit.MILLISECONDS));
    }

    public boolean running() { return running.get(); }

    /** One pass: write every registered job's current rows-so-far, sequentially. */
    public Mono<Void> flush() {
        var entries = registry.snapshot().entrySet();
        if (entries.isEmpty()) return Mono.empty();
        return Flux.fromIterable(entries)
                .concatMap(entry -> repository.updateProgress(entry.getKey(),
                                entry.getValue().claimToken(), entry.getValue().rowsSoFar())
                        // A false here is the claim fence doing its job (lease lost,
                        // job finished, or another pod owns it) — never an error.
                        .onErrorResume(ignored -> Mono.just(false)))
                .then();
    }

    public synchronized void stop() {
        running.set(false);
        ScheduledFuture<?> current = task.get();
        if (current != null) current.cancel(false);
        task.set(null);
    }

    @Override
    public void close() {
        stop();
        if (ownsScheduler) scheduler.shutdown();
    }

    private void flushSafely() {
        if (!running.get()) return;
        flush().subscribe();
    }

    private static java.util.concurrent.ThreadFactory namedFactory() {
        return runnable -> {
            Thread thread = new Thread(runnable, "omniflux-progress-flusher");
            thread.setDaemon(false);
            return thread;
        };
    }
}
