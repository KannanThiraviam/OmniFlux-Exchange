package com.omniflux.exchange.adapter.rest;

import reactor.core.publisher.Mono;

/**
 * The service authenticates to the Data API as itself.  A queued export must
 * not retain the caller's short-lived bearer token, so the caller's
 * {@code AuthContext} is sent as asserted subject headers separately.
 */
@FunctionalInterface
public interface DataApiCredentials {

    /** Returns the current service bearer token, normally from a small cache. */
    Mono<String> bearerToken();

    /**
     * Invalidates and refreshes the service credential after a 401.  A static
     * credential has nothing to refresh, so the default is deliberately a
     * second read of the same seam.
     */
    default Mono<String> refreshToken() {
        return bearerToken();
    }
}
