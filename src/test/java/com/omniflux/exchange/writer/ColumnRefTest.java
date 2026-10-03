package com.omniflux.exchange.writer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ColumnRefTest {

    @Test void convertsTheA1BoundaryColumns() {
        assertEquals("A", ColumnRef.of(0));
        assertEquals("Z", ColumnRef.of(25));
        assertEquals("AA", ColumnRef.of(26));
        assertEquals("AB", ColumnRef.of(27));
        assertEquals("AZ", ColumnRef.of(51));
        assertEquals("BA", ColumnRef.of(52));
        assertEquals("XFD", ColumnRef.of(16_383));
    }

    @Test void oneBasedConversionIsExplicit() {
        assertEquals("A", ColumnRef.fromOneBased(1));
        assertEquals("AA", ColumnRef.fromOneBased(27));
        assertEquals("XFD", ColumnRef.fromOneBased(16_384));
    }

    @Test void rejectsNegativeOrZeroInputs() {
        assertThrows(IllegalArgumentException.class, () -> ColumnRef.of(-1));
        assertThrows(IllegalArgumentException.class, () -> ColumnRef.fromOneBased(0));
    }
}
