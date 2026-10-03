package com.omniflux.exchange.adapter.rest;

import com.omniflux.exchange.TestProps;
import com.omniflux.exchange.config.OmnifluxProperties;
import com.omniflux.exchange.job.ErrorCode;
import com.omniflux.exchange.job.ExportException;
import com.omniflux.exchange.meta.*;
import com.omniflux.exchange.security.AuthContext;
import com.omniflux.exchange.security.PrincipalKey;
import com.omniflux.exchange.source.ExportRequest;
import com.omniflux.exchange.source.ExportScan;
import com.omniflux.exchange.source.FilterSpec;
import com.omniflux.exchange.source.RowRecord;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.*;

class RestRowSourceTest {

    private static final AuthContext AUTH = new AuthContext(
            new PrincipalKey("local", "dev@local", "local"), List.of("ANALYST"), "v1");
    private MockWebServer server;

    @BeforeEach
    void startServer() throws Exception {
        server = new MockWebServer();
        server.start();
    }

    @AfterEach
    void stopServer() throws Exception {
        server.shutdown();
    }

    @Test
    void decodesChunkedTopLevelArrayObjectsIncrementally() {
        server.enqueue(json("[{\"id\":2,\"country\":\"IN\"}]"));
        server.enqueue(new MockResponse().setHeader("Content-Type", "application/json")
                .setChunkedBody("[{\"id\":1,\"country\":\"IN\"},{\"id\":2,\"country\":\"IN\"}]", 1));

        StepVerifier.create(source().prepare(request())
                        .flatMapMany(ExportScan::records))
                .expectNextMatches(row -> row.key() == 1L)
                .expectNextMatches(row -> row.key() == 2L)
                .verifyComplete();
    }

    @Test
    void selectsThePrimaryKeyWithoutLeakingItIntoValues() throws Exception {
        server.enqueue(json("[{\"id\":7}]"));
        server.enqueue(json("[{\"id\":7,\"country\":\"IN\"}]"));

        var scan = prepare(source(), request());
        var record = Objects.requireNonNull(scan.records().blockFirst());
        server.takeRequest();
        var page = server.takeRequest();

        assertEquals(Set.of("country", "id"), Set.of(Objects.requireNonNull(
                page.getRequestUrl().queryParameter("select")).split(",")));
        assertEquals(7L, record.key());
        assertEquals(1, record.values().length);
        assertEquals(List.of("country"), scan.columns().stream().map(ColumnDescriptor::name).toList());
    }

    @Test
    void xlsxCountsTheFilteredHighWaterSliceBeforeReadingPages() throws Exception {
        server.enqueue(json("[{\"id\":250}]"));
        server.enqueue(new MockResponse().setResponseCode(206)
                .setHeader("Content-Range", "0-0/250")
                .setHeader("Content-Type", "application/json").setBody("[]"));
        server.enqueue(json("[{\"id\":1,\"country\":\"IN\"}]"));

        var scan = prepare(source(), new ExportRequest("mock", List.of("country"), List.of(),
                "XLSX", "RAW", 1));
        assertEquals(250, scan.rowCount());
        server.takeRequest(); // filtered high-water probe
        var count = server.takeRequest();
        assertTrue(Objects.requireNonNull(count.getHeader("Prefer")).contains("count=exact"));
        assertEquals("items=0-0", count.getHeader("Range"));
        assert count.getPath() != null;
        assertTrue(count.getPath().contains("id=lte.250"));

        assertEquals(1, scan.records().count().block());
    }

    @Test
    void countReturnsTheExactFilteredTotalWithoutReadingPages() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(206)
                .setHeader("Content-Range", "0-0/12345")
                .setHeader("Content-Type", "application/json").setBody("[]"));

        var total = source().count(request(FilterSpec.eq("country", "IN"))).block();

        assertEquals(12345L, total);
        assertEquals(1, server.getRequestCount());
        var count = server.takeRequest();
        assertTrue(Objects.requireNonNull(count.getHeader("Prefer")).contains("count=exact"));
        assertEquals("items=0-0", count.getHeader("Range"));
        assert count.getPath() != null;
        assertTrue(count.getPath().contains("select=id"));
        assertTrue(count.getPath().contains("limit=1"));
        assertTrue(count.getPath().contains("country=eq.IN"));
        assertFalse(count.getPath().contains("id=lte."));
    }

    @Test
    void capturesTheFilteredHighWaterOnceAndBoundsEveryPage() throws Exception {
        server.enqueue(json("[{\"id\":250}]"));
        server.enqueue(json(rows(1, 100)));
        server.enqueue(json(rows(101, 200)));
        server.enqueue(json(rows(201, 250)));

        var records = prepare(source(100), request(FilterSpec.eq("country", "IN")))
                .records().collectList().block();

        assertEquals(250, records.size());
        assertEquals(4, server.getRequestCount());
        var probe = server.takeRequest().getPath();
        assertTrue(probe.contains("select=id&order=id.desc&limit=1"));
        assertTrue(probe.contains("country=eq.IN"));

        assertPage(server.takeRequest().getPath(), false, 0);
        assertPage(server.takeRequest().getPath(), true, 100);
        assertPage(server.takeRequest().getPath(), true, 200);
    }

    @Test
    void traversesPagesExactlyOnceInKeyOrder() {
        server.enqueue(json("[{\"id\":1000}]"));
        server.enqueue(json(rows(1, 100)));
        server.enqueue(json(rows(101, 200)));
        server.enqueue(json(rows(201, 250)));

        var keys = prepare(source(100), request()).records()
                .map(RowRecord::key).collectList().block();

        assertEquals(LongStream.rangeClosed(1, 250).boxed().toList(), keys);
    }

    @Test
    void aFullFinalPageIsFollowedByOneEmptyPage() {
        server.enqueue(json("[{\"id\":2}]"));
        server.enqueue(json("[{\"id\":1}]"));
        server.enqueue(json("[{\"id\":2}]"));
        server.enqueue(json("[]"));

        var keys = prepare(source(1), request()).records()
                .map(RowRecord::key).collectList().block();

        assertEquals(List.of(1L, 2L), keys);
        assertEquals(4, server.getRequestCount());
    }

    @Test
    void anEmptyRelationHasNoHighWaterAndDoesNotRequestADataPage() {
        server.enqueue(json("[]"));

        var scan = prepare(source(), request());

        assertNull(scan.highWater());
        assertFalse(Objects.requireNonNull(scan.records().hasElements().block()));
        assertEquals(1, server.getRequestCount());
    }

    @Test
    void retriesA5xxBeforeTheFirstRow() {
        server.enqueue(new MockResponse().setResponseCode(503));
        server.enqueue(json("[]"));

        var scan = prepare(source(), request());
        assertFalse(Objects.requireNonNull(scan.records().hasElements().block()));
        assertEquals(2, server.getRequestCount());
    }

    @Test
    void decodesTheFirstObjectBeforeTheTailArrives() {
        var firstRowSeen = new CountDownLatch(1);
        var exchanges = new AtomicInteger();
        var properties = properties();
        var webClient = WebClient.builder().exchangeFunction(request -> {
            exchanges.incrementAndGet();
            Flux<DataBuffer> body;
            if (request.url().getQuery().contains("order=id.desc")) {
                body = Flux.just(buffer("[{\"id\":2}]"));
            } else {
                body = Flux.create(sink -> Thread.startVirtualThread(() -> {
                    try {
                        sink.next(buffer("[{\"id\":1,\"country\":\"IN\"},"));
                        if (!firstRowSeen.await(2, java.util.concurrent.TimeUnit.SECONDS)) {
                            sink.error(new AssertionError("the first row was not emitted before the tail"));
                            return;
                        }
                        sink.next(buffer("{\"id\":2,\"country\":\"IN\"}]"));
                        sink.complete();
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        sink.error(ex);
                    }
                }));
            }
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                    .body(body)
                    .build());
        }).build();
        var restSource = new RestRowSource(
                new DataApiClient(webClient, new StaticDataApiCredentials("m2m-token"),
                        properties.source().rest()),
                new RowJsonMapper(properties), relation(), AUTH, properties);

        StepVerifier.create(restSource.prepare(request()).flatMapMany(ExportScan::records))
                .expectNextMatches(row -> {
                    firstRowSeen.countDown();
                    return row.key() == 1L;
                })
                .expectNextMatches(row -> row.key() == 2L)
                .verifyComplete();
        assertEquals(2, exchanges.get());
    }

    @Test
    void doesNotRetryAnUpstreamFailureAfterADataRowWasEmitted() {
        var firstRowSeen = new CountDownLatch(1);
        var exchanges = new AtomicInteger();
        var properties = properties();
        var webClient = WebClient.builder().exchangeFunction(request -> {
            var attempt = exchanges.incrementAndGet();
            Flux<DataBuffer> body;
            if (request.url().getQuery().contains("order=id.desc")) {
                body = Flux.just(buffer("[{\"id\":1}]"));
            } else if (attempt == 2) {
                body = Flux.create(sink -> Thread.startVirtualThread(() -> {
                    sink.next(buffer("[{\"id\":1}]"));
                    try {
                        if (!firstRowSeen.await(2, java.util.concurrent.TimeUnit.SECONDS)) {
                            sink.error(new AssertionError("the first row was not emitted"));
                            return;
                        }
                        sink.error(new IOException("failure after first row"));
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        sink.error(ex);
                    }
                }));
            } else {
                body = Flux.just(buffer("[{\"id\":1}]"));
            }
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                    .body(body)
                    .build());
        }).build();
        var restSource = new RestRowSource(
                new DataApiClient(webClient, new StaticDataApiCredentials("m2m-token"),
                        properties.source().rest()),
                new RowJsonMapper(properties), relation(), AUTH, properties);

        StepVerifier.create(restSource.prepare(request()).flatMapMany(ExportScan::records))
                .expectNextMatches(row -> {
                    firstRowSeen.countDown();
                    return row.key() == 1L;
                })
                .expectError(ExportException.class)
                .verify();
        assertEquals(2, exchanges.get());
    }

    @Test
    void a4xxIsTerminalWithAnUpstreamClientError() {
        server.enqueue(new MockResponse().setResponseCode(400));

        var error = assertThrows(ExportException.class,
                () -> source().prepare(request()).block());
        assertEquals(ErrorCode.UPSTREAM_CLIENT_ERROR, error.code());
        assertEquals(1, server.getRequestCount());
    }

    @Test
    void filtersAreEncodedInTheQueryString() throws Exception {
        server.enqueue(json("[]"));
        prepare(source(), request(FilterSpec.eq("note", "a&b=c,d")))
                .records().blockLast();

        assertTrue(server.takeRequest().getPath().contains("note=eq.a%26b%3Dc%2Cd"));
    }

    @Test
    void encodesInFiltersWithoutEncodingThePostgrestSeparators() throws Exception {
        server.enqueue(json("[]"));
        prepare(source(), request(FilterSpec.in("note", List.of("a", "b c"))))
                .records().blockLast();

        assertTrue(server.takeRequest().getPath().contains("note=in.(a,b%20c)"));
    }

    @Test
    void sendsTheServiceCredentialAndWholeAssertedPrincipal() throws Exception {
        server.enqueue(json("[]"));
        prepare(source(), request()).records().blockLast();
        var request = server.takeRequest();

        assertEquals("Bearer m2m-token", request.getHeader("Authorization"));
        assertEquals("local", request.getHeader("X-Asserted-Issuer"));
        assertEquals("dev@local", request.getHeader("X-Asserted-Subject"));
        assertEquals("local", request.getHeader("X-Asserted-Tenant"));
        assertEquals("ANALYST", request.getHeader("X-Asserted-Roles"));
        assertEquals("v1", request.getHeader("X-Asserted-Authz-Version"));
    }

    @Test
    void refreshesAnExpiredServiceTokenOnceOn401() {
        var refreshes = new AtomicInteger();
        var credentials = new DataApiCredentials() {
            @Override public Mono<String> bearerToken() { return Mono.just("expired"); }
            @Override public Mono<String> refreshToken() {
                refreshes.incrementAndGet();
                return Mono.just("fresh");
            }
        };
        server.setDispatcher(new okhttp3.mockwebserver.Dispatcher() {
            @Override public @NonNull MockResponse dispatch(okhttp3.mockwebserver.@NonNull RecordedRequest request) {
                return "Bearer fresh".equals(request.getHeader("Authorization"))
                        ? json("[]") : new MockResponse().setResponseCode(401);
            }
        });

        var properties = properties();
        var client = new DataApiClient(properties, credentials);
        Objects.requireNonNull(new RestRowSource(client, new RowJsonMapper(properties), relation(), AUTH, properties)
                .prepare(request()).block()).records().blockLast();

        assertEquals(1, refreshes.get());
        assertEquals(2, server.getRequestCount());
    }

    @Test
    void treatsCredentialAcquisitionFailureAsAClientError() {
        var properties = properties();
        var credentials = (DataApiCredentials) () ->
                Mono.error(new IllegalStateException("token endpoint unavailable"));
        var error = assertThrows(ExportException.class, () ->
                new RestRowSource(new DataApiClient(properties, credentials),
                        new RowJsonMapper(properties), relation(), AUTH, properties)
                        .prepare(request()).block());

        assertEquals(ErrorCode.UPSTREAM_CLIENT_ERROR, error.code());
        assertEquals(0, server.getRequestCount());
    }

    @Test
    void fieldAndAggregateRowLimitsAreTypedAndUseUtf8Bytes() {
        var fieldProps = properties(Map.of("omniflux.limits.max-field-bytes", "3B",
                "omniflux.limits.max-row-bytes", "1KB"));
        server.enqueue(json("[{\"id\":1}]"));
        server.enqueue(json("[{\"id\":1,\"country\":\"😀\"}]"));
        var fieldError = assertThrows(ExportException.class, () -> {
            java.util.Objects.requireNonNull(new RestRowSource(new DataApiClient(fieldProps,
                    new StaticDataApiCredentials("m2m-token")),
                    new RowJsonMapper(fieldProps), relation(), AUTH, fieldProps)
                    .prepare(request()).block()).records().blockLast();
        });
        assertEquals(ErrorCode.FIELD_TOO_LARGE, fieldError.code());

        var columns = new java.util.ArrayList<ColumnDescriptor>();
        columns.add(new ColumnDescriptor("id", LogicalType.INTEGER, false, true));
        for (int i = 0; i < 10; i++) {
            columns.add(new ColumnDescriptor("f" + i, LogicalType.TEXT, true, false));
        }
        var wide = new RelationDescriptor("public", "mock", columns,
                new KeyContract("id"), Volatility.MUTABLE);
        var row = new StringBuilder("[{\"id\":1");
        for (int i = 0; i < 10; i++) row.append(",\"f").append(i).append("\":\"").repeat("x", 80).append('"');
        row.append("}]");
        var rowProps = properties(Map.of("omniflux.limits.max-field-bytes", "100B",
                "omniflux.limits.max-row-bytes", "500B"));
        server.enqueue(json("[{\"id\":1}]"));
        server.enqueue(json(row.toString()));
        var rowError = assertThrows(ExportException.class, () ->
                new RestRowSource(new DataApiClient(rowProps, new StaticDataApiCredentials("m2m-token")),
                        new RowJsonMapper(rowProps), wide, AUTH, rowProps)
                        .prepare(new ExportRequest("mock", List.of("f0", "f1", "f2", "f3", "f4",
                                "f5", "f6", "f7", "f8", "f9"), List.of())).block()
                        .records().blockLast());
        assertEquals(ErrorCode.ROW_TOO_LARGE, rowError.code());
    }

    @Test
    void theClientOwnsTheConfiguredCodecLimit() {
        var properties = properties(Map.of("omniflux.source.rest.max-in-memory-size", "7MB"));
        var client = new DataApiClient(properties, new StaticDataApiCredentials("m2m-token"));
        assertEquals(7 << 20, client.maxInMemorySizeBytes());
    }

    @Test
    void mapsACodecElementOverflowToRowTooLarge() {
        var properties = properties(Map.of("omniflux.source.rest.max-in-memory-size", "1KB"));
        server.enqueue(json("[{\"id\":1}]"));
        server.enqueue(json("[{\"id\":1,\"country\":\"" + "x".repeat(2_000) + "\"}]"));

        var error = assertThrows(ExportException.class, () -> {
            java.util.Objects.requireNonNull(new RestRowSource(new DataApiClient(properties,
                    new StaticDataApiCredentials("m2m-token")),
                    new RowJsonMapper(properties), relation(), AUTH, properties)
                    .prepare(request()).block()).records().blockLast();
        });

        assertEquals(ErrorCode.ROW_TOO_LARGE, error.code());
    }

    @Test
    void allowsAValidSourceRowWhoseJsonEnvelopeIsLargerThanItsSourceValues() {
        var sourceBytes = (4 << 20) - 1L;
        var properties = properties(Map.of(
                "omniflux.limits.max-field-bytes", "5MB",
                "omniflux.limits.max-row-bytes", "4MB",
                "omniflux.source.rest.max-in-memory-size", "8MB"));
        server.enqueue(json("[{\"id\":1}]"));
        server.enqueue(json("[{\"id\":1,\"country\":\""
                + "x".repeat(Math.toIntExact(sourceBytes)) + "\"}]"));

        var scan = new RestRowSource(new DataApiClient(properties,
                new StaticDataApiCredentials("m2m-token")),
                new RowJsonMapper(properties), relation(), AUTH, properties)
                .prepare(request()).block();
        assert scan != null;

        assertDoesNotThrow(() -> scan.records().blockLast());
    }

    private RestRowSource source() {
        return source(100);
    }

    /** prepare() is never empty in these scenarios; a null block() is a test bug. */
    private static ExportScan prepare(RestRowSource source, ExportRequest request) {
        return Objects.requireNonNull(source.prepare(request).block());
    }

    private RestRowSource source(int pageSize) {
        var properties = properties(Map.of("omniflux.source.rest.page-size", pageSize));
        return new RestRowSource(new DataApiClient(properties,
                new StaticDataApiCredentials("m2m-token")),
                new RowJsonMapper(properties), relation(), AUTH, properties);
    }

    private OmnifluxProperties properties() {
        return properties(Map.of());
    }

    private OmnifluxProperties properties(Map<String, Object> overrides) {
        var values = new java.util.HashMap<>(overrides);
        values.put("omniflux.source.rest.base-url", server.url("/").toString());
        values.putIfAbsent("omniflux.source.rest.response-timeout", "5s");
        values.putIfAbsent("omniflux.source.rest.connect-timeout", "2s");
        values.putIfAbsent("omniflux.source.rest.max-retries", 2);
        return TestProps.with(values);
    }

    private static RelationDescriptor relation() {
        return new RelationDescriptor("public", "mock",
                List.of(new ColumnDescriptor("id", LogicalType.INTEGER, false, true),
                        new ColumnDescriptor("country", LogicalType.TEXT, true, false),
                        new ColumnDescriptor("note", LogicalType.TEXT, true, false)),
                new KeyContract("id"), Volatility.MUTABLE);
    }

    private static ExportRequest request() {
        return new ExportRequest("mock", List.of(new String[]{"country"}), List.of());
    }

    private static ExportRequest request(FilterSpec filter) {
        return new ExportRequest("mock", List.of("country", "note"), List.of(filter));
    }

    private static MockResponse json(String body) {
        return new MockResponse().setHeader("Content-Type", "application/json").setBody(body);
    }

    private static DataBuffer buffer(String body) {
        return new DefaultDataBufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8));
    }

    private static String rows(long first, long last) {
        var rows = new StringBuilder("[");
        for (long key = first; key <= last; key++) {
            if (key > first) rows.append(',');
            rows.append("{\"id\":").append(key).append(",\"country\":\"IN\"}");
        }
        return rows.append(']').toString();
    }

    private static void assertPage(String path, boolean hasAfter, long after) {
        assertTrue(path.contains("id=lte."), path);
        assertTrue(path.contains("country=eq.IN"), path);
        if (hasAfter) assertTrue(path.contains("id=gt." + after), path);
        else assertFalse(path.contains("id=gt."), path);
    }
}
