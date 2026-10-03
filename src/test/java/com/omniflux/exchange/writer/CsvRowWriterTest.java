package com.omniflux.exchange.writer;

import com.omniflux.exchange.TestProps;
import com.omniflux.exchange.config.OmnifluxProperties;
import com.omniflux.exchange.job.ErrorCode;
import com.omniflux.exchange.job.ExportException;
import com.omniflux.exchange.meta.ColumnDescriptor;
import com.omniflux.exchange.meta.LogicalType;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Byte-for-byte golden tests of CSV dialect v1 (spec §6.5) plus the byte-limit
 * guards. Limit values come from the REAL application.yml via TestProps, not
 * hardcoded copies that would drift.
 */
class CsvRowWriterTest {

    private static final CsvDialect RAW_V1 = CsvDialect.RAW;

    private static final OmnifluxProperties PROPS = TestProps.defaults();
    private static final long MAX_FIELD_BYTES = PROPS.limits().maxFieldBytes().toBytes();   // 64 KiB
    private static final long MAX_ROW_BYTES = PROPS.limits().maxRowBytes().toBytes();      // 4 MiB
    private static final Set<Integer> ALLOWED = PROPS.limits().allowedControlChars().stream()
            .map(s -> s.codePointAt(0)).collect(Collectors.toUnmodifiableSet());

    // --------------------------------------------------------- golden outputs

    @Test void goldenNullVersusEmptyString() throws Exception {
        // Dialect v1: unquoted-empty == NULL, quoted-"" == empty string.
        // Commons CSV CANNOT produce this: under MINIMAL it force-quotes an empty
        // token only when it is FIRST, so field 3 would be indistinguishable from NULL.
        assertEquals("id,b,c,d\r\n1,,\"\",plain\r\n",
                write(new Object[]{1, null, "", "plain"}));
    }

    @Test void goldenQuotingAndDoubling() throws Exception {
        // Commas force quotes; an embedded quote is doubled (RFC 4180 §2.7).
        assertEquals("id,b,c,d\r\n2,\"a,b\",\"x\"\"y\",\r\n",
                write(new Object[]{2, "a,b", "x\"y", null}));
    }

    @Test void goldenEmbeddedNewline() throws Exception {
        // LF inside a field is DATA: it forces quoting and is written through
        // unchanged — CRLF is only ever the record terminator.
        assertEquals("id,b,c,d\r\n3,\"l1\nl2\",,\r\n",
                write(new Object[]{3, "l1\nl2", null, null}));
    }

    @Test void goldenTrailingNullColumn() throws Exception {
        // A trailing NULL still emits its separator: the record never ends early,
        // or the last column would silently vanish from wide rows.
        assertEquals("id,b,c,d\r\n4,a,b,\r\n",
                write(new Object[]{4, "a", "b", null}));
    }

    @Test void rawModeDoesNotAlterFormulas() throws Exception {
        // RAW is the explicit exact-value dialect: = + - @ prefixes pass
        // through untouched when a caller deliberately requests it.
        assertEquals("id,b,c,d\r\n1,=SUM(A1:A2),-1+1,@cmd\r\n",
                write(new Object[]{1, "=SUM(A1:A2)", "-1+1", "@cmd"}));
    }

    @Test void spreadsheetSafeNeutralizesDataANDHeaders() throws Exception {
        // Headers are attacker-influenced too: a view's column aliases are just
        // strings, so BOTH the header row and the data row are neutralized.
        var columns = List.of(ColumnDescriptor.of("=hdr", LogicalType.TEXT));
        assertEquals("'=hdr\r\n'=1+1\r\n",
                write(CsvDialect.SPREADSHEET_SAFE, columns, new Object[]{"=1+1"}));
    }

    @Test void closingTheWriterDoesNotCloseTheDownstreamStream() throws Exception {
        // Spec §4.3: the writer wraps the bridge in NonClosingOutputStream, so
        // close() never ends the byte publisher beneath it — but the bytes it
        // buffered must still arrive.
        var downstream = new ByteArrayOutputStream() {
            boolean closed;
            @Override public void close() { closed = true; }
        };
        var writer = new CsvRowWriter(downstream, RAW_V1, MAX_FIELD_BYTES, MAX_ROW_BYTES, ALLOWED);
        writer.writeHeader(List.of(ColumnDescriptor.of("t", LogicalType.TEXT)));
        writer.writeRow(new Object[]{"x"});
        writer.finishContainer();
        writer.close();
        assertFalse(downstream.closed, "the bridge stream outlives the writer");
        assertEquals("t\r\nx\r\n", downstream.toString(StandardCharsets.UTF_8));
    }

    // ---------------------------------------------------------- byte guards

    @Test void aggregateRowBytesAreEnforcedAcrossAllFields() {
        // Each field individually legal, the ROW over the limit. A per-field guard
        // alone lets 200 x 64KB = 12.8MB through against a 4MB row limit.
        var ex = assertThrows(ExportException.class,
                () -> write(asciiFieldsTotallingBytes()));
        assertEquals(ErrorCode.ROW_TOO_LARGE, ex.code());
    }

    @Test void aggregateRowBytesAreCountedInUTF8BYTESNotJavaChars() {
        // REGRESSION. With ASCII data, chars == bytes, so an accumulator that sums
        // String.length() passes the test above AND the isolated utf8Length() test
        // while under-counting real data by 3x. Only multibyte data can separate them.
        //
        // EVERY FIELD MUST STAY UNDER max-field-bytes, or the per-field guard fires
        // first and the test proves nothing about the AGGREGATE. 64 KiB / 3 bytes =
        // 21,845 characters is the ceiling for '€', so use 21,000:
        //   per field 21,000 x 3 = 63,000 bytes  (< 65,536 — legal)
        //   row       100 x 63,000 =  6,300,000  (> 4 MiB — ROW_TOO_LARGE)
        //   as chars  100 x 21,000 =  2,100,000  (< 4 Mi — a char-counter ACCEPTS it)
        var ex = assertThrows(ExportException.class,
                () -> write(fields(100, "€".repeat(21_000))));
        assertEquals(ErrorCode.ROW_TOO_LARGE, ex.code(),
                "if this is FIELD_TOO_LARGE the fields are too big and the test is vacuous");
    }

    @Test void aFieldOverTheFieldLimitIsFIELDTooLargeNotRowTooLarge() {
        // The other side of the same boundary: the two guards must not be confused
        // for one another, or the error a caller sees names the wrong dimension.
        var ex = assertThrows(ExportException.class,
                () -> write(fields(1, "€".repeat(21_846))));   // 65,538 bytes
        assertEquals(ErrorCode.FIELD_TOO_LARGE, ex.code());
    }

    @Test void aRowAtEXACTLYTheLimitIsAccepted() {
        // The other half of a boundary: an off-by-one that rejects legal rows is
        // just as wrong, and far more likely to reach production unnoticed because
        // it only shows up on the largest customer's data.
        assertDoesNotThrow(() -> write(multibyteRowOfExactlyBytes(4 << 20)));
    }

    @Test void aRowOneByteOverTheLimitIsRejected() {
        var ex = assertThrows(ExportException.class,
                () -> write(multibyteRowOfExactlyBytes((4 << 20) + 1)));
        assertEquals(ErrorCode.ROW_TOO_LARGE, ex.code());
    }

    // -------------------------------------------------------------- helpers

    /** Header + one row under the RAW dialect, fully flushed, as on storage. */
    private String write(Object[] row) throws Exception {
        return write(RAW_V1, columns(row.length), row);
    }

    private String write(CsvDialect dialect, List<ColumnDescriptor> columns, Object[] row)
            throws Exception {
        var out = new ByteArrayOutputStream();
        RowWriter writer = new CsvRowWriter(out, dialect, MAX_FIELD_BYTES, MAX_ROW_BYTES, ALLOWED);
        writer.writeHeader(columns);
        writer.writeRow(row);
        writer.finishContainer();
        writer.close();
        return out.toString(StandardCharsets.UTF_8);
    }

    /** The golden schema's names — id,b,c,d,e… — all TEXT; the dialect examples
     *  in spec §6.5 use exactly the first four. */
    private static List<ColumnDescriptor> columns(int n) {
        var names = new String[n];
        for (int i = 0; i < n; i++)
            names[i] = i == 0 ? "id" : i < 26 ? String.valueOf((char) ('a' + i)) : "c" + i;
        return Arrays.stream(names).map(nm -> ColumnDescriptor.of(nm, LogicalType.TEXT)).toList();
    }

    private static Object[] fields(int n, String s) {
        Object[] row = new Object[n];
        Arrays.fill(row, s);
        return row;
    }

    /** ASCII fields each exactly AT (never over) the per-field limit, summing to
     *  5 MiB = 80 x 65,536, so every field is individually legal and only the
     *  AGGREGATE guard can fire. */
    private static Object[] asciiFieldsTotallingBytes() {
        int totalBytes = 5 << 20;
        int perField = (int) MAX_FIELD_BYTES;
        var fields = new ArrayList<>();
        int remaining = totalBytes;
        while (remaining >= perField) {
            fields.add("a".repeat(perField));
            remaining -= perField;
        }
        if (remaining > 0) fields.add("a".repeat(remaining));
        return fields.toArray();
    }

    /** A row of multibyte ('€', 3 UTF-8 bytes) fields whose SOURCE VALUE bytes
     *  sum to EXACTLY {@code totalBytes}. Fields hold 21,845 '€' = 65,535 bytes,
     *  one under the 64 KiB field cap; a final field absorbs the remainder with
     *  whole euros plus 1-byte 'x's, so the byte count is exact — the off-by-one
     *  under test. A char-counter sees a third of this and accepts it. */
    private static Object[] multibyteRowOfExactlyBytes(int totalBytes) {
        int eurosPerField = (int) (MAX_FIELD_BYTES / 3);       // 21,845 '€' = 65,535 bytes
        var fields = new ArrayList<>();
        int remaining = totalBytes;
        while (remaining > MAX_FIELD_BYTES) {
            fields.add("€".repeat(eurosPerField));
            remaining -= eurosPerField * 3;
        }
        fields.add("€".repeat(remaining / 3) + "x".repeat(remaining % 3));
        return fields.toArray();
    }
}
