package com.omniflux.exchange.job;

import reactor.core.publisher.Mono;

/** Stable seam between job lifecycle and the later export assembly. */
@FunctionalInterface
public interface JobExecution {
    Mono<JobResult> execute(TransferJob job);
}
