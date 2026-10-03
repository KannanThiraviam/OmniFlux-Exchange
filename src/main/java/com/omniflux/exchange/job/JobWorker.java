package com.omniflux.exchange.job;

import com.omniflux.exchange.config.OmnifluxProperties;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import org.reactivestreams.Subscription;
import io.r2dbc.spi.R2dbcTimeoutException;
import io.r2dbc.spi.R2dbcTransientException;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.exception.SdkServiceException;

import java.util.Objects;
import java.util.Set;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;

/** Executes one claimed job and fences every state transition through the repository. */
public final class JobWorker implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(JobWorker.class);
    private final JobRepository repository;
    private final JobExecution execution;
    private final OmnifluxProperties properties;
    private final JobTelemetry telemetry;
    private final Set<LeaseRenewer> renewers = ConcurrentHashMap.newKeySet();
    private final java.util.Map<java.util.UUID, ActiveRun> activeRuns = new ConcurrentHashMap<>();
    private final AtomicBoolean accepting = new AtomicBoolean(true);
    private final Object drainMonitor = new Object();

    public JobWorker(JobRepository repository, JobExecution execution,
                     OmnifluxProperties properties) {
        this(repository, execution, properties, JobTelemetry.noop());
    }

    public JobWorker(JobRepository repository, JobExecution execution,
                     OmnifluxProperties properties, JobTelemetry telemetry) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.execution = Objects.requireNonNull(execution, "execution");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.telemetry = Objects.requireNonNull(telemetry, "telemetry");
    }

    /** Process an already claimed job. The claim token is mandatory. */
    public Mono<Void> run(TransferJob job) {
        Objects.requireNonNull(job, "job");
        if (job.status() != JobStatus.IN_PROGRESS || job.claimToken() == null) {
            return Mono.error(new IllegalArgumentException("worker requires an IN_PROGRESS job with a claim token"));
        }
        return Mono.defer(() -> runAccepted(job));
    }

    private Mono<Void> runAccepted(TransferJob job) {
        ActiveRun active = new ActiveRun(job);
        synchronized (drainMonitor) {
            if (!accepting.get()) return Mono.empty();
            activeRuns.put(job.id(), active);
        }
        long startedAt = System.nanoTime();
        var outcome = new java.util.concurrent.atomic.AtomicReference<>("cancelled");
        telemetry.started();
        return Mono.<Void, LeaseRenewer>using(
                () -> new LeaseRenewer(repository, job, properties),
                renewer -> {
                    renewers.add(renewer);
                    Mono<JobResult> export = Mono.defer(() -> {
                        FailPoint.reach(FailPoint.DURING_LEASE_HOLD);
                        return execution.execute(job);
                    });
                    LOG.info("Starting export job {} attempt {}", job.id(), job.attemptCount());
                    return export.takeUntilOther(renewer.lost())
                            .switchIfEmpty(Mono.error(new LeaseLost()))
                            .<Void>flatMap(result -> {
                                FailPoint.reach(FailPoint.AFTER_COMPLETE_MPU);
                                return repository.markCompleted(job.id(), job.claimToken(), result)
                                        .flatMap(fenced -> {
                                            if (Boolean.TRUE.equals(fenced)) {
                                                outcome.set("completed");
                                                LOG.info("Finished export job {}", job.id());
                                                return Mono.<Void>empty();
                                            }
                                            return result.deleteCompleted()
                                                    .then(Mono.<Void>error(new LeaseLost()));
                                        });
                            })
                            .onErrorResume(LeaseLost.class, ignored -> {
                                outcome.set("lease_lost");
                                return repository.markCancelled(job.id(), job.claimToken()).then();
                            })
                            .onErrorResume(error -> handleFailure(job, error, outcome))
                            .doFinally(ignored -> {
                                renewers.remove(renewer);
                                synchronized (drainMonitor) { drainMonitor.notifyAll(); }
                            });
                },
                LeaseRenewer::close)
                .doOnSubscribe(subscription -> {
                    active.subscription.set(subscription);
                })
                .doFinally(ignored -> {
                    activeRuns.remove(job.id(), active);
                    telemetry.finished(outcome.get(), Duration.ofNanos(System.nanoTime() - startedAt));
                    synchronized (drainMonitor) { drainMonitor.notifyAll(); }
                });
    }

    /** Claims and executes at most one job. Empty means the gate is full or the queue is empty. */
    public Mono<Void> runOnce(String workerId) {
        return repository.claim(workerId).flatMap(this::run);
    }

    public boolean accepting() {
        return accepting.get();
    }

    public void stopAccepting() {
        if (!accepting.compareAndSet(true, false)) return;
        // The container's termination grace period must exceed this drain window.
        // Keep renewing leases so an in-flight export can finish without burning
        // another attempt during a normal rolling shutdown.
        long deadline = System.nanoTime() + properties.queue().drainTimeout().toNanos();
        synchronized (drainMonitor) {
            while (!activeRuns.isEmpty()) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    break;
                }
                try {
                    TimeUnit.NANOSECONDS.timedWait(drainMonitor, remaining);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    LOG.warn("Shutdown drain interrupted with {} export(s) still running", activeRuns.size());
                    break;
                }
            }
        }
        if (!activeRuns.isEmpty()) {
            var remainingRuns = java.util.List.copyOf(activeRuns.values());
            LOG.warn("Shutdown drain timed out with {} export(s) still running; requeuing claims",
                    remainingRuns.size());
            for (ActiveRun active : remainingRuns) {
                try {
                    Boolean requeued = repository.requeueForShutdown(active.job.id(), active.job.claimToken())
                            .block(java.time.Duration.ofSeconds(5));
                    if (!Boolean.TRUE.equals(requeued)) {
                        LOG.warn("Could not requeue shutdown claim for job {}; claim may have changed", active.job.id());
                    }
                } catch (RuntimeException error) {
                    LOG.error("Failed to requeue shutdown claim for job {}", active.job.id(), error);
                } finally {
                    Subscription subscription = active.subscription.get();
                    if (subscription != null) subscription.cancel();
                }
            }
        }
    }

    @PreDestroy
    @Override
    public void close() {
        stopAccepting();
    }

    private Mono<Void> handleFailure(TransferJob job, Throwable error,
                                     java.util.concurrent.atomic.AtomicReference<String> outcome) {
        Throwable cause = unwrap(error);
        if (cause instanceof java.util.concurrent.CancellationException || !accepting.get()) {
            return Mono.empty();
        }
        ErrorCode code = classifyFailure(cause);
        LOG.error("Export job {} failed with {}", job.id(), code, cause);
        return repository.markFailed(job.id(), job.claimToken(), code,
                cause.getMessage() == null ? code.name() : cause.getMessage())
                .doOnSuccess(ignored -> outcome.set("failed"))
                .then();
    }

    private static Throwable unwrap(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null
                && (current instanceof java.util.concurrent.CompletionException
                || current instanceof java.util.concurrent.ExecutionException)) {
            current = current.getCause();
        }
        return current;
    }

    private static ErrorCode classifyFailure(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof ExportException export) return export.code();
            if (current instanceof java.util.concurrent.TimeoutException
                    || current instanceof java.net.SocketTimeoutException
                    || current instanceof R2dbcTimeoutException
                    || current instanceof io.netty.handler.timeout.ReadTimeoutException) {
                return ErrorCode.QUERY_TIMEOUT;
            }
            if (current instanceof R2dbcTransientException
                    || current instanceof java.sql.SQLTransientException
                    || current instanceof WebClientRequestException
                    || current instanceof java.io.IOException) {
                return ErrorCode.UPSTREAM_UNAVAILABLE;
            }
            if (current instanceof SdkClientException) return ErrorCode.STORAGE_UNAVAILABLE;
            if (current instanceof SdkServiceException service
                    && (service.statusCode() >= 500 || service.isThrottlingException())) {
                return ErrorCode.STORAGE_UNAVAILABLE;
            }
        }
        return ErrorCode.INTERNAL_ERROR;
    }

    private static final class LeaseLost extends RuntimeException {
        private LeaseLost() { super("lease lost"); }
    }

    private static final class ActiveRun {
        private final TransferJob job;
        private final AtomicReference<Subscription> subscription = new AtomicReference<>();
        private ActiveRun(TransferJob job) { this.job = job; }
    }
}
