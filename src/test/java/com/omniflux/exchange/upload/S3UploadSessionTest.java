package com.omniflux.exchange.upload;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;
import software.amazon.awssdk.core.async.AsyncRequestBody;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.model.*;

import java.nio.ByteBuffer;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class S3UploadSessionTest {
    @Test
    void startInstallsTheSingleSubscriptionGuardAndCompletesFromS3() {
        var client = Mockito.mock(S3AsyncClient.class);
        when(client.createMultipartUpload(any(CreateMultipartUploadRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(CreateMultipartUploadResponse.builder()
                        .uploadId("upload").build()));
        when(client.uploadPart(any(UploadPartRequest.class), any(AsyncRequestBody.class)))
                .thenReturn(CompletableFuture.completedFuture(UploadPartResponse.builder()
                        .eTag("etag").build()));
        when(client.completeMultipartUpload(any(CompleteMultipartUploadRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(CompleteMultipartUploadResponse.builder()
                        .eTag("etag").build()));
        var session = new S3UploadSession(client, "bucket", "key.csv");
        var body = Flux.just(ByteBuffer.wrap(new byte[]{1, 2}));

        assertThrows(IllegalStateException.class, session::body);
        session.start(body, "text/csv");
        assertInstanceOf(SingleSubscriptionPublisher.class, session.body());
        StepVerifier.create(session.completion())
                .expectNextMatches(result -> result.key().equals("key.csv")
                        && result.bytes() == 2L && result.etag().equals("etag")
                        && result.sha256() != null && result.sha256().matches("[0-9a-f]{64}"))
                .verifyComplete();
        assertThrows(IllegalStateException.class,
                () -> session.start(body, "text/csv"));
    }

    @Test
    void abortIsIdempotentAndDeleteCompletedDeletesTheObject() {
        var client = Mockito.mock(S3AsyncClient.class);
        when(client.createMultipartUpload(any(CreateMultipartUploadRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(CreateMultipartUploadResponse.builder()
                        .uploadId("upload").build()));
        when(client.abortMultipartUpload(any(AbortMultipartUploadRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(AbortMultipartUploadResponse.builder().build()));
        when(client.deleteObject(any(software.amazon.awssdk.services.s3.model.DeleteObjectRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(DeleteObjectResponse.builder().build()));
        var session = new S3UploadSession(client, "bucket", "key.csv");
        session.start(Flux.never(), "text/csv");
        session.abort();
        session.abort();
        session.deleteCompleted().block();
        verify(client).deleteObject(any(software.amazon.awssdk.services.s3.model.DeleteObjectRequest.class));
    }

    @Test
    void failedPartAbortsTheMultipartUpload() {
        var client = Mockito.mock(S3AsyncClient.class);
        when(client.createMultipartUpload(any(CreateMultipartUploadRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(CreateMultipartUploadResponse.builder()
                        .uploadId("failed-upload").build()));
        var failedPart = new CompletableFuture<UploadPartResponse>();
        failedPart.completeExceptionally(new IllegalStateException("COS rejected part"));
        when(client.uploadPart(any(UploadPartRequest.class), any(AsyncRequestBody.class)))
                .thenReturn(failedPart);
        when(client.abortMultipartUpload(any(AbortMultipartUploadRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(AbortMultipartUploadResponse.builder().build()));

        var session = new S3UploadSession(client, "bucket", "key.csv");
        session.start(Flux.just(ByteBuffer.wrap(new byte[]{1, 2})), "text/csv");

        StepVerifier.create(session.completion())
                .expectErrorMatches(error -> error.getMessage().contains("COS rejected part"))
                .verify();
        verify(client).abortMultipartUpload(any(AbortMultipartUploadRequest.class));
    }

    @Test
    void cancellationCancelsThePublisherAndAbortsTheMultipartUpload() {
        var client = Mockito.mock(S3AsyncClient.class);
        when(client.createMultipartUpload(any(CreateMultipartUploadRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(CreateMultipartUploadResponse.builder()
                        .uploadId("cancelled-upload").build()));
        when(client.abortMultipartUpload(any(AbortMultipartUploadRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(AbortMultipartUploadResponse.builder().build()));

        var session = new S3UploadSession(client, "bucket", "key.csv");
        session.start(Flux.never(), "text/csv");
        session.abort();

        StepVerifier.create(session.completion())
                .expectError(CancellationException.class)
                .verify();
        verify(client).abortMultipartUpload(any(AbortMultipartUploadRequest.class));
    }

    @Test
    void invalidConstructionAndPrematureCompletionAreRejected() {
        var client = Mockito.mock(S3AsyncClient.class);
        assertThrows(IllegalArgumentException.class, () -> new S3UploadSession(client, "", "key"));
        assertThrows(IllegalArgumentException.class, () -> new S3UploadSession(client, "bucket", " "));
        var session = new S3UploadSession(client, "bucket", "key");
        StepVerifier.create(session.completion())
                .expectErrorMessage("upload has not started").verify();
    }
}
