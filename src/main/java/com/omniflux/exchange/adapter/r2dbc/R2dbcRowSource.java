package com.omniflux.exchange.adapter.r2dbc;

import com.omniflux.exchange.config.OmnifluxProperties;
import com.omniflux.exchange.export.ExportTiming;
import com.omniflux.exchange.job.ErrorCode;
import com.omniflux.exchange.job.ExportException;
import com.omniflux.exchange.meta.ColumnDescriptor;
import com.omniflux.exchange.meta.RelationDescriptor;
import com.omniflux.exchange.meta.SchemaCatalog;
import com.omniflux.exchange.security.AuthContext;
import com.omniflux.exchange.source.*;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** R2DBC keyset adapter. It captures the high-water bound once per export. */
public final class R2dbcRowSource implements RowSource {
    private final DatabaseClient client;
    private final SchemaCatalog catalog;
    private final SqlDialect dialect;
    private final OmnifluxProperties props;
    private final ExportTiming timing;
    private final Integer pageSizeOverride;
    private final AuthContext auth;

    public R2dbcRowSource(DatabaseClient client, SchemaCatalog catalog,
                          SqlDialect dialect, OmnifluxProperties props,
                          ExportTiming timing, Integer pageSizeOverride,
                          AuthContext auth) {
        this.client = Objects.requireNonNull(client, "client");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.dialect = Objects.requireNonNull(dialect, "dialect");
        this.props = Objects.requireNonNull(props, "props");
        this.timing = timing;
        if (pageSizeOverride != null && pageSizeOverride < 1) {
            throw new IllegalArgumentException("pageSizeOverride must be positive");
        }
        this.pageSizeOverride = pageSizeOverride;
        this.auth = auth;
    }

    @Override
    public Mono<ExportScan> prepare(ExportRequest request) {
        Objects.requireNonNull(request, "request");
        return catalog.describe("r2dbc", request.relation(), auth).flatMap(meta -> {
            List<ColumnDescriptor> columns = requestedColumns(meta, request);
            String key = meta.keyContract().column();
            List<FilterSpec> filters = request.filters();
            catalog.requireFilters(meta, filters);
            return executeMaxKey(meta, key, filters)
                    .flatMap(highWater -> {
                        Mono<Long> count = isXlsx(request) && highWater.isPresent()
                                ? executeCount(meta, key, filters, highWater.getAsLong())
                                : Mono.just(Long.MIN_VALUE);
                        return count.map(rowCount -> new ExportScan(columns,
                                highWater.isPresent() ? highWater.getAsLong() : null,
                                records(meta, key, columns, filters,
                                        highWater.orElse(Long.MIN_VALUE)),
                                rowCount == Long.MIN_VALUE ? null : rowCount));
                    });
        });
    }

    private Mono<OptionalLong> executeMaxKey(RelationDescriptor meta, String key,
                                              List<FilterSpec> filters) {
        SqlPlan plan = dialect.maxKeyQuery(meta.schema(), meta.name(), key, filters);
        return execute(plan)
                .next()
                .map(row -> {
                    Object raw = row.values().values().stream().findFirst().orElse(null);
                    return raw == null ? OptionalLong.empty() : OptionalLong.of(asLong(raw));
                })
                .defaultIfEmpty(OptionalLong.empty())
                .onErrorMap(error -> error instanceof ExportException ? error
                        : new ExportException(ErrorCode.QUERY_TIMEOUT,
                        "could not determine the relation high-water key", error));
    }

    private Mono<Long> executeCount(RelationDescriptor meta, String key,
                                    List<FilterSpec> filters, long highWater) {
        SqlPlan plan = dialect.countQuery(meta.schema(), meta.name(), key, filters, highWater);
        return execute(plan)
                .next()
                .map(row -> row.values().values().stream().findFirst()
                        .map(R2dbcRowSource::asLong).orElse(0L))
                .defaultIfEmpty(0L)
                .onErrorMap(error -> error instanceof ExportException ? error
                        : new ExportException(ErrorCode.QUERY_TIMEOUT,
                        "could not count the filtered relation", error));
    }

    private static boolean isXlsx(ExportRequest request) {
        return "XLSX".equalsIgnoreCase(request.format());
    }

    /** Long.MIN_VALUE is the "absent" sentinel for primitive long options. */
    private Flux<RowRecord> records(RelationDescriptor meta, String key,
                                    List<ColumnDescriptor> columns, List<FilterSpec> filters,
                                    long highWaterInclusive) {
        if (highWaterInclusive == Long.MIN_VALUE) return Flux.empty();
        int pageSize = pageSizeOverride == null ? props.source().r2dbc().pageSize() : pageSizeOverride;
        return page(Long.MIN_VALUE, highWaterInclusive, meta, key, columns, filters, pageSize);
    }

    private Flux<RowRecord> page(long afterExclusive, long highWater, RelationDescriptor meta,
                                 String key, List<ColumnDescriptor> columns,
                                 List<FilterSpec> filters, int pageSize) {
        var count = new AtomicInteger();
        var last = new AtomicLong();
        SqlPlan plan = guardedPagePlan(meta, key, columns, filters, afterExclusive, highWater, pageSize);
        Flux<RowRecord> current = execute(plan)
                .map(row -> {
                    if (Boolean.TRUE.equals(row.values().get("__omniflux_row_too_large"))) {
                        throw new ExportException(ErrorCode.ROW_TOO_LARGE,
                                "a database row exceeds the configured export byte limits");
                    }
                    RowRecord rowRecord = toRowRecord(row, key, columns);
                    count.incrementAndGet();
                    last.set(rowRecord.key());
                    return rowRecord;
                });
        return current.concatWith(Flux.defer(() -> count.get() < pageSize
                ? Flux.empty()
                : page(last.get(), highWater, meta, key, columns,
                filters, pageSize)));
    }

    private SqlPlan guardedPagePlan(RelationDescriptor meta, String key,
                                    List<ColumnDescriptor> columns, List<FilterSpec> filters,
                                    long afterExclusive, long highWater, int pageSize) {
        var page = new PageSpec(meta.schema(), meta.name(), key, filters, afterExclusive,
                highWater, pageSize);
        return dialect.guardedPageQuery(page, columns,
                props.limits().maxFieldBytes().toBytes(), props.limits().maxRowBytes().toBytes());
    }

    private Flux<DbRow> execute(SqlPlan plan) {
        var statement = client.sql(plan.sql());
        for (int i = 0; i < plan.binds().size(); i++) statement = statement.bind(i, plan.binds().get(i));
        long started = System.nanoTime();
        Flux<DbRow> result = statement.map((row, metadata) -> {
                    var values = new java.util.LinkedHashMap<String, Object>();
                    metadata.getColumnMetadatas().forEach(column ->
                            values.put(column.getName(), row.get(column.getName())));
                    return new DbRow(values);
                })
                .all();
        return timing == null ? result : result.doFinally(signal ->
                timing.addSourceRead(System.nanoTime() - started));
    }

    private static List<ColumnDescriptor> requestedColumns(RelationDescriptor meta, ExportRequest request) {
        List<String> names = request.columns() == null || request.columns().isEmpty()
                ? meta.columns().stream().map(ColumnDescriptor::name).toList() : request.columns();
        List<ColumnDescriptor> requested = new ArrayList<>(names.size());
        for (String name : names) {
            requested.add(meta.columns().stream().filter(column -> column.name().equals(name))
                    .findFirst().orElseThrow(() -> new ExportException(ErrorCode.UNKNOWN_COLUMN,
                            "unknown column: " + name)));
        }
        return List.copyOf(requested);
    }

    private static RowRecord toRowRecord(DbRow row, String key, List<ColumnDescriptor> columns) {
        Object rawKey = row.values().get(key);
        if (rawKey == null) throw new ExportException(ErrorCode.UPSTREAM_CLIENT_ERROR,
                "R2DBC row did not contain continuation key " + key);
        Object[] values = new Object[columns.size()];
        for (int i = 0; i < columns.size(); i++) values[i] = row.values().get(columns.get(i).name());
        return new RowRecord(asLong(rawKey), values);
    }

    private static long asLong(Object value) {
        if (value instanceof Number n) return n.longValue();
        try { return new BigInteger(String.valueOf(value)).longValueExact(); }
        catch (RuntimeException e) { throw new ExportException(ErrorCode.UNSUPPORTED_PRIMARY_KEY,
                "continuation key is not an integer", e); }
    }

    private record DbRow(java.util.Map<String, Object> values) { }
}
