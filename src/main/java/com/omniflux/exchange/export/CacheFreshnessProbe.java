package com.omniflux.exchange.export;

import com.omniflux.exchange.job.TransferJob;
import com.omniflux.exchange.security.AuthContext;
import com.omniflux.exchange.source.ExportRequest;
import reactor.core.publisher.Mono;

/** Adapter-owned freshness check for append-only cache entries. */
@FunctionalInterface
public interface CacheFreshnessProbe {
    Mono<Boolean> isFresh(AuthContext auth, ExportRequest request, TransferJob origin);

    static CacheFreshnessProbe unavailable() {
        return (auth, request, origin) -> Mono.just(false);
    }
}
