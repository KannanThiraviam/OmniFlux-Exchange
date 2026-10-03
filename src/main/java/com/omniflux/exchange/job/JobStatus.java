package com.omniflux.exchange.job;

/** Persisted lifecycle states for a transfer job. */
public enum JobStatus {
    QUEUED,
    IN_PROGRESS,
    COMPLETED,
    FAILED,
    CANCELLED;

    public boolean terminal() {
        return this == COMPLETED || this == FAILED || this == CANCELLED;
    }

    public boolean workerOwned() {
        return this == IN_PROGRESS;
    }

    public static JobStatus parse(String value) {
        if (value == null) {
            return null;
        }
        return valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
    }
}
