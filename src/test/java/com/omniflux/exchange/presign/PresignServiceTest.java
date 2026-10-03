package com.omniflux.exchange.presign;

import com.omniflux.exchange.TestProps;
import com.omniflux.exchange.job.ErrorCode;
import com.omniflux.exchange.job.ExportException;
import com.omniflux.exchange.security.AuthContext;
import com.omniflux.exchange.security.PrincipalKey;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URI;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class PresignServiceTest {
    private static final PrincipalKey OWNER = new PrincipalKey("corp", "user", "tenant");

    @Test
    void presigningSignsResponseHeadersAndRecordsOneOwnerScopedIssuance() {
        var props = TestProps.defaults();
        var calls = new AtomicInteger();
        var access = new PresignService.JobAccess() {
            @Override
            public Mono<Optional<PresignService.CompletedJob>> findCompletedOwned(UUID id,
                                                                                   PrincipalKey owner) {
                return Mono.just(Optional.of(new PresignService.CompletedJob(
                        "exports/job/data.csv", "orders.csv", "text/csv; charset=utf-8", "CSV")));
            }

            @Override
            public Mono<Boolean> recordPresign(UUID id, PrincipalKey owner, String ip,
                                               Instant issuedAt) {
                calls.incrementAndGet();
                return Mono.just(owner.equals(OWNER));
            }
        };
        try (var signer = signer()) {
            var result = new PresignService(signer, access, props)
                    .presign(UUID.randomUUID(), new AuthContext(OWNER, java.util.List.of("ANALYST"), "v1"),
                            "127.0.0.1")
                    .block();

            assertNotNull(result);
            assertEquals(1, calls.get());
            assertEquals("orders.csv", result.filename());
            assertTrue(result.url().toString().contains("response-content-disposition"));
            assertTrue(result.url().toString().contains("response-content-type"));
        }
    }

    @Test
    void aForeignOrIncompleteJobIsReportedAsNotFound() {
        var access = new PresignService.JobAccess() {
            @Override
            public Mono<Optional<PresignService.CompletedJob>> findCompletedOwned(UUID id,
                                                                                   PrincipalKey owner) {
                return Mono.just(Optional.empty());
            }

            @Override
            public Mono<Boolean> recordPresign(UUID id, PrincipalKey owner, String ip,
                                               Instant issuedAt) {
                return Mono.just(true);
            }
        };
        try (var signer = signer()) {
            var error = assertThrows(ExportException.class, () ->
                    new PresignService(signer, access, TestProps.defaults())
                            .presign(UUID.randomUUID(), OWNER, null).block());
            assertEquals(ErrorCode.JOB_NOT_FOUND, error.code());
        }
    }

    private static S3Presigner signer() {
        return S3Presigner.builder()
                .endpointOverride(URI.create("http://localhost:9005"))
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create("access", "secret")))
                .build();
    }
}
