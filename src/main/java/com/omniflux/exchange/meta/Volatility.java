package com.omniflux.exchange.meta;

/** Consumed by Tasks 18 (metadata) and 20 (cache eligibility). */
public enum Volatility {
    /** Rows are only ever appended, and keys only ever increase. Cacheable, and
     *  a max(pk) probe is a sound freshness check. */
    MONOTONIC_APPEND_ONLY,
    /** Never changes after load. Cacheable without a freshness probe. */
    STATIC,
    /** Rows may be updated or deleted in place. NEVER cacheable: max(pk) is
     *  unchanged by an UPDATE, so a freshness probe cannot detect the change. */
    MUTABLE
}
