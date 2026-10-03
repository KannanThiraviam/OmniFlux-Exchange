package com.omniflux.exchange.config;

import java.time.ZoneId;
import java.util.Map;
import java.util.TimeZone;

/**
 * pgjdbc sends the JVM default zone in its startup packet, and PostgreSQL 18's
 * tzdata no longer accepts legacy IDs such as {@code Asia/Calcutta}, which the
 * JDK still reports on Windows hosts. Flyway's JDBC connection then fails with
 * {@code FATAL: invalid value for parameter "TimeZone"} before any query.
 * {@link ZoneId#normalized()} cannot help: to the JDK the legacy ID is
 * canonical. Each mapping is the same zone by rules and offset.
 */
public final class LegacyTimeZones {
    private static final Map<String, String> MODERN_SPELLINGS = Map.of(
            "Asia/Calcutta", "Asia/Kolkata",
            "Asia/Rangoon", "Asia/Yangon",
            "Asia/Saigon", "Asia/Ho_Chi_Minh",
            "Asia/Katmandu", "Asia/Kathmandu",
            "Asia/Dacca", "Asia/Dhaka",
            "Europe/Kiev", "Europe/Kyiv");

    private LegacyTimeZones() { }

    /** The modern spelling of a legacy zone ID, or the ID unchanged. */
    public static String modernSpelling(String zoneId) {
        return MODERN_SPELLINGS.getOrDefault(zoneId, zoneId);
    }

    /** Replaces a legacy JVM default zone with its modern spelling. */
    public static void normalizeJvmDefault() {
        String current = ZoneId.systemDefault().getId();
        String modern = modernSpelling(current);
        if (!modern.equals(current)) TimeZone.setDefault(TimeZone.getTimeZone(modern));
    }
}
