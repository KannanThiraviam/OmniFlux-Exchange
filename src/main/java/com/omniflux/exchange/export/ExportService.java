package com.omniflux.exchange.export;

import com.omniflux.exchange.config.OmnifluxProperties;
import com.omniflux.exchange.job.*;
import com.omniflux.exchange.meta.SchemaCatalog;
import com.omniflux.exchange.presign.PresignService;
import com.omniflux.exchange.security.AuthContext;
import com.omniflux.exchange.security.PrincipalKey;
import com.omniflux.exchange.source.ExportRequest;
import com.omniflux.exchange.source.FilterSpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Locale;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** HTTP-facing job orchestration over the repository's admission/fencing contract. */
@Service
public final class ExportService {
    private static final String PRINCIPAL_REQUIRED = "an authenticated principal is required";
    private static final String REQUEST_BODY_REQUIRED = "request body is required";
    private static final String REQUEST_CANNOT_ENCODE = "request cannot be encoded";
    private static final String JOB_NOT_FOUND_MSG = "job not found";
    private static final String RELATION_NOT_BLANK = "relation must not be blank";
    private static final String FORMAT_INVALID = "format must be CSV or XLSX";
    private static final String CSV_MODE_INVALID = "csvMode must be RAW or SPREADSHEET_SAFE";
    private static final String IDEMPOTENCY_TOO_LONG = "idempotencyKey is too long";
    private static final String RELATION_NOT_ALLOWLISTED = "relation is not allowlisted: ";
    private static final String TIMING_INVALID = "persisted timing is invalid";
    private static final String FORMAT_CSV = "CSV";
    private static final String FORMAT_XLSX = "XLSX";
    private static final String MODE_RAW = "RAW";
    private static final String MODE_SPREADSHEET_SAFE = "SPREADSHEET_SAFE";

    private final JobRepository jobs;
    private final PresignService presigns;
    private final OmnifluxProperties properties;
    private final ObjectMapper mapper;
    private final ExportCacheResolver cache;
    private final ExportTimingRegistry timings;
    private final SchemaCatalog catalog;

    public ExportService(JobRepository jobs, PresignService presigns,
                         OmnifluxProperties properties) {
        this(jobs, presigns, properties, new ObjectMapper(), null, new ExportTimingRegistry(), null);
    }

    public ExportService(JobRepository jobs, PresignService presigns,
                         OmnifluxProperties properties, ObjectMapper mapper) {
        this(jobs, presigns, properties, mapper, null, new ExportTimingRegistry(), null);
    }

    @Autowired
    public ExportService(JobRepository jobs, PresignService presigns,
                         OmnifluxProperties properties, ObjectMapper mapper,
                         ExportCacheResolver cache, ExportTimingRegistry timings,
                         SchemaCatalog catalog) {
        this.jobs = jobs;
        this.presigns = presigns;
        this.properties = properties;
        this.mapper = mapper;
        this.cache = cache;
        this.timings = timings;
        this.catalog = catalog;
    }

    public Mono<JobView> submit(AuthContext auth, SubmitCommand command, String clientIp) {
        if (auth == null) return Mono.error(new ExportException(
                ErrorCode.PRINCIPAL_UNRESOLVED, PRINCIPAL_REQUIRED));
        if (command == null) return Mono.error(new ExportException(
                ErrorCode.VALIDATION_ERROR, REQUEST_BODY_REQUIRED));
        final SubmitCommand normalized;
        try {
            normalized = command.normalized(properties);
        } catch (IllegalArgumentException e) {
            return Mono.error(new ExportException(ErrorCode.VALIDATION_ERROR, e.getMessage(), e));
        }
        try {
            if (!properties.security().allowedRelations().contains(normalized.relation())) {
                return Mono.error(new ExportException(ErrorCode.RELATION_NOT_ALLOWED,
                        RELATION_NOT_ALLOWLISTED + normalized.relation()));
            }
            var exportRequest = new ExportRequest(normalized.relation(), normalized.columns(), normalized.filters(),
                    normalized.format(), normalized.csvMode(), normalized.csvDialectVersion());
            String columns = mapper.writeValueAsString(normalized.columns());
            String filters = mapper.writeValueAsString(normalized.filters());
            TransferJob candidate = TransferJob.builder()
                    .id(UUID.randomUUID()).status(JobStatus.QUEUED).owner(auth.key())
                    .roles(auth.roles()).authzContextVersion(auth.authzContextVersion())
                    .clientIp(clientIp).idempotencyKey(normalized.idempotencyKey())
                    .relationName(normalized.relation()).columnsJson(columns).filtersJson(filters)
                    .format(normalized.format()).csvMode(normalized.csvMode())
                    .csvDialectVersion(normalized.csvDialectVersion())
                    .requestFingerprint(Fingerprint.of(auth, exportRequest, properties)).build();
            Mono<Optional<TransferJob>> cacheOrigin = cache == null
                    ? Mono.just(Optional.empty()) : cache.resolve(auth, exportRequest);
            Mono<Void> validateColumns = catalog == null ? Mono.empty()
                    : catalog.describe(properties.source().defaultAdapter(), normalized.relation(), auth)
                            .doOnNext(descriptor -> {
                                catalog.requireColumns(descriptor, normalized.columns());
                                catalog.requireFilters(descriptor, normalized.filters());
                            }).then();
            return validateColumns.then(cacheOrigin)
                    .flatMap(origin -> origin.map(found -> jobs.insertCacheHit(candidate, found))
                            .orElseGet(() -> Mono.defer(() -> jobs.submit(candidate))))
                    .map(ExportService::toView);
        } catch (RuntimeException e) {
            if (e instanceof ExportException export) return Mono.error(export);
            return Mono.error(new ExportException(ErrorCode.INTERNAL_ERROR,
                    REQUEST_CANNOT_ENCODE, e));
        }
    }

    /** Applies the HTTP idempotency key after request-body decoding. */
    public SubmitCommand withIdempotencyKey(SubmitCommand command, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) return command;
        return new SubmitCommand(command.relation(), command.columns(), command.filters(),
                command.format(), command.csvMode(), command.csvDialectVersion(), idempotencyKey);
    }

    public Mono<JobPage> list(AuthContext auth, int limit, String after) {
        if (auth == null) return Mono.error(new ExportException(
                ErrorCode.PRINCIPAL_UNRESOLVED, PRINCIPAL_REQUIRED));
        int bounded = Math.clamp(limit, 1, properties.ui().pageSize());
        Cursor cursor = Cursor.decode(after);
        return jobs.listOwned(auth.key(), cursor == null ? null : cursor.createdAt(),
                        cursor == null ? null : cursor.id(), bounded + 1)
                .collectList().map(rows -> {
                    boolean more = rows.size() > bounded;
                    List<TransferJob> page = more ? rows.subList(0, bounded) : rows;
                    String next = more && !page.isEmpty()
                            ? Cursor.encode(page.getLast().createdAt(), page.getLast().id()) : null;
                    return new JobPage(page.stream().map(ExportService::toView).toList(), next);
                });
    }

    public Mono<JobView> detail(UUID id, AuthContext auth) {
        return jobs.findOwned(id, requireAuth(auth).key()).map(ExportService::toView);
    }

    public Mono<PresignService.PresignedDownload> download(UUID id, AuthContext auth,
                                                             String clientIp) {
        return jobs.findOwned(id, requireAuth(auth).key())
                .filter(job -> job.status() == JobStatus.COMPLETED && job.objectKey() != null)
                .switchIfEmpty(Mono.error(jobNotFound()))
                .flatMap(ignored -> presigns.presign(id, auth, clientIp));
    }

    public Mono<JobView> retry(UUID id, AuthContext auth) {
        return jobs.retryOwned(id, requireAuth(auth).key()).map(ExportService::toView)
                .switchIfEmpty(Mono.error(jobNotFound()));
    }

    public Mono<JobView> cancel(UUID id, AuthContext auth) {
        PrincipalKey owner = requireAuth(auth).key();
        return jobs.cancel(id, owner)
                .flatMap(changed -> Boolean.TRUE.equals(changed) ? jobs.findOwned(id, owner)
                        : Mono.error(jobNotFound())).map(ExportService::toView);
    }

    public Mono<ExportTiming.Snapshot> timing(UUID id, AuthContext auth) {
        PrincipalKey owner = requireAuth(auth).key();
        return jobs.findOwned(id, owner)
                .flatMap(job -> {
                    ExportTiming.Snapshot local = timings.find(id);
                    if (local != null) return Mono.just(local);
                    if (job.timingJson() == null || job.timingJson().isBlank()) return Mono.empty();
                    try {
                        return Mono.just(mapper.readValue(job.timingJson(), ExportTiming.Snapshot.class));
                    } catch (RuntimeException error) {
                        return Mono.error(new ExportException(ErrorCode.INTERNAL_ERROR,
                                TIMING_INVALID, error));
                    }
                })
                .switchIfEmpty(Mono.error(jobNotFound()));
    }

    public Mono<List<JobAttempt>> attempts(UUID id, AuthContext auth) {
        PrincipalKey owner = requireAuth(auth).key();
        return jobs.findOwned(id, owner)
                .then(jobs.listAttempts(id, owner).collectList())
                .switchIfEmpty(Mono.error(jobNotFound()));
    }

    private static AuthContext requireAuth(AuthContext auth) {
        if (auth == null) throw new ExportException(ErrorCode.PRINCIPAL_UNRESOLVED, PRINCIPAL_REQUIRED);
        return auth;
    }

    private static JobView toView(TransferJob job) {
        return new JobView(job.id(), job.status().name(), job.relationName(), job.format(),
                job.rowCount(), job.byteCount(), job.contentSha256(), job.objectKey(), job.attemptCount(),
                job.createdAt(), job.finishedAt(),
                job.errorCode() == null ? null : job.errorCode().name(), job.errorMessage(), null,
                job.cacheHitOf() != null);
    }

    private static ExportException jobNotFound() {
        return new ExportException(ErrorCode.JOB_NOT_FOUND, JOB_NOT_FOUND_MSG);
    }

    public record SubmitCommand(String relation, List<String> columns, List<FilterSpec> filters,
                                String format, String csvMode, Integer csvDialectVersion,
                                String idempotencyKey) {
        public SubmitCommand {
            columns = List.copyOf(columns == null ? List.of() : columns);
            filters = List.copyOf(filters == null ? List.of() : filters);
        }

        public SubmitCommand normalized(OmnifluxProperties p) {
            if (relation == null || relation.isBlank()) throw new IllegalArgumentException(RELATION_NOT_BLANK);
            String selectedFormat = format == null || format.isBlank()
                    ? p.export().defaultFormat() : format.toUpperCase(Locale.ROOT);
            if (!selectedFormat.equals(FORMAT_CSV) && !selectedFormat.equals(FORMAT_XLSX)) {
                throw new IllegalArgumentException(FORMAT_INVALID);
            }
            String selectedCsvMode = csvMode == null || csvMode.isBlank()
                    ? p.export().csvMode() : csvMode.toUpperCase(Locale.ROOT);
            if (!selectedCsvMode.equals(MODE_RAW) && !selectedCsvMode.equals(MODE_SPREADSHEET_SAFE)) {
                throw new IllegalArgumentException(CSV_MODE_INVALID);
            }
            if (idempotencyKey != null && idempotencyKey.length() > 255) {
                throw new IllegalArgumentException(IDEMPOTENCY_TOO_LONG);
            }
            return new SubmitCommand(relation.trim(), columns, filters, selectedFormat,
                    selectedCsvMode,
                    csvDialectVersion == null ? p.export().csvDialectVersion() : csvDialectVersion,
                    idempotencyKey == null ? null : idempotencyKey.trim());
        }
    }

    public record JobView(UUID id, String status, String relation, String format,
                          long rowCount, long byteCount, String contentSha256, String objectKey,
                          int attemptCount,
                          Instant createdAt, Instant finishedAt, String errorCode,
                          String errorMessage, String cursor, boolean cacheHit) { }

    public record JobPage(List<JobView> items, String nextCursor) {
        public JobPage { items = List.copyOf(items == null ? List.of() : items); }
    }

    private record Cursor(Instant createdAt, UUID id) {
        static String encode(Instant createdAt, UUID id) {
            String value = createdAt.toString() + "|" + id;
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(value.getBytes(StandardCharsets.UTF_8));
        }

        static Cursor decode(String encoded) {
            if (encoded == null || encoded.isBlank()) return null;
            try {
                String value = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
                int separator = value.lastIndexOf('|');
                if (separator < 1) throw new IllegalArgumentException("invalid cursor");
                return new Cursor(Instant.parse(value.substring(0, separator)),
                        UUID.fromString(value.substring(separator + 1)));
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("invalid cursor", e);
            }
        }
    }
}
