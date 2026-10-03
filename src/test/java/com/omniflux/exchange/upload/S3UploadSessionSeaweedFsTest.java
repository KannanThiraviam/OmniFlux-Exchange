package com.omniflux.exchange.upload;

import com.omniflux.exchange.S3TestBase;
import com.omniflux.exchange.config.OmnifluxProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import reactor.core.publisher.Flux;
import software.amazon.awssdk.core.async.AsyncResponseTransformer;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** Exercises unknown-length multipart publishing against the suite-owned SeaweedFS service. */
@SpringBootTest
class S3UploadSessionSeaweedFsTest extends S3TestBase {
    @Autowired
    S3AsyncClient storage;

    @Autowired
    OmnifluxProperties properties;

    @Test
    void unknownLengthPublisherCompletesAndTheObjectCanBeReadBack() throws Exception {
        String key = "tests/upload-session-" + UUID.randomUUID() + ".csv";
        byte[] expected = "id,name\n1,Ada\n".getBytes(StandardCharsets.UTF_8);
        var session = new S3UploadSession(storage, properties.storage().bucket(), key);
        try {
            session.start(Flux.just(ByteBuffer.wrap(expected)), "text/csv; charset=utf-8");
            var result = session.completion().block();
            assertNotNull(result);
            byte[] actual = storage.getObject(GetObjectRequest.builder()
                            .bucket(properties.storage().bucket()).key(key).build(),
                    AsyncResponseTransformer.toBytes())
                    .join().asByteArray();
            assertEquals(key, result.key());
            assertEquals(expected.length, result.bytes());
            assertEquals(hex(MessageDigest.getInstance("SHA-256").digest(expected)), result.sha256());
            assertEquals(expected.length, actual.length);
            assertEquals(new String(expected, StandardCharsets.UTF_8),
                    new String(actual, StandardCharsets.UTF_8));
        } finally {
            session.deleteCompleted().block();
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder value = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) value.append(String.format(java.util.Locale.ROOT, "%02x", b));
        return value.toString();
    }
}
