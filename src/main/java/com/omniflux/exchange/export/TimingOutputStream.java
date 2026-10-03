package com.omniflux.exchange.export;

import org.jspecify.annotations.NonNull;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Objects;

/** Measures time spent handing encoded bytes to the reactive bridge. */
final class TimingOutputStream extends OutputStream {
    private final OutputStream delegate;
    private final ExportTiming timing;

    TimingOutputStream(OutputStream delegate, ExportTiming timing) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.timing = Objects.requireNonNull(timing, "timing");
    }

    @Override
    public void write(int value) throws IOException {
        long started = System.nanoTime();
        try { delegate.write(value); }
        finally { timing.addDownstreamWrite(System.nanoTime() - started); }
    }

    @Override
    public void write(byte @NonNull [] bytes, int offset, int length) throws IOException {
        long started = System.nanoTime();
        try { delegate.write(bytes, offset, length); }
        finally { timing.addDownstreamWrite(System.nanoTime() - started); }
    }

    @Override
    public void flush() throws IOException {
        long started = System.nanoTime();
        try { delegate.flush(); }
        finally { timing.addDownstreamWrite(System.nanoTime() - started); }
    }

    @Override
    public void close() throws IOException {
        delegate.close();
    }
}
