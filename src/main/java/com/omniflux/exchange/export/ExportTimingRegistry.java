package com.omniflux.exchange.export;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Bounded in-memory hand-off from a completed worker to the timing endpoint. */
@Component
public final class ExportTimingRegistry {
    private static final int MAX_ENTRIES = 256;
    private final Map<UUID, ExportTiming.Snapshot> values = new ConcurrentHashMap<>();

    public void save(UUID jobId, ExportTiming.Snapshot snapshot) {
        if (jobId == null || snapshot == null) return;
        values.put(jobId, snapshot);
        while (values.size() > MAX_ENTRIES) {
            values.keySet().stream().findFirst().ifPresent(values::remove);
        }
    }

    public ExportTiming.Snapshot find(UUID jobId) {
        return values.get(jobId);
    }
}
