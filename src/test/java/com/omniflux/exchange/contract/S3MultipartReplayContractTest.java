package com.omniflux.exchange.contract;

import com.omniflux.exchange.Containers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.core.io.buffer.*;
import reactor.core.publisher.Flux;
import software.amazon.awssdk.auth.credentials.*;
import software.amazon.awssdk.core.async.AsyncRequestBody;
import software.amazon.awssdk.core.async.AsyncResponseTransformer;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.core.exception.RetryableException;
import software.amazon.awssdk.core.interceptor.*;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.model.*;

import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Vendor-behavior tripwires for the S3 upload path (evidence:
 * docs/evidence/spike-results.md Q5–Q7 — observed SDK behavior, not an API
 * guarantee; this test is the regression tripwire the evidence file promised).
 *
 * <p>Shares the suite-owned SeaweedFS object store. Every key lives under
 * {@code contract/replay-<runId>/}; teardown removes only owned objects and aborts
 * owned incomplete multipart uploads. It touches no other prefix.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class S3MultipartReplayContractTest {

    private static final DataBufferFactory FACTORY = new DefaultDataBufferFactory();
    private static final int CHUNK = 64 * 1024;
    private static final long PART = 5L * 1024 * 1024;      // SDK minimum part size
    private static final int ROWS = (int) (13L * 1024 * 1024 / 128); // forces >= 3 parts
    private static final long EXPECTED_BYTES = ROWS * 128L;
    private static final String KEY_PREFIX = "contract/replay-";

    private final List<S3AsyncClient> clients = new ArrayList<>();
    private final List<String> objectKeys = new ArrayList<>();
    private ExecutorService exec;
    private String runId;

    @BeforeEach void setUp() {
        runId = UUID.randomUUID().toString().substring(0, 8);
        exec = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "contract-export-scheduler");
            t.setDaemon(true);
            return t;
        });
    }

    @AfterEach void tearDown() {
        // Every step is best-effort: assertions failing must not strand
        // objects, uploads, threads, or clients.
        abortOwnedUploadsByKeyPrefix();
        for (var key : objectKeys) {
            for (var s3 : clients) {
                try { s3.deleteObject(d -> d.bucket(Containers.BUCKET).key(key)).join(); }
                catch (Exception alreadyGone) { }
            }
        }
        for (var s3 : clients) s3.close();
        if (exec != null) exec.shutdownNow();
        clients.clear();
        objectKeys.clear();
    }

    private void abortOwnedUploadsByKeyPrefix() {
        for (var s3 : clients) {
            try {
                var mpus = s3.listMultipartUploads(r -> r.bucket(Containers.BUCKET)).join();
                mpus.uploads().stream()
                        .filter(u -> u.key().startsWith(KEY_PREFIX))
                        .forEach(u -> s3.abortMultipartUpload(a -> a.bucket(Containers.BUCKET)
                                .key(u.key()).uploadId(u.uploadId())).join());
            } catch (Exception bestEffort) { }
        }
    }

    private S3AsyncClient newClient(ExecutionInterceptor... interceptors) {
        var ovr = ClientOverrideConfiguration.builder();
        for (var i : interceptors) ovr.addExecutionInterceptor(i);
        var client = S3AsyncClient.builder()
                .endpointOverride(URI.create(Containers.s3Endpoint()))
                .region(Region.US_EAST_1)
                .forcePathStyle(true)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(Containers.s3AccessKey(),
                                                   Containers.s3SecretKey())))
                .multipartEnabled(true)
                .multipartConfiguration(c -> c
                        .minimumPartSizeInBytes(PART)
                        .thresholdInBytes(PART))
                .overrideConfiguration(ovr.build())
                .build();
        clients.add(client);
        return client;
    }

    /** Deterministic 128-byte rows; the writer re-runs per subscription (the real export's shape). */
    private Flux<ByteBuffer> body(AtomicInteger subscribeCount,
                                  AtomicLong bytesProduced, MessageDigest producedDigest) {
        return Flux.defer(() -> {
            subscribeCount.incrementAndGet();
            Flux<byte[]> rows = Flux.range(0, ROWS)
                    .map(i -> {
                        var sb = new StringBuilder(128);
                        sb.append("row-").append(i).append(',');
                        while (sb.length() < 127) sb.append('x');
                        sb.append('\n');
                        return sb.toString().getBytes(StandardCharsets.UTF_8);
                    });
            return Flux.from(DataBufferUtils.outputStreamPublisher(
                    out -> {
                        try (Stream<byte[]> s = rows.toStream(1)) {
                            s.forEach(b -> {
                                try {
                                    out.write(b);
                                    bytesProduced.addAndGet(b.length);
                                    producedDigest.update(b);
                                } catch (Exception e) { throw new RuntimeException(e); }
                            });
                        }
                    }, FACTORY, exec, CHUNK));
        }).map(db -> {
            ByteBuffer bb = db.toByteBuffer();      // deprecated + copies; that is what makes it safe
            DataBufferUtils.release(db);
            return bb;
        });
    }

    private void assertDownloadIsByteExact(S3AsyncClient s3, String key, MessageDigest producedDigest)
            throws Exception {
        var downloaded = s3.getObject(g -> g.bucket(Containers.BUCKET).key(key),
                AsyncResponseTransformer.toBytes()).join();
        var digest = MessageDigest.getInstance("SHA-256");
        digest.update(downloaded.asByteArray());
        assertEquals(hex(producedDigest.digest()), hex(digest.digest()),
                "downloaded object must be byte-exact against the produced fixture");
    }

    private static String hex(byte[] bytes) {
        var value = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) value.append(String.format(Locale.ROOT, "%02x", b));
        return value.toString();
    }

    // -------------------------------------------------------- Q5 + Q6
    @Test @Order(1)
    void unknownLengthMultipartUploadsByteExactlyWithASingleSubscription() throws Exception {
        var s3 = newClient();
        var subs = new AtomicInteger();
        var produced = new AtomicLong();
        var producedDigest = MessageDigest.getInstance("SHA-256");

        var body = AsyncRequestBody.fromPublisher(body(subs, produced, producedDigest));
        String key = KEY_PREFIX + runId + "-clean.csv";
        objectKeys.add(key);
        s3.putObject(r -> r.bucket(Containers.BUCKET).key(key), body).get(120, TimeUnit.SECONDS);

        long stored = s3.headObject(h -> h.bucket(Containers.BUCKET).key(key))
                .get(30, TimeUnit.SECONDS).contentLength();

        assertEquals(EXPECTED_BYTES, produced.get(), "writer must produce the full fixture");
        assertEquals(produced.get(), stored, "stored object size must match produced size");
        assertEquals(1, subs.get(), "clean upload must subscribe to the source exactly once");
        assertDownloadIsByteExact(s3, key, producedDigest);
    }

    // ----------------------------------------------------------------- Q7
    @Test @Order(2)
    void anInjectedUploadPartRetryBuffersInsteadOfResubscribing() throws Exception {
        var failedOnce = new AtomicBoolean();
        var uploadPartAttempts = new AtomicInteger();

        ExecutionInterceptor failFirstPart = new ExecutionInterceptor() {
            @Override
            public void beforeTransmission(Context.BeforeTransmission ctx, ExecutionAttributes attrs) {
                if (ctx.request() instanceof UploadPartRequest) {
                    uploadPartAttempts.incrementAndGet();
                    if (uploadPartAttempts.get() <= 2 && failedOnce.compareAndSet(false, true)) {
                        throw RetryableException.create("INJECTED transient UploadPart failure");
                    }
                }
            }
        };

        var s3 = newClient(failFirstPart);
        var subs = new AtomicInteger();
        var produced = new AtomicLong();
        var producedDigest = MessageDigest.getInstance("SHA-256");

        var body = AsyncRequestBody.fromPublisher(body(subs, produced, producedDigest));
        String key = KEY_PREFIX + runId + "-retried.csv";
        objectKeys.add(key);
        s3.putObject(r -> r.bucket(Containers.BUCKET).key(key), body).get(120, TimeUnit.SECONDS);

        // Measured verdict (evidence Q7): the SDK buffers each part and retries
        // from that buffer — 3 parts + 1 retry, ONE source subscription.
        assertEquals(4, uploadPartAttempts.get(), "expected 3 parts + 1 retried attempt");
        assertEquals(1, subs.get(),
                "the fromPublisher source must NOT be resubscribed on a part retry");
        assertDownloadIsByteExact(s3, key, producedDigest);
    }
}
