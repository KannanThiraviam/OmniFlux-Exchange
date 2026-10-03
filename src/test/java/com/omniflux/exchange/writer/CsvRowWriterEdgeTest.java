package com.omniflux.exchange.writer;

import com.omniflux.exchange.job.ErrorCode;
import com.omniflux.exchange.job.ExportException;
import com.omniflux.exchange.meta.ColumnDescriptor;
import com.omniflux.exchange.meta.LogicalType;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertThrows;

class CsvRowWriterEdgeTest {
    private static final List<ColumnDescriptor> COLUMNS = List.of(
            ColumnDescriptor.of("value", LogicalType.TEXT));

    @Test
    void headerMustPrecedeRowsAndRowsMustMatchItsWidth() throws Exception {
        try (var writer = writer(100)) {
            assertThrows(IllegalStateException.class, () -> writer.writeRow(new Object[]{"x"}));
            writer.writeHeader(COLUMNS);
            assertThrows(IllegalArgumentException.class, () -> writer.writeRow(new Object[]{"x", "y"}));
            writer.close();
            assertThrows(IllegalStateException.class, writer::finishContainer);
        }
    }

    @Test
    void fieldAndRowGuardsUseTheirTypedErrors() throws Exception {
        try (var field = writer(1);
             var row = new CsvRowWriter(new ByteArrayOutputStream(), CsvDialect.RAW,
                     100, 1, Set.of(9, 10, 13))) {
            field.writeHeader(COLUMNS);
            var fieldError = assertThrows(ExportException.class, () -> field.writeRow(new Object[]{"ab"}));
            org.junit.jupiter.api.Assertions.assertEquals(ErrorCode.FIELD_TOO_LARGE, fieldError.code());

            row.writeHeader(List.of(ColumnDescriptor.of("a", LogicalType.TEXT),
                    ColumnDescriptor.of("b", LogicalType.TEXT)));
            var rowError = assertThrows(ExportException.class, () -> row.writeRow(new Object[]{"a", "b"}));
            org.junit.jupiter.api.Assertions.assertEquals(ErrorCode.ROW_TOO_LARGE, rowError.code());
        }
    }

    @Test
    void controlCharactersOutsideTheAllowlistAreRejected() throws Exception {
        try (var writer = writer(100)) {
            writer.writeHeader(COLUMNS);
            var error = assertThrows(ExportException.class, () -> writer.writeRow(new Object[]{"bad\u0001"}));
            org.junit.jupiter.api.Assertions.assertEquals(ErrorCode.CHARACTER_NOT_REPRESENTABLE, error.code());
        }
    }

    /** Row limit fixed at 100: every case here is a per-field-boundary test. */
    private static CsvRowWriter writer(long field) {
        return new CsvRowWriter(new ByteArrayOutputStream(), CsvDialect.RAW, field, 100,
                Set.of(9, 10, 13));
    }
}
