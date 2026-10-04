package com.omniflux.exchange.adapter.rest;

import com.omniflux.exchange.TestProps;
import com.omniflux.exchange.job.ErrorCode;
import com.omniflux.exchange.job.ExportException;
import com.omniflux.exchange.meta.ColumnDescriptor;
import com.omniflux.exchange.meta.LogicalType;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RowJsonMapperTest {
    private final ObjectMapper json = new ObjectMapper();
    private final RowJsonMapper mapper = new RowJsonMapper(TestProps.defaults());
    private static final ColumnDescriptor KEY = new ColumnDescriptor("id", LogicalType.INTEGER, false, true);

    @Test
    void typedProjectionPreservesNullsDecimalsAndBinaryWithoutAddingTheInternalKey() {
        var row = (tools.jackson.databind.node.ObjectNode) json.readTree(
                "{\"id\":123,\"amount\":\"12.50\",\"enabled\":true,\"note\":\"€\",\"empty\":null}");
        row.set("payload", json.getNodeFactory().binaryNode(new byte[] {1, 2, 3}));
        var columns = List.of(column("amount", LogicalType.DECIMAL), column("enabled", LogicalType.BOOLEAN),
                column("note", LogicalType.TEXT), column("empty", LogicalType.TEXT),
                column("payload", LogicalType.BINARY), column("missing", LogicalType.TEXT));
        var record = mapper.toRecord(row, columns, "id", KEY);
        assertEquals(123, record.key());
        assertEquals(6, record.values().length);
        assertEquals(new BigDecimal("12.50"), record.values()[0]);
        assertEquals(true, record.values()[1]);
        assertEquals("€", record.values()[2]);
        assertNull(record.values()[3]);
        assertArrayEquals(new byte[] {1, 2, 3}, (byte[]) record.values()[4]);
        assertNull(record.values()[5]);
        assertEquals(123, mapper.key(row, "id"));
        assertEquals(123L, mapper.toRecord(row, List.of(KEY), "id", null).values()[0]);
    }

    @Test
    void malformedRowsKeysAndDeclaredTypesFailWithStableErrors() {
        for (String row : new String[] {"[]", "null", "{}", "{\"id\":null}", "{\"id\":\"1\"}",
                "{\"id\":9223372036854775808}"}) {
            assertEquals(ErrorCode.UPSTREAM_CLIENT_ERROR, assertThrows(ExportException.class,
                    () -> mapper.toRecord(json.readTree(row), List.of(KEY), "id", KEY)).code());
        }
        for (var fixture : Map.of(LogicalType.INTEGER, "\"x\"", LogicalType.DECIMAL, "\"bad\"",
                LogicalType.BOOLEAN, "1", LogicalType.UNSUPPORTED, "1").entrySet()) {
            var error = assertThrows(ExportException.class, () -> mapper.toRecord(
                    json.readTree("{\"id\":1,\"value\":" + fixture.getValue() + "}"),
                    List.of(column("value", fixture.getKey())), "id", KEY));
            assertEquals(fixture.getKey() == LogicalType.UNSUPPORTED
                    ? ErrorCode.UNSUPPORTED_COLUMN_TYPE : ErrorCode.UPSTREAM_CLIENT_ERROR, error.code());
        }
    }

    @Test
    void utf8FieldAndAggregateRowLimitsIncludeTheUnselectedContinuationKey() {
        var fieldBound = new RowJsonMapper(TestProps.with("omniflux.limits.max-field-bytes", "2B"));
        var row = json.readTree("{\"id\":1,\"value\":\"€\"}");
        assertEquals(ErrorCode.FIELD_TOO_LARGE, assertThrows(ExportException.class, () -> fieldBound.toRecord(
                row, List.of(column("value", LogicalType.TEXT)), "id", KEY)).code());
        var rowBound = new RowJsonMapper(TestProps.with(Map.of("omniflux.limits.max-field-bytes", "10B",
                "omniflux.limits.max-row-bytes", "3B")));
        assertEquals(ErrorCode.ROW_TOO_LARGE, assertThrows(ExportException.class, () -> rowBound.toRecord(
                row, List.of(column("value", LogicalType.TEXT)), "id", KEY)).code());
        assertEquals(1, rowBound.toRecord(row, List.of(), "id", KEY).key());
    }

    private static ColumnDescriptor column(String name, LogicalType type) {
        return ColumnDescriptor.of(name, type);
    }
}
