package com.omniflux.exchange.adapter.rest;

import com.omniflux.exchange.config.OmnifluxProperties;
import com.omniflux.exchange.job.ErrorCode;
import com.omniflux.exchange.job.ExportException;
import com.omniflux.exchange.security.AuthContext;
import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutException;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.ConnectException;
import java.net.URI;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeoutException;

/**
 * Small Data API HTTP seam.  Its Jackson codec limit is deliberately local to
 * this client: applying it to Spring's global codecs would also cap inbound
 * request bodies handled by the application.
 */
public final class DataApiClient {
    private static final int UNAUTHORIZED_STATUS = 401;
    private static final String DATA_API_RETURNED_HTTP = "Data API returned HTTP ";
    private static final String REFRESH_RETURNED_NO_TOKEN = "Data API credential refresh returned no token";
    private static final String BEARER_RETURNED_NO_TOKEN = "Data API credentials returned no bearer token";
    private static final String UNABLE_TO_REFRESH = "unable to refresh Data API credentials";
    private static final String UNABLE_TO_OBTAIN = "unable to obtain Data API credentials";
    private static final String NO_CONTENT_RANGE = "Data API count response did not include Content-Range";
    private static final String NO_EXACT_TOTAL = "Data API count response did not include an exact total";
    private static final String INVALID_CONTENT_RANGE = "Data API count response has an invalid Content-Range";
    private static final String HEADER_ISSUER = "X-Asserted-Issuer";
    private static final String HEADER_SUBJECT = "X-Asserted-Subject";
    private static final String HEADER_TENANT = "X-Asserted-Tenant";
    private static final String HEADER_ROLES = "X-Asserted-Roles";
    private static final String HEADER_AUTHZ_VERSION = "X-Asserted-Authz-Version";

    private final WebClient webClient;
    private final DataApiCredentials credentials;
    private final int maxInMemorySize;
    private final String baseUrl;
    private static final ObjectMapper JSON = new ObjectMapper();

    public DataApiClient(OmnifluxProperties properties, DataApiCredentials credentials) {
        this(properties.source().rest(), credentials);
    }

    public DataApiClient(OmnifluxProperties.Source.Rest config,
                         DataApiCredentials credentials) {
        this(createWebClient(config), credentials, config);
    }

    /** Constructor seam for focused tests and alternate HTTP transports. */
    public DataApiClient(WebClient webClient,
                         DataApiCredentials credentials,
                         OmnifluxProperties.Source.Rest config) {
        this.webClient = Objects.requireNonNull(webClient, "webClient");
        this.credentials = Objects.requireNonNull(credentials, "credentials");
        this.maxInMemorySize = Math.toIntExact(config.maxInMemorySize().toBytes());
        this.baseUrl = trimTrailingSlash(Objects.requireNonNull(config.baseUrl(), "baseUrl"));
    }

    /**
     * Streams one top-level JSON array element at a time.  The response is
     * never collected into a page-sized list.
     */
    public Flux<JsonNode> get(String path, AuthContext assertedSubject) {
        Objects.requireNonNull(path, "path");
        return token()
                .flatMapMany(token -> exchange(path, token, assertedSubject)
                        .onErrorResume(Unauthorized.class, ignored -> refreshedRequest(
                                path, assertedSubject)))
                .onErrorMap(DataApiClient::normalizeError);
    }

    /**
     * Requests one row with an exact Content-Range count. The body is released
     * immediately; the export never materializes the count response.
     */
    public Mono<Long> count(String path, AuthContext assertedSubject) {
        Objects.requireNonNull(path, "path");
        return token()
                .flatMap(token -> countExchange(path, token, assertedSubject)
                        .onErrorResume(Unauthorized.class, ignored -> refreshedCountRequest(
                                path, assertedSubject)))
                .onErrorMap(DataApiClient::normalizeError);
    }

    public int maxInMemorySizeBytes() {
        return maxInMemorySize;
    }

    private Flux<JsonNode> refreshedRequest(String path, AuthContext assertedSubject) {
        return credentials.refreshToken()
                .switchIfEmpty(Mono.error(new IllegalStateException(REFRESH_RETURNED_NO_TOKEN)))
                .onErrorMap(error -> error instanceof ExportException
                        ? error
                        : new ExportException(ErrorCode.UPSTREAM_CLIENT_ERROR, UNABLE_TO_REFRESH, error))
                .flatMapMany(token -> exchange(path, token, assertedSubject));
    }

    private Mono<Long> refreshedCountRequest(String path, AuthContext assertedSubject) {
        return credentials.refreshToken()
                .switchIfEmpty(Mono.error(new IllegalStateException(REFRESH_RETURNED_NO_TOKEN)))
                .onErrorMap(error -> error instanceof ExportException
                        ? error
                        : new ExportException(ErrorCode.UPSTREAM_CLIENT_ERROR, UNABLE_TO_REFRESH, error))
                .flatMap(token -> countExchange(path, token, assertedSubject));
    }

    private Mono<String> token() {
        return credentials.bearerToken()
                .switchIfEmpty(Mono.error(new IllegalStateException(BEARER_RETURNED_NO_TOKEN)))
                .onErrorMap(error -> error instanceof ExportException
                        ? error
                        : new ExportException(ErrorCode.UPSTREAM_CLIENT_ERROR, UNABLE_TO_OBTAIN, error));
    }

    private Flux<JsonNode> exchange(String path, String token, AuthContext assertedSubject) {
        return webClient.get()
                .uri(URI.create(baseUrl + (path.startsWith("/") ? path : "/" + path)))
                .accept(MediaType.APPLICATION_JSON)
                .headers(headers -> addHeaders(headers, token, assertedSubject))
                .exchangeToFlux(response -> {
                    var status = response.statusCode();
                    if (status.value() == UNAUTHORIZED_STATUS) {
                        return response.releaseBody().thenMany(Flux.error(new Unauthorized()));
                    }
                    if (status.is4xxClientError()) {
                        return response.releaseBody().thenMany(Flux.error(
                                new ExportException(ErrorCode.UPSTREAM_CLIENT_ERROR,
                                        DATA_API_RETURNED_HTTP + status.value())));
                    }
                    if (status.is5xxServerError()) {
                        return response.releaseBody().thenMany(Flux.error(
                                new ExportException(ErrorCode.UPSTREAM_UNAVAILABLE,
                                        DATA_API_RETURNED_HTTP + status.value())));
                    }
                    if (!status.is2xxSuccessful()) {
                        return response.releaseBody().thenMany(Flux.error(
                                new ExportException(ErrorCode.UPSTREAM_CLIENT_ERROR,
                                        DATA_API_RETURNED_HTTP + status.value())));
                    }
                    // Decode each top-level object as one Map.  Asking for a
                    // tree would make Jackson 3 emit the complete top-level
                    // array as one value, which defeats incremental paging.
                    return response.bodyToFlux(Map.class)
                            // The explicit witness keeps the raw-Map argument
                            // from collapsing valueToTree's inference; no cast.
                            .map(JSON::<JsonNode>valueToTree)
                            .map(node -> {
                                int encodedBytes = node.toString()
                                        .getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
                                if (encodedBytes > maxInMemorySize) {
                                    throw new DataBufferLimitException(
                                            "one Data API element exceeds max-in-memory-size");
                                }
                                return node;
                            });
                });
    }

    private Mono<Long> countExchange(String path, String token, AuthContext assertedSubject) {
        return webClient.get()
                .uri(URI.create(baseUrl + (path.startsWith("/") ? path : "/" + path)))
                .accept(MediaType.APPLICATION_JSON)
                .header("Prefer", "count=exact")
                .header("Range", "items=0-0")
                .headers(headers -> addHeaders(headers, token, assertedSubject))
                .exchangeToMono(response -> {
                    var status = response.statusCode();
                    if (status.value() == UNAUTHORIZED_STATUS) {
                        return discard(response).then(Mono.error(new Unauthorized()));
                    }
                    if (status.is4xxClientError()) {
                        return discard(response).then(Mono.error(new ExportException(
                                ErrorCode.UPSTREAM_CLIENT_ERROR,
                                DATA_API_RETURNED_HTTP + status.value())));
                    }
                    if (status.is5xxServerError()) {
                        return discard(response).then(Mono.error(new ExportException(
                                ErrorCode.UPSTREAM_UNAVAILABLE,
                                DATA_API_RETURNED_HTTP + status.value())));
                    }
                    if (!status.is2xxSuccessful()) {
                        return discard(response).then(Mono.error(new ExportException(
                                ErrorCode.UPSTREAM_CLIENT_ERROR,
                                DATA_API_RETURNED_HTTP + status.value())));
                    }
                    String contentRange = response.headers().asHttpHeaders().getFirst("Content-Range");
                    return discard(response).then(Mono.fromCallable(() -> parseCount(contentRange)));
                });
    }

    private static Mono<Void> discard(org.springframework.web.reactive.function.client.ClientResponse response) {
        // releaseBody() drains and releases per the framework's contract; a
        // hand-rolled bodyToFlux+release here double-releases buffers when the
        // response completes on the shared event loop (IllegalReferenceCountException).
        return response.releaseBody();
    }

    private static long parseCount(String contentRange) {
        if (contentRange == null) {
            throw new ExportException(ErrorCode.UPSTREAM_CLIENT_ERROR, NO_CONTENT_RANGE);
        }
        int slash = contentRange.lastIndexOf('/');
        if (slash < 0 || slash == contentRange.length() - 1
                || "*".equals(contentRange.substring(slash + 1).trim())) {
            throw new ExportException(ErrorCode.UPSTREAM_CLIENT_ERROR, NO_EXACT_TOTAL);
        }
        try {
            long count = Long.parseLong(contentRange.substring(slash + 1).trim());
            if (count < 0) throw new NumberFormatException("negative count");
            return count;
        } catch (NumberFormatException error) {
            throw new ExportException(ErrorCode.UPSTREAM_CLIENT_ERROR, INVALID_CONTENT_RANGE, error);
        }
    }

    private static void addHeaders(HttpHeaders headers, String token, AuthContext auth) {
        headers.setBearerAuth(token);
        addAssertedAuthHeaders(headers, auth);
    }

    /**
     * Asserted-subject headers carry the caller identity to governed Data APIs.
     * The names are a fixed wire contract shared with metadata lookups.
     */
    public static void addAssertedAuthHeaders(HttpHeaders headers, AuthContext auth) {
        if (auth == null) return;
        header(headers, HEADER_ISSUER, auth.key().issuer());
        header(headers, HEADER_SUBJECT, auth.key().subject());
        header(headers, HEADER_TENANT, auth.key().tenant());
        header(headers, HEADER_ROLES, String.join(",", auth.roles()));
        header(headers, HEADER_AUTHZ_VERSION, auth.authzContextVersion());
    }

    private static void header(HttpHeaders headers, String name, String value) {
        headers.set(name, value);
    }

    private static WebClient createWebClient(OmnifluxProperties.Source.Rest config) {
        int maxInMemory = Math.toIntExact(config.maxInMemorySize().toBytes());
        var strategies = org.springframework.web.reactive.function.client.ExchangeStrategies.builder()
                .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(maxInMemory))
                .build();
        var httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS,
                        Math.toIntExact(config.connectTimeout().toMillis()))
                .responseTimeout(config.responseTimeout());
        return WebClient.builder()
                .baseUrl(config.baseUrl())
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .exchangeStrategies(strategies)
                .build();
    }

    private static String trimTrailingSlash(String value) {
        var result = value;
        while (result.endsWith("/")) result = result.substring(0, result.length() - 1);
        return result;
    }

    private static Throwable normalizeError(Throwable error) {
        if (error instanceof ExportException) return error;
        if (error instanceof Unauthorized) {
            return new ExportException(ErrorCode.UPSTREAM_CLIENT_ERROR,
                    "Data API rejected the service credential after refresh", error);
        }
        if (hasCause(error, DataBufferLimitException.class)) {
            return new ExportException(ErrorCode.ROW_TOO_LARGE,
                    "one JSON row exceeds source.rest.max-in-memory-size", error);
        }
        if (hasCause(error, TimeoutException.class) || hasCause(error, ReadTimeoutException.class)) {
            return new ExportException(ErrorCode.QUERY_TIMEOUT,
                    "timed out reading the Data API response", error);
        }
        if (hasCause(error, ConnectException.class)) {
            return new ExportException(ErrorCode.UPSTREAM_UNAVAILABLE,
                    "could not connect to the Data API", error);
        }
        return new ExportException(ErrorCode.UPSTREAM_UNAVAILABLE,
                "Data API request failed", error);
    }

    private static boolean hasCause(Throwable error, Class<? extends Throwable> type) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (type.isInstance(current)) return true;
        }
        return false;
    }

    private static final class Unauthorized extends RuntimeException { }
}
