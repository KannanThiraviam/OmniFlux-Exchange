package com.omniflux.exchange.writer;

import com.omniflux.exchange.job.ErrorCode;
import com.omniflux.exchange.job.ExportException;
import com.omniflux.exchange.meta.ColumnDescriptor;
import com.omniflux.exchange.meta.LogicalType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CellValueFormatterTest {

    @Test void byteArrayIsHexNotAnObjectIdentity() {
        // "[B@1f2e3d" is a hashcode, not data — the export must carry the bytes.
        assertEquals("0x0a1b", CellValueFormatter.format(binCol(), new byte[]{0x0a, 0x1b}));
    }

    @Test void timestampsAreIsoUtcRegardlessOfJavaType() {
        // The same instant arrives as an Instant or as an OffsetDateTime in the
        // driver's zone; the export must not vary by which Java type carried it.
        var utc = Instant.parse("2024-01-15T10:30:00Z");
        var plusTwo = OffsetDateTime.ofInstant(utc, ZoneOffset.ofHours(2));   // 12:30+02:00
        assertEquals("2024-01-15T10:30:00Z", CellValueFormatter.format(tsCol(), utc));
        assertEquals(CellValueFormatter.format(tsCol(), utc), CellValueFormatter.format(tsCol(), plusTwo));
    }

    @Test void decimalsKeepScaleAndNeverUseScientificNotation() {
        // toPlainString(): 1E-8 is a valid BigDecimal literal but no spreadsheet
        // or CSV consumer owes us an exponent parser.
        assertEquals("0.00000001", CellValueFormatter.format(decCol(), new BigDecimal("1E-8")));
        assertEquals("1.230", CellValueFormatter.format(decCol(), new BigDecimal("1.230")));
    }

    @Test void controlCharactersOtherThanTabCrLfAreRejected() {
        // The restriction that makes wire-envelope-factor 3.0 a BOUND rather than an
        // average: a C0 character costs six JSON bytes for one source byte.
        // EVERY C0 and C1 code point, not a sample of five: an implementation with a
        // five-element denylist passes a five-element test and admits U+0001.
        for (int c = 0x00; c <= 0x9F; c++) {
            if (c == '\t' || c == '\r' || c == '\n') continue;
            if (c > 0x1F && c < 0x7F) continue;                 // printable ASCII
            int cp = c;
            var ex = assertThrows(ExportException.class,
                    () -> CellValueFormatter.format(textCol(), "a" + (char) cp + "b"),
                    "U+%04X was accepted".formatted(cp));
            assertEquals(ErrorCode.CHARACTER_NOT_REPRESENTABLE, ex.code(), "U+%04X".formatted(cp));
        }
    }

    @Test void everyPrintableCodePointIsAccepted() {
        // The other direction, or an implementation rejecting everything passes.
        for (int c = 0x20; c <= 0x7E; c++) {
            int cp = c;
            assertDoesNotThrow(() -> CellValueFormatter.format(textCol(), String.valueOf((char) cp)));
        }
        for (var s : List.of("€", "😀", "日本語", "\u00A0"))
            assertDoesNotThrow(() -> CellValueFormatter.format(textCol(), s));
    }

    @Test void tabCrAndLfSurviveBecauseTheyAreREALDataInTextColumns() {
        // …and dialect v1 quotes them. Rejecting them would break ordinary content.
        assertDoesNotThrow(() -> CellValueFormatter.format(textCol(), "a\tb\r\nc"));
    }

    @Test void unpairedSurrogatesThrowCharacterNotRepresentable() {
        var ex = assertThrows(ExportException.class,
                () -> CellValueFormatter.format(textCol(), "bad\uD800end"));
        assertEquals(ErrorCode.CHARACTER_NOT_REPRESENTABLE, ex.code());
    }

    @Test void utf8ByteLengthIsMeasuredNotCharacterLength() {
        // A 4-byte emoji is ONE char. A char-based limit under-counts by 4x.
        assertEquals(4, CellValueFormatter.utf8Length("😀"));
        assertEquals(3, CellValueFormatter.utf8Length("€"));
        assertEquals(1, CellValueFormatter.utf8Length("x"));
    }

    // -------------------------------------------------------------- helpers

    private static ColumnDescriptor textCol() { return ColumnDescriptor.of("t", LogicalType.TEXT); }
    private static ColumnDescriptor binCol()  { return ColumnDescriptor.of("b", LogicalType.BINARY); }
    private static ColumnDescriptor tsCol()   { return ColumnDescriptor.of("ts", LogicalType.TIMESTAMP); }
    private static ColumnDescriptor decCol()  { return ColumnDescriptor.of("d", LogicalType.DECIMAL); }
}
