package com.omniflux.exchange.export;

import com.omniflux.exchange.job.TransferJob;
import com.omniflux.exchange.security.AuthContext;
import com.omniflux.exchange.source.ExportRequest;
import reactor.core.publisher.Mono;

import java.util.Objects;

/** Compares a fresh, filter-scoped adapter high-water value with the origin. */
public final class AdapterCacheFreshnessProbe implements CacheFreshnessProbe {
    private final ExportRowSourceFactory sources;

    public AdapterCacheFreshnessProbe(ExportRowSourceFactory sources) {
        this.sources = Objects.requireNonNull(sources, "sources");
    }

    @Override
    public Mono<Boolean> isFresh(AuthContext auth, ExportRequest request, TransferJob origin) {
        if (origin == null || origin.highWaterKey() == null) return Mono.just(false);
        return sources.create(auth).prepare(request)
                .map(scan -> scan.highWater() != null
                        && scan.highWater().equals(origin.highWaterKey()));
    }
}
