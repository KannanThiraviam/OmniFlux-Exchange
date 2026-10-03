package com.omniflux.exchange.source;

import com.omniflux.exchange.meta.ColumnDescriptor;

import java.time.Duration;
import java.util.List;

public interface SqlDialect {
    String quote(String identifier);

    String relation(String schema, String relation);

    SqlPlan maxKeyQuery(String schema, String relation, String key, List<FilterSpec> filters);

    SqlPlan countQuery(String schema, String relation, String key,
                       List<FilterSpec> filters, long highWater);

    SqlPlan pageQuery(PageSpec page, List<String> columns);

    /**
     * Builds a page query that can mark an oversized row without silently
     * dropping it. Dialects that cannot project a bounded value may use the
     * ordinary page query; PostgreSQL overrides this with CASE expressions.
     */
    default SqlPlan guardedPageQuery(PageSpec page, List<ColumnDescriptor> columns,
                                     long maxFieldBytes, long maxRowBytes) {
        return pageQuery(page, columns.stream().map(ColumnDescriptor::name).toList());
    }

    String queryTimeoutHint(Duration timeout);
}
