package com.omniflux.exchange.meta;

import com.omniflux.exchange.job.ErrorCode;
import com.omniflux.exchange.job.ExportException;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Mono;

import java.util.*;
import java.util.regex.Pattern;

/**
 * PostgreSQL relation metadata provider used by the local R2DBC adapter.
 *
 * <p>All caller-controlled values are binds. The catalog names are fixed
 * {@code pg_catalog} identifiers and relation names are never interpolated
 * into SQL. The query is schema-qualified so a relation with the same name in
 * another schema cannot be described accidentally.
 */
public class PgMetadataProvider implements RelationMetadataProvider {

    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_]\\w*");
    private static final String SCHEMA_NAME = "schema_name";
    private static final String RELATION_NAME = "relation_name";
    private static final String METADATA_SQL = """
            SELECT n.nspname AS schema_name,
                   c.relname AS relation_name,
                   c.relkind::text AS relation_kind,
                   a.attname AS column_name,
                   format_type(a.atttypid, a.atttypmod) AS data_type,
                   NOT a.attnotnull AS nullable,
                   EXISTS (
                       SELECT 1
                         FROM pg_catalog.pg_index ix
                         JOIN pg_catalog.pg_attribute ka
                           ON ka.attrelid = ix.indexrelid
                          AND ka.attnum = ANY (ix.indkey)
                        WHERE ix.indrelid = c.oid
                          AND ix.indisprimary
                          AND ka.attname = a.attname
                   ) AS primary_key
              FROM pg_catalog.pg_class c
              JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
              JOIN pg_catalog.pg_attribute a ON a.attrelid = c.oid
             WHERE n.nspname = :schema_name
               AND c.relname = :relation_name
               AND c.relkind IN ('r', 'p', 'v', 'm', 'f')
               AND a.attnum > 0
               AND NOT a.attisdropped
             ORDER BY a.attnum
            """;

    private final DatabaseClient client;
    private final String schema;
    private final Map<String, String> configuredViewKeys;

    public PgMetadataProvider(DatabaseClient client) {
        this(client, "public", Map.of());
    }

    public PgMetadataProvider(DatabaseClient client, String schema) {
        this(client, schema, Map.of());
    }

    public PgMetadataProvider(DatabaseClient client, Map<String, String> configuredViewKeys) {
        this(client, "public", configuredViewKeys);
    }

    public PgMetadataProvider(DatabaseClient client, String schema,
                              Map<String, String> configuredViewKeys) {
        this.client = Objects.requireNonNull(client, "client");
        if (schema == null || !IDENTIFIER.matcher(schema).matches()) {
            throw new IllegalArgumentException("schema must be a simple SQL identifier");
        }
        this.schema = schema;
        this.configuredViewKeys = Map.copyOf(configuredViewKeys == null ? Map.of() : configuredViewKeys);
    }

    @Override
    public Mono<RelationDescriptor> describe(String relation) {
        if (!isIdentifier(relation)) {
            return Mono.error(new ExportException(ErrorCode.RELATION_NOT_ALLOWED,
                    "relation contains an unsafe identifier: " + relation));
        }

        return client.sql(METADATA_SQL)
                .bind(SCHEMA_NAME, schema)
                .bind(RELATION_NAME, relation)
                .map((row, metadata) -> new MetadataRow(
                        required(row.get(SCHEMA_NAME, String.class), SCHEMA_NAME),
                        required(row.get(RELATION_NAME, String.class), RELATION_NAME),
                        row.get("relation_kind", String.class),
                        required(row.get("column_name", String.class), "column_name"),
                        row.get("data_type", String.class),
                        Boolean.TRUE.equals(row.get("nullable", Boolean.class)),
                        Boolean.TRUE.equals(row.get("primary_key", Boolean.class))))
                .all()
                .collectList()
                .map(rows -> toDescriptor(relation, rows))
                .onErrorMap(error -> !(error instanceof ExportException),
                        error -> new ExportException(ErrorCode.UPSTREAM_UNAVAILABLE,
                                "PostgreSQL metadata lookup failed for " + relation, error));
    }

    private RelationDescriptor toDescriptor(String requestedRelation, List<MetadataRow> rows) {
        if (rows.isEmpty()) {
            throw new ExportException(ErrorCode.UNKNOWN_RELATION,
                    "relation does not exist in schema " + schema + ": " + requestedRelation);
        }

        var first = rows.getFirst();
        var columns = rows.stream()
                .map(row -> new ColumnDescriptor(row.columnName(), mapLogicalType(row.dataType()),
                        row.nullable(), row.primaryKey()))
                .toList();
        var primaryKeys = rows.stream()
                .filter(MetadataRow::primaryKey)
                .map(MetadataRow::columnName)
                .toList();
        var configuredKey = configuredViewKey(first.relationName());
        String key;
        if (configuredKey != null) {
            key = configuredKey;
        } else if (primaryKeys.size() == 1) {
            key = primaryKeys.getFirst();
        } else {
            throw new ExportException(ErrorCode.UNSUPPORTED_PRIMARY_KEY,
                    "relation must have exactly one integer primary key: " + requestedRelation);
        }

        var keyColumn = columns.stream()
                .filter(column -> column.name().equals(key))
                .findFirst()
                .orElseThrow(() -> new ExportException(ErrorCode.UNSUPPORTED_PRIMARY_KEY,
                        "key column is not present in relation: " + key));
        if (keyColumn.logicalType() != LogicalType.INTEGER) {
            throw new ExportException(ErrorCode.UNSUPPORTED_PRIMARY_KEY,
                    "key column must be an integer: " + key);
        }

        var volatility = isView(first.relationKind())
                ? Volatility.STATIC
                : Volatility.MUTABLE;
        return new RelationDescriptor(first.schemaName(), first.relationName(), columns,
                new KeyContract(key), volatility);
    }

    private String configuredViewKey(String relation) {
        return Optional.ofNullable(configuredViewKeys.get(schema + "." + relation))
                .or(() -> Optional.ofNullable(configuredViewKeys.get(relation)))
                .orElse(null);
    }

    static LogicalType mapLogicalType(String rawType) {
        if (rawType == null || rawType.isBlank()) return LogicalType.UNSUPPORTED;
        var type = rawType.toLowerCase(java.util.Locale.ROOT)
                .replaceAll("\\s+", " ")
                .trim();
        if (type.matches("(?:smallint|integer|bigint|int2|int4|int8|smallserial|serial|bigserial)")) {
            return LogicalType.INTEGER;
        }
        if (type.matches("(?:numeric|decimal|real|double precision|float|float4|float8|money)(?:\\(.*\\))?")) {
            return LogicalType.DECIMAL;
        }
        if (type.matches("(?:text|character varying|varchar|character|char)(?:\\(.*\\))?")) {
            return LogicalType.TEXT;
        }
        if (type.matches("(?:bytea|bit varying|varbit)(?:\\(.*\\))?")) {
            return LogicalType.BINARY;
        }
        if (Set.of("boolean", "bool").contains(type)) return LogicalType.BOOLEAN;
        if (type.equals("date")) return LogicalType.DATE;
        if (type.startsWith("timestamp")) return LogicalType.TIMESTAMP;
        if (type.equals("uuid")) return LogicalType.UUID;
        return LogicalType.UNSUPPORTED;
    }

    private static boolean isView(String relationKind) {
        return "v".equals(relationKind) || "m".equals(relationKind);
    }

    private static boolean isIdentifier(String value) {
        return value != null && IDENTIFIER.matcher(value).matches();
    }

    private static <T> T required(T value, String field) {
        if (value == null) throw new IllegalStateException("metadata row has no " + field);
        return value;
    }

    private record MetadataRow(String schemaName, String relationName, String relationKind,
                               String columnName, String dataType, boolean nullable,
                               boolean primaryKey) { }
}
