package com.omniflux.exchange.web;

import com.omniflux.exchange.S3TestBase;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * With the demo profile the route is registered and served through the
 * application's real filter/security chain (auth-mode DISABLED locally —
 * the same posture as every other demo endpoint).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("demo")
class NaiveExportRouteDemoTest extends S3TestBase {
    @LocalServerPort int port;

    @Test
    void theNaiveRouteIsRegisteredAndServedUnderTheRealSecurityChain() throws Exception {
        var response = NaiveExportRouteAbsentTest.post(port, 100);
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("\"rows\":100"), "unexpected body: " + response.body());
        assertTrue(response.body().contains("\"estimatedRetainedBytes\":110000"),
                "unexpected body: " + response.body());
    }
}
