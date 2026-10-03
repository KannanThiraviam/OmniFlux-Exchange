package com.omniflux.exchange.job;

import com.omniflux.exchange.config.OmnifluxProperties;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Single-flight lease renewal for one claimed attempt.
 *
 * <p>The scheduler belongs to this renewer. A slow database call for one job
 * therefore cannot delay renewal of another job. The worker composes
 * {@link #lost()} with its export publisher so a failed fence cancels the
 * stream rather than merely setting a flag nobody observes.</p>
 */
public final class LeaseRenewer implements AutoCloseable {
    private final Renewal renewal;
    private final UUID jobId;
    private final UUID claimToken;
    private final Duration leaseDuration;
    private final Duration interval;
    private final Duration deadline;
    private final Clock clock;
    private final Scheduler scheduler;
    private final boolean ownsScheduler;
    private final Sinks.Empty<Void> lost = Sinks.empty();
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicReference<Disposable> loop = new AtomicReference<>();

    public LeaseRenewer(JobRepository repository, TransferJob job, OmnifluxProperties properties) {
        this(repository, job, new LeaseTiming(properties.queue().leaseDuration(),
                properties.queue().leaseRenewInterval(),
                properties.queue().leaseRenewDeadline()));
    }

    public LeaseRenewer(JobRepository repository, TransferJob job,
                        Duration interval, Duration deadline) {
        this(repository, job, new LeaseTiming(interval, interval, deadline));
    }

    public LeaseRenewer(JobRepository repository, TransferJob job, LeaseTiming timing) {
        this(repository, job.id(), job.claimToken(), timing, Clock.systemUTC(), null);
    }

    public LeaseRenewer(JobRepository repository, UUID jobId, UUID claimToken,
                        Duration interval, Duration deadline) {
        this(repository, jobId, claimToken, new LeaseTiming(interval, interval, deadline),
                Clock.systemUTC(), null);
    }

    public LeaseRenewer(JobRepository repository, UUID jobId, UUID claimToken,
                        LeaseTiming timing, Clock clock, Scheduler scheduler) {
        this(jobId, claimToken, timing, clock, scheduler,
                Objects.requireNonNull(repository, "repository")::renewLease);
    }

    /** Injectable renewal seam for deterministic lifecycle tests and alternate stores. */
    public LeaseRenewer(UUID jobId, UUID claimToken, LeaseTiming timing, Clock clock,
                        Scheduler scheduler, Renewal renewal) {
        this.jobId = Objects.requireNonNull(jobId, "jobId");
        this.claimToken = Objects.requireNonNull(claimToken, "claimToken");
        Objects.requireNonNull(timing, "timing");
        this.leaseDuration = timing.leaseDuration();
        this.interval = timing.interval();
        this.deadline = timing.deadline();
        this.clock = Objects.requireNonNull(clock, "clock");
        this.ownsScheduler = scheduler == null;
        this.scheduler = scheduler == null
                ? Schedulers.newSingle("omniflux-lease-" + jobId)
                : scheduler;
        this.renewal = Objects.requireNonNull(renewal, "renewal");
    }

    /** Completes when ownership can no longer be proven. */
    public Mono<Void> lost() {
        return Mono.defer(() -> {
            start();
            return lost.asMono();
        });
    }

    private void start() {
        if (!started.compareAndSet(false, true) || closed.get()) return;
        loop.set(Flux.interval(interval, interval, scheduler)
                .concatMap(ignored -> renewUntilDeadline(), 1)
                .takeUntil(Boolean.FALSE::equals)
                .subscribe(renewed -> {
                    if (!Boolean.TRUE.equals(renewed)) signalLost();
                }, ignored -> signalLost(), this::signalLost));
    }

    private Mono<Boolean> renewUntilDeadline() {
        return renewUntil(clock.instant().plus(deadline));
    }

    private Mono<Boolean> renewUntil(Instant retryDeadline) {
        if (closed.get() || !clock.instant().isBefore(retryDeadline)) {
            return Mono.just(false);
        }
        Duration remaining = Duration.between(clock.instant(), retryDeadline);
        return renewal.renew(jobId, claimToken, leaseDuration)
                .timeout(remaining, scheduler)
                .onErrorResume(error -> {
                    if (!retryable(error)) return Mono.just(false);
                    Duration left = Duration.between(clock.instant(), retryDeadline);
                    if (left.isZero() || left.isNegative()) return Mono.just(false);
                    Duration pause = left.compareTo(interval) < 0 ? left : interval;
                    return Mono.delay(pause, scheduler).then(renewUntil(retryDeadline));
                });
    }

    private static boolean retryable(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof ExportException export && !export.isTransient()) return false;
            current = current.getCause();
        }
        return true;
    }

    private void signalLost() {
        if (closed.get()) return;
        lost.tryEmitEmpty();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        Disposable current = loop.get();
        if (current != null) current.dispose();
        lost.tryEmitEmpty();
        if (ownsScheduler) scheduler.dispose();
    }

    @FunctionalInterface
    public interface Renewal {
        Mono<Boolean> renew(UUID jobId, UUID claimToken, Duration leaseDuration);
    }

    /** Lease timings for one attempt; all three durations must be positive. */
    public record LeaseTiming(Duration leaseDuration, Duration interval, Duration deadline) {
        public LeaseTiming {
            requirePositive(leaseDuration, "leaseDuration");
            requirePositive(interval, "interval");
            requirePositive(deadline, "deadline");
        }

        private static void requirePositive(Duration value, String name) {
            Objects.requireNonNull(value, name);
            if (value.isZero() || value.isNegative()) {
                throw new IllegalArgumentException(name + " must be positive");
            }
        }
    }
}
