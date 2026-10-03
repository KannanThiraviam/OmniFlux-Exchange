package com.omniflux.exchange.web;

/** Reference estimator for the intentionally materializing counter-example. */
public final class NaiveExportRunner {
    private NaiveExportRunner() { }

    public static long estimatedRetainedBytes(long rows, long bytesPerRow) {
        if (rows < 0 || bytesPerRow < 0) throw new IllegalArgumentException("sizes must not be negative");
        return Math.multiplyExact(rows, bytesPerRow);
    }
}
