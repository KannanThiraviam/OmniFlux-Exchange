package com.omniflux.exchange.config;

import com.omniflux.exchange.adapter.rest.DataApiClient;
import com.omniflux.exchange.export.AdapterCacheFreshnessProbe;
import com.omniflux.exchange.export.CacheFreshnessProbe;
import com.omniflux.exchange.export.ExportPipeline;
import com.omniflux.exchange.export.ExportRowSourceFactory;
import com.omniflux.exchange.job.ExpiredLeaseSweeper;
import com.omniflux.exchange.job.JobProgressFlusher;
import com.omniflux.exchange.job.JobProgressRegistry;
import com.omniflux.exchange.job.JobQueuePoller;
import com.omniflux.exchange.job.JobRepository;
import com.omniflux.exchange.job.JobWorker;
import com.omniflux.exchange.job.JobTelemetry;
import com.omniflux.exchange.meta.SchemaCatalog;
import com.omniflux.exchange.source.SqlDialect;
import com.omniflux.exchange.writer.RowWriterFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.r2dbc.core.DatabaseClient;

import java.util.Set;
import java.util.concurrent.ExecutorService;

/** Wires the queue worker to the real source, streaming writer and S3 upload. */
@Configuration(proxyBeanMethods = false)
public class ExportRuntimeConfig {
    @Bean
    RowWriterFactory rowWriterFactory(OmnifluxProperties properties) {
        var limits = properties.limits();
        Set<Integer> controls = limits.allowedControlChars().stream()
                .map(value -> value.codePointAt(0)).collect(java.util.stream.Collectors.toUnmodifiableSet());
        var xlsx = properties.xlsx();
        return new RowWriterFactory(limits.maxFieldBytes().toBytes(), limits.maxRowBytes().toBytes(),
                controls, new RowWriterFactory.XlsxLimits(xlsx.maxDataRows(), xlsx.maxCellChars(),
                        xlsx.compressionLevel(), xlsx.dateMode(), xlsx.illegalCharPolicy()));
    }

    @Bean
    ExportPipeline exportPipeline(RowWriterFactory writers, ExecutorService exportExecutor,
                                  OmnifluxProperties properties) {
        return new ExportPipeline(writers, exportExecutor, properties);
    }

    @Bean
    ExportRowSourceFactory exportRowSourceFactory(OmnifluxProperties properties,
                                                  SchemaCatalog catalog,
                                                  DatabaseClient database,
                                                  DataApiClient dataApi,
                                                  SqlDialect dialect) {
        return new ExportRowSourceFactory(properties, catalog, database, dataApi, dialect);
    }

    @Bean
    CacheFreshnessProbe cacheFreshnessProbe(ExportRowSourceFactory sources) {
        return new AdapterCacheFreshnessProbe(sources);
    }

    @Bean
    JobWorker jobWorker(JobRepository repository, com.omniflux.exchange.job.JobExecution execution,
                        OmnifluxProperties properties, JobTelemetry telemetry) {
        return new JobWorker(repository, execution, properties, telemetry);
    }

    @Bean(destroyMethod = "close")
    JobQueuePoller jobQueuePoller(JobRepository repository, JobWorker worker,
                                  OmnifluxProperties properties) {
        String workerId = System.getenv().getOrDefault("HOSTNAME",
                "omniflux-worker-" + ProcessHandle.current().pid());
        return new JobQueuePoller(repository, worker, workerId,
                properties.queue().pollInterval(), properties.queue().drainTimeout());
    }

    @EventListener(ApplicationReadyEvent.class)
    void startQueuePollingAfterDatabaseInitialization(ApplicationReadyEvent event) {
        var context = event.getApplicationContext();
        context.getBean(JobQueuePoller.class).start();
        context.getBean(ExpiredLeaseSweeper.class).start(
                context.getBean(OmnifluxProperties.class).queue().sweepInterval());
        context.getBean(JobProgressFlusher.class).start(JobProgressFlusher.DEFAULT_INTERVAL);
    }

    @Bean(destroyMethod = "close")
    ExpiredLeaseSweeper expiredLeaseSweeper(JobRepository repository) {
        return new ExpiredLeaseSweeper(repository);
    }

    @Bean(destroyMethod = "close")
    JobProgressFlusher jobProgressFlusher(JobRepository repository,
                                          JobProgressRegistry registry) {
        return new JobProgressFlusher(repository, registry);
    }
}
