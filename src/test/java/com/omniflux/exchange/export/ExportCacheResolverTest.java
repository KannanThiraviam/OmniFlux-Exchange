package com.omniflux.exchange.export;

import com.omniflux.exchange.TestProps;
import com.omniflux.exchange.config.OmnifluxProperties;
import com.omniflux.exchange.job.JobRepository;
import com.omniflux.exchange.job.JobStatus;
import com.omniflux.exchange.job.TransferJob;
import com.omniflux.exchange.meta.*;
import com.omniflux.exchange.security.AuthContext;
import com.omniflux.exchange.security.PrincipalKey;
import com.omniflux.exchange.source.ExportRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class ExportCacheResolverTest {
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final PrincipalKey OWNER = new PrincipalKey("issuer", "alice", "tenant");
    private static final AuthContext AUTH = new AuthContext(OWNER, List.of("ANALYST"), "v1");
    private static final ExportRequest REQUEST = new ExportRequest("mock_orders", List.of("id"), List.of());

    private JobRepository jobs;
    private S3AsyncClient storage;

    @BeforeEach
    void setUp() {
        jobs = Mockito.mock(JobRepository.class);
        storage = Mockito.mock(S3AsyncClient.class);
    }

    @Test
    void disabledCacheDoesNotConsultMetadataOrStorage() {
        var properties = TestProps.defaults();
        var catalog = catalog(properties, Volatility.STATIC);
        var resolver = new ExportCacheResolver(jobs, catalog, storage, properties, fixedClock());

        assertTrue(Objects.requireNonNull(resolver.resolve(AUTH, REQUEST).block()).isEmpty());
        verify(jobs, never()).findCacheOrigin(anyString(), any());
        verify(storage, never()).headObject(any(HeadObjectRequest.class));
    }

    @Test
    void nullRequestsAndRelationsNotOptedIntoCachingFallThrough() {
        var properties = TestProps.defaults();
        var catalog = catalog(properties, Volatility.STATIC);
        var resolver = new ExportCacheResolver(jobs, catalog, storage, properties, fixedClock());
        assertTrue(Objects.requireNonNull(resolver.resolve(null, REQUEST).block()).isEmpty());
        assertTrue(Objects.requireNonNull(resolver.resolve(AUTH, null).block()).isEmpty());

        var enabled = TestProps.with("omniflux.cache.enabled", true);
        var enabledResolver = new ExportCacheResolver(jobs, catalog(enabled, Volatility.STATIC),
                storage, enabled, fixedClock());
        assertTrue(Objects.requireNonNull(enabledResolver.resolve(AUTH, REQUEST).block()).isEmpty());
    }

    @Test
    void mutableRelationsAreNeverServed() {
        var properties = enabledProperties();
        var resolver = new ExportCacheResolver(jobs, catalog(properties, Volatility.MUTABLE),
                storage, properties, fixedClock());

        assertTrue(Objects.requireNonNull(resolver.resolve(AUTH, REQUEST).block()).isEmpty());
        verify(jobs, never()).findCacheOrigin(anyString(), any());
    }

    @Test
    void aFreshOriginWithAnExistingObjectIsReturned() {
        var properties = enabledProperties();
        var origin = origin();
        when(jobs.findCacheOrigin(anyString(), any())).thenReturn(Mono.just(Optional.of(origin)));
        when(storage.headObject(any(HeadObjectRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(HeadObjectResponse.builder().build()));
        var resolver = new ExportCacheResolver(jobs, catalog(properties, Volatility.STATIC),
                storage, properties, fixedClock());

        assertEquals(origin, Objects.requireNonNull(resolver.resolve(AUTH, REQUEST).block()).orElseThrow());
        verify(storage).headObject(any(HeadObjectRequest.class));
    }

    @Test
    void missingOriginOrDeletedObjectFallsThrough() {
        var properties = enabledProperties();
        when(jobs.findCacheOrigin(anyString(), any())).thenReturn(Mono.just(Optional.empty()));
        var resolver = new ExportCacheResolver(jobs, catalog(properties, Volatility.STATIC),
                storage, properties, fixedClock());
        assertTrue(Objects.requireNonNull(resolver.resolve(AUTH, REQUEST).block()).isEmpty());

        var origin = origin();
        when(jobs.findCacheOrigin(anyString(), any())).thenReturn(Mono.just(Optional.of(origin)));
        when(storage.headObject(any(HeadObjectRequest.class)))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("404")));
        assertTrue(Objects.requireNonNull(resolver.resolve(AUTH, REQUEST).block()).isEmpty());
    }

    @Test
    void blankObjectKeysCannotBecomeCacheHits() {
        var properties = enabledProperties();
        var blank = origin().toBuilder().objectKey(" ").build();
        when(jobs.findCacheOrigin(anyString(), any())).thenReturn(Mono.just(Optional.of(blank)));
        var resolver = new ExportCacheResolver(jobs, catalog(properties, Volatility.STATIC),
                storage, properties, fixedClock());
        assertTrue(Objects.requireNonNull(resolver.resolve(AUTH, REQUEST).block()).isEmpty());
        verify(storage, never()).headObject(any(HeadObjectRequest.class));
    }

    @Test
    void monotonicRelationsRequireAFreshFilteredHighWaterBeforeHeadObject() {
        var properties = enabledProperties();
        var origin = origin().toBuilder().highWaterKey(100L).build();
        when(jobs.findCacheOrigin(anyString(), any())).thenReturn(Mono.just(Optional.of(origin)));
        var probeCalls = new java.util.concurrent.atomic.AtomicInteger();
        CacheFreshnessProbe probe = (auth, request, candidate) -> {
            assertEquals(AUTH, auth);
            assertEquals(REQUEST, request);
            assertEquals(origin, candidate);
            probeCalls.incrementAndGet();
            return Mono.just(false);
        };
        var resolver = new ExportCacheResolver(jobs, catalog(properties, Volatility.MONOTONIC_APPEND_ONLY),
                storage, properties, fixedClock(), probe);

        assertTrue(Objects.requireNonNull(resolver.resolve(AUTH, REQUEST).block()).isEmpty());
        assertEquals(1, probeCalls.get());
        verify(storage, never()).headObject(any(HeadObjectRequest.class));

        when(storage.headObject(any(HeadObjectRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(HeadObjectResponse.builder().build()));
        var hitResolver = new ExportCacheResolver(jobs, catalog(properties, Volatility.MONOTONIC_APPEND_ONLY),
                storage, properties, fixedClock(), (auth, request, candidate) -> Mono.just(true));
        assertFalse(Objects.requireNonNull(hitResolver.resolve(AUTH, REQUEST).block()).isEmpty());
    }

    private static OmnifluxProperties enabledProperties() {
        return TestProps.with(Map.of("omniflux.cache.enabled", true,
                "omniflux.cache.relations.mock_orders", true,
                "omniflux.security.allowed-relations", List.of("mock_orders"),
                "omniflux.cache.ttl", "1h"));
    }

    private static SchemaCatalog catalog(OmnifluxProperties properties, Volatility volatility) {
        RelationMetadataProvider provider = name -> Mono.just(new RelationDescriptor(
                "public", name,
                List.of(new ColumnDescriptor("id", LogicalType.INTEGER, false, true)),
                new KeyContract("id"), volatility));
        return new SchemaCatalog(Map.of(properties.source().defaultAdapter(), provider), properties);
    }

    private static TransferJob origin() {
        return TransferJob.builder().id(UUID.randomUUID()).status(JobStatus.COMPLETED).owner(OWNER)
                .relationName("mock_orders").format("CSV").objectKey("exports/origin/data.csv")
                .rowCount(10).byteCount(100).generatedAt(NOW.minusSeconds(30))
                .requestFingerprint("fingerprint").highWaterKey(100L).build();
    }

    private static Clock fixedClock() {
        return Clock.fixed(NOW, ZoneOffset.UTC);
    }
}
