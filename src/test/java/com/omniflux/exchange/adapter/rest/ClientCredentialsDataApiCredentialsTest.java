package com.omniflux.exchange.adapter.rest;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ClientCredentialsDataApiCredentialsTest {
    @Test
    void credentialsAreFormEncodedCachedAndExplicitlyRefreshed() throws Exception {
        try (var server = new MockWebServer()) {
            server.start();
            server.enqueue(json("{\"access_token\":\"first\"}"));
            server.enqueue(json("{\"access_token\":\"second\"}"));
            var credentials = new ClientCredentialsDataApiCredentials(WebClient.builder(),
                    server.url("/token").toString(), "fixture-client", "fixture-secret", "export.read");
            assertEquals("first", credentials.bearerToken().block());
            assertEquals("first", credentials.bearerToken().block());
            assertEquals(1, server.getRequestCount(), "cached token must avoid another authentication request");
            var request = server.takeRequest(1, TimeUnit.SECONDS);
            assertNotNull(request);
            assertEquals("POST", request.getMethod());
            assertTrue(request.getHeader("Content-Type").startsWith("application/x-www-form-urlencoded"));
            String body = request.getBody().readUtf8();
            assertTrue(body.contains("grant_type=client_credentials"));
            assertTrue(body.contains("scope=export.read"));
            assertEquals("second", credentials.refreshToken().block());
            assertEquals(2, server.getRequestCount());
        }
    }

    @Test
    void missingTokensAndUpstreamAuthErrorsCannotBecomeBearerCredentials() throws Exception {
        try (var server = new MockWebServer()) {
            server.start();
            var credentials = new ClientCredentialsDataApiCredentials(WebClient.create(),
                    server.url("/token").toString(), "fixture-client", "fixture-secret", null);
            for (String response : new String[] {"{}", "{\"access_token\":123}", "{\"access_token\":\" \"}"}) {
                server.enqueue(json(response));
                assertThrows(IllegalStateException.class, () -> credentials.refreshToken().block());
            }
            server.enqueue(new MockResponse().setResponseCode(401));
            var error = assertThrows(IllegalStateException.class, () -> credentials.refreshToken().block());
            assertTrue(error.getMessage().contains("401"));
            var blank = new ClientCredentialsDataApiCredentials(WebClient.create(), "", "id", "secret", "");
            assertThrows(IllegalStateException.class, () -> blank.bearerToken().block());
        }
    }

    private static MockResponse json(String body) {
        return new MockResponse().setHeader("Content-Type", "application/json").setBody(body);
    }
}
