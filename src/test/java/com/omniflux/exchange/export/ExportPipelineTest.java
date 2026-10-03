package com.omniflux.exchange.export;

import com.omniflux.exchange.TestProps;
import com.omniflux.exchange.config.OmnifluxProperties;
import com.omniflux.exchange.meta.ColumnDescriptor;
import com.omniflux.exchange.meta.LogicalType;
import com.omniflux.exchange.upload.UploadResult;
import com.omniflux.exchange.upload.UploadSession;
import com.omniflux.exchange.writer.RowWriterFactory;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExportPipelineTest {
    private static final List<ColumnDescriptor> COLUMNS = List.of(
            new ColumnDescriptor("id", LogicalType.INTEGER, false, true),
            ColumnDescriptor.of("name", LogicalType.TEXT));

    @Test
    void csvBytesAreProducedThroughTheBoundedBridge() {
        try (var executor = Executors.newSingleThreadExecutor()) {
            var pipeline = pipeline(TestProps.defaults(), executor);
            StepVerifier.create(pipeline.bytes(Flux.just(
                            new Object[]{1, "Alice"}, new Object[]{2, "Bob"}),
                    COLUMNS, "CSV", "RAW").map(buffer -> {
                        try {
                            return buffer.toString(java.nio.charset.StandardCharsets.UTF_8);
                        } finally {
                            org.springframework.core.io.buffer.DataBufferUtils.release(buffer);
                        }
                    }).collectList().map(parts -> String.join("", parts)))
                    .expectNext("id,name\r\n1,Alice\r\n2,Bob\r\n")
                    .verifyComplete();
        }
    }

    @Test
    void runStartsTheSessionWithTheFormatContentTypeAndCompletesIt() {
        try (var executor = Executors.newSingleThreadExecutor()) {
            var session = new CapturingSession();
            var result = Objects.requireNonNull(pipeline(TestProps.defaults(), executor)
                    .run(Flux.<Object[]>just(new Object[]{1, "Alice"}), COLUMNS,
                            "CSV", "RAW", session).block());
            assertEquals("text/csv; charset=utf-8", session.contentType);
            assertEquals("exports/data.csv", result.key());
            assertTrue(session.bytes > 0);
            assertTrue(session.started.get());
        }
    }

    @Test
    void xlsxUsesTheSpreadsheetContentType() {
        try (var executor = Executors.newSingleThreadExecutor()) {
            var session = new CapturingSession();
            pipeline(TestProps.defaults(), executor)
                    .run(Flux.<Object[]>just(new Object[]{1, "Alice"}), COLUMNS,
                            "XLSX", "RAW", session).block();
            assertEquals("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    session.contentType);
        }
    }

    @Test
    void objectLimitAbortsTheUpload() {
        var properties = TestProps.with("omniflux.export.max-object-bytes", "1B");
        try (var executor = Executors.newSingleThreadExecutor()) {
            var session = new CapturingSession();
            StepVerifier.create(pipeline(properties, executor)
                            .run(Flux.<Object[]>just(new Object[]{1, "Alice"}), COLUMNS,
                                    "CSV", "RAW", session))
                    .expectErrorMatches(error -> error.getMessage().contains("EXPORT_TOO_LARGE"))
                    .verify();
            assertTrue(session.aborted.get());
        }
    }

    private static ExportPipeline pipeline(OmnifluxProperties properties, ExecutorService executor) {
        var allowed = properties.limits().allowedControlChars().stream()
                .map(value -> (int) value.charAt(0)).collect(java.util.stream.Collectors.toSet());
        return new ExportPipeline(new RowWriterFactory(
                properties.limits().maxFieldBytes().toBytes(),
                properties.limits().maxRowBytes().toBytes(), Set.copyOf(allowed)), executor, properties);
    }

    private static final class CapturingSession implements UploadSession {
        private Flux<ByteBuffer> body;
        private String contentType;
        private long bytes;
        private final AtomicBoolean started = new AtomicBoolean();
        private final AtomicBoolean aborted = new AtomicBoolean();

        @Override public void start(Flux<ByteBuffer> body, String contentType) {
            this.body = body;
            this.contentType = contentType;
            started.set(true);
        }

        @Override public Mono<UploadResult> completion() {
            return Mono.defer(() -> body.reduce(0L, (count, buffer) -> count + buffer.remaining())
                    .map(count -> {
                        bytes = count;
                        return new UploadResult("exports/data.csv", count, "sha", "etag");
                    }));
        }

        @Override public void abort() { aborted.set(true); }
        @Override public Mono<Void> deleteCompleted() { return Mono.empty(); }
    }
}
