package com.omniflux.exchange.web;

import com.omniflux.exchange.config.OmnifluxProperties;
import com.omniflux.exchange.export.ExportRowSourceFactory;
import com.omniflux.exchange.meta.ColumnDescriptor;
import com.omniflux.exchange.meta.RelationDescriptor;
import com.omniflux.exchange.meta.SchemaCatalog;
import com.omniflux.exchange.meta.Volatility;
import com.omniflux.exchange.security.AuthContext;
import com.omniflux.exchange.security.CurrentUserProvider;
import com.omniflux.exchange.source.ExportRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;

@RestController
@RequestMapping("/api/meta")
public final class MetaController {
    /**
     * Ceiling for one relation's best-effort count probe, so a slow upstream
     * count can never gate the relation list. A constant rather than config on
     * purpose: the config schema in the design spec (§12) is authoritative.
     */
    private static final Duration COUNT_TIMEOUT = Duration.ofSeconds(5);

    private final SchemaCatalog catalog;
    private final String adapter;
    private final CurrentUserProvider users;
    private final ExportRowSourceFactory sources;

    public MetaController(SchemaCatalog catalog, OmnifluxProperties properties,
                          CurrentUserProvider users) {
        this(catalog, properties, users, null);
    }

    @Autowired
    public MetaController(SchemaCatalog catalog, OmnifluxProperties properties,
                          CurrentUserProvider users, ExportRowSourceFactory sources) {
        this.catalog = catalog;
        this.adapter = properties.source().defaultAdapter();
        this.users = users;
        this.sources = sources;
    }

    @GetMapping("/relations")
    public Mono<RelationList> relations(ServerWebExchange exchange) {
        return users.currentAuth(exchange.getRequest())
                .flatMap(auth -> catalog.refresh(adapter, auth)
                .thenMany(Flux.defer(() -> Flux.fromIterable(catalog.snapshot(adapter).values())))
                // Counts run concurrently and are folded into ONE response so the
                // UI renders the picker from a single consistent snapshot; the
                // per-relation probe is best-effort and never fails the list.
                .flatMapSequential(relation -> withRowCount(relation, auth), 4)
                .collectList())
                .map(RelationList::new);
    }

    private Mono<RelationSummary> withRowCount(RelationDescriptor relation, AuthContext auth) {
        RelationSummary uncounted = new RelationSummary(relation.name(), relation.schema(),
                relation.keyContract().column(), relation.volatility(), null);
        if (sources == null) return Mono.just(uncounted);
        ExportRequest countRequest = new ExportRequest(relation.name(),
                List.of(relation.keyContract().column()), List.of(), "CSV", "RAW", null);
        return sources.create(auth, null, 1).count(countRequest)
                .timeout(COUNT_TIMEOUT)
                .onErrorResume(error -> Mono.empty())
                .map(count -> new RelationSummary(relation.name(), relation.schema(),
                        relation.keyContract().column(), relation.volatility(), count))
                .defaultIfEmpty(uncounted);
    }

    @GetMapping("/relations/{relation}/columns")
    public Mono<RelationColumns> columns(@PathVariable String relation,
                                         ServerWebExchange exchange) {
        return users.currentAuth(exchange.getRequest())
                .flatMap(auth -> catalog.describe(adapter, relation, auth))
                .map(value -> new RelationColumns(value.name(), value.schema(),
                        value.columns(), value.volatility()));
    }

    public record RelationList(List<RelationSummary> relations) {
        public RelationList { relations = List.copyOf(relations); }
    }

    /** Best-effort row count; null means the probe was unavailable, never that the table is empty. */
    public record RelationSummary(String name, String schema, String keyColumn,
                                  Volatility volatility, Long rowCount) { }

    public record RelationColumns(String name, String schema, List<ColumnDescriptor> columns,
                                  Volatility volatility) {
        public RelationColumns { columns = List.copyOf(columns); }
    }
}
