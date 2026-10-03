package com.omniflux.exchange.adapter.rest;

import com.omniflux.exchange.config.OmnifluxProperties;
import com.omniflux.exchange.job.ErrorCode;
import com.omniflux.exchange.job.ExportException;
import com.omniflux.exchange.meta.ColumnDescriptor;
import com.omniflux.exchange.meta.LogicalType;
import com.omniflux.exchange.source.RowRecord;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

/** Maps one Data API JSON object to the source SPI's positional row shape. */
public final class RowJsonMapper {

    private static final String COLUMN_LABEL = "column ";

    private final long maxFieldBytes;
    private final long maxRowBytes;

    public RowJsonMapper(OmnifluxProperties properties) {
        this.maxFieldBytes = properties.limits().maxFieldBytes().toBytes();
        this.maxRowBytes = properties.limits().maxRowBytes().toBytes();
        if (maxFieldBytes < 0 || maxRowBytes < 0) {
            throw new IllegalArgumentException("row and field byte limits must not be negative");
        }
    }

    /**
     * The key descriptor is supplied separately because the internally
     * selected primary key need not be part of the output projection.
     */
    public RowRecord toRecord(JsonNode row, List<ColumnDescriptor> outputColumns,
                              String keyColumn, ColumnDescriptor keyDescriptor) {
        requireObject(row);
        Objects.requireNonNull(outputColumns, "outputColumns");
        Objects.requireNonNull(keyColumn, "keyColumn");

        var keyNode = row.get(keyColumn);
        long key = readKey(keyNode, keyColumn);
        long rowBytes = 0;

        if (keyDescriptor != null) {
            rowBytes = addMeasuredValue(rowBytes, keyDescriptor, keyNode);
        }

        var values = new Object[outputColumns.size()];
        for (int i = 0; i < outputColumns.size(); i++) {
            var column = outputColumns.get(i);
            var node = row.get(column.name());
            values[i] = convert(node, column);
            if (!column.name().equals(keyColumn) || keyDescriptor == null) {
                rowBytes = addMeasuredValue(rowBytes, column, node);
            }
        }
        return new RowRecord(key, values);
    }

    public long key(JsonNode row, String keyColumn) {
        requireObject(row);
        return readKey(row.get(keyColumn), keyColumn);
    }

    private long addMeasuredValue(long rowBytes, ColumnDescriptor column, JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) return rowBytes;
        var value = convert(node, column);
        long bytes = sourceBytes(value, node, column.logicalType());
        if (bytes > maxFieldBytes) {
            throw new ExportException(ErrorCode.FIELD_TOO_LARGE,
                    column.name() + ": " + bytes + " > " + maxFieldBytes + " bytes");
        }
        if (rowBytes > maxRowBytes - bytes) {
            throw new ExportException(ErrorCode.ROW_TOO_LARGE,
                    "row source bytes exceed " + maxRowBytes + " bytes");
        }
        return rowBytes + bytes;
    }

    private static Object convert(JsonNode node, ColumnDescriptor column) {
        if (node == null || node.isNull() || node.isMissingNode()) return null;
        return switch (column.logicalType()) {
            case INTEGER -> {
                if (!node.isIntegralNumber() || !node.canConvertToLong()) {
                    throw upstream(COLUMN_LABEL + column.name() + " is not an integer");
                }
                yield node.longValue();
            }
            case DECIMAL -> {
                if (!node.isNumber() && !node.isString()) {
                    throw upstream(COLUMN_LABEL + column.name() + " is not a decimal");
                }
                try {
                    yield node.isNumber() ? node.decimalValue() : new BigDecimal(node.stringValue());
                } catch (NumberFormatException ex) {
                    throw new ExportException(ErrorCode.UPSTREAM_CLIENT_ERROR,
                            COLUMN_LABEL + column.name() + " is not a decimal", ex);
                }
            }
            case BOOLEAN -> {
                if (!node.isBoolean()) throw upstream(COLUMN_LABEL + column.name() + " is not boolean");
                yield node.booleanValue();
            }
            case TEXT, DATE, TIMESTAMP, UUID -> node.isString() ? node.stringValue() : node.asString();
            case BINARY -> node.isBinary() ? binary(node, column) : node.asString();
            case UNSUPPORTED -> throw new ExportException(ErrorCode.UNSUPPORTED_COLUMN_TYPE,
                    COLUMN_LABEL + column.name() + " is unsupported");
        };
    }

    private static byte[] binary(JsonNode node, ColumnDescriptor column) {
        try {
            return node.binaryValue();
        } catch (Exception ex) {
            throw new ExportException(ErrorCode.UPSTREAM_CLIENT_ERROR,
                    COLUMN_LABEL + column.name() + " contains invalid binary JSON", ex);
        }
    }

    private static long sourceBytes(Object value, JsonNode node, LogicalType type) {
        if (value == null) return 0;
        if (type == LogicalType.BINARY && value instanceof byte[] bytes) return bytes.length;
        return sourceText(value, node).getBytes(StandardCharsets.UTF_8).length;
    }

    private static String sourceText(Object value, JsonNode node) {
        if (value instanceof String text) return text;
        return node.isString() ? node.stringValue() : node.toString();
    }

    private static long readKey(JsonNode node, String keyColumn) {
        if (node == null || node.isNull() || !node.isIntegralNumber() || !node.canConvertToLong()) {
            throw upstream("primary key " + keyColumn + " is not a 64-bit integer");
        }
        return node.longValue();
    }

    private static void requireObject(JsonNode row) {
        if (row == null || !row.isObject()) {
            throw upstream("Data API response element is not a JSON object");
        }
    }

    private static ExportException upstream(String detail) {
        return new ExportException(ErrorCode.UPSTREAM_CLIENT_ERROR, detail);
    }
}
