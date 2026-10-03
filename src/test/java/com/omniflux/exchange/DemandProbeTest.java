package com.omniflux.exchange;

import com.omniflux.exchange.config.OmnifluxProperties;
import com.omniflux.exchange.export.ExportPipeline;
import com.omniflux.exchange.meta.ColumnDescriptor;
import com.omniflux.exchange.meta.LogicalType;
import com.omniflux.exchange.upload.UploadResult;
import com.omniflux.exchange.upload.UploadSession;
import com.omniflux.exchange.writer.RowWriterFactory;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Regression probe for demand at the blocking writer bridge. */
class DemandProbeTest {
    private static final List<ColumnDescriptor> COLUMNS = List.of(
            new ColumnDescriptor("id", LogicalType.INTEGER, false, true));

    @Test
    void defaultBridgeRequestsOneRowAtATime() {
        assertEquals(1, maxRequest(TestProps.defaults()));
    }

    @Test
    void configuredPrefetchIsTheMeasuredUpperBound() {
        assertEquals(8, maxRequest(TestProps.with("omniflux.export.prefetch", 8)));
    }

    private static long maxRequest(OmnifluxProperties properties) {
        var max = new AtomicLong();
        var rows = Flux.range(0, 100).map(i -> new Object[]{(long) i})
                .doOnRequest(n -> max.accumulateAndGet(n, Math::max));
        var allowed = properties.limits().allowedControlChars().stream()
                .map(s -> (int) s.charAt(0)).collect(java.util.stream.Collectors.toSet());
        var factory = new RowWriterFactory(properties.limits().maxFieldBytes().toBytes(),
                properties.limits().maxRowBytes().toBytes(), Set.copyOf(allowed));
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            new ExportPipeline(factory, executor, properties)
                    .run(rows, COLUMNS, "CSV", "RAW", new DrainSession()).block();
        }
        return max.get();
    }

    private static final class DrainSession implements UploadSession {
        private Flux<ByteBuffer> body;

        @Override public void start(Flux<ByteBuffer> body, String contentType) { this.body = body; }
        @Override public Mono<UploadResult> completion() {
            return Mono.defer(() -> body.then(Mono.just(new UploadResult("probe", 0, null, null))));
        }
        @Override public void abort() { }
        @Override public Mono<Void> deleteCompleted() { return Mono.empty(); }
    }
}
