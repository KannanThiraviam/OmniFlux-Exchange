package com.omniflux.exchange.observability;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static java.nio.file.StandardWatchEventKinds.*;

/**
 * The production EventSource: a real WatchService, registered recursively
 * over the given roots and re-registered onto any directory created after
 * the watch starts. Package-private: FsWatcher is the only consumer, and it
 * keeps a reference to this concrete type (not just EventSource) so it can
 * expose awaitRegistered(...).
 */
final class RealEventSource implements EventSource {

    private final WatchService watchService;
    private final Map<WatchKey, Path> keyToDir = new ConcurrentHashMap<>();
    private final Set<Path> registered = ConcurrentHashMap.newKeySet();
    private final Object registrationLock = new Object();

    RealEventSource(List<Path> roots) throws IOException {
        this.watchService = FileSystems.getDefault().newWatchService();
        for (Path root : roots) {
            registerRecursively(root);
        }
    }

    // SimpleFileVisitor's overridden method carries JDK nullability annotations
    // we cannot mirror without the org.jetbrains:annotations dependency.
    @SuppressWarnings("NullableProblems")
    private void registerRecursively(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) return;
        Files.walkFileTree(dir, new SimpleFileVisitor<>() {
            @Override
            public java.nio.file.FileVisitResult preVisitDirectory(Path d, BasicFileAttributes attrs) throws IOException {
                register(d);
                return java.nio.file.FileVisitResult.CONTINUE;
            }
        });
    }

    private void register(Path dir) throws IOException {
        WatchKey key = dir.register(watchService, ENTRY_CREATE, ENTRY_MODIFY, ENTRY_DELETE);
        keyToDir.put(key, dir);
        registered.add(dir);
        synchronized (registrationLock) {
            registrationLock.notifyAll();
        }
    }

    @Override
    public List<WatchEvent<?>> poll(Duration timeout) throws InterruptedException {
        WatchKey key = watchService.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
        if (key == null) return List.of();

        Path dir = keyToDir.get(key);
        List<WatchEvent<?>> events = key.pollEvents();
        List<WatchEvent<?>> result = new ArrayList<>(events.size());

        for (WatchEvent<?> event : events) {
            result.add(event);
            if (event.kind() == OVERFLOW || dir == null) continue;
            Path child = dir.resolve((Path) event.context());
            if (event.kind() == ENTRY_CREATE && Files.isDirectory(child)) {
                try {
                    registerRecursively(child);
                } catch (IOException _) {
                    // removed again already; nothing to watch
                }
            }
        }

        boolean valid = key.reset();
        if (!valid) keyToDir.remove(key);
        return result;
    }

    boolean isRegistered(Path dir) {
        return registered.contains(dir);
    }

    void awaitRegistered(Path dir, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        synchronized (registrationLock) {
            while (!isRegistered(dir)) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    throw new AssertionError("timed out waiting for " + dir + " to be registered");
                }
                registrationLock.wait(Math.min(TimeUnit.NANOSECONDS.toMillis(remaining), 10));
            }
        }
    }

    @Override
    public void close() throws IOException {
        watchService.close();
    }
}
