package com.omniflux.exchange.web;

import com.omniflux.exchange.TestProps;
import com.omniflux.exchange.config.OmnifluxProperties;
import com.omniflux.exchange.export.ExportRowSourceFactory;
import com.omniflux.exchange.meta.*;
import com.omniflux.exchange.security.AuthContext;
import com.omniflux.exchange.security.CurrentUserProvider;
import com.omniflux.exchange.security.PrincipalKey;
import com.omniflux.exchange.source.ExportRequest;
import com.omniflux.exchange.source.ExportScan;
import com.omniflux.exchange.source.RowRecord;
import com.omniflux.exchange.source.RowSource;
import org.junit.jupiter.api.Test;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DataControllerTest {
    private static final AuthContext AUTH = new AuthContext(
            new PrincipalKey("local", "alice", "tenant-a"), List.of("ANALYST"), "v1");

    @Test
    void browseRejectsMalformedFilterJsonBeforeCallingTheSource() {
        var sources = mock(ExportRowSourceFactory.class);
        var controller = controller(sources, mock(SchemaCatalog.class), mock(CurrentUserProvider.class));

        assertThrows(IllegalArgumentException.class,
                () -> controller.browse("mock_orders", 50, null, "not-json", exchange()).block());
    }

    @Test
    void browseUsesTheGovernedSourceWithCallerContextAndTypedFilters() {
        var sources = mock(ExportRowSourceFactory.class);
        var catalog = mock(SchemaCatalog.class);
        var users = mock(CurrentUserProvider.class);
        var source = mock(RowSource.class);
        var relation = new RelationDescriptor("public", "mock_orders",
                List.of(ColumnDescriptor.of("id", LogicalType.INTEGER),
                        ColumnDescriptor.of("country", LogicalType.TEXT)),
                new KeyContract("id"), Volatility.MUTABLE);
        when(catalog.describe("rest", "mock_orders", AUTH)).thenReturn(Mono.just(relation));
        when(users.currentAuth(any(ServerHttpRequest.class))).thenReturn(Mono.just(AUTH));
        when(sources.create(AUTH, null, 51)).thenReturn(source);
        when(source.prepare(any(ExportRequest.class))).thenReturn(Mono.just(new ExportScan(
                relation.columns(), 10L,
                Flux.just(new RowRecord(10, new Object[]{10L, "IN"})))));

        var page = Objects.requireNonNull(controller(sources, catalog, users)
                .browse("mock_orders", 50, null,
                        "[{\"column\":\"id\",\"operator\":\"GTE\",\"value\":10}]",
                        exchange()).block());

        assertEquals(1, page.rows().size());
        assertEquals(10L, page.rows().getFirst().get("id"));
        var request = org.mockito.ArgumentCaptor.forClass(ExportRequest.class);
        verify(source).prepare(request.capture());
        assertEquals(1, request.getValue().filters().size());
        assertEquals(10, request.getValue().filters().getFirst().value());
    }

    @Test void maxPageSizeFetchesOneMoreRowWithoutWrappingToIntMin() {
        var sources = mock(ExportRowSourceFactory.class);
        var catalog = mock(SchemaCatalog.class);
        var users = mock(CurrentUserProvider.class);
        var source = mock(RowSource.class);
        var relation = new RelationDescriptor("public", "mock_orders",
                List.of(ColumnDescriptor.of("id", LogicalType.INTEGER)),
                new KeyContract("id"), Volatility.MUTABLE);
        when(catalog.describe("rest", "mock_orders", AUTH)).thenReturn(Mono.just(relation));
        when(users.currentAuth(any(ServerHttpRequest.class))).thenReturn(Mono.just(AUTH));
        when(sources.create(AUTH, null, Integer.MAX_VALUE)).thenReturn(source);
        when(source.prepare(any(ExportRequest.class))).thenReturn(Mono.just(new ExportScan(
                relation.columns(), 10L,
                Flux.just(new RowRecord(10, new Object[]{10L})))));

        var page = Objects.requireNonNull(controller(sources, catalog, users,
                        TestProps.with("omniflux.ui.page-size", Integer.MAX_VALUE)))
                .browse("mock_orders", Integer.MAX_VALUE, null, null, exchange()).block();

        assertEquals(1, page.rows().size());
        assertEquals(10L, page.rows().getFirst().get("id"));
    }

    private static DataController controller(ExportRowSourceFactory sources,
                                              SchemaCatalog catalog,
                                              CurrentUserProvider users) {
        return controller(sources, catalog, users, TestProps.defaults());
    }

    private static DataController controller(ExportRowSourceFactory sources,
                                              SchemaCatalog catalog,
                                              CurrentUserProvider users,
                                              OmnifluxProperties properties) {
        return new DataController(sources, catalog, users, properties, new ObjectMapper());
    }

    private static MockServerWebExchange exchange() {
        return MockServerWebExchange.from(MockServerHttpRequest.get("/").build());
    }
}
