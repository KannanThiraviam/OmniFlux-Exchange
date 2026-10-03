package com.omniflux.exchange.upload;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.ByteBuffer;

/** One attempt-scoped object upload. Implementations must never resubscribe to the body. */
public interface UploadSession {
    void start(Flux<ByteBuffer> body, String contentType);

    Mono<UploadResult> completion();

    void abort();

    Mono<Void> deleteCompleted();
}
