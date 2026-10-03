package com.omniflux.exchange.source;

import reactor.core.publisher.Mono;

public interface RowSource {
    Mono<ExportScan> prepare(ExportRequest request);

    /**
     * Exact row count for the request's filters, without reading pages. Adapters
     * that cannot count cheaply keep the default: callers treat the error as
     * "estimate unavailable", never as an export failure.
     */
    default Mono<Long> count(ExportRequest request) {
        return Mono.error(new UnsupportedOperationException(
                "row count not supported by this source adapter"));
    }
}
