package com.omniflux.exchange.upload;

import com.omniflux.exchange.export.ExportTiming;
import com.omniflux.exchange.job.FailPoint;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.core.async.AsyncRequestBody;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.model.*;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Bounded explicit S3 multipart upload.
 *
 * <p>The AWS SDK's unknown-length {@code putObject} publisher hides the
 * multipart boundary. Explicit parts keep the upload bounded and provide a
 * real acknowledgement from {@code uploadPart}; the fault-injection point is
 * reached only after that acknowledgement.</p>
 */
public final class S3UploadSession implements UploadSession {
    private final S3AsyncClient client;
    private final String bucket;
    private final String key;
    private final ExportTiming timing;
    private final long partSize;
    private final long failPointPartBytes;
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean aborted = new AtomicBoolean();
    private final AtomicLong bytes = new AtomicLong();
    private final AtomicReference<SingleSubscriptionPublisher> guardedBody = new AtomicReference<>();
    private final AtomicReference<CompletableFuture<UploadResult>> future = new AtomicReference<>();
    private final AtomicReference<MessageDigest> digest = new AtomicReference<>();
    private final AtomicReference<String> uploadId = new AtomicReference<>();
    private final AtomicBoolean firstPartReached = new AtomicBoolean();

    public S3UploadSession(S3AsyncClient client, String bucket, String key) {
        this(client, bucket, key, null, 8L << 20);
    }

    public S3UploadSession(S3AsyncClient client, String bucket, String key, ExportTiming timing) {
        this(client, bucket, key, timing, 8L << 20);
    }

    public S3UploadSession(S3AsyncClient client, String bucket, String key,
                           ExportTiming timing, long partSize) {
        this.client = Objects.requireNonNull(client, "client");
        this.bucket = requireText(bucket, "bucket");
        this.key = requireText(key, "key");
        this.timing = timing;
        if (partSize < 1) throw new IllegalArgumentException("partSize must be positive");
        this.partSize = partSize;
        this.failPointPartBytes = partSize;
    }

    public Publisher<ByteBuffer> body() {
        SingleSubscriptionPublisher body = guardedBody.get();
        if (body == null) throw new IllegalStateException("upload has not started");
        return body;
    }

    @Override
    public void start(Flux<ByteBuffer> body, String contentType) {
        Objects.requireNonNull(body, "body");
        if (!started.compareAndSet(false, true)) {
            throw new IllegalStateException("upload session already started");
        }
        digest.set(newDigest());
        SingleSubscriptionPublisher guarded = SingleSubscriptionPublisher.of(
                body.doOnNext(buffer -> {
                    bytes.addAndGet(buffer.remaining());
                    digest.get().update(buffer.asReadOnlyBuffer());
                }));
        guardedBody.set(guarded);
        if (timing != null) timing.uploadStarted();

        CreateMultipartUploadRequest create = CreateMultipartUploadRequest.builder()
                .bucket(bucket).key(key).contentType(contentType).build();
        CompletableFuture<UploadResult> upload = Mono.fromFuture(client.createMultipartUpload(create))
                .flatMap(initial -> {
                    String id = requireText(initial.uploadId(), "uploadId");
                    uploadId.set(id);
                    PartAccumulator accumulator = new PartAccumulator(id);
                    return Flux.from(guarded)
                            .concatMap(accumulator::append)
                            .concatWith(Mono.defer(accumulator::finish))
                            .collectList()
                            .flatMap(parts -> complete(id, parts));
                })
                .doOnError(ignored -> abortMultipart())
                .toFuture();
        future.set(upload);
    }

    @Override
    public Mono<UploadResult> completion() {
        return Mono.defer(() -> {
            CompletableFuture<UploadResult> upload = future.get();
            if (upload == null) return Mono.error(new IllegalStateException("upload has not started"));
            return Mono.fromFuture(upload);
        });
    }

    @Override
    public void abort() {
        if (!aborted.compareAndSet(false, true)) return;
        SingleSubscriptionPublisher body = guardedBody.get();
        if (body != null) body.cancel();
        CompletableFuture<UploadResult> upload = future.get();
        if (upload != null) upload.cancel(true);
        abortMultipart();
    }

    @Override
    public Mono<Void> deleteCompleted() {
        return Mono.fromFuture(() -> client.deleteObject(DeleteObjectRequest.builder()
                        .bucket(bucket).key(key).build()))
                .then();
    }

    private Mono<UploadResult> complete(String id, List<CompletedPart> parts) {
        if (parts.isEmpty()) return Mono.error(new IllegalStateException("multipart upload has no parts"));
        CompleteMultipartUploadRequest request = CompleteMultipartUploadRequest.builder()
                .bucket(bucket).key(key).uploadId(id)
                .multipartUpload(multipart -> multipart.parts(parts))
                .build();
        return Mono.fromFuture(client.completeMultipartUpload(request))
                .map(response -> {
                    if (timing != null) timing.uploadCompleted(bytes.get());
                    return new UploadResult(key, bytes.get(), hex(digest.get().digest()), response.eTag());
                });
    }

    private void abortMultipart() {
        String id = uploadId.get();
        if (id == null || id.isBlank()) return;
        client.abortMultipartUpload(AbortMultipartUploadRequest.builder()
                        .bucket(bucket).key(key).uploadId(id).build())
                .exceptionally(ignored -> null);
    }

    private final class PartAccumulator {
        private final String id;
        private final ByteArrayOutputStream current = new ByteArrayOutputStream(Math.toIntExact(partSize));
        private int nextPart = 1;

        private PartAccumulator(String id) { this.id = id; }

        private Flux<CompletedPart> append(ByteBuffer buffer) {
            ByteBuffer source = buffer.duplicate();
            List<byte[]> complete = new ArrayList<>();
            while (source.hasRemaining()) {
                int room = Math.toIntExact(partSize - current.size());
                int count = Math.min(room, source.remaining());
                byte[] chunk = new byte[count];
                source.get(chunk);
                current.writeBytes(chunk);
                if (current.size() == partSize) {
                    complete.add(current.toByteArray());
                    current.reset();
                }
            }
            return Flux.fromIterable(complete).concatMap(this::uploadPart);
        }

        private Mono<CompletedPart> finish() {
            if (current.size() == 0) return Mono.empty();
            return uploadPart(current.toByteArray()).doOnSuccess(ignored -> current.reset());
        }

        private Mono<CompletedPart> uploadPart(byte[] content) {
            int partNumber = nextPart++;
            UploadPartRequest request = UploadPartRequest.builder()
                    .bucket(bucket).key(key).uploadId(id).partNumber(partNumber)
                    .contentLength((long) content.length).build();
            return Mono.fromFuture(client.uploadPart(request, AsyncRequestBody.fromBytes(content)))
                    .map(response -> {
                        if (partNumber == 1 && bytes.get() >= failPointPartBytes
                                && firstPartReached.compareAndSet(false, true)) {
                            // This callback runs after COS acknowledged the
                            // part, which makes the recovery proof meaningful.
                            FailPoint.reach(FailPoint.AFTER_FIRST_PART);
                        }
                        return CompletedPart.builder().partNumber(partNumber)
                                .eTag(response.eTag()).build();
                    });
        }
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }

    private static MessageDigest newDigest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException error) { throw new IllegalStateException("SHA-256 is required by the JDK", error); }
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) result.append(String.format(java.util.Locale.ROOT, "%02x", value));
        return result.toString();
    }
}
