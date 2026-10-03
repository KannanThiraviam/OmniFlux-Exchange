package com.omniflux.exchange.adapter.rest;

import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * OAuth2 client-credentials implementation used by the production REST
 * adapter.  The token publisher is cached once it has been subscribed to;
 * refresh replaces that cached publisher atomically.
 */
public final class ClientCredentialsDataApiCredentials implements DataApiCredentials {

    private final WebClient client;
    private final String tokenUrl;
    private final String clientId;
    private final String clientSecret;
    private final String scope;
    private final AtomicReference<Mono<String>> cached = new AtomicReference<>();

    public ClientCredentialsDataApiCredentials(WebClient.Builder builder,
                                               String tokenUrl,
                                               String clientId,
                                               String clientSecret,
                                               String scope) {
        this(builder.build(), tokenUrl, clientId, clientSecret, scope);
    }

    public ClientCredentialsDataApiCredentials(WebClient client,
                                               String tokenUrl,
                                               String clientId,
                                               String clientSecret,
                                               String scope) {
        this.client = Objects.requireNonNull(client, "client");
        this.tokenUrl = Objects.requireNonNull(tokenUrl, "tokenUrl");
        this.clientId = Objects.requireNonNull(clientId, "clientId");
        this.clientSecret = Objects.requireNonNull(clientSecret, "clientSecret");
        this.scope = scope == null ? "" : scope;
    }

    @Override
    public Mono<String> bearerToken() {
        var current = cached.get();
        if (current != null) return current;

        var candidate = fetchToken().cache();
        if (cached.compareAndSet(null, candidate)) return candidate;
        return cached.get();
    }

    @Override
    public Mono<String> refreshToken() {
        cached.set(null);
        return bearerToken();
    }

    private Mono<String> fetchToken() {
        if (tokenUrl.isBlank()) {
            return Mono.error(new IllegalStateException("source.rest.auth.token-url is required"));
        }

        var form = BodyInserters.fromFormData("grant_type", "client_credentials")
                .with("client_id", clientId)
                .with("client_secret", clientSecret);
        if (!scope.isBlank()) form = form.with("scope", scope);

        return client.post()
                .uri(tokenUrl)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .exchangeToMono(response -> {
                    if (response.statusCode().isError()) {
                        return response.releaseBody().then(Mono.error(
                                new IllegalStateException("Data API credential endpoint returned "
                                        + response.statusCode().value())));
                    }
                    return response.bodyToMono(JsonNode.class);
                })
                .flatMap(body -> {
                    var token = body.path("access_token");
                    if (!token.isString() || token.stringValue().isBlank()) {
                        return Mono.error(new IllegalStateException(
                                "Data API credential response has no access_token"));
                    }
                    return Mono.just(token.stringValue());
                });
    }
}
