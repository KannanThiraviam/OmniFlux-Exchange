package com.omniflux.exchange.meta;

import com.omniflux.exchange.Containers;
import com.omniflux.exchange.PostgresTestBase;
import com.omniflux.exchange.job.ErrorCode;
import com.omniflux.exchange.job.ExportException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.r2dbc.core.DatabaseClient;

import java.sql.DriverManager;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class PgMetadataProviderIntegrationTest extends PostgresTestBase {
    @Autowired private DatabaseClient database;
    private String schema;

    @BeforeEach
    void setup() throws Exception {
        schema = "meta_" + UUID.randomUUID().toString().replace("-", "");
        execute("CREATE SCHEMA " + schema);
        execute("CREATE TABLE " + schema + ".items (id bigint PRIMARY KEY, note varchar(20), "
                + "amount numeric(12,2), enabled boolean, day date, moment timestamp, identifier uuid, "
                + "payload bytea, unsupported jsonb)");
        execute("CREATE VIEW " + schema + ".item_view AS SELECT id, note FROM " + schema + ".items");
        execute("CREATE TABLE " + schema + ".no_key (id bigint)");
        execute("CREATE TABLE " + schema + ".wrong_key (id uuid PRIMARY KEY)");
        execute("CREATE TABLE " + schema + ".composite (id bigint, other bigint, PRIMARY KEY(id, other))");
    }

    @AfterEach
    void cleanup() throws Exception { execute("DROP SCHEMA " + schema + " CASCADE"); }

    @Test
    void catalogUsesTheRequestedSchemaAndPreservesRealPostgresColumnTypes() {
        var provider = new PgMetadataProvider(database, schema);
        var result = provider.describe("items").block();
        assertNotNull(result);
        assertEquals(schema, result.schema());
        assertEquals("id", result.keyContract().column());
        assertEquals(Volatility.MUTABLE, result.volatility());
        assertFalse(result.column("id").nullable());
        assertTrue(result.column("note").nullable());
        assertEquals(LogicalType.INTEGER, result.column("id").logicalType());
        assertEquals(LogicalType.TEXT, result.column("note").logicalType());
        assertEquals(LogicalType.DECIMAL, result.column("amount").logicalType());
        assertEquals(LogicalType.BOOLEAN, result.column("enabled").logicalType());
        assertEquals(LogicalType.DATE, result.column("day").logicalType());
        assertEquals(LogicalType.TIMESTAMP, result.column("moment").logicalType());
        assertEquals(LogicalType.UUID, result.column("identifier").logicalType());
        assertEquals(LogicalType.BINARY, result.column("payload").logicalType());
        assertEquals(LogicalType.UNSUPPORTED, result.column("unsupported").logicalType());
        assertEquals(ErrorCode.UNKNOWN_RELATION, assertThrows(ExportException.class,
                () -> new PgMetadataProvider(database, "public").describe("items").block()).code());
    }

    @Test
    void viewsRequireAnExplicitIntegerContinuationKey() {
        assertEquals(ErrorCode.UNSUPPORTED_PRIMARY_KEY, assertThrows(ExportException.class,
                () -> new PgMetadataProvider(database, schema).describe("item_view").block()).code());
        for (String key : new String[] {"item_view", schema + ".item_view"}) {
            var result = new PgMetadataProvider(database, schema, Map.of(key, "id")).describe("item_view").block();
            assertNotNull(result);
            assertEquals(Volatility.STATIC, result.volatility());
            assertEquals("id", result.keyContract().column());
        }
        assertEquals(ErrorCode.UNSUPPORTED_PRIMARY_KEY, assertThrows(ExportException.class,
                () -> new PgMetadataProvider(database, schema, Map.of("item_view", "missing"))
                        .describe("item_view").block()).code());
        assertEquals(ErrorCode.UNSUPPORTED_PRIMARY_KEY, assertThrows(ExportException.class,
                () -> new PgMetadataProvider(database, schema, Map.of("item_view", "note"))
                        .describe("item_view").block()).code());
    }

    @Test
    void unsupportedKeysAndUnsafeIdentifiersFailBeforeAnExportCanStart() {
        var provider = new PgMetadataProvider(database, schema);
        for (String relation : new String[] {"no_key", "wrong_key", "composite"}) {
            assertEquals(ErrorCode.UNSUPPORTED_PRIMARY_KEY, assertThrows(ExportException.class,
                    () -> provider.describe(relation).block()).code());
        }
        for (String relation : new String[] {"items; DROP SCHEMA public", "public.items", ""}) {
            assertEquals(ErrorCode.RELATION_NOT_ALLOWED, assertThrows(ExportException.class,
                    () -> provider.describe(relation).block()).code());
        }
        assertThrows(IllegalArgumentException.class, () -> new PgMetadataProvider(database, "unsafe.schema"));
    }

    private static void execute(String sql) throws Exception {
        try (var connection = DriverManager.getConnection(Containers.postgresJdbcUrl(),
                Containers.postgresUser(), Containers.postgresPassword());
             var statement = connection.createStatement()) { statement.execute(sql); }
    }
}
