package com.omniflux.exchange.presign;

import com.omniflux.exchange.config.OmnifluxProperties;
import com.omniflux.exchange.job.ErrorCode;
import com.omniflux.exchange.job.ExportException;
import com.omniflux.exchange.job.JobRepository;
import com.omniflux.exchange.job.TransferJob;
import com.omniflux.exchange.security.AuthContext;
import com.omniflux.exchange.security.PrincipalKey;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

import java.net.URI;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Creates owner-scoped download URLs. Presigning is local HMAC work: it never
 * performs a request to object storage. The repository operation that records
 * issuance is deliberately separate and must be an atomic owner-scoped update.
 */
@Service
public final class PresignService {
    private final S3Presigner signer;
    private final JobAccess jobs;
    private final OmnifluxProperties properties;

    public PresignService(S3Presigner signer, JobAccess jobs,
                          OmnifluxProperties properties) {
        this.signer = signer;
        this.jobs = jobs;
        this.properties = properties;
    }

    @Autowired
    public PresignService(S3Presigner signer, JobRepository repository,
                          OmnifluxProperties properties) {
        this(signer, new RepositoryJobAccess(repository), properties);
    }

    public Mono<PresignedDownload> presign(UUID jobId, AuthContext auth, String clientIp) {
        if (auth == null) {
            return Mono.error(new ExportException(ErrorCode.PRINCIPAL_UNRESOLVED,
                    "an authenticated principal is required"));
        }
        return presign(jobId, auth.key(), clientIp);
    }

    public Mono<PresignedDownload> presign(UUID jobId, PrincipalKey owner, String clientIp) {
        if (jobId == null || owner == null) {
            return Mono.error(jobNotFound());
        }
        return jobs.findCompletedOwned(jobId, owner)
                .flatMap(found -> found.map(job -> issue(jobId, owner, job, clientIp))
                        .orElseGet(() -> Mono.error(jobNotFound())))
                .onErrorMap(error -> error instanceof ExportException
                        ? error
                        : new ExportException(ErrorCode.UPSTREAM_UNAVAILABLE,
                                "could not resolve completed job", error));
    }

    private Mono<PresignedDownload> issue(UUID jobId, PrincipalKey owner,
                                          CompletedJob job, String clientIp) {
        return Mono.defer(() -> {
            String filename = safeFilename(job.filename(), job.format());
            String contentType = safeContentType(job.contentType(), job.format());
            Instant issuedAt = Instant.now();
            Instant expiresAt = issuedAt.plus(properties.storage().presignTtl());

            GetObjectPresignRequest request = GetObjectPresignRequest.builder()
                    .signatureDuration(properties.storage().presignTtl())
                    .getObjectRequest(get -> get.bucket(properties.storage().bucket())
                            .key(job.objectKey())
                            .responseContentDisposition("attachment; filename=\"" + filename + "\"")
                            .responseContentType(contentType))
                    .build();
            URI url = URI.create(signer.presignGetObject(request).url().toString());

            return jobs.recordPresign(jobId, owner, clientIp, issuedAt)
                    .flatMap(recorded -> Boolean.TRUE.equals(recorded)
                            ? Mono.just(new PresignedDownload(url, issuedAt, expiresAt,
                                    contentType, filename))
                            : Mono.error(jobNotFound()));
        });
    }

    private static String safeFilename(String filename, String format) {
        String fallback = "export." + ("XLSX".equalsIgnoreCase(format) ? "xlsx" : "csv");
        if (filename == null || filename.isBlank()) return fallback;
        String clean = filename.replaceAll("[\\r\\n\\\"\\\\]", "_").trim();
        return clean.isBlank() ? fallback : clean;
    }

    private static String safeContentType(String contentType, String format) {
        String fallback = "XLSX".equalsIgnoreCase(format)
                ? "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                : "text/csv; charset=utf-8";
        if (contentType == null || contentType.isBlank()
                || contentType.indexOf('\r') >= 0 || contentType.indexOf('\n') >= 0) {
            return fallback;
        }
        return contentType;
    }

    private static ExportException jobNotFound() {
        return new ExportException(ErrorCode.JOB_NOT_FOUND, "job not found");
    }

    public interface JobAccess {
        Mono<Optional<CompletedJob>> findCompletedOwned(UUID jobId, PrincipalKey owner);

        /** Returns false when the owner-scoped update affected zero rows. */
        Mono<Boolean> recordPresign(UUID jobId, PrincipalKey owner, String clientIp,
                                    Instant issuedAt);
    }

    public record CompletedJob(String objectKey, String filename, String contentType,
                               String format) {
        public CompletedJob {
            if (objectKey == null || objectKey.isBlank()) {
                throw new IllegalArgumentException("objectKey must not be blank");
            }
        }
    }

    public record PresignedDownload(URI url, Instant issuedAt, Instant expiresAt,
                                    String contentType, String filename) { }

    private record RepositoryJobAccess(JobRepository repository) implements JobAccess {

        @Override
        public Mono<Optional<CompletedJob>> findCompletedOwned(UUID jobId, PrincipalKey owner) {
            return repository.findOwned(jobId, owner)
                    .map(job -> job.status().name().equals("COMPLETED") && job.objectKey() != null
                            ? Optional.of(completed(job)) : Optional.<CompletedJob>empty())
                    .onErrorResume(ExportException.class, error ->
                            error.code() == ErrorCode.JOB_NOT_FOUND
                                    ? Mono.just(Optional.empty()) : Mono.error(error));
        }

        @Override
        public Mono<Boolean> recordPresign(UUID jobId, PrincipalKey owner, String clientIp,
                                           Instant issuedAt) {
            return repository.recordPresign(jobId, owner, clientIp)
                    .map(ignored -> true)
                    .onErrorResume(ExportException.class, error ->
                            error.code() == ErrorCode.JOB_NOT_FOUND
                                    ? Mono.just(false) : Mono.error(error));
        }

        private static CompletedJob completed(TransferJob job) {
            String format = job.format();
            String extension = "XLSX".equalsIgnoreCase(format) ? "xlsx" : "csv";
            String contentType = "XLSX".equalsIgnoreCase(format)
                    ? "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                    : "text/csv; charset=utf-8";
            return new CompletedJob(job.objectKey(), job.relationName() + "." + extension,
                    contentType, format);
        }
    }
}
