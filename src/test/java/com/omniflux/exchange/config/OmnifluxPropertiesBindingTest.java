package com.omniflux.exchange.config;

import com.omniflux.exchange.PostgresTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

@SpringBootTest
class OmnifluxPropertiesBindingTest extends PostgresTestBase {

    @Autowired OmnifluxProperties p;

    // A record binds happily with every field null, and an all-non-null test
    // passes against that. Assert representative VALUES from every subtree.
    @Test void limits()   { assertEquals(64L << 10, p.limits().maxFieldBytes().toBytes());
                            assertEquals(4L << 20,  p.limits().maxRowBytes().toBytes());
                            assertEquals(List.of("\t", "\r", "\n"), p.limits().allowedControlChars()); }
    @Test void export()   { // max-page-bytes is DELETED — see the note in Task 3 Step 4
                            assertEquals(64L << 10, p.export().bridgeChunkSize().toBytes());
                            assertEquals(1, p.export().prefetch());
                            assertEquals(4, p.export().scheduler().threads()); }
    @Test void storage()  { assertEquals(8L << 20, p.storage().partSize().toBytes());
                            assertEquals(16L << 20, p.storage().uploadBuffer().toBytes()); }
    @Test void source()   { assertEquals("rest", p.source().defaultAdapter());
                            assertEquals(5000, p.source().rest().pageSize());
                            // max-row-bytes(8MB) x wire-envelope-factor(1.5): a legal
                            // 4MB row is LARGER than 4MB once JSON names, quotes and
                            // escapes are counted, so an 8MB codec cap would reject it.
                            assertEquals(6.0, p.source().rest().wireEnvelopeFactor());
                            assertEquals(1L << 20, p.source().rest().wireEnvelopeHeadroom().toBytes());
                            // 4MB x 3.0 + 1MB. The headroom covers what the FACTOR
                            // does not: property names, punctuation, array syntax and
                            // the internally-selected PK. With the factor alone a row
                            // of fully-escaped values consumes the entire budget and
                            // the envelope itself has nowhere to go.
                            assertEquals(25L << 20, p.source().rest().maxInMemorySize().toBytes()); }
    @Test void seedBytes() { assertEquals(32L << 20, p.seed().batchBytes().toBytes()); }
    @Test void queue()    { assertEquals(4, p.queue().maxConcurrent());
                            assertEquals(500, p.queue().maxDepth()); }
    @Test void security() { assertEquals("DISABLED", p.security().authMode());
                            assertEquals(200, p.security().maxColumns());
                            assertEquals(100, p.security().maxInValues());
                            assertEquals("v1", p.security().authzContextVersion()); }
    @Test void seed()     { assertEquals(5_000, p.seed().batchSize()); }
    @Test void bucketExpiry() {
        // The cache TTL is validated against THIS. Without the key the check in
        // StartupValidator has nothing to compare to and silently does nothing.
        assertEquals(java.time.Duration.ofDays(7), p.storage().bucketExpiry());
    }
    @Test void xlsx()     { assertEquals(1_000_000, p.xlsx().maxDataRows()); }
    @Test void resources(){ assertEquals(2.5, p.resources().javaExpansionFactor());
                            assertEquals(320L << 20, p.resources().maxHeapBudget().toBytes());
                            assertEquals(96L << 20, p.resources().nonHeapReserve().toBytes()); }
    @Test void ui()       { assertEquals(50, p.ui().pageSize()); }

    @Test void cacheIsOffByDefault() { assertFalse(p.cache().enabled()); }

    @Test void devPrincipalIsAFullPrincipalKey() {
        var d = p.security().devPrincipal();
        assertEquals("local", d.issuer());
        assertEquals("dev@local", d.subject());
        assertEquals("local", d.tenant());
    }
}
