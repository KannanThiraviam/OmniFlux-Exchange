package com.omniflux.exchange.source;

import java.util.List;

/** SQL text and its ordered bind values travel together. */
public record SqlPlan(String sql, List<Object> binds) {
    public SqlPlan {
        if (sql == null || sql.isBlank()) throw new IllegalArgumentException("sql must not be blank");
        binds = List.copyOf(binds == null ? List.of() : binds);
    }
}
