package com.omniflux.exchange.job;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Live rows-so-far for attempts executing on THIS pod. Entries exist only while
 * a job is streaming; the executing pipeline registers its counter on start and
 * removes it in a doFinally, so a crashed worker leaves no stale entry for this
 * pod's flusher to keep writing.
 *
 * <p>Progress is periodically persisted by {@link JobProgressFlusher} so the API
 * tier (any pod) can show it; the write is fenced by claim token and status, so
 * a worker that lost its lease cannot write onto another pod's job.</p>
 */
@Component
public final class JobProgressRegistry {
    private final ConcurrentHashMap<UUID, Progress> rows = new ConcurrentHashMap<>();

    private record Progress(UUID claimToken, AtomicLong rowsSoFar) { }

    public void register(UUID jobId, UUID claimToken, AtomicLong rowsSoFar) {
        rows.put(jobId, new Progress(claimToken, rowsSoFar));
    }

    public void clear(UUID jobId) {
        rows.remove(jobId);
    }

    /** Point-in-time view: jobId -> (claimToken, rows so far). */
    public Map<UUID, ProgressView> snapshot() {
        Map<UUID, ProgressView> view = new LinkedHashMap<>();
        rows.forEach((jobId, progress) -> view.put(jobId,
                new ProgressView(progress.claimToken(), progress.rowsSoFar().get())));
        return view;
    }

    public record ProgressView(UUID claimToken, long rowsSoFar) { }
}
