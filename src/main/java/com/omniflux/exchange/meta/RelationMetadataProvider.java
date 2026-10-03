package com.omniflux.exchange.meta;

import com.omniflux.exchange.security.AuthContext;
import reactor.core.publisher.Mono;

/**
 * Resolves a relation from the catalog owned by a source adapter.
 *
 * <p>The adapter is deliberately part of the {@link SchemaCatalog} lookup,
 * rather than this SPI. A REST relation may live behind a Data API backed by
 * Db2, while an R2DBC relation is described by the local PostgreSQL catalog.
 */
@FunctionalInterface
public interface RelationMetadataProvider {

    /**
     * Describes one relation. The returned descriptor must contain the
     * relation's columns and its continuation-key contract.
     *
     * @param relation an unqualified relation name
     * @return a descriptor, or an error represented by {@code ExportException}
     */
    Mono<RelationDescriptor> describe(String relation);

    /** Caller-aware metadata seam; providers may attach the caller context. */
    default Mono<RelationDescriptor> describe(String relation, AuthContext auth) {
        return describe(relation);
    }
}
