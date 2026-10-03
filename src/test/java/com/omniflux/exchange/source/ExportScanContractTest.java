package com.omniflux.exchange.source;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import static org.junit.jupiter.api.Assertions.*;

class ExportScanContractTest {

    @Test void anEmptyRelationIsRepresentableWithoutASentinel() {
        // A bare long cannot express "no rows" without a magic value, and a magic
        // value is a bug waiting for a relation whose keys reach it — so an empty
        // relation is null, never a sentinel.
        var scan = new ExportScan(java.util.List.of(), null, Flux.empty());
        assertNull(scan.highWater());
    }

    @Test void recordsAreSingleSubscription() {
        // A cold Flux in a record would silently RE-RUN the entire export if
        // subscribed twice — different bytes, doubled load, no error.
        var scan = new ExportScan(java.util.List.of(), 1L,
                                  ExportScan.singleSubscription(Flux.just(new RowRecord(1, new Object[0]))));
        scan.records().blockLast();
        assertThrows(IllegalStateException.class, () -> scan.records().blockLast());
    }

    @Test void rowRecordKeepsTheKeySeparateFromExportedValues() {
        // The PK is selected internally for continuation. If it were prepended to
        // values, the header (requested columns only) and the data would differ in
        // width on every single export.
        var r = new RowRecord(42L, new Object[]{"IN"});
        assertEquals(42L, r.key());
        assertEquals(1, r.values().length);
    }
}
