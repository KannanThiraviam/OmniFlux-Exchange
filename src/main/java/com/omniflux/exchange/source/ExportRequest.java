package com.omniflux.exchange.source;

import java.util.List;

/**
 * What a caller asked to export, in the shape a {@link RowSource} needs to
 * prepare a scan: which relation, which columns (the internally-selected
 * primary key is added by the adapter, never listed here — spec §5.1), and
 * which predicates narrow it.
 *
 * <p>The three-argument constructor is retained for source adapters and tests
 * that do not need output identity. The normalized format and CSV settings are
 * carried when the request is submitted so source preflight and cache identity
 * observe the same request.
 */
public record ExportRequest(String relation, List<String> columns, List<FilterSpec> filters,
                            String format, String csvMode, Integer csvDialectVersion) {
    public ExportRequest(String relation, List<String> columns, List<FilterSpec> filters) {
        this(relation, columns, filters, null, null, null);
    }

    public ExportRequest {
        if (relation == null || relation.isBlank())
            throw new IllegalArgumentException("relation must not be blank");
        columns = List.copyOf(columns == null ? List.of() : columns);
        filters = List.copyOf(filters == null ? List.of() : filters);
        format = normalize(format);
        csvMode = normalize(csvMode);
        if (csvDialectVersion != null && csvDialectVersion < 0) {
            throw new IllegalArgumentException("csvDialectVersion must not be negative");
        }
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim().toUpperCase();
    }
}
