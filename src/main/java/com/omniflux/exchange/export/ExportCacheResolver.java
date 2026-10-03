package com.omniflux.exchange.export;

import com.omniflux.exchange.config.OmnifluxProperties;
import com.omniflux.exchange.job.JobRepository;
import com.omniflux.exchange.job.TransferJob;
import com.omniflux.exchange.meta.SchemaCatalog;
import com.omniflux.exchange.meta.Volatility;
import com.omniflux.exchange.security.AuthContext;
import com.omniflux.exchange.source.ExportRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

/** Resolves only fresh, origin cache rows whose object still exists. */
@Service
public final class ExportCacheResolver {
    private final JobRepository jobs;
    private final SchemaCatalog catalog;
    private final S3AsyncClient storage;
    private final OmnifluxProperties properties;
    private final Clock clock;
    private final CacheFreshnessProbe freshness;

    @Autowired
    public ExportCacheResolver(JobRepository jobs, SchemaCatalog catalog,
                               S3AsyncClient storage, OmnifluxProperties properties,
                               CacheFreshnessProbe freshness) {
        this(jobs, catalog, storage, properties, Clock.systemUTC(), freshness);
    }

    public ExportCacheResolver(JobRepository jobs, SchemaCatalog catalog,
                               S3AsyncClient storage, OmnifluxProperties properties,
                               Clock clock) {
        this(jobs, catalog, storage, properties, clock, CacheFreshnessProbe.unavailable());
    }

    public ExportCacheResolver(JobRepository jobs, SchemaCatalog catalog,
                               S3AsyncClient storage, OmnifluxProperties properties,
                               Clock clock, CacheFreshnessProbe freshness) {
        this.jobs = jobs;
        this.catalog = catalog;
        this.storage = storage;
        this.properties = properties;
        this.clock = clock;
        this.freshness = freshness;
    }

    public Mono<Optional<TransferJob>> resolve(AuthContext auth, ExportRequest request) {
        if (auth == null || request == null || !cacheEnabledFor(request)) {
            return Mono.just(Optional.empty());
        }
        return catalog.describe(properties.source().defaultAdapter(), request.relation(), auth)
                .flatMap(descriptor -> descriptor.volatility() == Volatility.MUTABLE
                        ? Mono.just(Optional.empty())
                        : findFreshOrigin(descriptor.volatility(), auth, request));
    }

    private boolean cacheEnabledFor(ExportRequest request) {
        return properties.cache().enabled()
                && Boolean.TRUE.equals(properties.cache().relations().get(request.relation()));
    }

    private Mono<Optional<TransferJob>> findFreshOrigin(Volatility volatility, AuthContext auth,
                                                        ExportRequest request) {
        String fingerprint = Fingerprint.of(auth, request, properties);
        Instant cutoff = clock.instant().minus(properties.cache().ttl());
        return jobs.findCacheOrigin(fingerprint, cutoff)
                .flatMap(candidate -> resolveCandidate(volatility, auth, request, candidate));
    }

    private Mono<Optional<TransferJob>> resolveCandidate(Volatility volatility, AuthContext auth,
                                                         ExportRequest request,
                                                         Optional<TransferJob> candidate) {
        if (candidate.isEmpty()) return Mono.just(candidate);
        TransferJob origin = candidate.get();
        return freshnessFor(volatility, auth, request, origin)
                .flatMap(fresh -> originIfObjectExists(origin, fresh));
    }

    private Mono<Optional<TransferJob>> originIfObjectExists(TransferJob origin, Boolean fresh) {
        if (!Boolean.TRUE.equals(fresh)) return Mono.just(Optional.empty());
        return objectExists(origin)
                .map(exists -> Boolean.TRUE.equals(exists) ? Optional.of(origin) : Optional.empty());
    }

    private Mono<Boolean> freshnessFor(Volatility volatility, AuthContext auth,
                                       ExportRequest request, TransferJob origin) {
        if (volatility == Volatility.STATIC) return Mono.just(true);
        return freshness.isFresh(auth, request, origin).onErrorReturn(false);
    }

    private Mono<Boolean> objectExists(TransferJob job) {
        if (job.objectKey() == null || job.objectKey().isBlank()) return Mono.just(false);
        return Mono.fromFuture(storage.headObject(HeadObjectRequest.builder()
                        .bucket(properties.storage().bucket()).key(job.objectKey()).build()))
                .map(ignored -> true)
                .onErrorReturn(false);
    }
}
