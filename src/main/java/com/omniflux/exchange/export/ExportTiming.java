package com.omniflux.exchange.export;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-attempt timing counters for the proof harness and operator diagnostics.
 * Streaming stages overlap, so these values are phase wall times rather than
 * additive slices of the end-to-end duration.
 */
public final class ExportTiming {
    private final long startedNanos = System.nanoTime();
    private final AtomicLong sourceReadNanos = new AtomicLong();
    private final AtomicLong sourceCalls = new AtomicLong();
    private final AtomicLong csvWriterNanos = new AtomicLong();
    private final AtomicLong csvRows = new AtomicLong();
    private final AtomicLong downstreamWriteNanos = new AtomicLong();
    private final AtomicLong cosUploadNanos = new AtomicLong();
    private final AtomicLong cosUploadBytes = new AtomicLong();
    private final AtomicLong uploadStartedNanos = new AtomicLong();
    private final java.util.concurrent.atomic.AtomicBoolean uploadRecorded =
            new java.util.concurrent.atomic.AtomicBoolean();

    public void addSourceRead(long nanos) {
        sourceReadNanos.addAndGet(Math.max(0L, nanos));
        sourceCalls.incrementAndGet();
    }

    public void addWriter(long nanos, boolean row) {
        csvWriterNanos.addAndGet(Math.max(0L, nanos));
        if (row) csvRows.incrementAndGet();
    }

    public void addDownstreamWrite(long nanos) {
        downstreamWriteNanos.addAndGet(Math.max(0L, nanos));
    }

    public void uploadStarted() {
        uploadStartedNanos.compareAndSet(0L, System.nanoTime());
    }

    public void uploadCompleted(long bytes) {
        if (!uploadRecorded.compareAndSet(false, true)) return;
        long started = uploadStartedNanos.get();
        if (started != 0L) cosUploadNanos.addAndGet(Math.max(0L, System.nanoTime() - started));
        cosUploadBytes.set(Math.max(0L, bytes));
    }

    public Snapshot snapshot() {
        long writer = csvWriterNanos.get();
        long downstream = downstreamWriteNanos.get();
        return new Snapshot(
                millis(sourceReadNanos.get()),
                sourceCalls.get(),
                millis(Math.max(0L, writer - downstream)),
                millis(writer),
                millis(downstream),
                millis(cosUploadNanos.get()),
                cosUploadBytes.get(),
                millis(System.nanoTime() - startedNanos));
    }

    private static long millis(long nanos) {
        return Duration.ofNanos(Math.max(0L, nanos)).toMillis();
    }

    public record Snapshot(
            long sourceReadMillis,
            long sourceCalls,
            long csvProcessingMillis,
            long writerMillis,
            long downstreamWriteMillis,
            long cosUploadMillis,
            long cosUploadBytes,
            long exportMillis) { }
}
