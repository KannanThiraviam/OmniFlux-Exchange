package com.omniflux.exchange.export;

import com.omniflux.exchange.job.TransferJob;
import com.omniflux.exchange.meta.ColumnDescriptor;
import com.omniflux.exchange.meta.LogicalType;
import com.omniflux.exchange.security.AuthContext;
import com.omniflux.exchange.security.PrincipalKey;
import com.omniflux.exchange.source.ExportRequest;
import com.omniflux.exchange.source.ExportScan;
import com.omniflux.exchange.source.RowSource;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The probe compares high-water values captured from different boxed
 *  instances (the adapter's scan vs. the stored job); identity comparison made
 *  every key above 127 look stale and silently disabled the export cache. */
class AdapterCacheFreshnessProbeTest {
    private static final AuthContext AUTH = new AuthContext(
            new PrincipalKey("local", "alice", "tenant-a"), List.of("ANALYST"), "v1");

    @Test void equalHighWaterValuesFromDistinctBoxesAreFresh() {
        var sources = mock(ExportRowSourceFactory.class);
        var source = mock(RowSource.class);
        var origin = mock(TransferJob.class);
        when(sources.create(AUTH)).thenReturn(source);
        when(source.prepare(any(ExportRequest.class))).thenReturn(Mono.just(new ExportScan(
                List.of(ColumnDescriptor.of("id", LogicalType.INTEGER)),
                1000L, Flux.empty())));
        when(origin.highWaterKey()).thenReturn(1000L);

        Boolean fresh = new AdapterCacheFreshnessProbe(sources)
                .isFresh(AUTH, request(), origin).block();

        assertEquals(Boolean.TRUE, fresh);
    }

    private static ExportRequest request() {
        return new ExportRequest("mock_orders", List.of("id"), List.of(), "CSV", "RAW", null);
    }
}
