package com.omniflux.exchange.writer;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * {@code close()} flushes and nothing more (spec §4.3: "the writer wraps the
 * bridge stream in NonClosingOutputStream, so closing the writer never closes
 * the bridge"). The bridge beneath the writer owns the downstream's lifetime;
 * a writer that closed it would truncate the byte publisher mid-flight.
 */
public final class NonClosingOutputStream extends FilterOutputStream {

    public NonClosingOutputStream(OutputStream downstream) {
        super(downstream);
    }

    /** FilterOutputStream loops byte-by-byte through write(int); 4 MiB rows
     *  must go through the array write of the underlying stream directly. */
    // FilterOutputStream.write(byte[],int,int) carries JDK nullability annotations
    // we cannot mirror without the org.jetbrains:annotations dependency.
    @SuppressWarnings("NullableProblems")
    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        out.write(b, off, len);
    }

    @Override
    public void close() throws IOException {
        flush();
    }
}
