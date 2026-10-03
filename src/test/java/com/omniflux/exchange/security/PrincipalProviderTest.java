package com.omniflux.exchange.security;

import com.omniflux.exchange.TestProps;
import com.omniflux.exchange.job.ErrorCode;
import com.omniflux.exchange.job.ExportException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PrincipalProviderTest {
    @Test
    void disabledModeUsesTheConfiguredPrincipalAndIgnoresEveryHeader() {
        var props = TestProps.defaults();
        var provider = new FixedPrincipalProvider(props.security());
        var headers = headers(Map.of(
                "X-Auth-Issuer", "evil",
                "X-Auth-Subject", "admin@corp",
                "X-Auth-Tenant", "other",
                "X-Auth-Roles", "ADMIN,SUPERUSER",
                "X-Auth-Authz-Version", "v999"));

        var auth = provider.currentAuth(headers).block();

        assertNotNull(auth);
        assertEquals(new PrincipalKey("local", "dev@local", "local"), auth.key());
        assertEquals(List.of("ANALYST"), auth.roles());
        assertEquals("v1", auth.authzContextVersion());
    }

    @Test
    void headerModeRequiresAndValidatesAllIdentityFields() {
        var provider = new HeaderPrincipalProvider("X-Auth-");
        var headers = headers(Map.of(
                "X-Auth-Issuer", "corp",
                "X-Auth-Subject", "analyst@corp",
                "X-Auth-Tenant", "tenant-a",
                "X-Auth-Roles", "ANALYST,REVIEWER",
                "X-Auth-Authz-Version", "v7"));

        var auth = provider.currentAuth(headers).block();

        assertNotNull(auth);
        assertEquals(new PrincipalKey("corp", "analyst@corp", "tenant-a"), auth.key());
        assertEquals(List.of("ANALYST", "REVIEWER"), auth.roles());
        assertEquals("v7", auth.authzContextVersion());
    }

    @Test
    void headerModeRejectsMissingBlankOverlongAndControlValues() {
        var provider = new HeaderPrincipalProvider("X-Auth-");
        var valid = Map.of(
                "X-Auth-Issuer", "corp",
                "X-Auth-Subject", "analyst@corp",
                "X-Auth-Tenant", "tenant-a",
                "X-Auth-Roles", "ANALYST",
                "X-Auth-Authz-Version", "v7");

        for (String field : valid.keySet()) {
            var missing = new HttpHeaders();
            valid.forEach((key, value) -> { if (!key.equals(field)) missing.add(key, value); });
            assertCode(provider, missing);

            var blank = headers(valid);
            blank.set(field, "  ");
            assertCode(provider, blank);

            var overlong = headers(valid);
            overlong.set(field, "x".repeat(1025));
            assertCode(provider, overlong);

            var control = headers(valid);
            control.set(field, "safe\nforged");
            assertCode(provider, control);
        }
    }

    private static void assertCode(HeaderPrincipalProvider provider, HttpHeaders headers) {
        var error = assertThrows(ExportException.class, () -> provider.currentAuth(headers).block());
        assertEquals(ErrorCode.PRINCIPAL_UNRESOLVED, error.code());
    }

    private static HttpHeaders headers(Map<String, String> values) {
        var headers = new HttpHeaders();
        values.forEach(headers::add);
        return headers;
    }
}
