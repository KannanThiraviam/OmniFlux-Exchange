package com.omniflux.exchange.meta;

import com.omniflux.exchange.TestProps;
import com.omniflux.exchange.config.OmnifluxProperties;
import com.omniflux.exchange.job.ErrorCode;
import com.omniflux.exchange.job.ExportException;
import com.omniflux.exchange.source.FilterSpec;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;

class RequestGuardsTest {

    @Test
    void rejectsRelationsOutsideTheAllowlistAndProtectedRelations() {
        var catalog = catalog(properties(List.of("mock_orders", "transfer_jobs", "admission_gate",
                "pg_class", "information_schema")), orders());
        assertCode(ErrorCode.RELATION_NOT_ALLOWED,
                () -> catalog.describe("rest", "other_relation").block());
        assertCode(ErrorCode.RELATION_NOT_ALLOWED,
                () -> catalog.describe("rest", "transfer_jobs").block());
        assertCode(ErrorCode.RELATION_NOT_ALLOWED,
                () -> catalog.describe("rest", "admission_gate").block());
        assertCode(ErrorCode.RELATION_NOT_ALLOWED,
                () -> catalog.describe("rest", "pg_class").block());
        assertCode(ErrorCode.RELATION_NOT_ALLOWED,
                () -> catalog.describe("rest", "information_schema.tables").block());
    }

    @Test
    void rejectsUnknownDuplicateAndOverlargeColumnSelections() {
        var relation = orders();
        var catalog = catalog(properties(List.of("mock_orders")), relation);
        var described = Objects.requireNonNull(catalog.describe("rest", "mock_orders").block());

        assertCode(ErrorCode.UNKNOWN_COLUMN,
                () -> catalog.requireColumns(described, List.of("missing")));
        assertCode(ErrorCode.DUPLICATE_COLUMN,
                () -> catalog.requireColumns(described, List.of("note", "note")));

        var many = new ArrayList<String>();
        for (int i = 0; i < 201; i++) many.add("note" + i);
        var wide = new RelationDescriptor("public", "mock_orders",
                java.util.stream.Stream.concat(
                        java.util.stream.Stream.of(new ColumnDescriptor("id", LogicalType.INTEGER, false, true)),
                        many.stream().map(name -> ColumnDescriptor.of(name, LogicalType.TEXT))).toList(),
                new KeyContract("id"), Volatility.MUTABLE);
        var limited = catalog(propertiesWithMaxColumns(List.of("mock_orders")), wide);
        var wideDescription = Objects.requireNonNull(limited.describe("rest", "mock_orders").block());
        assertCode(ErrorCode.TOO_MANY_COLUMNS,
                () -> limited.requireColumns(wideDescription, many));
    }

    @Test
    void rejectsInListsLongerThanTheConfiguredMaximum() {
        var catalog = catalog(propertiesWithMaxInValues(List.of("mock_orders")), orders());
        var described = Objects.requireNonNull(catalog.describe("rest", "mock_orders").block());
        assertCode(ErrorCode.TOO_MANY_IN_VALUES,
                () -> catalog.requireFilters(described,
                        List.of(FilterSpec.in("note", List.of("a", "b", "c")))));
    }

    @Test
    void rejectsUnsupportedColumnTypesWithATypedError() {
        var relation = new RelationDescriptor("public", "mock_blobs",
                List.of(new ColumnDescriptor("id", LogicalType.INTEGER, false, true),
                        ColumnDescriptor.of("payload", LogicalType.UNSUPPORTED)),
                new KeyContract("id"), Volatility.MUTABLE);
        var catalog = catalog(properties(List.of("mock_blobs")), relation);
        var described = Objects.requireNonNull(catalog.describe("rest", "mock_blobs").block());
        var ex = assertThrows(ExportException.class,
                () -> catalog.requireColumns(described, List.of("payload")));
        assertEquals(ErrorCode.UNSUPPORTED_COLUMN_TYPE, ex.code());
    }

    @Test
    void byteGuardsOnlyApplyToVariableWidthLogicalTypes() {
        var relation = new RelationDescriptor("public", "mock_orders",
                List.of(new ColumnDescriptor("id", LogicalType.INTEGER, false, true),
                        ColumnDescriptor.of("note", LogicalType.TEXT),
                        ColumnDescriptor.of("purchase_date", LogicalType.DATE)),
                new KeyContract("id"), Volatility.MUTABLE);
        var catalog = catalog(properties(List.of("mock_orders")), relation);
        var described = Objects.requireNonNull(catalog.describe("rest", "mock_orders").block());
        assertTrue(described.column("note").needsByteGuard());
        assertFalse(described.column("purchase_date").needsByteGuard());
        assertFalse(described.column("id").needsByteGuard());
    }

    @Test
    void rejectsNonIntegerContinuationKeys() {
        var relation = new RelationDescriptor("public", "mock_uuid_pk",
                List.of(new ColumnDescriptor("id", LogicalType.UUID, false, true)),
                new KeyContract("id"), Volatility.MUTABLE);
        var catalog = catalog(properties(List.of("mock_uuid_pk")), relation);
        assertCode(ErrorCode.UNSUPPORTED_PRIMARY_KEY,
                () -> catalog.describe("rest", "mock_uuid_pk").block());
    }

    private static RelationDescriptor orders() {
        return new RelationDescriptor("public", "mock_orders",
                List.of(new ColumnDescriptor("id", LogicalType.INTEGER, false, true),
                        ColumnDescriptor.of("note", LogicalType.TEXT),
                        ColumnDescriptor.of("purchase_date", LogicalType.DATE)),
                new KeyContract("id"), Volatility.MUTABLE);
    }

    private static SchemaCatalog catalog(OmnifluxProperties properties,
                                         RelationDescriptor relation) {
        return new SchemaCatalog(Map.of("rest", relationName -> Mono.just(relation)), properties);
    }

    private static OmnifluxProperties properties(List<String> relations) {
        return TestProps.with(Map.of("omniflux.security.allowed-relations", relations));
    }

    private static OmnifluxProperties propertiesWithMaxColumns(List<String> relations) {
        return TestProps.with(Map.of("omniflux.security.allowed-relations", relations,
                "omniflux.security.max-columns", 200));
    }

    private static OmnifluxProperties propertiesWithMaxInValues(List<String> relations) {
        return TestProps.with(Map.of("omniflux.security.allowed-relations", relations,
                "omniflux.security.max-in-values", 2));
    }

    private static void assertCode(ErrorCode expected, org.junit.jupiter.api.function.Executable action) {
        var ex = assertThrows(ExportException.class, action);
        assertEquals(expected, ex.code());
    }
}
