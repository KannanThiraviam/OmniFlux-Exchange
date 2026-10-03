package com.omniflux.exchange.export;

import com.omniflux.exchange.adapter.rest.DataApiClient;
import com.omniflux.exchange.config.OmnifluxProperties;
import com.omniflux.exchange.job.*;
import com.omniflux.exchange.meta.SchemaCatalog;
import com.omniflux.exchange.security.AuthContext;
import com.omniflux.exchange.source.*;
import com.omniflux.exchange.upload.S3UploadSession;
import com.omniflux.exchange.upload.UploadResult;
import com.omniflux.exchange.upload.UploadSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/** Builds the attempt-scoped source, writer and upload for one claimed job. */
@Component
public final class ExportJobExecution implements JobExecution {
    private static final TypeReference<List<String>> COLUMNS = new TypeReference<>() { };
    private static final TypeReference<List<FilterSpec>> FILTERS = new TypeReference<>() { };

    private final OmnifluxProperties properties;
    private final SchemaCatalog catalog;
    private final S3AsyncClient storage;
    private final ExportPipeline pipeline;
    private final ObjectMapper mapper;
    private final ExportRowSourceFactory sources;
    private final ExportTimingRegistry timings;
    private final JobRepository jobs;
    private final JobProgressRegistry progress;

    public ExportJobExecution(OmnifluxProperties properties, SchemaCatalog catalog,
                              DatabaseClient database, DataApiClient dataApi,
                              SqlDialect dialect, S3AsyncClient storage,
                              ExportPipeline pipeline) {
        this(properties, catalog, database, dataApi, dialect, storage, pipeline,
                new ExportTimingRegistry(), null, null);
    }

    public ExportJobExecution(OmnifluxProperties properties, SchemaCatalog catalog,
                              DatabaseClient database, DataApiClient dataApi,
                              SqlDialect dialect, S3AsyncClient storage,
                              ExportPipeline pipeline, ExportTimingRegistry timings) {
        this(properties, catalog, database, dataApi, dialect, storage, pipeline, timings, null, null);
    }

    @Autowired
    public ExportJobExecution(OmnifluxProperties properties, SchemaCatalog catalog,
                              DatabaseClient database, DataApiClient dataApi,
                              SqlDialect dialect, S3AsyncClient storage,
                              ExportPipeline pipeline, ExportTimingRegistry timings,
                              JobRepository jobs, JobProgressRegistry progress) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.storage = Objects.requireNonNull(storage, "storage");
        this.pipeline = Objects.requireNonNull(pipeline, "pipeline");
        this.mapper = new ObjectMapper();
        this.sources = new ExportRowSourceFactory(properties, catalog, database, dataApi, dialect);
        this.timings = Objects.requireNonNull(timings, "timings");
        this.jobs = jobs;
        this.progress = progress == null ? new JobProgressRegistry() : progress;
    }

    @Override
    public Mono<JobResult> execute(TransferJob job) {
        return Mono.defer(() -> executeClaimed(job));
    }

    private Mono<JobResult> executeClaimed(TransferJob job) {
        try {
            ExportTiming timing = new ExportTiming();
            var request = request(job);
            var auth = new AuthContext(job.owner(), job.roles(), job.authzContextVersion());
            return catalog.describe(properties.source().defaultAdapter(), job.relationName(), auth)
                    .flatMap(descriptor -> runScan(job, timing, request, auth));
        } catch (RuntimeException error) {
            return Mono.error(error instanceof ExportException ? error
                    : new ExportException(ErrorCode.UPSTREAM_CLIENT_ERROR,
                            "job request cannot be decoded", error));
        }
    }

    private Mono<JobResult> runScan(TransferJob job, ExportTiming timing, ExportRequest request,
                                    AuthContext auth) {
        RowSource source = sources.create(auth, timing);
        return source.prepare(request).flatMap(scan -> scanWithinRowLimit(job, timing, scan));
    }

    /** Rejects filtered XLSX exports whose scan already exceeds the product limit. */
    private Mono<JobResult> scanWithinRowLimit(TransferJob job, ExportTiming timing,
                                               ExportScan scan) {
        if (isXlsx(job) && scan.rowCount() != null
                && scan.rowCount() > properties.xlsx().maxDataRows()) {
            return Mono.<JobResult>error(new ExportException(ErrorCode.XLSX_ROW_LIMIT,
                    "filtered export contains " + scan.rowCount()
                            + " data rows; maximum is " + properties.xlsx().maxDataRows()))
                    .doOnError(error -> timings.save(job.id(), timing.snapshot()));
        }
        UploadSession upload = upload(job, timing);
        AtomicLong rows = new AtomicLong();
        return runPipeline(job, timing, upload, rows, scan);
    }

    private Mono<JobResult> runPipeline(TransferJob job, ExportTiming timing,
                                        UploadSession upload, AtomicLong rows, ExportScan scan) {
        progress.register(job.id(), job.claimToken(), rows);
        return pipeline.run(
                        scan.records().doOnNext(ignored -> rows.incrementAndGet())
                                .map(RowRecord::values),
                        scan.columns(), format(job), csvMode(job), upload, timing)
                .flatMap(result -> recordOutcome(job, timing, upload, rows, scan, result))
                .doOnError(error -> timings.save(job.id(), timing.snapshot()))
                .doFinally(ignored -> progress.clear(job.id()));
    }

    /** Persists attempt timings, then the durable timing copy when a repository is present. */
    private Mono<JobResult> recordOutcome(TransferJob job, ExportTiming timing,
                                          UploadSession upload, AtomicLong rows, ExportScan scan,
                                          UploadResult result) {
        timings.save(job.id(), timing.snapshot());
        JobResult completed = JobResult.from(result, rows.get(), scan.highWater(), upload);
        if (jobs == null) return Mono.just(completed);
        try {
            String timingJson = mapper.writeValueAsString(timing.snapshot());
            return jobs.recordTiming(job.id(), job.claimToken(), timingJson)
                    .thenReturn(completed);
        } catch (RuntimeException error) {
            return Mono.error(new ExportException(ErrorCode.UPSTREAM_CLIENT_ERROR,
                    "could not persist export timing", error));
        }
    }

    private UploadSession upload(TransferJob job, ExportTiming timing) {
        String extension = "XLSX".equalsIgnoreCase(format(job)) ? "xlsx" : "csv";
        return new S3UploadSession(storage, properties.storage().bucket(),
                job.attemptObjectKey(properties.storage().exportPrefix(), extension), timing,
                properties.storage().partSize().toBytes());
    }

    private ExportRequest request(TransferJob job) {
        try {
            List<String> columns = mapper.readValue(defaultJson(job.columnsJson()), COLUMNS);
            List<FilterSpec> filters = mapper.readValue(defaultJson(job.filtersJson()), FILTERS);
            return new ExportRequest(job.relationName(), columns, filters,
                    format(job), csvMode(job), job.csvDialectVersion());
        } catch (RuntimeException error) {
            throw new ExportException(ErrorCode.UPSTREAM_CLIENT_ERROR,
                    "stored job request is invalid", error);
        }
    }

    private String format(TransferJob job) {
        return job.format() == null || job.format().isBlank()
                ? properties.export().defaultFormat() : job.format();
    }

    private String csvMode(TransferJob job) {
        return job.csvMode() == null || job.csvMode().isBlank()
                ? properties.export().csvMode() : job.csvMode();
    }

    private boolean isXlsx(TransferJob job) {
        return "XLSX".equalsIgnoreCase(format(job));
    }

    private static String defaultJson(String value) {
        return value == null || value.isBlank() ? "[]" : value;
    }
}
