package com.omniflux.exchange.meta;

import com.omniflux.exchange.config.OmnifluxProperties;
import com.omniflux.exchange.job.ErrorCode;
import com.omniflux.exchange.job.ExportException;
import com.omniflux.exchange.security.AuthContext;
import com.omniflux.exchange.source.FilterOperator;
import com.omniflux.exchange.source.FilterSpec;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/**
 * The request-facing, immutable view of relation metadata.
 *
 * <p>Requests resolve relation names through this catalog before an adapter
 * can build SQL or a Data API URL. Refreshes construct a complete new map and
 * publish it with one {@link AtomicReference#set(Object)}, so readers observe
 * either the old catalog or the new one, never a partially loaded map.
 */
public final class SchemaCatalog {

    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_]\\w*");
    private static final Set<String> SERVICE_RELATIONS = Set.of("transfer_jobs", "admission_gate");

    private final Map<String, RelationMetadataProvider> providers;
    private final OmnifluxProperties properties;
    private final AtomicReference<CatalogSnapshot> snapshot =
            new AtomicReference<>(new CatalogSnapshot(Map.of()));

    public SchemaCatalog(Map<String, RelationMetadataProvider> providers,
                         OmnifluxProperties properties) {
        if (providers == null || providers.isEmpty()) {
            throw new IllegalArgumentException("at least one metadata provider is required");
        }
        this.providers = Map.copyOf(providers);
        this.properties = Objects.requireNonNull(properties, "properties");
    }

    /** Returns the provider selected for one adapter. */
    public RelationMetadataProvider providerFor(String adapter) {
        var provider = providers.get(adapter);
        if (provider == null) {
            throw new ExportException(ErrorCode.UPSTREAM_CLIENT_ERROR,
                    "no metadata provider is registered for adapter: " + adapter);
        }
        return provider;
    }

    /** Describes a relation through the provider selected by the adapter. */
    public Mono<RelationDescriptor> describe(String adapter, String relation) {
        return describe(adapter, relation, null);
    }

    /** Describes a relation while preserving the caller context for governed APIs. */
    public Mono<RelationDescriptor> describe(String adapter, String relation, AuthContext auth) {
        var canonical = requireAllowedRelation(relation);
        return providerFor(adapter).describe(canonical, auth)
                .switchIfEmpty(Mono.error(new ExportException(ErrorCode.UNKNOWN_RELATION,
                        "metadata provider returned no relation: " + canonical)))
                .map(descriptor -> validateDescriptor(canonical, descriptor))
                .doOnNext(descriptor -> publish(adapter, canonical, descriptor))
                .onErrorMap(error -> !(error instanceof ExportException),
                        error -> new ExportException(ErrorCode.UPSTREAM_UNAVAILABLE,
                                "metadata lookup failed for " + canonical, error));
    }

    /** Refreshes metadata using the caller context required by governed APIs. */
    public Mono<Void> refresh(String adapter, AuthContext auth) {
        var names = allowedRelations();
        return Flux.fromIterable(names)
                .concatMap(name -> providerFor(adapter).describe(name, auth)
                        .switchIfEmpty(Mono.error(new ExportException(ErrorCode.UNKNOWN_RELATION,
                                "metadata provider returned no relation: " + name)))
                        .map(descriptor -> validateDescriptor(name, descriptor)))
                .collectMap(RelationDescriptor::name, descriptor -> descriptor,
                        LinkedHashMap::new)
                .doOnNext(descriptors -> replace(adapter, descriptors))
                .then();
    }

    /** Publishes a preloaded, validated adapter snapshot. Useful for startup and tests. */
    public void refresh(String adapter, Map<String, RelationDescriptor> descriptors) {
        var validated = new LinkedHashMap<String, RelationDescriptor>();
        for (var entry : descriptors.entrySet()) {
            var canonical = requireAllowedRelation(entry.getKey());
            validated.put(canonical, validateDescriptor(canonical, entry.getValue()));
        }
        replace(adapter, validated);
    }

    /** Returns an immutable adapter snapshot. */
    public Map<String, RelationDescriptor> snapshot(String adapter) {
        return snapshot.get().relationsByAdapter().getOrDefault(adapter, Map.of());
    }

    /** Returns the default adapter snapshot. */
    public Map<String, RelationDescriptor> snapshot() {
        return snapshot(defaultAdapter());
    }

    /** Validates requested columns against a descriptor and preserves request order. */
    public void requireColumns(RelationDescriptor relation,
                               List<String> requestedColumns) {
        Objects.requireNonNull(relation, "relation");
        var names = requestedColumns == null || requestedColumns.isEmpty()
                ? relation.columns().stream().map(ColumnDescriptor::name).toList()
                : List.copyOf(requestedColumns);
        var maxColumns = security().maxColumns();
        if (names.size() > maxColumns) {
            throw new ExportException(ErrorCode.TOO_MANY_COLUMNS,
                    "requested " + names.size() + " columns; maximum is " + maxColumns);
        }
        var seen = new HashSet<String>();
        for (String name : names) {
            if (!seen.add(name)) {
                throw new ExportException(ErrorCode.DUPLICATE_COLUMN,
                        "column requested more than once: " + name);
            }
            var column = relation.columns().stream()
                    .filter(candidate -> candidate.name().equals(name))
                    .findFirst()
                    .orElseThrow(() -> new ExportException(ErrorCode.UNKNOWN_COLUMN,
                            "unknown column " + name + " on relation " + relation.name()));
            if (column.logicalType() == LogicalType.UNSUPPORTED) {
                throw new ExportException(ErrorCode.UNSUPPORTED_COLUMN_TYPE,
                        "column type is not exportable: " + name);
            }
        }
    }

    /** Validates filter columns and the bounded IN-list contract. */
    public void requireFilters(RelationDescriptor relation, List<FilterSpec> filters) {
        if (filters == null) return;
        for (FilterSpec filter : filters) {
            requireColumns(relation, List.of(filter.column()));
            if (filter.operator() == FilterOperator.IN) {
                if (!(filter.value() instanceof java.util.Collection<?> values)) {
                    throw new ExportException(ErrorCode.TOO_MANY_IN_VALUES,
                            "IN filter must carry a collection of values");
                }
                if (values.size() > security().maxInValues()) {
                    throw new ExportException(ErrorCode.TOO_MANY_IN_VALUES,
                            "IN filter has " + values.size() + " values; maximum is "
                                    + security().maxInValues());
                }
            }
        }
    }

    /** Returns the configured names after applying the same protected-name checks. */
    public List<String> allowedRelations() {
        var configured = security().allowedRelations();
        return configured == null ? List.of() : configured.stream()
                .map(this::requireAllowedRelation)
                .distinct()
                .toList();
    }

    private RelationDescriptor validateDescriptor(String requested, RelationDescriptor descriptor) {
        if (descriptor == null) {
            throw new ExportException(ErrorCode.UNKNOWN_RELATION, "metadata provider returned null: " + requested);
        }
        if (!requested.equals(descriptor.name())) {
            throw new ExportException(ErrorCode.UNKNOWN_RELATION,
                    "metadata provider returned a different relation: " + descriptor.name());
        }
        var configuredViewKey = security().viewKeys().get(requested);
        var keyName = configuredViewKey == null ? descriptor.keyContract().column() : configuredViewKey;
        var key = descriptor.columns().stream()
                .filter(column -> column.name().equals(keyName))
                .findFirst()
                .orElseThrow(() -> new ExportException(ErrorCode.UNSUPPORTED_PRIMARY_KEY,
                        "key column is not present in relation: " + keyName));
        if (key.logicalType() != LogicalType.INTEGER) {
            throw new ExportException(ErrorCode.UNSUPPORTED_PRIMARY_KEY,
                    "key column must be an integer: " + keyName);
        }
        if (configuredViewKey == null && !key.primaryKey()) {
            throw new ExportException(ErrorCode.UNSUPPORTED_PRIMARY_KEY,
                    "relation has no declared integer primary key: " + requested);
        }
        var canonicalKey = keyName.equals(descriptor.keyContract().column())
                ? descriptor.keyContract() : new KeyContract(keyName);
        return canonicalKey.equals(descriptor.keyContract())
                ? descriptor
                : new RelationDescriptor(descriptor.schema(), descriptor.name(), descriptor.columns(),
                        canonicalKey, descriptor.volatility());
    }

    private String requireAllowedRelation(String rawRelation) {
        var relation = canonicalRelation(rawRelation);
        if (relation == null || isProtectedRelation(relation)) {
            throw new ExportException(ErrorCode.RELATION_NOT_ALLOWED,
                    "relation is protected or has an unsafe identifier: " + rawRelation);
        }
        var allowed = security().allowedRelations();
        if (allowed == null || !allowed.contains(rawRelation) && !allowed.contains(relation)) {
            throw new ExportException(ErrorCode.RELATION_NOT_ALLOWED,
                    "relation is not on the allowlist: " + rawRelation);
        }
        return relation;
    }

    private static String canonicalRelation(String raw) {
        if (raw == null || raw.isBlank()) return null;
        var dot = raw.indexOf('.');
        if (dot >= 0 && dot != raw.lastIndexOf('.')) return null;
        var relation = dot >= 0 ? raw.substring(dot + 1) : raw;
        if (!IDENTIFIER.matcher(relation).matches()) return null;
        if (dot >= 0) {
            var schema = raw.substring(0, dot);
            if (!IDENTIFIER.matcher(schema).matches()) return null;
            if (schema.equals("pg_catalog") || schema.equals("information_schema")) return null;
        }
        return relation;
    }

    private static boolean isProtectedRelation(String relation) {
        return SERVICE_RELATIONS.contains(relation)
                || relation.startsWith("pg_")
                || relation.equals("information_schema")
                || relation.equals("pg_catalog");
    }

    private void publish(String adapter, String relation, RelationDescriptor descriptor) {
        snapshot.updateAndGet(current -> {
            var byAdapter = new LinkedHashMap<>(current.relationsByAdapter());
            var relations = new LinkedHashMap<>(byAdapter.getOrDefault(adapter, Map.of()));
            relations.put(relation, descriptor);
            byAdapter.put(adapter, Map.copyOf(relations));
            return new CatalogSnapshot(Map.copyOf(byAdapter));
        });
    }

    private void replace(String adapter, Map<String, RelationDescriptor> descriptors) {
        snapshot.updateAndGet(current -> {
            var byAdapter = new LinkedHashMap<>(current.relationsByAdapter());
            byAdapter.put(adapter, Map.copyOf(new LinkedHashMap<>(descriptors)));
            return new CatalogSnapshot(Map.copyOf(byAdapter));
        });
    }

    private String defaultAdapter() {
        return properties.source() == null || properties.source().defaultAdapter() == null
                ? providers.keySet().iterator().next() : properties.source().defaultAdapter();
    }

    private OmnifluxProperties.Security security() {
        if (properties.security() == null) {
            throw new ExportException(ErrorCode.RELATION_NOT_ALLOWED, "security configuration is missing");
        }
        return properties.security();
    }

    private record CatalogSnapshot(Map<String, Map<String, RelationDescriptor>> relationsByAdapter) {
        private CatalogSnapshot {
            relationsByAdapter = Map.copyOf(relationsByAdapter);
        }
    }
}
