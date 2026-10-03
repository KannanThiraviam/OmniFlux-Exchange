package com.omniflux.exchange.observability;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.WatchEvent;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import static java.nio.file.StandardWatchEventKinds.OVERFLOW;

/**
 * Watches a set of directories for filesystem activity and asserts none
 * occurred. assertClean() drains to quiescence (a 200ms settle window,
 * capped at 5s) before it asserts, because WatchService delivery is
 * asynchronous: a spool file created and deleted microseconds earlier may
 * still have its event queued.
 */
public final class FsWatcher implements AutoCloseable {

    private static final Duration SETTLE_WINDOW = Duration.ofMillis(200);
    private static final Duration SETTLE_CAP = Duration.ofSeconds(5);
    private static final Duration PUMP_POLL = Duration.ofMillis(200);

    private final EventSource source;
    private final RealEventSource real; // non-null only for watching(...)

    private final Map<WatchEvent.Kind<?>, AtomicInteger> counts = new ConcurrentHashMap<>();
    private final AtomicLong lastEventAtNanos = new AtomicLong(System.nanoTime());
    private final Object quiesce = new Object();
    private volatile boolean overflow;
    private volatile boolean closed;
    private final Thread pump;

    private FsWatcher(EventSource source, RealEventSource real) {
        this.source = source;
        this.real = real;
        this.pump = new Thread(this::pumpLoop, "fs-watcher-pump");
        this.pump.setDaemon(true);
        this.pump.start();
    }

    public static FsWatcher watching(List<Path> roots) throws IOException {
        RealEventSource rs = new RealEventSource(roots);
        return new FsWatcher(rs, rs);
    }

    public static FsWatcher over(EventSource source) {
        return new FsWatcher(source, null);
    }

    public static List<Path> writableMounts(MountSource mounts) {
        return mounts.mounts().stream()
                .filter(MountSource.Mount::writable)
                .map(MountSource.Mount::path)
                .toList();
    }

    public void awaitRegistered(Path dir, Duration timeout) throws InterruptedException {
        if (real == null) {
            throw new UnsupportedOperationException("awaitRegistered requires a real, filesystem-backed watch");
        }
        real.awaitRegistered(dir, timeout);
    }

    public int events(WatchEvent.Kind<?> kind) {
        AtomicInteger c = counts.get(kind);
        return c == null ? 0 : c.get();
    }

    public boolean overflowed() {
        return overflow;
    }

    public void assertClean() {
        long deadline = System.nanoTime() + SETTLE_CAP.toNanos();
        long settleNanos = SETTLE_WINDOW.toNanos();
        // Wait for quiescence (no event for SETTLE_WINDOW), bounded by SETTLE_CAP;
        // the pump thread signals on every delivered batch.
        synchronized (quiesce) {
            while (System.nanoTime() - lastEventAtNanos.get() < settleNanos
                    && System.nanoTime() < deadline) {
                try {
                    quiesce.wait(10);
                } catch (InterruptedException _) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        if (overflow) {
            throw new IllegalStateException("OVERFLOW: the WatchService dropped events under pressure");
        }
        String detail = counts.entrySet().stream()
                .filter(e -> e.getValue().get() > 0)
                .map(e -> e.getValue().get() + " " + shortName(e.getKey()))
                .collect(Collectors.joining(", "));
        if (!detail.isEmpty()) {
            throw new AssertionError("unexpected filesystem activity: " + detail);
        }
    }

    private static String shortName(WatchEvent.Kind<?> kind) {
        return kind.name().replace("ENTRY_", "");
    }

    private void pumpLoop() {
        while (!closed) {
            try {
                List<WatchEvent<?>> batch = source.poll(PUMP_POLL);
                if (!batch.isEmpty()) {
                    synchronized (quiesce) {
                        lastEventAtNanos.set(System.nanoTime());
                        quiesce.notifyAll();
                    }
                }
                for (WatchEvent<?> event : batch) {
                    if (event.kind() == OVERFLOW) {
                        overflow = true;
                    } else {
                        counts.computeIfAbsent(event.kind(), k -> new AtomicInteger()).incrementAndGet();
                    }
                }
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException _) {
                overflow = true;
            }
        }
    }

    @Override
    public void close() throws IOException {
        closed = true;
        pump.interrupt();
        source.close();
    }
}
