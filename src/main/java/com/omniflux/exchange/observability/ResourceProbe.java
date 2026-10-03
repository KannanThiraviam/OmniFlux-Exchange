package com.omniflux.exchange.observability;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import java.time.Duration;
import java.util.OptionalLong;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Samples RSS and cgroup v2 memory limits on its own thread; snapshot() and
 * cgroupLimitBytes() are meant to read only cached volatile fields and
 * touch no filesystem, so a stalled /proc read never delays the endpoint
 * that reports on it.
 */
public final class ResourceProbe {

    private static final Path CGROUP_PEAK = Path.of("/sys/fs/cgroup/memory.peak");
    private static final Path CGROUP_MAX = Path.of("/sys/fs/cgroup/memory.max");
    private static final Path CGROUP_EVENTS = Path.of("/sys/fs/cgroup/memory.events");
    private static final Path CGROUP_CURRENT = Path.of("/sys/fs/cgroup/memory.current");

    /** Sentinel for "not sampled / unavailable"; cgroup values are unsigned. */
    private static final long UNAVAILABLE = -1L;

    private final FsReader reader;
    private final Duration interval;

    private volatile long rssBytes;
    private volatile long cgroupPeakBytes = UNAVAILABLE;
    private volatile long cgroupLimitBytes = UNAVAILABLE;
    private volatile long cgroupOomKillCount = UNAVAILABLE;

    private ScheduledExecutorService sampler;

    public ResourceProbe(FsReader reader, Duration interval) {
        this.reader = reader;
        this.interval = interval;
    }

    /** The ONLY blocking part: every cgroup v2 read happens here, on the sampler thread. */
    public void sample() {
        rssBytes = readRss();
        cgroupPeakBytes = readLong(CGROUP_PEAK).orElse(UNAVAILABLE);
        cgroupLimitBytes = readLong(CGROUP_MAX).orElse(UNAVAILABLE);
        cgroupOomKillCount = readOomKillCount().orElse(UNAVAILABLE);
    }

    public void startSampling() {
        if (sampler != null) return;
        sampler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "resource-probe-sampler");
            thread.setDaemon(true);
            return thread;
        });
        sampler.scheduleWithFixedDelay(this::sampleSafely, 0, interval.toMillis(),
                TimeUnit.MILLISECONDS);
    }

    /** A failing read must not kill the sampling schedule. */
    private void sampleSafely() {
        try {
            sample();
        } catch (RuntimeException _) {
            // a stalled or failing read must not kill the sampling thread
        }
    }

    /** Touches no filesystem: reads only fields sample() already wrote. */
    public Snapshot snapshot() {
        return new Snapshot(rssBytes, optional(cgroupPeakBytes), optional(cgroupOomKillCount));
    }

    public OptionalLong cgroupLimitBytes() {
        return optional(cgroupLimitBytes);
    }

    /**
     * Resident memory, from cgroup v2 `memory.current` where it exists.
     * <p>
     * NOT getCommittedVirtualMemorySize(): that is committed VIRTUAL address
     * space, routinely several GB for a JVM with -Xmx320m, and it is not what
     * anything kills on. This value exists because "heap alone does not predict
     * a cgroup OOM kill", so it has to be the number the cgroup actually
     * accounts; memory.current is that number, and memory.max is what it is compared against.
     * <p>
     * Off-Linux there is no cgroup, and no honest RSS from a portable API, so
     * this falls back to the JVM's own used heap + non-heap. That is an
     * UNDERSTATEMENT, and it is labeled as such: development is on Windows,
     * the measurement that matters runs in a container, and a fabricated
     * precise-looking number would be worse than an obviously approximate one.
     */
    private long readRss() {
        OptionalLong current = readLong(CGROUP_CURRENT);
        if (current.isPresent()) return current.getAsLong();

        var mem = ManagementFactory.getMemoryMXBean();
        return mem.getHeapMemoryUsage().getUsed() + mem.getNonHeapMemoryUsage().getUsed();
    }

    /** True when RSS came from the cgroup; false when it is the off-Linux estimate. */
    public boolean rssIsCgroupAccounted() { return readLong(CGROUP_CURRENT).isPresent(); }

    private OptionalLong readLong(Path path) {
        try {
            String s = reader.read(path).strip();
            if (s.equals("max")) return OptionalLong.empty();
            return OptionalLong.of(Long.parseLong(s));
        } catch (IOException | NumberFormatException _) {
            return OptionalLong.empty();
        }
    }

    private OptionalLong readOomKillCount() {
        try {
            String content = reader.read(CGROUP_EVENTS);
            for (String line : content.split("\n")) {
                String[] parts = line.trim().split("\\s+");
                if (parts.length == 2 && parts[0].equals("oom_kill")) {
                    return OptionalLong.of(Long.parseLong(parts[1]));
                }
            }
            return OptionalLong.empty();
        } catch (IOException _) {
            return OptionalLong.empty();
        }
    }

    private static OptionalLong optional(long value) {
        return value < 0 ? OptionalLong.empty() : OptionalLong.of(value);
    }

    public record Snapshot(long rssBytes, OptionalLong cgroupPeakBytes, OptionalLong cgroupOomKillCount) { }
}
