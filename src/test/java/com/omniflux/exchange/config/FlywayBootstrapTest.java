package com.omniflux.exchange.config;

import com.omniflux.exchange.Containers;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The two database states this service must start against, exercised for real
 * against the suite-owned Testcontainers PostgreSQL instance.
 *
 * <p>The EMPTY database is the fresh-deployment case: Flyway applies V1, V2,
 * then V3's admission-gate repair
 * and owns the history from row one. The PRE-SEEDED database models the schema
 * produced by {@code init-db/01-schema.sql}; it has no
 * {@code flyway_schema_history}. Without {@code baseline-on-migrate} Flyway
 * refuses to run against that pre-created schema.
 *
 * <h2>Why schemas, not one PostgreSQLContainer per path</h2>
 *
 * Each database state below is a dedicated schema in the suite-owned
 * PostgreSQL container. This keeps each test isolated while exercising the
 * same Flyway code and exact SQL text used by Compose; generated schema names
 * avoid collisions and {@link TestSchema#close()} drops each schema.
 *
 * <p>Flyway is driven through its Java API with the exact configuration
 * application.yml ships ({@code baseline-on-migrate}, {@code baseline-version 1})
 * rather than by booting Spring: the property under test is Flyway's own
 * behavior, and a Spring context here would re-test what StackTest already
 * covers.
 */
class FlywayBootstrapTest {

    @Test void migratesAnEmptyDatabaseNormally() {
        try (var db = emptyDatabase()) {
            assertEquals(7, flyway(db).migrate().migrationsExecuted, "V1 through V7");
            assertTrue(tableExists(db, "transfer_jobs"));
            assertTrue(tableExists(db, "mock_wide"));
            assertTrue(columnExists(db));
        }
    }

    @Test void baselinesAPreSeededDatabaseInsteadOfRefusingToStart() {
        // THE COMPOSE CASE. init-db already created the schema and there is no
        // flyway_schema_history. Without baseline-on-migrate Flyway REFUSES to
        // run and the application never starts.
        try (var db = preSeededDatabase()) {
            // V1 is already applied by init-db, so Flyway BASELINES at V1 and
            // V1 is already present, so Flyway baselines at 1 and applies V2-V7.
            assertEquals(6, flyway(db).migrate().migrationsExecuted, "V2 through V7");
            assertTrue(tableExists(db, "flyway_schema_history"));
            assertTrue(columnExists(db), "V2 must have run");
            assertTrue(tableExists(db, "admission_gate"), "V3 must preserve the gate");
            assertEquals("1", baselineVersionIn(db));
        }
    }

    @Test void v2IsIdempotentOnBOTHPaths() {
        // It runs against an empty database (after V1) and against a database
        // init-db already populated. Applying it twice must be a no-op on each.
        try (var empty = emptyDatabase(); var seeded = preSeededDatabase()) {
            for (var db : List.of(empty, seeded)) {
                flyway(db).migrate();
                assertDoesNotThrow(() ->
                        applyRaw(db));
                assertEquals(200, countColumns(db) - 1);
                assertFalse(tableExists(db, "schema_version"), "V2 drops the obsolete table");
            }
        }
    }

    // -- the two databases -----------------------------------------------------

    /**
     * A schema that exists but holds nothing — the fresh-deployment database.
     * AutoCloseable so try-with-resources yields the cleanup for free.
     */
    private static TestSchema emptyDatabase() {
        var db = new TestSchema();
        db.execute("CREATE SCHEMA " + db.name());   // absent = empty by construction
        return db;
    }

    /**
     * The pre-seeded database state: init-db's script has run and nothing
     * else has. Applying the script over JDBC lands in the same objects the
     * docker-entrypoint's psql run produces — identical statements, identical
     * text — which is the state Flyway must decide to baseline rather than
     * refuse.
     */
    private static TestSchema preSeededDatabase() {
        var db = emptyDatabase();
        db.applyScript(Path.of("init-db/01-schema.sql"));
        return db;
    }

    /**
     * The configuration application.yml ships, pointed at the schema under
     * test. {@code baselineVersion("1")} is what makes the pre-seeded path a
     * baseline rather than a refusal.
     */
    private static Flyway flyway(TestSchema db) {
        return Flyway.configure()
                .dataSource(db.url(), db.user(), db.password())
                .schemas(db.name())
                .createSchemas(true)
                .baselineOnMigrate(true)
                .baselineVersion("1")
                .load();
    }

    // -- assertions' supporting queries ----------------------------------------

    private static boolean tableExists(TestSchema db, String table) {
        try (var c = db.connect();
             var ps = c.prepareStatement("""
                     SELECT EXISTS (
                       SELECT 1 FROM information_schema.tables
                       WHERE table_schema = ? AND table_name = ?)""")) {
            ps.setString(1, db.name());
            ps.setString(2, table);
            try (var rs = ps.executeQuery()) { rs.next(); return rs.getBoolean(1); }
        } catch (SQLException e) { throw new IllegalStateException(e); }
    }

    private static boolean columnExists(TestSchema db) {
        try (var c = db.connect();
             var ps = c.prepareStatement("""
                     SELECT EXISTS (
                       SELECT 1 FROM information_schema.columns
                       WHERE table_schema = ?
                         AND table_name = ? AND column_name = ?)""")) {
            ps.setString(1, db.name());
            ps.setString(2, "mock_orders");
            ps.setString(3, "note");
            try (var rs = ps.executeQuery()) { rs.next(); return rs.getBoolean(1); }
        } catch (SQLException e) { throw new IllegalStateException(e); }
    }

    /** Column count of a relation; the id column of mock_wide is not one of the 200. */
    private static int countColumns(TestSchema db) {
        try (var c = db.connect();
             var ps = c.prepareStatement("""
                     SELECT count(*) FROM information_schema.columns
                     WHERE table_schema = ? AND table_name = ?""")) {
            ps.setString(1, db.name());
            ps.setString(2, "mock_wide");
            try (var rs = ps.executeQuery()) { rs.next(); return rs.getInt(1); }
        } catch (SQLException e) { throw new IllegalStateException(e); }
    }

    /**
     * The version history itself says Flyway baselined at. {@code "1"} means
     * "everything up to and including V1 was already applied by someone else"
     * — here, init-db.
     */
    // Schema and table are fixed test literals, never user input.
    @SuppressWarnings("SqlSourceToSinkFlow")
    private static String baselineVersionIn(TestSchema db) {
        try (var c = db.connect();
             var ps = c.prepareStatement(
                     "SELECT version FROM " + db.name() + ".flyway_schema_history"
                   + " WHERE type = 'BASELINE'")) {
            try (var rs = ps.executeQuery()) {
                assertTrue(rs.next(), "no BASELINE row in flyway_schema_history");
                return rs.getString(1);
            }
        } catch (SQLException e) { throw new IllegalStateException(e); }
    }

    /**
     * Re-applies a migration OUT of band — Flyway owns migrations, so going
     * around it is the point: the empty-DB and pre-seeded paths must both
     * tolerate a replay of V2 without error and without effect.
     */
    private static void applyRaw(TestSchema db) {
        db.applyScript(Path.of("src/main/resources/db/migration").resolve("V2__demo_relations.sql"));
    }

    // -- the schema handle ------------------------------------------------------

    /**
     * One throwaway schema in suite-owned Testcontainers PostgreSQL. Generated
     * names make concurrent or crashed runs collide with nothing; close() removes the
     * schema, so the shared database is exactly as this test found it.
     */
    private static final class TestSchema implements AutoCloseable {

        private final String name = "flyway_t_" + UUID.randomUUID().toString()
                .replace("-", "").substring(0, 12);

        String name()     { return name; }
        String url()      { return Containers.postgresJdbcUrl() + "?currentSchema=" + name; }
        String user()     { return Containers.postgresUser(); }
        String password() { return Containers.postgresPassword(); }

        Connection connect() throws SQLException {
            return DriverManager.getConnection(url(), user(), password());
        }

        // DDL strings are fixed literals from this test class.
        @SuppressWarnings("SqlSourceToSinkFlow")
        void execute(String sql) {
            try (var c = connect(); var st = c.createStatement()) {
                st.execute(sql);
            } catch (SQLException e) { throw new IllegalStateException(sql, e); }
        }

        /** Runs a .sql file's text as one multi-statement batch, as psql would. */
        void applyScript(Path file) {
            try (var c = connect(); var st = c.createStatement()) {
                st.execute("SET search_path TO " + name);
                st.execute(Files.readString(file));
            } catch (Exception e) { throw new IllegalStateException(file.toString(), e); }
        }

        @Override public void close() {
            // Not connect(): the session's currentSchema is the schema being dropped.
            try (var c = DriverManager.getConnection(Containers.postgresJdbcUrl(),
                                                     user(), password());
                 var st = c.createStatement()) {
                st.execute("DROP SCHEMA IF EXISTS " + name + " CASCADE");
            } catch (SQLException e) { throw new IllegalStateException(e); }
        }
    }
}
