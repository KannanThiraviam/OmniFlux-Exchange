package com.omniflux.exchange.config;

import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LegacyTimeZonesTest {

    @Test
    void legacyIdsMapToTheSameZoneUnderTheirModernSpelling() {
        assertEquals("Asia/Kolkata", LegacyTimeZones.modernSpelling("Asia/Calcutta"));
        assertEquals("Europe/Kyiv", LegacyTimeZones.modernSpelling("Europe/Kiev"));
        Instant now = Instant.now();
        assertEquals(ZoneId.of("Asia/Calcutta").getRules().getOffset(now),
                ZoneId.of("Asia/Kolkata").getRules().getOffset(now));
    }

    @Test
    void modernAndUnknownIdsAreUnchanged() {
        assertEquals("UTC", LegacyTimeZones.modernSpelling("UTC"));
        assertEquals("Asia/Kolkata", LegacyTimeZones.modernSpelling("Asia/Kolkata"));
    }
}
