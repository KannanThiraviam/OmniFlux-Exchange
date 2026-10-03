package com.omniflux.exchange.job;

import java.time.Instant;
import java.util.UUID;

/** Durable lifecycle record for one fenced worker attempt. */
public record JobAttempt(int attemptNo, UUID claimToken, String workerId, String status,
                         Instant startedAt, Instant finishedAt, String objectKey,
                         long rowCount, long byteCount, String contentSha256,
                         String timingJson, String errorCode, String errorMessage) { }
