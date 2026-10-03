package com.omniflux.exchange.source;

import java.util.List;

/**
 * One column predicate in an {@link ExportRequest}. `column` is validated
 * against the schema catalog's allowlist (Task 9) before it ever reaches SQL
 * or the Data API URL — FilterSpec itself carries only the (already-typed)
 * value(s); it is never a source of a literal in a query string.
 *
 * <p>See {@link FilterOperator} for why the plan and the spec leave this type
 * without a body — it is reconstructed here from Task 10's `FilterSpec.eq(...)`
 * usage.
 */
public record FilterSpec(String column, FilterOperator operator, Object value) {
    public FilterSpec {
        if (column == null || column.isBlank())
            throw new IllegalArgumentException("FilterSpec column must not be blank");
        if (operator == null)
            throw new IllegalArgumentException("FilterSpec operator is required");
    }

    public static FilterSpec eq(String column, Object value) { return new FilterSpec(column, FilterOperator.EQ, value); }
    public static FilterSpec ne(String column, Object value) { return new FilterSpec(column, FilterOperator.NE, value); }
    public static FilterSpec gt(String column, Object value) { return new FilterSpec(column, FilterOperator.GT, value); }
    public static FilterSpec gte(String column, Object value) { return new FilterSpec(column, FilterOperator.GTE, value); }
    public static FilterSpec lt(String column, Object value) { return new FilterSpec(column, FilterOperator.LT, value); }
    public static FilterSpec lte(String column, Object value) { return new FilterSpec(column, FilterOperator.LTE, value); }
    public static FilterSpec in(String column, List<?> values) { return new FilterSpec(column, FilterOperator.IN, List.copyOf(values)); }
}
