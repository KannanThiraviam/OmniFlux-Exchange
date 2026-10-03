package com.omniflux.exchange.source;

import java.util.List;

/**
 * Keyset paging bounds shared by every dialect page query: the fully qualified
 * relation, its key column, the pushed-down filters, and the continuation
 * window (exclusive lower bound, inclusive high-water upper bound, page limit).
 */
public record PageSpec(String schema, String relation, String key,
                       List<FilterSpec> filters, long afterExclusive, long highWater,
                       int limit) { }
