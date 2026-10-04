package com.omniflux.exchange.adapter.r2dbc;

import com.omniflux.exchange.Containers;
import com.omniflux.exchange.TestProps;
import com.omniflux.exchange.job.ErrorCode;
import com.omniflux.exchange.job.ExportException;
import com.omniflux.exchange.meta.ColumnDescriptor;
import com.omniflux.exchange.meta.KeyContract;
import com.omniflux.exchange.meta.LogicalType;
import com.omniflux.exchange.meta.RelationDescriptor;
import com.omniflux.exchange.meta.SchemaCatalog;
import com.omniflux.exchange.meta.Volatility;
import com.omniflux.exchange.source.ExportRequest;
import com.omniflux.exchange.source.SqlPlan;
import io.r2dbc.spi.ConnectionFactories;
import io.r2dbc.spi.ConnectionFactoryOptions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Mono;

import java.sql.DriverManager;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real lock waits verify deadlines at high-water, count and page query boundaries. */
class R2dbcRowSourceTimeoutTest {
    @ParameterizedTest
    @ValueSource(strings = {"high-water", "count", "page"})
    void blockedQueriesAreCancelledInPostgresAndConnectionsRemainUsable(String stage) throws Exception {
        String relation = "deadline_" + UUID.randomUUID().toString().replace("-", "");
        var options = ConnectionFactoryOptions.parse(Containers.postgresJdbcUrl().replace("jdbc:", "r2dbc:"))
                .mutate().option(ConnectionFactoryOptions.USER, Containers.postgresUser())
                .option(ConnectionFactoryOptions.PASSWORD, Containers.postgresPassword()).build();
        var pool = new io.r2dbc.pool.ConnectionPool(io.r2dbc.pool.ConnectionPoolConfiguration
                .builder(ConnectionFactories.get(options)).initialSize(0).maxSize(1).build());
        var client = DatabaseClient.create(pool);
        try (var control = DriverManager.getConnection(Containers.postgresJdbcUrl(),
                Containers.postgresUser(), Containers.postgresPassword());
             var statement = control.createStatement()) {
            statement.execute("CREATE TABLE " + relation + " (id bigint PRIMARY KEY)");
            statement.execute("INSERT INTO " + relation + " VALUES (1)");
            try {
                var props = TestProps.with(Map.of("omniflux.security.allowed-relations", List.of(relation),
                        "omniflux.security.query-timeout", "400ms"));
                var descriptor = new RelationDescriptor("public", relation,
                        List.of(new ColumnDescriptor("id", LogicalType.INTEGER, false, true)),
                        new KeyContract("id"), Volatility.STATIC);
                var catalog = new SchemaCatalog(Map.of("r2dbc", name -> Mono.just(descriptor)), props);
                var dialect = spy(new PgDialect());
                if (stage.equals("count")) {
                    doReturn(new SqlPlan("SELECT 1::bigint AS id", List.of()))
                            .when(dialect).maxKeyQuery(anyString(), anyString(), anyString(), anyList());
                }
                var source = new R2dbcRowSource(client, catalog, dialect, props, null, null, null);
                var request = new ExportRequest(relation, List.of("id"), List.of(),
                        stage.equals("count") ? "XLSX" : "CSV", null, null);
                var scan = stage.equals("page") ? source.prepare(request).block(Duration.ofSeconds(5)) : null;
                control.setAutoCommit(false);
                statement.execute("LOCK TABLE " + relation + " IN ACCESS EXCLUSIVE MODE");
                long started = System.nanoTime();
                var error = assertThrows(ExportException.class, () -> {
                    if (scan == null) source.prepare(request).block(Duration.ofSeconds(5));
                    else scan.records().collectList().block(Duration.ofSeconds(5));
                });
                assertEquals(ErrorCode.QUERY_TIMEOUT, error.code());
                assertTrue(Duration.ofNanos(System.nanoTime() - started).compareTo(Duration.ofSeconds(3)) < 0);

                // Keep the lock held while checking: returning an error alone is
                // insufficient if the database statement remains blocked.
                long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
                int active;
                do {
                    statement.execute("SELECT pg_stat_clear_snapshot()");
                    try (var rows = statement.executeQuery("SELECT count(*) FROM pg_stat_activity "
                            + "WHERE pid <> pg_backend_pid() AND state = 'active' AND query LIKE '%" + relation + "%'") ) {
                        rows.next();
                        active = rows.getInt(1);
                    }
                    if (active == 0) break;
                    Thread.sleep(20);
                } while (System.nanoTime() < deadline);
                assertEquals(0, active, "the timed-out SELECT must stop in PostgreSQL while the lock remains held");
                String restored = client.sql("SHOW statement_timeout").map((row, meta) -> row.get(0, String.class))
                        .one().block(Duration.ofSeconds(5));
                assertEquals("0", restored, "transaction-local timeout must not leak into the pooled connection");
            } finally {
                control.rollback();
                control.setAutoCommit(true);
                statement.execute("DROP TABLE " + relation);
            }
        } finally {
            pool.dispose();
        }
    }
}
