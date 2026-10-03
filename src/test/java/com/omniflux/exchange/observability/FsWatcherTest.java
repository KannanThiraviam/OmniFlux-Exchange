package com.omniflux.exchange.observability;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.WatchEvent;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.omniflux.exchange.observability.MountSource.mount;
import static java.nio.file.StandardWatchEventKinds.*;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

class FsWatcherTest {

    @TempDir
    Path dir;

    @Test
    void detectsAFileCreatedANDDeletedBetweenSamples() throws Exception {
        // REGRESSION. A periodic directory walk misses a short-lived spool file
        // entirely — which is precisely what POI's SXSSF does, and precisely the
        // behavior this project claims not to have.
        //
        // Assert BOTH events: a watcher registering only ENTRY_CREATE passes an
        // assertion about CREATE while being blind to the deletion half, and
        // assertClean() — the method every later task actually calls — is the
        // thing that must see the whole story.
        try (var w = FsWatcher.watching(List.of(dir))) {
            Files.delete(Files.createTempFile(dir, "x", ".tmp"));
            await().untilAsserted(() -> {
                assertEquals(1, w.events(ENTRY_CREATE));
                assertEquals(1, w.events(ENTRY_DELETE));
            });
            assertThrows(AssertionError.class, w::assertClean);
        }
    }

    @Test
    void detectsAWriteToAnALREADYEXISTINGFile() throws Exception {
        // REGRESSION. Counting only CREATE passes when a library reuses one
        // pre-existing spool file and rewrites it for every export.
        Path existing = Files.createFile(dir.resolve("spool.tmp"));
        try (var w = FsWatcher.watching(List.of(dir))) {
            Files.writeString(existing, "x");
            // NOTE: on Windows, a single content write to an existing file
            // deterministically raises TWO ENTRY_MODIFY notifications (data
            // and last-modified-time metadata) rather than one. The claim
            // this test exists to verify is "MODIFY is observed at all for a
            // rewritten scratch file", so the count is asserted as >= 1
            // rather than == 1, which would otherwise fail on Windows
            // regardless of implementation correctness.
            await().untilAsserted(() -> assertTrue(w.events(ENTRY_MODIFY) >= 1));
            // …and assertClean() — the method every later task actually calls —
            // must FAIL on it. Checking only the raw counter passes an
            // assertClean() that considers CREATE and ignores MODIFY, which is
            // precisely the reused-scratch-file case this test exists for.
            assertThrows(AssertionError.class, w::assertClean);
        }
    }

    @Test
    void detectsARenameIntoAWatchedDirectory() throws Exception {
        // The atomic-move spool idiom: a writer stages a file OUTSIDE the
        // watched directory then moves it in with one atomic filesystem
        // operation. WatchService reports a move-IN as ENTRY_CREATE at the
        // destination even though nothing was "created" from the writer's
        // point of view.
        Path outside = Files.createTempFile("staged", ".tmp");
        try (var w = FsWatcher.watching(List.of(dir))) {
            Files.move(outside, dir.resolve("moved-in.tmp"), StandardCopyOption.ATOMIC_MOVE);
            await().untilAsserted(() -> assertEquals(1, w.events(ENTRY_CREATE)));
            assertThrows(AssertionError.class, w::assertClean);
        }
    }

    @Test
    void watchesSubdirectoriesCreatedAFTERTheWatchStarts() throws Exception {
        // WatchService is NOT recursive. A library that mkdir's its own scratch
        // directory would otherwise be invisible.
        try (var w = FsWatcher.watching(List.of(dir))) {
            Path sub = Files.createDirectory(dir.resolve("scratch"));
            // WatchService registration of the new directory is itself
            // asynchronous. Creating the file immediately races it, and the test
            // then fails intermittently for a reason that has nothing to do with
            // the code under test. Wait for the acknowledgement.
            w.awaitRegistered(sub, Duration.ofSeconds(5));
            Files.createFile(sub.resolve("y.tmp"));
            await().untilAsserted(() -> assertTrue(w.events(ENTRY_CREATE) >= 2));
        }
    }

    @Test
    void anOverflowEventFAILSTheRunRatherThanBeingIgnored() throws Exception {
        // REGRESSION. WatchService drops events under pressure and reports
        // OVERFLOW. Ignoring it turns "zero events observed" into "zero events
        // I still had buffer space to notice" — a claim about the buffer, not
        // about the code.
        //
        // Forcing a real OVERFLOW is platform-dependent and flaky. Inject it:
        // FsWatcher takes its event source as a collaborator, and the fake emits
        // one StandardWatchEventKinds.OVERFLOW.
        try (var clean = FsWatcher.watching(List.of(dir))) {
            assertDoesNotThrow(clean::assertClean);
        }
        try (var overflowed = FsWatcher.over(fakeEventSourceEmittingOverflow())) {
            var ex = assertThrows(IllegalStateException.class, overflowed::assertClean);
            assertTrue(ex.getMessage().contains("OVERFLOW"));
        }
    }

    @Test
    void assertCleanWAITSForEventsAlreadyInFlight() throws Exception {
        // REGRESSION. WatchService delivery is ASYNCHRONOUS. A spool file created
        // and deleted microseconds before assertClean() may have its events still
        // queued, so a naive assertion passes and the disk claim is false.
        //
        // A real filesystem event usually arrives fast enough that an
        // implementation with NO drain still wins the race and passes. Inject a
        // late event instead.
        //
        // But the delay must be SHORTER than the 200 ms settle window, or the
        // CORRECT implementation legitimately declares quiescence first and the
        // test fails against working code.
        //
        // The fake also signals that delivery has BEGUN, so assertClean() is
        // entered with an event in flight rather than one merely scheduled.
        var src = eventSourceDeliveringEntryCreate(Duration.ofMillis(120));
        try (var w = FsWatcher.over(src)) {
            src.awaitDeliveryStarted();          // in flight, not yet delivered
            var ex = assertThrows(AssertionError.class, w::assertClean);
            assertTrue(ex.getMessage().contains("1 CREATE"));
        }
    }

    @Test
    void assertCleanDoesNotHangWhenNothingEverHappens() throws Exception {
        // The other half of a settle window: it must terminate on a quiet run,
        // which is the case every export in Task 8 and Task 22 actually takes.
        try (var w = FsWatcher.over(eventSourceDeliveringNothing())) {
            assertTimeout(Duration.ofSeconds(2), w::assertClean);
        }
    }

    @Test
    void enumeratesEVERYWritableMountFromTheMountSourceNotAFixedList() {
        // REGRESSION. Asserting that tmpdir and user.dir are present passes an
        // implementation that returns exactly those two and misses a third
        // writable mount — which is the only case the method exists for.
        //
        // MountSource is injectable precisely so this is testable: a fake supplies
        // four mounts, two writable, and the result must be EXACTLY the writable
        // two. Production wires MountSource.linux(), which parses
        // /proc/self/mounts.
        var fake = MountSource.of(
                mount("/", false),
                mount("/tmp", true),
                mount("/data", true),
                mount("/config", false));

        assertEquals(Set.of(Path.of("/tmp"), Path.of("/data")),
                Set.copyOf(FsWatcher.writableMounts(fake)));
    }

    @Test
    void onAReadOnlyRootTheWatchSetIsTheTmpfsAndNothingElse() {
        // The proof container's actual shape: read_only: true + tmpfs at /tmp.
        assertEquals(List.of(Path.of("/tmp")), FsWatcher.writableMounts(readOnlyRootMounts()));
    }

    // --- fakes ------------------------------------------------------------

    private static MountSource readOnlyRootMounts() {
        return MountSource.of(mount("/", false), mount("/tmp", true));
    }

    private static EventSource fakeEventSourceEmittingOverflow() {
        return new EventSource() {
            private volatile boolean delivered;

            @Override
            public List<WatchEvent<?>> poll(Duration timeout) {
                if (delivered) return List.of();
                delivered = true;
                return List.of(fakeEvent(OVERFLOW));
            }

            @Override
            public void close() {
            }
        };
    }

    private static DelayedEventSource eventSourceDeliveringEntryCreate(Duration delay) {
        return new DelayedEventSource(delay);
    }

    private static EventSource eventSourceDeliveringNothing() {
        return new EventSource() {
            @Override
            public List<WatchEvent<?>> poll(Duration timeout) throws InterruptedException {
                Thread.sleep(Math.min(timeout.toMillis(), 50));
                return List.of();
            }

            @Override
            public void close() {
            }
        };
    }

    private static WatchEvent<?> fakeEvent(WatchEvent.Kind<?> kind) {
        return new WatchEvent<>() {
            @Override
            public WatchEvent.Kind<Object> kind() {
                @SuppressWarnings("unchecked")
                WatchEvent.Kind<Object> k = (WatchEvent.Kind<Object>) kind;
                return k;
            }

            @Override
            public int count() {
                return 1;
            }

            @Override
            public Object context() {
                return null;
            }
        };
    }

    /** A fake EventSource whose poll() signals it has begun delivering before
     *  it actually returns the event, and returns it only after {@code delay}. */
    private static final class DelayedEventSource implements EventSource {
        private final Duration delay;
        private final CountDownLatch started = new CountDownLatch(1);
        private final AtomicBoolean delivered = new AtomicBoolean(false);

        DelayedEventSource(Duration delay) {
            this.delay = delay;
        }

        @Override
        public List<WatchEvent<?>> poll(Duration timeout) throws InterruptedException {
            if (delivered.get()) {
                // already delivered once; block quietly so the pump thread
                // doesn't spin, and never deliver a second event.
                Thread.sleep(Math.min(timeout.toMillis(), 50));
                return List.of();
            }
            started.countDown();
            Thread.sleep(delay.toMillis());
            delivered.set(true);
            return List.of(fakeEvent(ENTRY_CREATE));
        }

        void awaitDeliveryStarted() throws InterruptedException {
            if (!started.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("poll() was never invoked");
            }
        }

        @Override
        public void close() {
        }
    }
}
