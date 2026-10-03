package com.omniflux.exchange.web;

import com.omniflux.exchange.config.OmnifluxProperties;
import com.omniflux.exchange.export.ExportRowSourceFactory;
import com.omniflux.exchange.meta.ColumnDescriptor;
import com.omniflux.exchange.meta.RelationDescriptor;
import com.omniflux.exchange.meta.SchemaCatalog;
import com.omniflux.exchange.security.AuthContext;
import com.omniflux.exchange.security.CurrentUserProvider;
import com.omniflux.exchange.source.ExportRequest;
import com.omniflux.exchange.source.FilterSpec;
import com.omniflux.exchange.source.RowRecord;
import com.omniflux.exchange.source.RowSource;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.*;

/** Read-only preview over the same governed source adapter used by exports. */
@RestController
@RequestMapping("/api/data")
public final class DataController {
    private static final TypeReference<List<FilterSpec>> FILTERS = new TypeReference<>() { };

    private final ExportRowSourceFactory sources;
    private final SchemaCatalog catalog;
    private final CurrentUserProvider users;
    private final int configuredPageSize;
    private final String catalogAdapter;
    private final ObjectMapper mapper;

    public DataController(ExportRowSourceFactory sources, SchemaCatalog catalog,
                          CurrentUserProvider users, OmnifluxProperties properties,
                          ObjectMapper mapper) {
        this.sources = sources;
        this.catalog = catalog;
        this.users = users;
        this.configuredPageSize = properties.ui().pageSize();
        this.catalogAdapter = properties.source().defaultAdapter();
        this.mapper = mapper;
    }

    @GetMapping("/{relation}")
    public Mono<DataPage> browse(@PathVariable String relation,
                                 @RequestParam(defaultValue = "50") int limit,
                                 @RequestParam(required = false) String after,
                                 @RequestParam(required = false) String filters,
                                 ServerWebExchange exchange) {
        if (limit < 1) return Mono.error(new IllegalArgumentException("limit must be positive"));
        int boundedLimit = Math.min(limit, configuredPageSize);
        return parseAfter(after).zipWith(parseFilters(filters))
                .flatMap(values -> users.currentAuth(exchange.getRequest())
                        .flatMap(auth -> catalog.describe(catalogAdapter, relation, auth)
                                .flatMap(descriptor -> {
                                    List<FilterSpec> parsed = values.getT2();
                                    catalog.requireFilters(descriptor, parsed);
                                    return query(descriptor, boundedLimit,
                                            values.getT1().orElse(null), parsed, auth);
                                })));
    }

    /** Best-effort exact count for export size estimates; adapters that cannot
     *  count cheaply surface their unsupported error to the caller. */
    @GetMapping("/{relation}/count")
    public Mono<RelationCount> count(@PathVariable String relation,
                                     @RequestParam(required = false) String filters,
                                     ServerWebExchange exchange) {
        return parseFilters(filters)
                .zipWith(users.currentAuth(exchange.getRequest()))
                .flatMap(values -> catalog.describe(catalogAdapter, relation, values.getT2())
                        .flatMap(descriptor -> {
                            List<FilterSpec> parsed = values.getT1();
                            catalog.requireFilters(descriptor, parsed);
                            ExportRequest request = new ExportRequest(descriptor.name(),
                                    List.of(descriptor.keyContract().column()), parsed,
                                    "CSV", "RAW", null);
                            return sources.create(values.getT2(), null, 1).count(request);
                        }))
                .map(RelationCount::new);
    }

    private Mono<DataPage> query(RelationDescriptor relation, int limit, Long after,
                                 List<FilterSpec> filters, AuthContext auth) {
        String key = relation.keyContract().column();
        List<String> columns = relation.columns().stream().map(ColumnDescriptor::name).toList();
        List<FilterSpec> effective = new ArrayList<>(filters);
        if (after != null) effective.add(FilterSpec.gt(key, after));
        ExportRequest request = new ExportRequest(relation.name(), columns, effective, "CSV", "RAW", null);
        // one extra row detects hasNext; clamp so a MAX_VALUE page size cannot wrap
        int fetch = (int) Math.min((long) limit + 1, Integer.MAX_VALUE);
        RowSource source = sources.create(auth, null, fetch);
        return source.prepare(request)
                .flatMapMany(scan -> scan.records().take(fetch)
                        .map(rowRecord -> values(relation.columns(), rowRecord)))
                .collectList()
                .map(rows -> {
                    boolean hasNext = rows.size() > limit;
                    List<Map<String, Object>> page = hasNext ? rows.subList(0, limit) : rows;
                    String next = hasNext && !page.isEmpty()
                            ? encodeCursor(((Number) page.getLast().get(key)).longValue()) : null;
                    return new DataPage(page, next);
                });
    }

    private static Map<String, Object> values(List<ColumnDescriptor> columns, RowRecord rowRecord) {
        Map<String, Object> values = new LinkedHashMap<>();
        Object[] row = rowRecord.values();
        if (row.length != columns.size()) {
            throw new IllegalStateException("source projection width does not match metadata");
        }
        for (int i = 0; i < columns.size(); i++) values.put(columns.get(i).name(), row[i]);
        return values;
    }

    private Mono<List<FilterSpec>> parseFilters(String json) {
        if (json == null || json.isBlank()) return Mono.just(List.of());
        try {
            return Mono.just(List.copyOf(mapper.readValue(json, FILTERS)));
        } catch (RuntimeException error) {
            return Mono.error(new IllegalArgumentException("invalid filters", error));
        }
    }

    private static Mono<Optional<Long>> parseAfter(String cursor) {
        if (cursor == null || cursor.isBlank()) return Mono.just(Optional.empty());
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            return Mono.just(Optional.of(Long.parseLong(decoded)));
        } catch (IllegalArgumentException e) {
            return Mono.error(new IllegalArgumentException("invalid cursor", e));
        }
    }

    public static String encodeCursor(long key) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(Long.toString(key).getBytes(StandardCharsets.UTF_8));
    }

    public record DataPage(List<Map<String, Object>> rows, String nextCursor) {
        public DataPage { rows = List.copyOf(rows); }
    }

    public record RelationCount(long count) { }
}
