package com.omniflux.exchange.security;

import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;
import reactor.core.publisher.Mono;

/** Resolves the caller once at the HTTP boundary. */
public interface CurrentUserProvider {

    Mono<AuthContext> currentAuth(HttpHeaders headers);

    default Mono<AuthContext> currentAuth(ServerHttpRequest request) {
        return currentAuth(request.getHeaders());
    }
}
