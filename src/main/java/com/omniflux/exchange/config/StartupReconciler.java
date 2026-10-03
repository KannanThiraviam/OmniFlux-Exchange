package com.omniflux.exchange.config;

import com.omniflux.exchange.job.JobRepository;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.model.*;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Repairs storage left behind by a crashed worker.
 *
 * <p>Provider-side lifecycle rules are configured independently and vary by
 * object-store implementation. This reconciler provides an application-level
 * recovery path correlated with the database attempt lease. A live lease
 * always wins: upload age or the absence of a worker process is not sufficient
 * evidence to delete it.</p>
 */
@Component
public final class StartupReconciler {
    private static final Logger LOG = LoggerFactory.getLogger(StartupReconciler.class);

    private final S3AsyncClient storage;
    private final JobRepository jobs;
    private final OmnifluxProperties properties;
    private final ScheduledExecutorService scheduler;
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicReference<ScheduledFuture<?>> task = new AtomicReference<>();

    @Autowired
    public StartupReconciler(S3AsyncClient storage, JobRepository jobs,
                             OmnifluxProperties properties) {
        this(storage, jobs, properties, Clock.systemUTC(), newScheduler());
    }

    public StartupReconciler(S3AsyncClient storage, JobRepository jobs,
                             OmnifluxProperties properties, Clock clock) {
        this(storage, jobs, properties, clock, newScheduler());
    }

    public StartupReconciler(S3AsyncClient storage, JobRepository jobs,
                             OmnifluxProperties properties, Clock clock,
                             ScheduledExecutorService scheduler) {
        this.storage = Objects.requireNonNull(storage, "storage");
        this.jobs = Objects.requireNonNull(jobs, "jobs");
        this.properties = Objects.requireNonNull(properties, "properties");
        // clock is accepted (and validated) but not yet consulted: lease-age
        // correlation is a planned use, and today nothing reads it.
        Objects.requireNonNull(clock, "clock");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    }

    /** Runs once after application readiness; callers can also invoke it in tests. */
    @EventListener(ApplicationReadyEvent.class)
    public void reconcileOnStartup() {
        reconcile().subscribe(null,
                error -> LOG.warn("Startup storage reconciliation failed", error));
        if (started.compareAndSet(false, true)) {
            Duration interval = properties.queue().sweepInterval();
            task.set(scheduler.scheduleWithFixedDelay(() -> reconcile().subscribe(null,
                            error -> LOG.warn("Periodic storage reconciliation failed", error)),
                    interval.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS));
        }
    }

    /** Idempotent, paginated reconciliation pass. */
    public Mono<Void> reconcile() {
        String bucket = properties.storage().bucket();
        String prefix = properties.storage().exportPrefix();
        return jobs.sweepExpired()
                .then(abortExpiredMultipartUploads(bucket, prefix))
                .then(deleteExpiredUnreferencedObjects(bucket, prefix));
    }

    private Mono<Void> abortExpiredMultipartUploads(String bucket, String prefix) {
        return multipartUploads(bucket, prefix)
                .concatMap(upload -> jobs.isExpiredAttemptObject(upload.key())
                        .flatMap(expired -> Boolean.TRUE.equals(expired)
                                ? abort(bucket, upload.key(), upload.uploadId())
                                : Mono.empty()))
                .then();
    }

    private Mono<Void> deleteExpiredUnreferencedObjects(String bucket, String prefix) {
        return objects(bucket, prefix)
                .concatMap(object -> jobs.isObjectReferenced(object.key())
                        .flatMap(referenced -> {
                            if (Boolean.TRUE.equals(referenced)) return Mono.empty();
                            return jobs.isExpiredAttemptObject(object.key())
                                    .flatMap(expired -> Boolean.TRUE.equals(expired)
                                            ? delete(bucket, object.key()) : Mono.empty());
                        }))
                .then()
                .then();
    }

    private Mono<Void> abort(String bucket, String key, String uploadId) {
        return Mono.fromFuture(storage.abortMultipartUpload(AbortMultipartUploadRequest.builder()
                        .bucket(bucket).key(key).uploadId(uploadId).build()))
                .onErrorResume(StartupReconciler::notFound, ignored -> Mono.empty())
                .then();
    }

    private Mono<Void> delete(String bucket, String key) {
        return Mono.fromFuture(storage.deleteObject(r -> r.bucket(bucket).key(key)))
                .onErrorResume(StartupReconciler::notFound, ignored -> Mono.empty())
                .then();
    }

    private static boolean notFound(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof S3Exception s3 && s3.statusCode() == 404) return true;
            current = current.getCause();
        }
        return false;
    }

    private Flux<software.amazon.awssdk.services.s3.model.MultipartUpload> multipartUploads(
            String bucket, String prefix) {
        return multipartUploads(ListMultipartUploadsRequest.builder()
                .bucket(bucket).prefix(prefix).build());
    }

    private Flux<software.amazon.awssdk.services.s3.model.MultipartUpload> multipartUploads(
            ListMultipartUploadsRequest request) {
        return Mono.fromFuture(storage.listMultipartUploads(request))
                .flatMapMany(response -> {
                    Flux<software.amazon.awssdk.services.s3.model.MultipartUpload> current =
                            Flux.fromIterable(response.uploads());
                    if (!Boolean.TRUE.equals(response.isTruncated())) return current;
                    ListMultipartUploadsRequest next = request.toBuilder()
                            .keyMarker(response.nextKeyMarker())
                            .uploadIdMarker(response.nextUploadIdMarker())
                            .build();
                    return current.concatWith(multipartUploads(next));
                });
    }

    private Flux<S3Object> objects(String bucket, String prefix) {
        return objects(ListObjectsV2Request.builder().bucket(bucket).prefix(prefix).build());
    }

    private Flux<S3Object> objects(ListObjectsV2Request request) {
        return Mono.fromFuture(storage.listObjectsV2(request))
                .flatMapMany(response -> {
                    Flux<S3Object> current = Flux.fromIterable(response.contents());
                    if (!Boolean.TRUE.equals(response.isTruncated())) return current;
                    return current.concatWith(objects(request.toBuilder()
                            .continuationToken(response.nextContinuationToken()).build()));
                });
    }

    @PreDestroy
    public void close() {
        ScheduledFuture<?> current = task.get();
        if (current != null) current.cancel(false);
        scheduler.shutdownNow();
    }

    private static ScheduledExecutorService newScheduler() {
        return Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "omniflux-storage-reconciler");
            thread.setDaemon(true);
            return thread;
        });
    }
}
