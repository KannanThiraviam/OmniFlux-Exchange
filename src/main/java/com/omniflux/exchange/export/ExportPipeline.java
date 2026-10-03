package com.omniflux.exchange.export;

import com.omniflux.exchange.config.OmnifluxProperties;
import com.omniflux.exchange.job.ErrorCode;
import com.omniflux.exchange.job.ExportException;
import com.omniflux.exchange.meta.ColumnDescriptor;
import com.omniflux.exchange.upload.UploadResult;
import com.omniflux.exchange.upload.UploadSession;
import com.omniflux.exchange.writer.RowWriter;
import com.omniflux.exchange.writer.RowWriterFactory;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.io.Closeable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/** Bridges blocking row writers to the reactive upload body with bounded demand. */
public final class ExportPipeline {
    private final RowWriterFactory writers;
    private final Executor exportExecutor;
    private final DataBufferFactory bufferFactory;
    private final int prefetch;
    private final int chunkSize;
    private final long maxObjectBytes;

    public ExportPipeline(RowWriterFactory writers, Executor exportExecutor, OmnifluxProperties props) {
        this(writers, exportExecutor, new DefaultDataBufferFactory(), props);
    }

    public ExportPipeline(RowWriterFactory writers, Executor exportExecutor,
                          DataBufferFactory bufferFactory, OmnifluxProperties props) {
        this.writers = Objects.requireNonNull(writers, "writers");
        this.exportExecutor = Objects.requireNonNull(exportExecutor, "exportExecutor");
        this.bufferFactory = Objects.requireNonNull(bufferFactory, "bufferFactory");
        this.prefetch = Math.max(1, props.export().prefetch());
        this.chunkSize = Math.toIntExact(props.export().bridgeChunkSize().toBytes());
        this.maxObjectBytes = props.export().maxObjectBytes().toBytes();
    }

    public Flux<DataBuffer> bytes(Flux<Object[]> rows, List<ColumnDescriptor> columns,
                                  String format, String csvMode) {
        return bytes(rows, columns, format, csvMode, null);
    }

    private Flux<DataBuffer> bytes(Flux<Object[]> rows, List<ColumnDescriptor> columns,
                                   String format, String csvMode, ExportTiming timing) {
        Sinks.Empty<Void> cancelSignal = Sinks.empty();
        Flux<Object[]> coupled = rows.takeUntilOther(cancelSignal.asMono());
        return Flux.from(DataBufferUtils.outputStreamPublisher(
                        out -> {
                            RowWriter writer = writers.create(format, csvMode,
                                    timing == null ? out : new TimingOutputStream(out, timing));
                            try (Stream<Object[]> stream = coupled.toStream(prefetch)) {
                                timedWriterCall(timing, () -> writer.writeHeader(columns), false);
                                stream.forEach(row -> {
                                    try {
                                        timedWriterCall(timing, () -> writeRow(writer, row), true);
                                    } catch (IOException error) {
                                        throw new UncheckedIOException(error);
                                    }
                                });
                                timedWriterCall(timing, writer::finishContainer, false);
                            } catch (IOException e) {
                                throw new UncheckedIOException(e);
                            } finally {
                                closeQuietly(writer);
                            }
                        }, bufferFactory, exportExecutor, chunkSize))
                .doOnCancel(cancelSignal::tryEmitEmpty);
    }

    public Mono<UploadResult> run(Flux<Object[]> rows, List<ColumnDescriptor> columns,
                                  String format, String csvMode, UploadSession session) {
        return run(rows, columns, format, csvMode, session, null);
    }

    public Mono<UploadResult> run(Flux<Object[]> rows, List<ColumnDescriptor> columns,
                                  String format, String csvMode, UploadSession session,
                                  ExportTiming timing) {
        return Mono.defer(() -> {
            AtomicLong objectBytes = new AtomicLong();
            Flux<ByteBuffer> body = bytes(rows, columns, format, csvMode, timing)
                    .map(buffer -> {
                        try {
                            return buffer.toByteBuffer();
                        } finally {
                            DataBufferUtils.release(buffer);
                        }
                    })
                    .doOnNext(buffer -> countTowardsMaxObjectBytes(objectBytes, buffer));
            return Mono.fromRunnable(() -> session.start(body, contentTypeFor(format)))
                    .subscribeOn(Schedulers.fromExecutor(exportExecutor))
                    .then(session.completion())
                    .doOnCancel(session::abort)
                    .doOnError(error -> session.abort());
        });
    }

    private static void timedWriterCall(ExportTiming timing, IoCall call, boolean row) throws IOException {
        if (timing == null) {
            call.run();
            return;
        }
        long started = System.nanoTime();
        try { call.run(); }
        finally { timing.addWriter(System.nanoTime() - started, row); }
    }

    @FunctionalInterface
    private interface IoCall { void run() throws IOException; }

    private void countTowardsMaxObjectBytes(AtomicLong total, ByteBuffer buffer) {
        long next = total.addAndGet(buffer.remaining());
        if (next > maxObjectBytes) {
            throw new ExportException(ErrorCode.EXPORT_TOO_LARGE,
                    "object exceeds max-object-bytes=" + maxObjectBytes);
        }
    }

    private static void writeRow(RowWriter writer, Object[] row) {
        writer.writeRow(row);
    }

    private static String contentTypeFor(String format) {
        return "XLSX".equalsIgnoreCase(format)
                ? "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                : "text/csv; charset=utf-8";
    }

    private static void closeQuietly(Closeable closeable) {
        try {
            closeable.close();
        } catch (IOException _) {
            // The output-stream publisher reports the write failure through the body.
        }
    }
}
