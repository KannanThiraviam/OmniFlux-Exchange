package com.omniflux.exchange.observability;

import java.io.IOException;
import java.nio.file.WatchEvent;
import java.time.Duration;
import java.util.List;

/**
 * Every {@code WatchService} interaction {@link FsWatcher} performs goes
 * through this, so a test can emit an {@code OVERFLOW} or a delayed event
 * without racing the kernel.
 */
public interface EventSource extends AutoCloseable {

    /** Blocks up to {@code timeout} for the next batch; empty means quiescent. */
    List<WatchEvent<?>> poll(Duration timeout) throws InterruptedException;

    @Override
    void close() throws IOException;
}
