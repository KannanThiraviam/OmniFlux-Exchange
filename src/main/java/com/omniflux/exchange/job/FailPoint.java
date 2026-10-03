package com.omniflux.exchange.job;

/**
 * Deliberate crash points used only by forked fault-injection tests.
 * Production has no armed failpoint unless the system property is explicitly
 * supplied; the default path is a no-op.
 */
public enum FailPoint {
    AFTER_FIRST_PART,
    AFTER_COMPLETE_MPU,
    DURING_LEASE_HOLD,
    NONE;

    public static FailPoint armed() {
        String value = System.getProperty("omniflux.failpoint", "NONE");
        try {
            return valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException _) {
            return NONE;
        }
    }

    public static void reach(FailPoint point) {
        if (point != null && point != NONE && point == armed()) {
            Runtime.getRuntime().halt(137);
        }
    }
}
