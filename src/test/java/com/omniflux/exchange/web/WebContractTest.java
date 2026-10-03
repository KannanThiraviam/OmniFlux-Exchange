package com.omniflux.exchange.web;

import com.omniflux.exchange.TestProps;
import com.omniflux.exchange.job.ErrorCode;
import com.omniflux.exchange.job.ExportException;
import com.omniflux.exchange.observability.EventSource;
import com.omniflux.exchange.observability.FsWatcher;
import com.omniflux.exchange.observability.ResourceProbe;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebInputException;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WebContractTest {

    /** Custom security header name; kept as a constant, not a magic literal. */
    private static final String CSP_HEADER = "Content-Security-Policy";
    @Test
    void errorHandlerUsesTheDeclaredDomainStatus() {
        var response = new ErrorHandler().exportFailure(
                new ExportException(ErrorCode.JOB_NOT_FOUND, "hidden job"));

        assertEquals(404, response.getStatusCode().value());
        assertEquals(org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON,
                response.getHeaders().getContentType());
        assertEquals("JOB_NOT_FOUND", java.util.Objects.requireNonNull(response.getBody())
                .getProperties().get("code"));
        assertEquals("hidden job", response.getBody().getProperties().get("message"));
    }

    @Test
    void malformedFrameworkInputIsAValidationErrorAndUnexpectedErrorsAreInternalErrors() {
        var handler = new ErrorHandler();
        var malformed = handler.malformedInput(new ServerWebInputException("bad uuid"));
        assertEquals(400, malformed.getStatusCode().value());
        assertEquals("VALIDATION_ERROR", java.util.Objects.requireNonNull(malformed.getBody())
                .getProperties().get("code"));
        var unexpected = handler.unexpected(new IllegalStateException("boom"));
        assertEquals(500, unexpected.getStatusCode().value());
        assertEquals("INTERNAL_ERROR", java.util.Objects.requireNonNull(unexpected.getBody())
                .getProperties().get("code"));
    }

    @Test
    void systemResponseRedactsSecretsAndPreservesAbsentCgroupValues() throws Exception {
        ResourceProbe probe = new ResourceProbe(path -> { throw new java.io.IOException("absent"); },
                Duration.ofSeconds(1));
        EventSource source = new EventSource() {
            @Override public List<java.nio.file.WatchEvent<?>> poll(Duration timeout) { return List.of(); }
            @Override public void close() { }
        };
        FsWatcher watcher = FsWatcher.over(source);
        try (watcher) {
            var result = new SystemController(probe, watcher, TestProps.defaults())
                    .resourceSnapshotHttp().block();
            assertEquals(SystemController.PUBLISHED_KEYS, result.effectiveConfig().keySet());
            assertTrue(result.effectiveConfig().keySet().stream()
                    .noneMatch(key -> key.toLowerCase().matches(".*(secret|password|token|credential|key).*")));
            assertNull(result.cgroup().peakBytes());
            assertNull(result.cgroup().limitBytes());
            assertNull(result.cgroup().oomKills());
            assertFalse(result.filesystem().overflowed());
        }
    }

    @Test
    void dataCursorEncodingIsOpaqueAndRoundTripSafe() {
        String cursor = DataController.encodeCursor(123456L);
        assertEquals("123456", new String(java.util.Base64.getUrlDecoder().decode(cursor),
                java.nio.charset.StandardCharsets.UTF_8));
        assertFalse(cursor.contains(" "));
    }

    @Test
    void browserResponsesCarryTheRestrictiveContentSecurityPolicy() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/").build());
        new WebConfiguration().contentSecurityPolicyFilter()
                .filter(exchange, ignored -> Mono.empty()).block();

        assertEquals("default-src 'self'; frame-ancestors 'none'; base-uri 'none'; "
                        + "object-src 'none'; form-action 'self'",
                exchange.getResponse().getHeaders().getFirst(CSP_HEADER));
    }
}
