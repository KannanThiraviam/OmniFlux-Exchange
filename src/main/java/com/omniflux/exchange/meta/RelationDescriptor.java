package com.omniflux.exchange.meta;

import java.util.List;
import java.util.NoSuchElementException;

/**
 * One relation's full shape as resolved by a {@code RelationMetadataProvider}
 * with its columns, continuation key, and how it moves
 * over time.
 */
public record RelationDescriptor(String schema, String name, List<ColumnDescriptor> columns,
                                  KeyContract keyContract, Volatility volatility) {

    public RelationDescriptor {
        if (schema == null || schema.isBlank()) throw new IllegalArgumentException("schema must not be blank");
        if (name == null || name.isBlank()) throw new IllegalArgumentException("name must not be blank");
        if (keyContract == null) throw new IllegalArgumentException("keyContract is required");
        if (volatility == null) throw new IllegalArgumentException("volatility is required");
        columns = List.copyOf(columns == null ? List.of() : columns);
    }

    public ColumnDescriptor column(String columnName) {
        return columns.stream()
                .filter(c -> c.name().equals(columnName))
                .findFirst()
                .orElseThrow(() -> new NoSuchElementException("no such column: " + columnName));
    }
}
