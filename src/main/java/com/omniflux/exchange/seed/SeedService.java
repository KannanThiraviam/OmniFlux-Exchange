package com.omniflux.exchange.seed;

import com.omniflux.exchange.config.OmnifluxProperties;
import com.omniflux.exchange.job.ErrorCode;
import com.omniflux.exchange.job.ExportException;
import com.omniflux.exchange.meta.ColumnDescriptor;
import com.omniflux.exchange.meta.LogicalType;
import com.omniflux.exchange.meta.RelationDescriptor;
import com.omniflux.exchange.meta.SchemaCatalog;
import org.springframework.context.annotation.Profile;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Demo-only bounded seeder. It computes a row-count and byte bounded batch,
 * then generates only one batch at a time before issuing a multi-row insert.
 */
@Service
@Profile("demo")
public final class SeedService {
    private static final int FAST_SEED_BATCH_ROWS = 100_000;

    /**
     * Bounded multi-row fixture insert for the demo customers relation;
     * PostgreSQL generates the same deterministic demo values server-side.
     */
    private static final String FAST_SEED_CUSTOMERS_SQL = """
            INSERT INTO public.mock_customers
                (full_name, email_address, country, membership_status)
            SELECT 'seed-' || n,
                   'seed' || n || '@example.test',
                   'seed-' || n,
                   'seed-' || n
            FROM generate_series(CAST(:start AS bigint),
                                  CAST(:end AS bigint) - 1) AS generated(n)
            """;

    /** Deterministic prefix shared by the generated demo column values. */
    private static final String SEED_VALUE_PREFIX = "seed-";

    private final DatabaseClient database;
    private final SchemaCatalog catalog;
    private final OmnifluxProperties properties;
    private final String adapter;

    public SeedService(DatabaseClient database, SchemaCatalog catalog,
                       OmnifluxProperties properties) {
        this.database = database;
        this.catalog = catalog;
        this.properties = properties;
        this.adapter = properties.source().defaultAdapter();
    }

    public Mono<Long> seed(String relationName, long targetRows) {
        if (targetRows < 0) {
            return Mono.error(new IllegalArgumentException("target row count must not be negative"));
        }
        if (!properties.security().allowedRelations().contains(relationName)) {
            return Mono.error(new ExportException(ErrorCode.RELATION_NOT_ALLOWED,
                    "relation is not allowlisted: " + relationName));
        }
        return catalog.describe(adapter, relationName)
                .flatMap(relation -> seedRelation(relation, targetRows));
    }

    private Mono<Long> seedRelation(RelationDescriptor relation, long targetRows) {
        String table = quoteRelation(relation);
        return database.sql("SELECT count(*) AS row_count FROM " + table)
                .map((row, metadata) -> {
                    Number count = (Number) row.get("row_count");
                    return count == null ? 0L : count.longValue();
                })
                .one()
                .defaultIfEmpty(0L)
                .flatMap(existing -> {
                    long remaining = targetRows - existing;
                    if (remaining <= 0) return Mono.just(targetRows);
                    if ("mock_customers".equalsIgnoreCase(relation.name())) {
                        return fastSeedCustomers(existing, targetRows);
                    }
                    int batchSize = batchSizeFor(relation);
                    Flux<Long> indexes = Flux.range(0, Math.toIntExact(remaining))
                            .map(Integer::longValue);
                    return indexes.buffer(batchSize)
                            .concatMap(batch -> insertBatch(relation, batch))
                            .then(Mono.just(targetRows));
                });
    }

    /**
     * The generic path deliberately binds one bounded batch at a time, which
     * is the safe default for arbitrary relations. The local 10M proof needs a
     * practical fixture, though: a 5,000-row multi-value statement takes hours
     * to create millions of rows on a developer laptop. PostgreSQL generates
     * the same deterministic demo values server-side in bounded 100k-row
     * batches, so the application still owns the setup while retaining a small
     * client memory footprint.
     */
    private Mono<Long> fastSeedCustomers(long existing, long targetRows) {
        long batches = (targetRows - existing + FAST_SEED_BATCH_ROWS - 1)
                / FAST_SEED_BATCH_ROWS;
        return Flux.range(0, Math.toIntExact(batches))
                .concatMap(batch -> {
                    long start = existing + (long) batch * FAST_SEED_BATCH_ROWS;
                    long end = Math.min(targetRows, start + FAST_SEED_BATCH_ROWS);
                    return database.sql(FAST_SEED_CUSTOMERS_SQL)
                            .bind("start", start)
                            .bind("end", end)
                            .fetch().rowsUpdated();
                })
                .then(Mono.just(targetRows));
    }

    private Mono<Long> insertBatch(RelationDescriptor relation, List<Long> indexes) {
        List<ColumnDescriptor> writable = relation.columns().stream()
                .filter(column -> !column.primaryKey())
                .toList();
        if (writable.isEmpty()) {
            return database.sql("INSERT INTO " + quoteRelation(relation)
                            + " DEFAULT VALUES")
                    .fetch().rowsUpdated()
                    .map(Long::valueOf);
        }

        StringBuilder sql = new StringBuilder("INSERT INTO ")
                .append(quoteRelation(relation)).append(" (")
                .append(String.join(", ", writable.stream().map(c -> quote(c.name())).toList()))
                .append(") VALUES ");
        List<Binding> bindings = new ArrayList<>();
        for (int row = 0; row < indexes.size(); row++) {
            if (row > 0) sql.append(", ");
            sql.append('(');
            for (int col = 0; col < writable.size(); col++) {
                if (col > 0) sql.append(", ");
                String name = "v_" + row + "_" + col;
                sql.append(':').append(name);
                bindings.add(new Binding(name, valueFor(writable.get(col), indexes.get(row))));
            }
            sql.append(')');
        }
        DatabaseClient.GenericExecuteSpec statement = database.sql(sql.toString());
        for (Binding binding : bindings) {
            if (binding.value() == null) statement = statement.bindNull(binding.name(), String.class);
            else statement = statement.bind(binding.name(), binding.value());
        }
        return statement.fetch().rowsUpdated().map(Long::valueOf);
    }

    private int batchSizeFor(RelationDescriptor relation) {
        long estimated = Math.max(1L, relation.columns().stream()
                .mapToLong(column -> column.logicalType().variableWidth() ? 256L : 32L)
                .sum());
        long byBytes = Math.max(1L, properties.seed().batchBytes().toBytes() / estimated);
        return (int) Math.clamp(properties.seed().batchSize(), 1L, byBytes);
    }

    private static Object valueFor(ColumnDescriptor column, long row) {
        if (column.logicalType() == LogicalType.TEXT) {
            if (column.name().equalsIgnoreCase("note")) {
                return switch ((int) (row % 6)) {
                    case 0 -> "quote \" comma, newline\n€";
                    case 1 -> "";
                    case 2 -> null;
                    default -> SEED_VALUE_PREFIX + row;
                };
            }
            return column.name().toLowerCase().contains("email")
                    ? "seed" + row + "@example.test" : SEED_VALUE_PREFIX + row;
        }
        return switch (column.logicalType()) {
            case INTEGER -> row + 1;
            case DECIMAL -> BigDecimal.valueOf(row).movePointLeft(2);
            case BOOLEAN -> row % 2 == 0;
            case DATE -> LocalDate.of(2024, 1, 1).plusDays(row % 365);
            case UUID -> UUID.nameUUIDFromBytes((column.name() + row)
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            case TIMESTAMP -> java.time.LocalDateTime.of(2024, 1, 1, 0, 0)
                    .plusSeconds(row);
            case BINARY -> (SEED_VALUE_PREFIX + row).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            case UNSUPPORTED -> throw new ExportException(ErrorCode.UNSUPPORTED_COLUMN_TYPE,
                    "cannot seed unsupported column: " + column.name());
            default -> throw new ExportException(ErrorCode.UNSUPPORTED_COLUMN_TYPE,
                    "cannot seed column: " + column.name());
        };
    }

    private static String quoteRelation(RelationDescriptor relation) {
        return quote(relation.schema()) + "." + quote(relation.name());
    }

    private static String quote(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    private record Binding(String name, Object value) { }
}
