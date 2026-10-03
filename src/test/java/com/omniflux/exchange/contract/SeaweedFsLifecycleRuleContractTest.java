package com.omniflux.exchange.contract;

import com.omniflux.exchange.Containers;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.*;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Local object-store contract check. SeaweedFS accepts both incomplete
 * multipart cleanup and ordinary object-expiry lifecycle rules.
 *
 * <p>This validates that the local dev service accepts the rules; the cloud
 * COS lifecycle behavior must still be verified in its target environment.
 */
class SeaweedFsLifecycleRuleContractTest {

    @Test
    void localObjectStoreAcceptsMultipartAbortAndObjectExpiryRules() {
        String bucket = "contract-lifecycle-" + UUID.randomUUID().toString().substring(0, 8);
        try (S3Client s3 = S3Client.builder()
                .endpointOverride(URI.create(Containers.s3Endpoint()))
                .region(Region.US_EAST_1)
                .forcePathStyle(true)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(Containers.s3AccessKey(),
                                                   Containers.s3SecretKey())))
                .build()) {
            s3.createBucket(b -> b.bucket(bucket));
            try {
                var abortRule = LifecycleRule.builder()
                        .id("abort-incomplete-multipart")
                        .status(ExpirationStatus.ENABLED)
                        .filter(LifecycleRuleFilter.builder().prefix("exports/").build())
                        .abortIncompleteMultipartUpload(
                                AbortIncompleteMultipartUpload.builder()
                                        .daysAfterInitiation(1).build())
                        .build();

                var expiryRule = LifecycleRule.builder()
                        .id("expire-objects")
                        .status(ExpirationStatus.ENABLED)
                        .filter(LifecycleRuleFilter.builder().prefix("exports/").build())
                        .expiration(LifecycleExpiration.builder().days(1).build())
                        .build();
                s3.putBucketLifecycleConfiguration(r -> r.bucket(bucket)
                        .lifecycleConfiguration(c -> c.rules(List.of(abortRule, expiryRule))));
                var readBack = s3.getBucketLifecycleConfiguration(r -> r.bucket(bucket));
                assertTrue(readBack.rules().stream()
                                .anyMatch(r -> "expire-objects".equals(r.id())),
                        "object-expiry rule must be accepted and readable");
                assertTrue(readBack.rules().stream()
                                .anyMatch(r -> "abort-incomplete-multipart".equals(r.id())),
                        "multipart cleanup rule must be accepted and readable");
            } finally {
                try { s3.deleteBucket(b -> b.bucket(bucket)); }
                catch (Exception bestEffort) { /* teardown is best-effort */ }
            }
        }
    }
}
