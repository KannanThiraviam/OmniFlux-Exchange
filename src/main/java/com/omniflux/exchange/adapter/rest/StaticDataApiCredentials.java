package com.omniflux.exchange.adapter.rest;

import reactor.core.publisher.Mono;

import java.util.Objects;

/** Fixed credential for the unauthenticated local PostgREST stand-in. */
public final class StaticDataApiCredentials implements DataApiCredentials {

    private final String token;

    public StaticDataApiCredentials(String token) {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("Data API bearer token must not be blank");
        }
        this.token = Objects.requireNonNull(token, "token");
    }

    @Override
    public Mono<String> bearerToken() {
        return Mono.just(token);
    }

    @Override
    public Mono<String> refreshToken() {
        return Mono.just(token);
    }
}
