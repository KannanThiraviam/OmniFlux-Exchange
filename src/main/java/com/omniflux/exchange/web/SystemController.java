package com.omniflux.exchange.web;

import com.omniflux.exchange.config.OmnifluxProperties;
import com.omniflux.exchange.observability.FsWatcher;
import com.omniflux.exchange.observability.ResourceProbe;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.lang.management.BufferPoolMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryUsage;
import java.nio.file.StandardWatchEventKinds;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** In-memory operational evidence endpoint; it performs no filesystem reads. */
@RestController
@RequestMapping("/api/system")
public final class SystemController {
    public static final Set<String> PUBLISHED_KEYS = Set.of(
            "omniflux.auth-mode",
            "omniflux.limits.max-field-bytes",
            "omniflux.limits.max-row-bytes",
            "omniflux.export.default-format",
            "omniflux.export.csv-mode",
            "omniflux.export.prefetch",
            "omniflux.storage.region",
            "omniflux.storage.bucket",
            "omniflux.storage.public-endpoint",
            "omniflux.storage.path-style",
            "omniflux.storage.part-size",
            "omniflux.source.default-adapter",
            "omniflux.source.rest.page-size",
            "omniflux.source.r2dbc.page-size",
            "omniflux.queue.max-concurrent",
            "omniflux.queue.max-depth",
            "omniflux.xlsx.max-data-rows",
            "omniflux.seed.batch-size",
            "omniflux.seed.batch-bytes",
            "omniflux.observability.fs-watch-mode",
            "omniflux.resources.max-heap-budget",
            "omniflux.ui.page-size");

    private final ResourceProbe resources;
    private final FsWatcher filesystem;
    private final OmnifluxProperties properties;
    private final JobMetrics jobs;

    public SystemController(ResourceProbe resources, FsWatcher filesystem,
                            OmnifluxProperties properties) {
        this(resources, filesystem, properties, JobMetrics.EMPTY);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public SystemController(ResourceProbe resources, FsWatcher filesystem,
                            OmnifluxProperties properties, JobMetrics jobs) {
        this.resources = resources;
        this.filesystem = filesystem;
        this.properties = properties;
        this.jobs = jobs == null ? JobMetrics.EMPTY : jobs;
    }

    @GetMapping("/resources")
    public Mono<ResourceResponse> resourceSnapshotHttp() {
        return jobs.snapshot().map(this::resourceSnapshot);
    }

    private ResourceResponse resourceSnapshot(JobMetrics.Snapshot jobSnapshot) {
        ResourceProbe.Snapshot snapshot = resources.snapshot();
        MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        return new ResourceResponse(
                podName(),
                properties.security().authMode(),
                new Heap(heap.getUsed(), heapPeakBytes(),
                        heap.getMax() > 0 ? heap.getMax() : Runtime.getRuntime().maxMemory()),
                new Rss(snapshot.rssBytes()),
                new Direct(directBufferBytes()),
                new Cgroup(optional(snapshot.cgroupPeakBytes()),
                        optional(resources.cgroupLimitBytes()),
                        optional(snapshot.cgroupOomKillCount())),
                new Filesystem(
                        filesystem.events(StandardWatchEventKinds.ENTRY_CREATE),
                        filesystem.events(StandardWatchEventKinds.ENTRY_MODIFY),
                        filesystem.events(StandardWatchEventKinds.ENTRY_DELETE),
                        filesystem.overflowed(),
                        properties.observability().watchPaths()),
                new Jobs(jobSnapshot.active(), jobSnapshot.queued(), jobSnapshot.globalActive(),
                        properties.queue().maxConcurrent()),
                jobSnapshot.rowsPerSec(), jobSnapshot.cacheHitRate(), effectiveConfig());
    }

    private static long heapPeakBytes() {
        return ManagementFactory.getMemoryPoolMXBeans().stream()
                .filter(pool -> pool.getType() == java.lang.management.MemoryType.HEAP)
                .map(MemoryPoolMXBean::getPeakUsage)
                .filter(java.util.Objects::nonNull)
                .mapToLong(MemoryUsage::getUsed)
                .max()
                .orElse(ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed());
    }

    private Map<String, Object> effectiveConfig() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("omniflux.auth-mode", properties.security().authMode());
        values.put("omniflux.limits.max-field-bytes", properties.limits().maxFieldBytes().toBytes());
        values.put("omniflux.limits.max-row-bytes", properties.limits().maxRowBytes().toBytes());
        values.put("omniflux.export.default-format", properties.export().defaultFormat());
        values.put("omniflux.export.csv-mode", properties.export().csvMode());
        values.put("omniflux.export.prefetch", properties.export().prefetch());
        values.put("omniflux.storage.region", properties.storage().region());
        values.put("omniflux.storage.bucket", properties.storage().bucket());
        values.put("omniflux.storage.public-endpoint", properties.storage().publicEndpoint());
        values.put("omniflux.storage.path-style", properties.storage().pathStyle());
        values.put("omniflux.storage.part-size", properties.storage().partSize().toBytes());
        values.put("omniflux.source.default-adapter", properties.source().defaultAdapter());
        values.put("omniflux.source.rest.page-size", properties.source().rest().pageSize());
        values.put("omniflux.source.r2dbc.page-size", properties.source().r2dbc().pageSize());
        values.put("omniflux.queue.max-concurrent", properties.queue().maxConcurrent());
        values.put("omniflux.queue.max-depth", properties.queue().maxDepth());
        values.put("omniflux.xlsx.max-data-rows", properties.xlsx().maxDataRows());
        values.put("omniflux.seed.batch-size", properties.seed().batchSize());
        values.put("omniflux.seed.batch-bytes", properties.seed().batchBytes().toBytes());
        values.put("omniflux.observability.fs-watch-mode", properties.observability().fsWatchMode());
        values.put("omniflux.resources.max-heap-budget",
                properties.resources().maxHeapBudget().toBytes());
        values.put("omniflux.ui.page-size", properties.ui().pageSize());
        return values;
    }

    private static long directBufferBytes() {
        return ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class).stream()
                .filter(pool -> pool.getName().equalsIgnoreCase("direct"))
                .mapToLong(BufferPoolMXBean::getMemoryUsed)
                .sum();
    }

    private static String podName() {
        String value = System.getenv("HOSTNAME");
        return value == null || value.isBlank() ? "local" : value;
    }

    private static Long optional(java.util.OptionalLong value) {
        return value.isPresent() ? value.getAsLong() : null;
    }

    public interface JobMetrics {
        Snapshot ZERO = new Snapshot(0, 0, 0, 0.0, 0.0);

        JobMetrics EMPTY = () -> Mono.just(ZERO);

        Mono<Snapshot> snapshot();

        record Snapshot(long active, long queued, long globalActive,
                        double rowsPerSec, double cacheHitRate) { }
    }

    public record ResourceResponse(String podName, String authMode, Heap heap, Rss rss, Direct direct,
                                   Cgroup cgroup, Filesystem filesystem, Jobs jobs,
                                   double rowsPerSec, double cacheHitRate,
                                   Map<String, Object> effectiveConfig) { }

    public record Heap(long usedBytes, long peakBytes, long maxBytes) { }
    public record Rss(long usedBytes) { }
    public record Direct(long usedBytes) { }
    public record Cgroup(Long peakBytes, Long limitBytes, Long oomKills) { }
    public record Filesystem(int create, int modify, int delete, boolean overflowed,
                             List<String> watchedMounts) { }
    public record Jobs(long active, long queued, long globalActive, long globalLimit) { }
}
