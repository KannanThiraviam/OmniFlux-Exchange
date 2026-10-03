package com.omniflux.exchange.export;

import com.omniflux.exchange.config.OmnifluxProperties;
import com.omniflux.exchange.security.AuthContext;
import com.omniflux.exchange.source.ExportRequest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/** Stable cache identity for the bytes an export request is allowed to produce. */
public final class Fingerprint {
    private Fingerprint() { }

    public static String of(AuthContext auth, ExportRequest request,
                            OmnifluxProperties properties) {
        if (auth == null || request == null || properties == null) {
            throw new IllegalArgumentException("auth, request and properties are required");
        }
        var filters = request.filters().stream()
                .map(Fingerprint::canonicalFilter)
                .sorted()
                .toList();
        String format = request.format() == null ? properties.export().defaultFormat() : request.format();
        String csvMode = request.csvMode() == null ? properties.export().csvMode() : request.csvMode();
        int dialect = request.csvDialectVersion() == null
                ? properties.export().csvDialectVersion() : request.csvDialectVersion();
        var canonical = String.join("\n", List.of(
                "fingerprint-v1",
                field(auth.key().issuer()), field(auth.key().subject()), field(auth.key().tenant()),
                field(auth.authzContextVersion()), field(request.relation()),
                field(String.join("\u001f", request.columns())),
                field(String.join("\u001f", filters)),
                field(format), field(csvMode), Integer.toString(dialect),
                "writer-v1"));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static String canonicalFilter(com.omniflux.exchange.source.FilterSpec filter) {
        return field(filter.column()) + "|" + filter.operator().name() + "|"
                + field(canonicalValue(filter.value()));
    }

    private static String canonicalValue(Object value) {
        switch (value) {
            case null -> {
                return "null";
            }
            case Map<?, ?> map -> {
                return map.entrySet().stream()
                        .map(entry -> canonicalValue(entry.getKey()) + "=" + canonicalValue(entry.getValue()))
                        .sorted().reduce("{}", (a, b) -> a + ";" + b);
            }
            case Iterable<?> iterable -> {
                var values = new ArrayList<String>();
                iterable.forEach(item -> values.add(canonicalValue(item)));
                return "[" + String.join(",", values) + "]";
            }
            default -> {
                // deliberate no-op: arrays and plain values are handled below the switch
            }
        }
        if (value.getClass().isArray()) {
            int length = java.lang.reflect.Array.getLength(value);
            var values = new ArrayList<String>(length);
            for (int i = 0; i < length; i++) {
                values.add(canonicalValue(java.lang.reflect.Array.get(value, i)));
            }
            return "[" + String.join(",", values) + "]";
        }
        return value.getClass().getName() + ":" + value;
    }

    private static String field(String value) {
        if (value == null) return "-1:";
        return value.length() + ":" + value;
    }
}
