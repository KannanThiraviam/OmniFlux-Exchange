package com.omniflux.exchange.job;

import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Moves expired attempts back to the queue or to terminal FAILED. */
public final class ExpiredLeaseSweeper implements AutoCloseable {
    private final JobRepository repository;
    private final ScheduledExecutorService scheduler;
    private final boolean ownsScheduler;
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicReference<ScheduledFuture<?>> task = new AtomicReference<>();

    public ExpiredLeaseSweeper(JobRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.scheduler = Executors.newSingleThreadScheduledExecutor(namedFactory());
        this.ownsScheduler = true;
    }

    public ExpiredLeaseSweeper(JobRepository repository, ScheduledExecutorService scheduler) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.ownsScheduler = false;
    }

    public Mono<Long> sweep() {
        return repository.sweepExpired();
    }

    public synchronized void start(Duration interval) {
        if (interval == null || interval.isZero() || interval.isNegative()) {
            throw new IllegalArgumentException("interval must be positive");
        }
        if (!running.compareAndSet(false, true)) return;
        task.set(scheduler.scheduleWithFixedDelay(() -> sweep().subscribe(), 0,
                interval.toMillis(), TimeUnit.MILLISECONDS));
    }

    public boolean running() { return running.get(); }

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

    private static ThreadFactory namedFactory() {
        return runnable -> {
            Thread thread = new Thread(runnable, "omniflux-lease-sweeper");
            thread.setDaemon(false);
            return thread;
        };
    }
}
