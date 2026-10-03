package com.omniflux.exchange.web;

import com.omniflux.exchange.PostgresTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Without the demo profile the route must not exist at all — not merely be denied. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class NaiveExportRouteAbsentTest extends PostgresTestBase {
    @LocalServerPort int port;

    @Test
    void theNaiveRouteIsNotRegisteredWithoutTheDemoProfile() throws Exception {
        var response = post(port, 100);
        assertEquals(404, response.statusCode(),
                "POST /api/exports/naive must 404 without the demo profile");
    }

    static HttpResponse<String> post(int port, int rows) throws Exception {
        var request = HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/api/exports/naive"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"rows\":" + rows + "}"))
                .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }
}
