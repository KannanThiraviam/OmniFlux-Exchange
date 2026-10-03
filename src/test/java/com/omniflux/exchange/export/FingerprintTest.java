package com.omniflux.exchange.export;

import com.omniflux.exchange.TestProps;
import com.omniflux.exchange.security.AuthContext;
import com.omniflux.exchange.security.PrincipalKey;
import com.omniflux.exchange.source.ExportRequest;
import com.omniflux.exchange.source.FilterSpec;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FingerprintTest {
    private static final PrincipalKey ALICE = new PrincipalKey("issuer", "alice", "tenant");
    private static final ExportRequest REQUEST = new ExportRequest(
            "mock_orders", List.of("id", "amount"),
            List.of(FilterSpec.eq("country", "IN"), FilterSpec.in("id", List.of(2, 1))));

    @Test
    void principalAndAuthorizationVersionArePartOfTheIdentity() {
        var base = new AuthContext(ALICE, List.of("ANALYST"), "v1");
        assertNotEquals(fp(base), fp(new AuthContext(
                new PrincipalKey("other", "alice", "tenant"), List.of("ANALYST"), "v1")));
        assertNotEquals(fp(base), fp(new AuthContext(
                new PrincipalKey("issuer", "bob", "tenant"), List.of("ANALYST"), "v1")));
        assertNotEquals(fp(base), fp(new AuthContext(
                new PrincipalKey("issuer", "alice", "other"), List.of("ANALYST"), "v1")));
        assertNotEquals(fp(base), fp(new AuthContext(ALICE, List.of("ANALYST"), "v2")));
        assertEquals(fp(base), fp(new AuthContext(ALICE, List.of("VIEWER", "ANALYST"), "v1")));
    }

    @Test
    void orderedColumnsAndCanonicalFiltersAreStable() {
        var auth = new AuthContext(ALICE, List.of(), "v1");
        assertNotEquals(Fingerprint.of(auth,
                new ExportRequest("mock_orders", List.of("id", "amount"), REQUEST.filters()), TestProps.defaults()),
                Fingerprint.of(auth,
                        new ExportRequest("mock_orders", List.of("amount", "id"), REQUEST.filters()), TestProps.defaults()));
        var first = new ExportRequest("mock_orders", List.of("id"),
                List.of(FilterSpec.eq("b", 2), FilterSpec.eq("a", 1)));
        var second = new ExportRequest("mock_orders", List.of("id"),
                List.of(FilterSpec.eq("a", 1), FilterSpec.eq("b", 2)));
        assertEquals(Fingerprint.of(auth, first, TestProps.defaults()),
                Fingerprint.of(auth, second, TestProps.defaults()));
    }

    @Test
    void valuesWithMapsIterablesAndArraysHaveDeterministicTypes() {
        var auth = new AuthContext(ALICE, List.of(), "v1");
        var mapOne = Map.of("b", List.of(2, 1), "a", new int[]{3, 4});
        var mapTwo = Map.of("a", new int[]{3, 4}, "b", List.of(2, 1));
        var one = new ExportRequest("mock_orders", List.of("id"), List.of(FilterSpec.eq("payload", mapOne)));
        var two = new ExportRequest("mock_orders", List.of("id"), List.of(FilterSpec.eq("payload", mapTwo)));
        assertEquals(Fingerprint.of(auth, one, TestProps.defaults()),
                Fingerprint.of(auth, two, TestProps.defaults()));
        assertNotEquals(Fingerprint.of(auth, one, TestProps.defaults()),
                Fingerprint.of(auth, new ExportRequest("mock_orders", List.of("id"),
                        List.of(FilterSpec.eq("payload", null))), TestProps.defaults()));
    }

    @Test
    void writerConfigurationChangesTheIdentityAndOutputIsSha256Hex() {
        var auth = new AuthContext(ALICE, List.of(), "v1");
        var raw = Fingerprint.of(auth, REQUEST, TestProps.defaults());
        var safe = Fingerprint.of(auth, REQUEST,
                TestProps.with(Map.of("omniflux.export.csv-mode", "SPREADSHEET_SAFE",
                        "omniflux.export.csv-dialect-version", 2)));
        assertNotEquals(raw, safe);
        assertEquals(64, raw.length());
        assertEquals(raw.toLowerCase(), raw);
    }

    @Test
    void requestedFormatIsPartOfTheIdentity() {
        var auth = new AuthContext(ALICE, List.of(), "v1");
        var csv = new ExportRequest("mock_orders", List.of("id"), List.of(),
                "CSV", "RAW", 1);
        var xlsx = new ExportRequest("mock_orders", List.of("id"), List.of(),
                "XLSX", "RAW", 1);
        assertNotEquals(Fingerprint.of(auth, csv, TestProps.defaults()),
                Fingerprint.of(auth, xlsx, TestProps.defaults()));
    }

    @Test
    void requiredArgumentsAreValidated() {
        var auth = new AuthContext(ALICE, List.of(), "v1");
        assertThrows(IllegalArgumentException.class, () -> Fingerprint.of(null, REQUEST, TestProps.defaults()));
        assertThrows(IllegalArgumentException.class, () -> Fingerprint.of(auth, null, TestProps.defaults()));
        assertThrows(IllegalArgumentException.class, () -> Fingerprint.of(auth, REQUEST, null));
    }

    private static String fp(AuthContext auth) {
        return Fingerprint.of(auth, REQUEST, TestProps.defaults());
    }
}
