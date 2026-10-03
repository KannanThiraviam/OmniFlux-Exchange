package com.omniflux.exchange.job;

import reactor.core.publisher.Mono;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** Small scheduling seam for polling the database queue without an in-memory queue. */
public final class JobQueuePoller implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(JobQueuePoller.class);
    private static final long ERROR_LOG_INTERVAL_NANOS = Duration.ofSeconds(30).toNanos();
    private final JobRepository repository;
    private final JobWorker worker;
    private final String workerId;
    private final Duration interval;
    private final ScheduledExecutorService scheduler;
    private final boolean ownsScheduler;
    private final Duration drainTimeout;
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean closing = new AtomicBoolean();
    private final AtomicInteger pendingClaims = new AtomicInteger();
    private final AtomicLong lastErrorLogNanos = new AtomicLong();
    private final AtomicLong lastGateMismatchLogNanos = new AtomicLong();
    private final Object claimsMonitor = new Object();
    private final AtomicReference<ScheduledFuture<?>> task = new AtomicReference<>();

    public JobQueuePoller(JobRepository repository, JobWorker worker,
                          String workerId, Duration interval) {
        this(repository, worker, workerId, interval, Duration.ofSeconds(30),
                Executors.newSingleThreadScheduledExecutor(namedFactory()), true);
    }

    public JobQueuePoller(JobRepository repository, JobWorker worker,
                          String workerId, Duration interval, Duration drainTimeout) {
        this(repository, worker, workerId, interval, drainTimeout,
                Executors.newSingleThreadScheduledExecutor(namedFactory()), true);
    }

    public JobQueuePoller(JobRepository repository, JobWorker worker, String workerId,
                          Duration interval, ScheduledExecutorService scheduler) {
        this(repository, worker, workerId, interval, Duration.ofSeconds(30), scheduler, false);
    }

    public JobQueuePoller(JobRepository repository, JobWorker worker, String workerId,
                          Duration interval, Duration drainTimeout,
                          ScheduledExecutorService scheduler) {
        this(repository, worker, workerId, interval, drainTimeout, scheduler, false);
    }

    private JobQueuePoller(JobRepository repository, JobWorker worker, String workerId,
                           Duration interval, Duration drainTimeout,
                           ScheduledExecutorService scheduler,
                           boolean ownsScheduler) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.worker = Objects.requireNonNull(worker, "worker");
        this.workerId = requireText(workerId);
        this.interval = positive(interval);
        this.drainTimeout = positive(drainTimeout);
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.ownsScheduler = ownsScheduler;
    }

    public synchronized void start() {
        if (!running.compareAndSet(false, true)) return;
        task.set(scheduler.scheduleWithFixedDelay(this::pollOnceSafely, 0,
                interval.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS));
    }

    public boolean running() {
        return running.get();
    }

    public Mono<Void> pollOnce() {
        return Mono.defer(() -> {
            synchronized (claimsMonitor) {
                if (closing.get()) return Mono.empty();
                pendingClaims.incrementAndGet();
            }
            var settled = new AtomicBoolean();
            Runnable finish = () -> {
                if (settled.compareAndSet(false, true)) finishPendingClaim();
            };
            return repository.claim(workerId)
                    .flatMap(job -> worker.run(job).doOnSubscribe(ignored -> finish.run()))
                    .doOnSuccess(ignored -> finish.run())
                    .doOnError(error -> finish.run());
        });
    }

    public synchronized void stop() {
        running.set(false);
        ScheduledFuture<?> current = task.get();
        if (current != null) current.cancel(false);
        task.set(null);
    }

    private void pollOnceSafely() {
        if (!running.get()) return;
        pollOnce().subscribe(null, this::logPollFailure);
    }

    private void logPollFailure(Throwable error) {
        long now = System.nanoTime();
        if (isGateMismatch(error)) {
            if (claimLogPermit(lastGateMismatchLogNanos, now)) {
                LOG.error("Queue processing is blocked: admission gate/config mismatch for worker {}: {}",
                        workerId, error.getMessage(), error);
            }
            return;
        }
        if (claimLogPermit(lastErrorLogNanos, now)) {
            LOG.error("Queue claim or job dispatch failed for worker {} (further errors are rate-limited to once per 30s)",
                    workerId, error);
        }
    }

    private static boolean claimLogPermit(AtomicLong lastLogged, long now) {
        while (true) {
            long previous = lastLogged.get();
            if (previous != 0 && now - previous < ERROR_LOG_INTERVAL_NANOS) return false;
            if (lastLogged.compareAndSet(previous, now)) return true;
        }
    }

    private static boolean isGateMismatch(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof IllegalStateException && current.getMessage() != null
                    && current.getMessage().contains("admission_gate.max_concurrent")) return true;
        }
        return false;
    }

    @Override
    public void close() {
        stop();
        synchronized (claimsMonitor) { closing.set(true); }
        waitForPendingClaims();
        worker.stopAccepting();
        if (ownsScheduler) scheduler.shutdown();
    }

    private void finishPendingClaim() {
        if (pendingClaims.decrementAndGet() <= 0) {
            synchronized (claimsMonitor) { claimsMonitor.notifyAll(); }
        }
    }

    private void waitForPendingClaims() {
        long deadline = System.nanoTime() + drainTimeout.toNanos();
        synchronized (claimsMonitor) {
            while (pendingClaims.get() > 0) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    LOG.warn("Timed out waiting for {} queue claim(s) to settle before worker drain",
                            pendingClaims.get());
                    return;
                }
                try {
                    java.util.concurrent.TimeUnit.NANOSECONDS.timedWait(claimsMonitor, remaining);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    LOG.warn("Interrupted while waiting for queue claims before worker drain");
                    return;
                }
            }
        }
    }

    private static ThreadFactory namedFactory() {
        return runnable -> {
            Thread thread = new Thread(runnable, "omniflux-queue-poller");
            thread.setDaemon(false);
            return thread;
        };
    }

    private static String requireText(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("workerId" + " must not be blank");
        return value;
    }

    private static Duration positive(Duration value) {
        Objects.requireNonNull(value, "interval");
        if (value.isZero() || value.isNegative()) throw new IllegalArgumentException("interval" + " must be positive");
        return value;
    }
}
