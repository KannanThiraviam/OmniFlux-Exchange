package com.omniflux.exchange.adapter.r2dbc;

import com.omniflux.exchange.meta.ColumnDescriptor;
import com.omniflux.exchange.meta.LogicalType;
import com.omniflux.exchange.source.FilterOperator;
import com.omniflux.exchange.source.FilterSpec;
import com.omniflux.exchange.source.PageSpec;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PgDialectTest {
    private final PgDialect dialect = new PgDialect();

    @Test
    void quotesIdentifiersAndRelationsWithoutAllowingRawSyntax() {
        assertEquals("\"a\"\"b\"", dialect.quote("a\"b"));
        assertEquals("\"public\".\"orders\"", dialect.relation("public", "orders"));
        assertEquals("octet_length(\"note\")", dialect.byteLengthExpr("note"));
        assertThrows(IllegalArgumentException.class, () -> dialect.quote(" "));
    }

    @Test
    void maxKeyAndPageQueriesKeepFilterAndKeysetBindsInOrder() {
        var filters = List.of(FilterSpec.eq("country", "IN"),
                FilterSpec.gte("id", 10), FilterSpec.in("kind", List.of("a", "b")));
        var max = dialect.maxKeyQuery("public", "orders", "id", filters);
        assertTrue(max.sql().startsWith("SELECT max(\"id\") FROM \"public\".\"orders\" WHERE"));
        assertEquals(List.of("IN", 10, "a", "b"), max.binds());

        var page = dialect.pageQuery(
                new PageSpec("public", "orders", "id", filters, 10L, 100, 50),
                List.of("id", "note"));
        assertTrue(page.sql().contains("\"id\" > $5"));
        assertTrue(page.sql().contains("\"id\" <= $6"));
        assertTrue(page.sql().endsWith("LIMIT $7"));
        assertEquals(List.of("IN", 10, "a", "b", 10L, 100L, 50), page.binds());
    }

    @Test
    void countQueryUsesTheSameFiltersAndHighWaterBound() {
        var count = dialect.countQuery("public", "orders", "id",
                List.of(FilterSpec.lte("id", 1_000_000), FilterSpec.eq("country", "IN")),
                1_000_000);
        assertEquals("SELECT count(*) FROM \"public\".\"orders\" WHERE \"id\" <= $1 AND \"country\" = $2 AND \"id\" <= $3",
                count.sql());
        assertEquals(List.of(1_000_000, "IN", 1_000_000L), count.binds());
    }

    @Test
    void everyOperatorAndValidationBranchIsExplicit() {
        for (var filter : List.of(FilterSpec.ne("id", 1), FilterSpec.gt("id", 1),
                FilterSpec.lte("id", 1), FilterSpec.lt("id", 1))) {
            assertTrue(dialect.maxKeyQuery("public", "orders", "id", List.of(filter))
                    .sql().contains("\"id\" "));
        }
        assertThrows(IllegalArgumentException.class, () ->
                dialect.maxKeyQuery("public", "orders", "id", List.of(FilterSpec.in("id", List.of()))));
        assertThrows(IllegalArgumentException.class, () ->
                dialect.maxKeyQuery("public", "orders", "id",
                        List.of(new FilterSpec("id", FilterOperator.IN, null))));
        assertThrows(IllegalArgumentException.class, () -> dialect.pageQuery(
                new PageSpec("public", "orders", "id", List.of(), Long.MIN_VALUE, 1, 0),
                List.of("id")));
        assertThrows(IllegalArgumentException.class, () -> dialect.queryTimeoutHint(Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> dialect.queryTimeoutHint(Duration.ofNanos(1)));
        assertEquals("SET LOCAL statement_timeout = 25", dialect.queryTimeoutHint(Duration.ofMillis(25)));
    }

    @Test
    void emptyAndNullFilterListsProduceUnfilteredKeysetQueries() {
        assertTrue(dialect.maxKeyQuery("public", "orders", "id", null)
                .sql().contains("FROM \"public\".\"orders\""));
        var page = dialect.pageQuery(
                new PageSpec("public", "orders", "id", List.of(), Long.MIN_VALUE, 10, 5),
                List.of("id"));
        assertTrue(page.sql().contains("WHERE \"id\" <= $1"));
        assertFalse(page.sql().contains(" AND "));
        assertEquals(List.of(10L, 5), page.binds());
        assertThrows(IllegalArgumentException.class, () -> dialect.relation("", "orders"));
        assertThrows(IllegalArgumentException.class, () -> dialect.queryTimeoutHint(null));
    }

    @Test
    void guardedPagesPreserveNullsAndFailOversizedRowsInsteadOfFilteringThemOut() {
        var plan = dialect.guardedPageQuery(
                new PageSpec("public", "orders", "id", List.of(), Long.MIN_VALUE, 100, 50),
                List.of(ColumnDescriptor.of("note", LogicalType.TEXT)), 8, 16);

        assertTrue(plan.sql().startsWith("SELECT CASE WHEN"));
        assertTrue(plan.sql().contains("\"id\""), "continuation key is selected internally");
        assertTrue(plan.sql().contains("__omniflux_row_too_large"));
        assertFalse(plan.sql().contains("WHERE octet_length"),
                "oversized rows must be marked, never silently removed by WHERE");
        assertEquals(List.of(100L, 8L, 16L, 50), plan.binds());
    }
}
