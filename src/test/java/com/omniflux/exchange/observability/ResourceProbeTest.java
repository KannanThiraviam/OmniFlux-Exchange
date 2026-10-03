package com.omniflux.exchange.observability;

import org.junit.jupiter.api.Test;

import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ResourceProbeTest {

    @Test
    void snapshotPerformsZEROFilesystemReads() {
        // REGRESSION. "It completed on a parallel scheduler" passes an
        // implementation that reads /proc every call: without BlockHound
        // installed (Task 19) nothing objects, and a fast read finishes inside
        // the verifier's window.
        //
        // Count the reads instead. The probe takes its filesystem access as a
        // collaborator, so the test can assert that snapshot() uses it NOT AT ALL.
        var reads = new AtomicInteger();
        var probe = new ResourceProbe(countingReader(reads), Duration.ofSeconds(1));

        probe.sample();                                   // the ONLY blocking part
        int afterSample = reads.get();
        assertTrue(afterSample > 0, "sample() is what reads");

        for (int i = 0; i < 100; i++) probe.snapshot();
        assertEquals(afterSample, reads.get(), "snapshot() must read nothing");
    }

    @Test
    void aSlowSampleNeverDelaysASnapshot() {
        // The sampler runs on its own thread; a stalled /proc read must not
        // propagate to the endpoint. Otherwise, the resource endpoint becomes
        // the thing that stalls under the very pressure it is there to report.
        var probe = new ResourceProbe(blockingForeverReader(), Duration.ofMillis(10));
        probe.startSampling();
        assertTimeout(Duration.ofMillis(200), probe::snapshot);
    }

    @Test
    void reportsRssAndCgroupPeakNotOnlyHeap() {
        // Heap alone does not predict an OOM kill: direct buffers, thread stacks
        // and the JVM's own footprint are all outside it, and the cgroup kills
        // on RSS.
        //
        // cgroupPeakBytes() is an OptionalLong in EVERY test. The values are
        // INJECTED through the same FsReader seam, because the development
        // host is Windows and has no cgroup filesystem at all.
        var probe = new ResourceProbe(readerReturning(Map.of(
                "/sys/fs/cgroup/memory.current", "188743680",
                "/sys/fs/cgroup/memory.peak", "201326592",
                "/sys/fs/cgroup/memory.max", "536870912",
                "/sys/fs/cgroup/memory.events", "oom_kill 0\n")), Duration.ofSeconds(1));
        probe.sample();

        var s = probe.snapshot();
        // RSS must be the CGROUP-ACCOUNTED number, not merely "some positive
        // value". `assertTrue(rssBytes() > 0)` passed an implementation reading
        // getCommittedVirtualMemorySize() -- committed VIRTUAL address space,
        // routinely several GB for a JVM with -Xmx320m, and not what anything
        // kills on. Task 22 asserts peak RSS < 512MB; a virtual-memory reading
        // makes that assertion either always-fail or meaningless.
        assertEquals(188_743_680L, s.rssBytes(), "RSS must come from memory.current");
        assertTrue(probe.rssIsCgroupAccounted());
        assertEquals(OptionalLong.of(201_326_592L), s.cgroupPeakBytes());
        assertEquals(OptionalLong.of(536_870_912L), probe.cgroupLimitBytes());
        assertEquals(OptionalLong.of(0L), s.cgroupOomKillCount());
    }

    @Test
    void aNonZeroOomKillCountIsReportedRatherThanSwallowed() {
        // Task 22 asserts a DELTA of zero across a run. A probe that cannot
        // report a non-zero value makes that assertion unfalsifiable.
        var probe = new ResourceProbe(readerReturning(Map.of(
                "/sys/fs/cgroup/memory.events", "oom_kill 3\n")), Duration.ofSeconds(1));
        probe.sample();
        assertEquals(OptionalLong.of(3L), probe.snapshot().cgroupOomKillCount());
    }

    @Test
    void cgroupReadsDegradeGracefullyOffLinux() {
        // Development is on Windows; the proof runs in a container. Absent
        // cgroup files must yield an ABSENT reading, never a zero that would
        // silently satisfy an oom_kill assertion.
        //
        // The fixture is the same injected reader: one that throws
        // NoSuchFileException for every cgroup path.
        var probe = new ResourceProbe(readerWithoutCgroup(), Duration.ofSeconds(1));
        probe.sample();
        assertTrue(probe.snapshot().cgroupPeakBytes().isEmpty());
        assertTrue(probe.cgroupLimitBytes().isEmpty());
    }

    // --- fakes --------------------------------------------------------------

    private static FsReader countingReader(AtomicInteger reads) {
        return path -> {
            reads.incrementAndGet();
            return "0";
        };
    }

    private static FsReader blockingForeverReader() {
        return path -> {
            try {
                new CountDownLatch(1).await(); // never counts down: blocks forever
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return "0";
        };
    }

    // Path.of("/sys/fs/cgroup/...").toString() renders with the HOST
    // filesystem's separator — backslashes on the Windows dev machine, even
    // though the literal was written with forward slashes for the real,
    // Linux-only cgroup v2 path. Normalize before matching so these fakes
    // behave the same on both platforms.
    private static String normalized(Path path) {
        return path.toString().replace('\\', '/');
    }

    private static FsReader readerReturning(Map<String, String> content) {
        return path -> {
            String value = content.get(normalized(path));
            if (value == null) throw new NoSuchFileException(path.toString());
            return value;
        };
    }

    /** Absent for every cgroup path, the shape of a non-Linux host. */
    private static FsReader readerWithoutCgroup() {
        return path -> {
            throw new NoSuchFileException(path.toString());
        };
    }
}
