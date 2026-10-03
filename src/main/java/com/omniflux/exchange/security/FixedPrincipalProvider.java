package com.omniflux.exchange.security;

import com.omniflux.exchange.config.OmnifluxProperties;
import org.springframework.http.HttpHeaders;
import reactor.core.publisher.Mono;


/** DISABLED mode: use only the configured local principal and ignore headers. */
public record FixedPrincipalProvider(AuthContext principal) implements CurrentUserProvider {

    public FixedPrincipalProvider(OmnifluxProperties.Security security) {
        this(new AuthContext(
                new PrincipalKey(
                        required(security.devPrincipal().issuer(), "dev issuer"),
                        required(security.devPrincipal().subject(), "dev subject"),
                        required(security.devPrincipal().tenant(), "dev tenant")),
                security.devPrincipal().roles(),
                required(security.authzContextVersion(), "authz context version")));
    }

    @Override
    public Mono<AuthContext> currentAuth(HttpHeaders ignored) {
        return Mono.just(principal);
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " must not be blank");
        }
        return value;
    }
}
