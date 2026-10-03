package com.omniflux.exchange.job;

import com.omniflux.exchange.upload.UploadResult;
import com.omniflux.exchange.upload.UploadSession;
import reactor.core.publisher.Mono;

import java.util.Objects;

/** Result of one attempt, including the true number of rows scanned. */
public record JobResult(
        String objectKey,
        long rowCount,
        long byteCount,
        String contentSha256,
        UploadSession uploadSession,
        Long highWaterKey) {

    public JobResult {
        if (objectKey == null || objectKey.isBlank()) {
            throw new IllegalArgumentException("objectKey must not be blank");
        }
        if (rowCount < 0 || byteCount < 0) {
            throw new IllegalArgumentException("rowCount and byteCount must not be negative");
        }
    }

    public static JobResult from(UploadResult upload, long rowCount,
                                 Long highWaterKey, UploadSession session) {
        Objects.requireNonNull(upload, "upload");
        return new JobResult(upload.key(), rowCount, Math.max(0, upload.bytes()),
                upload.sha256(), session, highWaterKey);
    }

    /** Deletes an already completed object when the final database fence is lost. */
    public Mono<Void> deleteCompleted() {
        return uploadSession == null ? Mono.empty() : uploadSession.deleteCompleted();
    }
}
