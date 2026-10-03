package com.omniflux.exchange.meta;

import com.omniflux.exchange.TestProps;
import com.omniflux.exchange.job.ErrorCode;
import com.omniflux.exchange.job.ExportException;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class SchemaCatalogTest {

    @Test
    void selectsTheMetadataProviderByAdapterWhenDescribing() {
        var restCalls = new AtomicInteger();
        var r2dbcCalls = new AtomicInteger();
        var relation = table(new ColumnDescriptor("id", LogicalType.INTEGER, false, true),
                new ColumnDescriptor("note", LogicalType.TEXT, true, false));
        var rest = provider(relation, restCalls);
        var r2dbc = provider(relation, r2dbcCalls);
        var catalog = new SchemaCatalog(Map.of("rest", rest, "r2dbc", r2dbc),
                properties("mock_orders"));

        assertEquals(relation, catalog.describe("rest", "mock_orders").block());
        assertEquals(1, restCalls.get());
        assertEquals(0, r2dbcCalls.get());

        catalog.describe("r2dbc", "mock_orders").block();
        assertEquals(1, r2dbcCalls.get());
    }

    @Test
    void catalogRefreshPublishesOneImmutableSnapshot() {
        var first = table(new ColumnDescriptor("id", LogicalType.INTEGER, false, true));
        var second = table(new ColumnDescriptor("id", LogicalType.INTEGER, false, true),
                ColumnDescriptor.of("note", LogicalType.TEXT));
        var catalog = new SchemaCatalog(Map.of("r2dbc", provider(first, new AtomicInteger())),
                properties("mock_orders"));

        catalog.refresh("r2dbc", Map.of("mock_orders", first));
        var before = catalog.snapshot("r2dbc");
        catalog.refresh("r2dbc", Map.of("mock_orders", second));

        assertEquals(1, before.get("mock_orders").columns().size());
        assertEquals(2, catalog.snapshot("r2dbc").get("mock_orders").columns().size());
        assertThrows(UnsupportedOperationException.class,
                () -> catalog.snapshot("r2dbc").put("other", second));
    }

    @Test
    void aConfiguredViewKeyIsUsedButAViewWithoutOneIsRejected() {
        var view = new RelationDescriptor("public", "mock_view",
                List.of(new ColumnDescriptor("id", LogicalType.INTEGER, true, false)),
                new KeyContract("id"), Volatility.STATIC);
        var noKeyCatalog = new SchemaCatalog(Map.of("rest", provider(view, new AtomicInteger())),
                properties("mock_view"));
        var noKey = assertThrows(ExportException.class,
                () -> noKeyCatalog.describe("rest", "mock_view").block());
        assertEquals(ErrorCode.UNSUPPORTED_PRIMARY_KEY, noKey.code());

        var configured = new SchemaCatalog(Map.of("rest", provider(view, new AtomicInteger())),
                propertiesWithViewKey());
        var described = Objects.requireNonNull(configured.describe("rest", "mock_view").block());
        assertEquals("id", described.keyContract().column());
    }

    @Test
    void providerForExposesTheRegisteredImplementation() {
        var provider = new RelationMetadataProvider() {
            @Override public Mono<RelationDescriptor> describe(String relation) {
                return Mono.empty();
            }
        };
        var catalog = new SchemaCatalog(Map.of("r2dbc", provider), properties("mock_orders"));
        assertInstanceOf(RelationMetadataProvider.class, catalog.providerFor("r2dbc"));
    }

    private static RelationMetadataProvider provider(RelationDescriptor descriptor, AtomicInteger calls) {
        return relation -> {
            calls.incrementAndGet();
            return Mono.just(descriptor);
        };
    }

    private static RelationDescriptor table(ColumnDescriptor... columns) {
        return new RelationDescriptor("public", "mock_orders", List.of(columns),
                new KeyContract("id"), Volatility.MUTABLE);
    }

    private static com.omniflux.exchange.config.OmnifluxProperties properties(String... relations) {
        return TestProps.with(Map.of("omniflux.security.allowed-relations", List.of(relations)));
    }

    private static com.omniflux.exchange.config.OmnifluxProperties propertiesWithViewKey() {
        return TestProps.with(Map.of(
                "omniflux.security.allowed-relations", List.of("mock_view"),
                "omniflux.security.view-keys.mock_view", "id"));
    }
}
