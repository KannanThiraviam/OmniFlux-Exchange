package com.omniflux.exchange.source;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** RowRecord is a value passed between pipeline stages; records compare array
 *  components by reference, so equality must be overridden to compare content. */
class RowRecordTest {

    @Test void equalArrayContentsMakeEqualRecords() {
        assertEquals(new RowRecord(7, new Object[]{"a", 1L}),
                new RowRecord(7, new Object[]{"a", 1L}));
    }

    @Test void equalRecordsShareHashCode() {
        assertEquals(new RowRecord(7, new Object[]{"a", 1L}).hashCode(),
                new RowRecord(7, new Object[]{"a", 1L}).hashCode());
    }

    @Test void differentKeyOrContentsAreNotEqual() {
        assertNotEquals(new RowRecord(7, new Object[]{"a"}), new RowRecord(8, new Object[]{"a"}));
        assertNotEquals(new RowRecord(7, new Object[]{"a"}), new RowRecord(7, new Object[]{"b"}));
        assertNotEquals(new RowRecord(7, new Object[]{"a"}), new RowRecord(7, new Object[]{"a", "b"}));
    }

    @Test void toStringShowsContentsNotArrayIdentity() {
        String s = new RowRecord(7, new Object[]{"a", 1L}).toString();
        assertTrue(s.contains("7") && s.contains("[a, 1]"), s);
    }
}
