package com.omniflux.exchange.adapter.rest;

import com.omniflux.exchange.config.OmnifluxProperties;
import com.omniflux.exchange.export.ExportTiming;
import com.omniflux.exchange.job.ErrorCode;
import com.omniflux.exchange.job.ExportException;
import com.omniflux.exchange.meta.ColumnDescriptor;
import com.omniflux.exchange.meta.RelationDescriptor;
import com.omniflux.exchange.security.AuthContext;
import com.omniflux.exchange.security.PrincipalKey;
import com.omniflux.exchange.source.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * Streaming PostgREST/Data API source using a captured integer high-water mark
 * and keyset continuation.  Metadata is injected so this class does not fall
 * back to the local database for a production Data API relation.
 */
public final class RestRowSource implements RowSource {

    private static final String SELECT_PARAM = "select=";

    private static final String SINGLE_ROW_LIMIT = "&limit=1";
    private final DataApiClient client;
    private final RowJsonMapper mapper;
    private final Function<String, Mono<RelationDescriptor>> descriptorResolver;
    private final AuthContext authContext;
    private final int pageSize;
    private final int maxRetries;
    private final ExportTiming timing;

    public RestRowSource(DataApiClient client, RowJsonMapper mapper,
                         RelationDescriptor descriptor,
                         AuthContext authContext,
                         OmnifluxProperties properties) {
        this(client, mapper, name -> Mono.just(descriptor), authContext, properties);
    }

    public RestRowSource(DataApiClient client, RelationDescriptor descriptor,
                         AuthContext authContext,
                         OmnifluxProperties properties) {
        this(client, new RowJsonMapper(properties), descriptor, authContext, properties);
    }

    public RestRowSource(DataApiClient client, RowJsonMapper mapper,
                         RelationDescriptor descriptor,
                         OmnifluxProperties properties) {
        this(client, mapper, descriptor, null, properties);
    }

    public RestRowSource(DataApiClient client, RowJsonMapper mapper,
                         RelationDescriptor descriptor,
                         PrincipalKey key, List<String> roles,
                         String authorizationContextVersion,
                         OmnifluxProperties properties) {
        this(client, mapper, descriptor,
                key == null ? null : new AuthContext(key, roles, authorizationContextVersion),
                properties);
    }

    /** Resolver seam for a later SchemaCatalog/Data API metadata provider. */
    public RestRowSource(DataApiClient client, RowJsonMapper mapper,
                         Function<String, Mono<RelationDescriptor>> descriptorResolver,
                         AuthContext authContext,
                         OmnifluxProperties properties) {
        this(client, mapper, descriptorResolver, authContext, properties, null);
    }

    public RestRowSource(DataApiClient client, RowJsonMapper mapper,
                         Function<String, Mono<RelationDescriptor>> descriptorResolver,
                         AuthContext authContext,
                         OmnifluxProperties properties,
                         ExportTiming timing) {
        this(client, mapper, descriptorResolver, authContext, properties, timing, null);
    }

    /** Browse seam: use a smaller bounded upstream page than export workers. */
    public RestRowSource(DataApiClient client, RowJsonMapper mapper,
                         Function<String, Mono<RelationDescriptor>> descriptorResolver,
                         AuthContext authContext,
                         OmnifluxProperties properties,
                         ExportTiming timing, Integer pageSizeOverride) {
        this.client = Objects.requireNonNull(client, "client");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.descriptorResolver = Objects.requireNonNull(descriptorResolver, "descriptorResolver");
        this.authContext = authContext;
        var rest = Objects.requireNonNull(properties, "properties").source().rest();
        if (rest.pageSize() <= 0) throw new IllegalArgumentException("source.rest.page-size must be positive");
        if (rest.maxRetries() < 0) throw new IllegalArgumentException("source.rest.max-retries must not be negative");
        if (pageSizeOverride != null && pageSizeOverride < 1) {
            throw new IllegalArgumentException("pageSizeOverride must be positive");
        }
        this.pageSize = pageSizeOverride == null ? rest.pageSize() : pageSizeOverride;
        this.maxRetries = rest.maxRetries();
        this.timing = timing;
    }

    @Override
    public Mono<ExportScan> prepare(ExportRequest request) {
        Objects.requireNonNull(request, "request");
        return Mono.defer(() -> descriptorResolver.apply(request.relation()))
                .switchIfEmpty(Mono.error(new ExportException(ErrorCode.UNKNOWN_RELATION,
                        "no metadata for relation " + request.relation())))
                .map(descriptor -> selection(request, descriptor))
                .flatMap(selection -> highWater(selection, request.filters())
                        .flatMap(highWater -> {
                            if (!isXlsx(request) || highWater.isEmpty()) {
                                return Mono.just(scan(selection, request.filters(),
                                        highWater.orElse(Long.MIN_VALUE), Long.MIN_VALUE));
                            }
                            return timed(client.count(countUrl(selection, request.filters(),
                                            highWater.getAsLong()), authContext))
                                    .map(count -> scan(selection, request.filters(),
                                            highWater.getAsLong(), count));
                        }));
    }

    /** Exact filtered total via the same Prefer: count=exact probe the XLSX guard uses. */
    @Override
    public Mono<Long> count(ExportRequest request) {
        Objects.requireNonNull(request, "request");
        return Mono.defer(() -> descriptorResolver.apply(request.relation()))
                .switchIfEmpty(Mono.error(new ExportException(ErrorCode.UNKNOWN_RELATION,
                        "no metadata for relation " + request.relation())))
                .map(descriptor -> selection(request, descriptor))
                .flatMap(selection -> timed(client.count(
                        countAllUrl(selection, request.filters()), authContext)));
    }

    private String countAllUrl(Selection selection, List<FilterSpec> filters) {
        var query = new StringBuilder()
                .append(SELECT_PARAM).append(encodedIdentifier(selection.keyColumn()))
                .append(SINGLE_ROW_LIMIT);
        appendFilters(query, filters);
        return path(selection.relation(), query);
    }

    private ExportScan scan(Selection selection, List<FilterSpec> filters,
                            long highWaterInclusive, long rowCountExact) {        Flux<RowRecord> records = highWaterInclusive == Long.MIN_VALUE
                ? Flux.empty()
                : pagesFrom(selection, filters, Long.MIN_VALUE, highWaterInclusive);
        return new ExportScan(selection.outputColumns(),
                highWaterInclusive == Long.MIN_VALUE ? null : highWaterInclusive,
                ExportScan.singleSubscription(records),
                rowCountExact == Long.MIN_VALUE ? null : rowCountExact);
    }

    private String countUrl(Selection selection, List<FilterSpec> filters, long highWater) {
        var query = new StringBuilder()
                .append(SELECT_PARAM).append(encodedIdentifier(selection.keyColumn()))
                .append(SINGLE_ROW_LIMIT)
                .append('&').append(encodedIdentifier(selection.keyColumn()))
                .append("=lte.").append(highWater);
        appendFilters(query, filters);
        return path(selection.relation(), query);
    }

    private static boolean isXlsx(ExportRequest request) {
        return "XLSX".equalsIgnoreCase(request.format());
    }

    private Mono<OptionalLong> highWater(Selection selection, List<FilterSpec> filters) {
            return timed(client.get(probeUrl(selection, filters), authContext))
                .retryWhen(retrySpec(new AtomicBoolean()))
                .next()
                .map(row -> OptionalLong.of(mapper.key(row, selection.keyColumn())))
                .defaultIfEmpty(OptionalLong.empty());
    }

    private Flux<RowRecord> pagesFrom(Selection selection, List<FilterSpec> filters,
                                      long afterExclusive, long highWater) {
        return Flux.defer(() -> {
            var lastKey = new AtomicLong();
            var count = new AtomicInteger();
            Flux<RowRecord> page = fetchPage(selection, filters, afterExclusive, highWater)
                    .doOnNext(row -> {
                        lastKey.set(row.key());
                        count.incrementAndGet();
                    });
            return page.concatWith(Flux.defer(() -> count.get() < pageSize
                    ? Flux.empty()
                    : pagesFrom(selection, filters, lastKey.get(), highWater)));
        });
    }

    private Flux<RowRecord> fetchPage(Selection selection, List<FilterSpec> filters,
                                      long afterExclusive, long highWater) {
        return Flux.defer(() -> {
            var emitted = new AtomicBoolean();
            var retry = retrySpec(emitted);
            return timed(client.get(pageUrl(selection, filters, afterExclusive, highWater),
                    authContext))
                    .map(node -> mapper.toRecord(node, selection.outputColumns(),
                            selection.keyColumn(), selection.keyDescriptor()))
                    .doOnNext(ignored -> emitted.set(true))
                    // A retry after emission would duplicate rows into the
                    // current upload part, which cannot be rolled back.
                    .retryWhen(retry);
        });
    }

    private <T> Flux<T> timed(Flux<T> source) {
        if (timing == null) return source;
        long started = System.nanoTime();
        return source.doFinally(signal -> timing.addSourceRead(System.nanoTime() - started));
    }

    private <T> Mono<T> timed(Mono<T> source) {
        if (timing == null) return source;
        long started = System.nanoTime();
        return source.doFinally(signal -> timing.addSourceRead(System.nanoTime() - started));
    }

    private Retry retrySpec(AtomicBoolean emitted) {
        return Retry.fixedDelay(maxRetries, Duration.ofMillis(50))
                .filter(error -> !emitted.get() && transientError(error))
                .onRetryExhaustedThrow((spec, signal) -> signal.failure());
    }

    private static boolean transientError(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof ExportException exception) return exception.isTransient();
        }
        return false;
    }

    private Selection selection(ExportRequest request, RelationDescriptor descriptor) {
        var keyColumn = descriptor.keyContract().column();
        var keyDescriptor = descriptor.columns().stream()
                .filter(column -> column.name().equals(keyColumn))
                .findFirst()
                .orElseThrow(() -> new ExportException(ErrorCode.UNKNOWN_COLUMN,
                        "primary key column " + keyColumn + " is not in relation metadata"));
        if (keyDescriptor.logicalType() != com.omniflux.exchange.meta.LogicalType.INTEGER) {
            throw new ExportException(ErrorCode.UNSUPPORTED_PRIMARY_KEY,
                    "REST keyset pagination requires an integer primary key: " + keyColumn);
        }

        var requested = request.columns().isEmpty()
                ? descriptor.columns().stream().map(ColumnDescriptor::name).toList()
                : request.columns();
        var seen = new LinkedHashSet<String>();
        var output = new ArrayList<ColumnDescriptor>();
        for (var name : requested) {
            if (!seen.add(name)) {
                throw new ExportException(ErrorCode.DUPLICATE_COLUMN, "duplicate column: " + name);
            }
            output.add(descriptor.columns().stream()
                    .filter(column -> column.name().equals(name))
                    .findFirst()
                    .orElseThrow(() -> new ExportException(ErrorCode.UNKNOWN_COLUMN,
                            "unknown column: " + name)));
        }

        var select = new LinkedHashSet<>(seen);
        select.add(keyColumn);
        return new Selection(request.relation(), keyColumn, keyDescriptor,
                List.copyOf(output), List.copyOf(select));
    }

    private String probeUrl(Selection selection, List<FilterSpec> filters) {
        var query = new StringBuilder()
                .append(SELECT_PARAM).append(encodedIdentifier(selection.keyColumn()))
                .append("&order=").append(encodedIdentifier(selection.keyColumn())).append(".desc")
                .append(SINGLE_ROW_LIMIT);
        appendFilters(query, filters);
        return path(selection.relation(), query);
    }

    private String pageUrl(Selection selection, List<FilterSpec> filters,
                           long afterExclusive, long highWater) {
        var query = new StringBuilder()
                .append(SELECT_PARAM).append(select(selection))
                .append("&order=").append(encodedIdentifier(selection.keyColumn())).append(".asc")
                .append("&limit=").append(pageSize)
                .append('&').append(encodedIdentifier(selection.keyColumn()))
                .append("=lte.").append(highWater);
        if (afterExclusive != Long.MIN_VALUE) {
            query.append('&').append(encodedIdentifier(selection.keyColumn()))
                    .append("=gt.").append(afterExclusive);
        }
        appendFilters(query, filters);
        return path(selection.relation(), query);
    }

    private static String select(Selection selection) {
        return selection.selectColumns().stream().map(RestRowSource::encodedIdentifier)
                .reduce((left, right) -> left + "," + right).orElseThrow();
    }

    private static void appendFilters(StringBuilder query, List<FilterSpec> filters) {
        if (filters == null) return;
        for (var filter : filters) {
            query.append('&').append(encodedIdentifier(filter.column())).append('=')
                    .append(operator(filter.operator())).append('.')
                    .append(encodedFilterValue(filter));
        }
    }

    private static String operator(FilterOperator operator) {
        return switch (operator) {
            case EQ -> "eq";
            case NE -> "neq";
            case GT -> "gt";
            case GTE -> "gte";
            case LT -> "lt";
            case LTE -> "lte";
            case IN -> "in";
        };
    }

    private static String encodedFilterValue(FilterSpec filter) {
        if (filter.operator() != FilterOperator.IN) {
            return encode(String.valueOf(filter.value()));
        }
        if (!(filter.value() instanceof Iterable<?> values)) {
            throw new ExportException(ErrorCode.UPSTREAM_CLIENT_ERROR,
                    "IN filter value must be a collection: " + filter.column());
        }
        var encoded = java.util.stream.StreamSupport.stream(values.spliterator(), false)
                .map(value -> encode(String.valueOf(value))).toList();
        return "(" + String.join(",", encoded) + ")";
    }

    private static String path(String relation, StringBuilder query) {
        return "/" + encodedIdentifier(relation) + "?" + query;
    }

    private static String encodedIdentifier(String value) {
        return encode(value);
    }

    /** RFC 3986 query encoding: spaces become %20, never '+'. */
    private static String encode(String value) {
        var bytes = value.getBytes(StandardCharsets.UTF_8);
        var result = new StringBuilder(bytes.length);
        for (byte byteValue : bytes) {
            int c = byteValue & 0xff;
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '-' || c == '.'
                    || c == '_' || c == '~') {
                result.append((char) c);
            } else {
                result.append('%');
                result.append(Character.toUpperCase(Character.forDigit(c >>> 4, 16)));
                result.append(Character.toUpperCase(Character.forDigit(c & 0xf, 16)));
            }
        }
        return result.toString();
    }

    private record Selection(String relation, String keyColumn,
                             ColumnDescriptor keyDescriptor,
                             List<ColumnDescriptor> outputColumns,
                             List<String> selectColumns) { }
}
