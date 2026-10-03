package com.omniflux.exchange.adapter.r2dbc;

import com.omniflux.exchange.meta.ColumnDescriptor;
import com.omniflux.exchange.source.*;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/** PostgreSQL quoting and keyset SQL, deliberately isolated from the adapter. */
public final class PgDialect implements SqlDialect {

    private static final String SQL_AND = " AND ";
    private static final String SQL_WHERE = " WHERE ";

    @Override
    public String quote(String identifier) {
        if (identifier == null || identifier.isBlank()) {
            throw new IllegalArgumentException("identifier must not be blank");
        }
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    @Override
    public String relation(String schema, String relation) {
        return quote(schema) + "." + quote(relation);
    }

    public String byteLengthExpr(String column) {
        return "octet_length(" + quote(column) + ")";
    }

    @Override
    public SqlPlan maxKeyQuery(String schema, String relation, String key, List<FilterSpec> filters) {
        List<Object> binds = new ArrayList<>();
        String where = predicates(filters, binds);
        String sql = "SELECT max(" + quote(key) + ") FROM " + relation(schema, relation)
                + where;
        return new SqlPlan(sql, binds);
    }

    @Override
    public SqlPlan countQuery(String schema, String relation, String key,
                              List<FilterSpec> filters, long highWater) {
        List<Object> binds = new ArrayList<>();
        String where = keysetWhere(key, filters, binds, highWater, Long.MIN_VALUE);
        return new SqlPlan("SELECT count(*) FROM " + relation(schema, relation) + where, binds);
    }

    @Override
    public SqlPlan pageQuery(PageSpec page, List<String> columns) {
        if (page.limit() < 1) throw new IllegalArgumentException("limit must be positive");
        List<Object> binds = new ArrayList<>();
        String where = keysetWhere(page.key(), page.filters(), binds, page.highWater(),
                page.afterExclusive());
        int limitIndex = binds.size() + 1;
        binds.add(page.limit());
        String select = columns.stream().map(this::quote).collect(Collectors.joining(", "));
        String sql = "SELECT " + select + " FROM " + relation(page.schema(), page.relation()) + where
                + " ORDER BY " + quote(page.key()) + " ASC LIMIT $" + limitIndex;
        return new SqlPlan(sql, binds);
    }

    @Override
    public SqlPlan guardedPageQuery(PageSpec page, List<ColumnDescriptor> columns,
                                    long maxFieldBytes, long maxRowBytes) {
        if (page.limit() < 1) throw new IllegalArgumentException("limit must be positive");
        String schema = page.schema();
        String relation = page.relation();
        String key = page.key();
        int limit = page.limit();
        List<Object> binds = new ArrayList<>();
        String where = keysetWhere(key, page.filters(), binds, page.highWater(),
                page.afterExclusive());

        List<ColumnDescriptor> variable = columns.stream()
                .filter(ColumnDescriptor::needsByteGuard).toList();
        String valid = "TRUE";
        if (!variable.isEmpty()) {
            int fieldLimitIndex = binds.size() + 1;
            binds.add(maxFieldBytes);
            String total = variable.stream()
                    .map(column -> "coalesce(octet_length(" + quote(column.name()) + "), 0)")
                    .reduce((left, right) -> left + " + " + right).orElse("0");
            String fields = variable.stream()
                    .map(column -> "(" + quote(column.name()) + " IS NULL OR octet_length("
                            + quote(column.name()) + ") <= $" + fieldLimitIndex + ")")
                    .reduce((left, right) -> left + SQL_AND + right).orElse("TRUE");
            int rowLimitIndex = binds.size() + 1;
            binds.add(maxRowBytes);
            valid = "(" + fields + SQL_AND + total + " <= $" + rowLimitIndex + ")";
        }

        String validSql = valid;
        List<String> selected = columns.stream().map(column -> {
            String quoted = quote(column.name());
            return column.needsByteGuard()
                    ? "CASE WHEN " + validSql + " THEN " + quoted + " ELSE NULL END AS " + quoted
                    : quoted;
        }).collect(Collectors.toCollection(ArrayList::new));
        if (columns.stream().noneMatch(column -> column.name().equals(key))) {
            selected.add(quote(key));
        }
        if (!variable.isEmpty()) {
            selected.add("CASE WHEN " + valid
                    + " THEN FALSE ELSE TRUE END AS \"__omniflux_row_too_large\"");
        }
        int limitIndex = binds.size() + 1;
        binds.add(limit);
        String sql = "SELECT " + String.join(", ", selected) + " FROM "
                + relation(schema, relation) + where + " ORDER BY " + quote(key)
                + " ASC LIMIT $" + limitIndex;
        return new SqlPlan(sql, binds);
    }

    @Override
    public String queryTimeoutHint(Duration timeout) {
        if (timeout == null || timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        long millis = timeout.toMillis();
        if (millis < 1) throw new IllegalArgumentException("timeout must be at least one millisecond");
        return "SET LOCAL statement_timeout = " + millis;
    }

    /**
     * Shared keyset WHERE: validated filter predicates, then the exclusive
     * continuation bound when set (Long.MIN_VALUE = none), then the inclusive
     * high-water bound. Binds are appended in clause order.
     */
    private String keysetWhere(String key, List<FilterSpec> filters, List<Object> binds,
                               long highWater, long afterExclusive) {
        List<String> clauses = new ArrayList<>();
        String predicates = predicates(filters, binds);
        if (!predicates.isBlank()) clauses.add(predicates.substring(SQL_WHERE.length()));
        if (afterExclusive != Long.MIN_VALUE) {
            clauses.add(quote(key) + " > $" + (binds.size() + 1));
            binds.add(afterExclusive);
        }
        clauses.add(quote(key) + " <= $" + (binds.size() + 1));
        binds.add(highWater);
        return SQL_WHERE + String.join(SQL_AND, clauses);
    }

    /** Binds are 1-based: the next placeholder is always $(binds.size() + 1). */
    private String predicates(List<FilterSpec> filters, List<Object> binds) {
        if (filters == null || filters.isEmpty()) return "";
        List<String> clauses = new ArrayList<>();
        for (FilterSpec filter : filters) {
            String column = quote(filter.column());
            FilterOperator op = filter.operator();
            if (op == FilterOperator.IN) {
                if (!(filter.value() instanceof List<?> values) || values.isEmpty()) {
                    throw new IllegalArgumentException("IN requires at least one value");
                }
                List<String> placeholders = IntStream.range(0, values.size())
                        .mapToObj(i -> "$" + (binds.size() + 1 + i))
                        .toList();
                clauses.add(column + " IN (" + String.join(", ", placeholders) + ")");
                binds.addAll(values);
                continue;
            }
            Object value = filter.value();
            clauses.add(column + " " + operatorSql(op) + " $" + (binds.size() + 1));
            binds.add(value);
        }
        return SQL_WHERE + String.join(SQL_AND, clauses);
    }

    private static String operatorSql(FilterOperator operator) {
        return switch (operator) {
            case EQ -> "=";
            case NE -> "<>";
            case GT -> ">";
            case GTE -> ">=";
            case LT -> "<";
            case LTE -> "<=";
            case IN -> throw new IllegalArgumentException("IN is handled separately");
        };
    }
}
