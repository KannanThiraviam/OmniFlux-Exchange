package com.omniflux.exchange.job;

import com.omniflux.exchange.security.PrincipalKey;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable representation of one row in {@code transfer_jobs}.
 *
 * <p>The authorization snapshot is deliberately separate from {@link PrincipalKey}:
 * roles and the authorization-context version describe what was allowed at
 * submission time, while the principal remains the ownership key for the row.</p>
 */
public record TransferJob(UUID id, String direction, JobStatus status, PrincipalKey owner, List<String> roles,
                          String authzContextVersion, String clientIp, String idempotencyKey, String relationName,
                          String columnsJson, String filtersJson, String format, String csvMode,
                          Integer csvDialectVersion, String objectKey, long rowCount, long byteCount,
                          String contentSha256, String timingJson, Long lastKey, Long highWaterKey, int attemptCount,
                          String workerId, UUID claimToken, Instant leaseUntil, boolean cancelRequested,
                          String requestFingerprint, Instant generatedAt, UUID cacheHitOf, int presignCount,
                          Instant lastPresignAt, String lastPresignIp, ErrorClass errorClass, ErrorCode errorCode,
                          String errorMessage, Instant createdAt, Instant finishedAt) {
    public static final String EXPORT_DIRECTION = "EXPORT";

    /**
     * Full schema-shaped constructor, useful for database adapters and tests.
     */
    public TransferJob(
            UUID id,
            String direction,
            JobStatus status,
            PrincipalKey owner,
            List<String> roles,
            String authzContextVersion,
            String clientIp,
            String idempotencyKey,
            String relationName,
            String columnsJson,
            String filtersJson,
            String format,
            String csvMode,
            Integer csvDialectVersion,
            String objectKey,
            long rowCount,
            long byteCount,
            String contentSha256,
            String timingJson,
            Long lastKey,
            Long highWaterKey,
            int attemptCount,
            String workerId,
            UUID claimToken,
            Instant leaseUntil,
            boolean cancelRequested,
            String requestFingerprint,
            Instant generatedAt,
            UUID cacheHitOf,
            int presignCount,
            Instant lastPresignAt,
            String lastPresignIp,
            ErrorClass errorClass,
            ErrorCode errorCode,
            String errorMessage,
            Instant createdAt,
            Instant finishedAt) {
        this.id = id;
        this.direction = requireText(direction, "direction");
        this.status = Objects.requireNonNull(status, "status");
        this.owner = Objects.requireNonNull(owner, "owner");
        this.roles = List.copyOf(roles == null ? List.of() : roles);
        this.authzContextVersion = authzContextVersion;
        this.clientIp = clientIp;
        this.idempotencyKey = idempotencyKey;
        this.relationName = requireText(relationName, "relationName");
        this.columnsJson = columnsJson;
        this.filtersJson = filtersJson;
        this.format = requireText(format, "format");
        this.csvMode = csvMode;
        this.csvDialectVersion = csvDialectVersion;
        this.objectKey = objectKey;
        this.rowCount = nonNegative(rowCount, "rowCount");
        this.byteCount = nonNegative(byteCount, "byteCount");
        this.contentSha256 = contentSha256;
        this.timingJson = timingJson;
        this.lastKey = lastKey;
        this.highWaterKey = highWaterKey;
        this.attemptCount = Math.toIntExact(nonNegative(attemptCount, "attemptCount"));
        this.workerId = workerId;
        this.claimToken = claimToken;
        this.leaseUntil = leaseUntil;
        this.cancelRequested = cancelRequested;
        this.requestFingerprint = requestFingerprint;
        this.generatedAt = generatedAt;
        this.cacheHitOf = cacheHitOf;
        this.presignCount = Math.toIntExact(nonNegative(presignCount, "presignCount"));
        this.lastPresignAt = lastPresignAt;
        this.lastPresignIp = lastPresignIp;
        this.errorClass = errorClass;
        this.errorCode = errorCode;
        this.errorMessage = errorMessage;
        this.createdAt = createdAt;
        this.finishedAt = finishedAt;
    }

    /**
     * Small constructor for queue and worker unit tests.
     */
    public TransferJob(UUID id, JobStatus status, PrincipalKey owner,
                       String relationName, String format, Instant createdAt) {
        this(id, EXPORT_DIRECTION, status, owner, List.of(), null, null, null,
                relationName, null, null, format, null, null, null,
                0, 0, null, null, null, null, 0, null, null, null, false,
                null, null, null, 0, null, null, null, null, null,
                createdAt, null);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static TransferJob queued(PrincipalKey owner, String relationName, String format) {
        return builder().owner(owner).relationName(relationName).format(format).build();
    }

    public String requestedBy() {
        return owner.subject();
    }

    public String issuer() {
        return owner.issuer();
    }

    public boolean terminal() {
        return status.terminal();
    }

    public String attemptObjectKey(String prefix, String extension) {
        String cleanPrefix = prefix == null ? "" : prefix;
        String cleanExtension = extension == null || extension.isBlank()
                ? "bin" : extension.replaceFirst("^\\.", "");
        return cleanPrefix + id + "/a" + Math.max(1, attemptCount)
                + "/data." + cleanExtension;
    }

    public Builder toBuilder() {
        return new Builder(this);
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static long nonNegative(long value, String name) {
        if (value < 0) throw new IllegalArgumentException(name + " must not be negative");
        return value;
    }

    public static final class Builder {
        private UUID id;
        private String direction = EXPORT_DIRECTION;
        private JobStatus status = JobStatus.QUEUED;
        private PrincipalKey owner;
        private List<String> roles = List.of();
        private String authzContextVersion;
        private String clientIp;
        private String idempotencyKey;
        private String relationName;
        private String columnsJson;
        private String filtersJson;
        private String format = "CSV";
        private String csvMode;
        private Integer csvDialectVersion;
        private String objectKey;
        private long rowCount;
        private long byteCount;
        private String contentSha256;
        private String timingJson;
        private Long lastKey;
        private Long highWaterKey;
        private int attemptCount;
        private String workerId;
        private UUID claimToken;
        private Instant leaseUntil;
        private boolean cancelRequested;
        private String requestFingerprint;
        private Instant generatedAt;
        private UUID cacheHitOf;
        private int presignCount;
        private Instant lastPresignAt;
        private String lastPresignIp;
        private ErrorClass errorClass;
        private ErrorCode errorCode;
        private String errorMessage;
        private Instant createdAt;
        private Instant finishedAt;

        public Builder() {
        }

        private Builder(TransferJob job) {
            id = job.id;
            direction = job.direction;
            status = job.status;
            owner = job.owner;
            roles = job.roles;
            authzContextVersion = job.authzContextVersion;
            clientIp = job.clientIp;
            idempotencyKey = job.idempotencyKey;
            relationName = job.relationName;
            columnsJson = job.columnsJson;
            filtersJson = job.filtersJson;
            format = job.format;
            csvMode = job.csvMode;
            csvDialectVersion = job.csvDialectVersion;
            objectKey = job.objectKey;
            rowCount = job.rowCount;
            byteCount = job.byteCount;
            contentSha256 = job.contentSha256;
            timingJson = job.timingJson;
            lastKey = job.lastKey;
            highWaterKey = job.highWaterKey;
            attemptCount = job.attemptCount;
            workerId = job.workerId;
            claimToken = job.claimToken;
            leaseUntil = job.leaseUntil;
            cancelRequested = job.cancelRequested;
            requestFingerprint = job.requestFingerprint;
            generatedAt = job.generatedAt;
            cacheHitOf = job.cacheHitOf;
            presignCount = job.presignCount;
            lastPresignAt = job.lastPresignAt;
            lastPresignIp = job.lastPresignIp;
            errorClass = job.errorClass;
            errorCode = job.errorCode;
            errorMessage = job.errorMessage;
            createdAt = job.createdAt;
            finishedAt = job.finishedAt;
        }

        public Builder id(UUID value) {
            id = value;
            return this;
        }

        public Builder status(JobStatus value) {
            status = value;
            return this;
        }

        public Builder owner(PrincipalKey value) {
            owner = value;
            return this;
        }

        public Builder roles(List<String> value) {
            roles = value;
            return this;
        }

        public Builder authzContextVersion(String value) {
            authzContextVersion = value;
            return this;
        }

        public Builder clientIp(String value) {
            clientIp = value;
            return this;
        }

        public Builder idempotencyKey(String value) {
            idempotencyKey = value;
            return this;
        }

        public Builder relationName(String value) {
            relationName = value;
            return this;
        }

        public Builder columnsJson(String value) {
            columnsJson = value;
            return this;
        }

        public Builder filtersJson(String value) {
            filtersJson = value;
            return this;
        }

        public Builder format(String value) {
            format = value;
            return this;
        }

        public Builder csvMode(String value) {
            csvMode = value;
            return this;
        }

        public Builder csvDialectVersion(Integer value) {
            csvDialectVersion = value;
            return this;
        }

        public Builder objectKey(String value) {
            objectKey = value;
            return this;
        }

        public Builder rowCount(long value) {
            rowCount = value;
            return this;
        }

        public Builder byteCount(long value) {
            byteCount = value;
            return this;
        }

        public Builder contentSha256(String value) {
            contentSha256 = value;
            return this;
        }

        public Builder timingJson(String value) {
            timingJson = value;
            return this;
        }

        public Builder highWaterKey(Long value) {
            highWaterKey = value;
            return this;
        }

        public Builder attemptCount(int value) {
            attemptCount = value;
            return this;
        }

        public Builder claimToken(UUID value) {
            claimToken = value;
            return this;
        }

        public Builder requestFingerprint(String value) {
            requestFingerprint = value;
            return this;
        }

        public Builder generatedAt(Instant value) {
            generatedAt = value;
            return this;
        }

        public Builder cacheHitOf(UUID value) {
            cacheHitOf = value;
            return this;
        }

        public Builder errorMessage(String value) {
            errorMessage = value;
            return this;
        }

        public Builder createdAt(Instant value) {
            createdAt = value;
            return this;
        }

        public Builder finishedAt(Instant value) {
            finishedAt = value;
            return this;
        }

        public TransferJob build() {
            return new TransferJob(id, direction, status, owner, roles, authzContextVersion,
                    clientIp, idempotencyKey, relationName, columnsJson, filtersJson, format,
                    csvMode, csvDialectVersion, objectKey, rowCount, byteCount, contentSha256,
                    timingJson,
                    lastKey, highWaterKey, attemptCount, workerId, claimToken, leaseUntil,
                    cancelRequested, requestFingerprint, generatedAt, cacheHitOf, presignCount,
                    lastPresignAt, lastPresignIp, errorClass, errorCode, errorMessage,
                    createdAt, finishedAt);
        }
    }
}
