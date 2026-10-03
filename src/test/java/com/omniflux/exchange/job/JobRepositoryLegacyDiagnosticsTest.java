package com.omniflux.exchange.job;

import com.omniflux.exchange.TestProps;
import com.omniflux.exchange.security.PrincipalKey;
import io.r2dbc.spi.Row;
import io.r2dbc.spi.RowMetadata;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.r2dbc.core.RowsFetchSpec;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;

import java.time.Instant;
import java.util.UUID;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/** Regression coverage for rows written by releases with different diagnostics. */
class JobRepositoryLegacyDiagnosticsTest {
    private static final PrincipalKey OWNER = new PrincipalKey("issuer", "alice", "tenant");
    private static final Instant CREATED = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void jobsListKeepsServingAJobWithUnknownDiagnosticEnums() {
        UUID id = UUID.randomUUID();
        DatabaseClient database = Mockito.mock(DatabaseClient.class);
        DatabaseClient.GenericExecuteSpec query = Mockito.mock(DatabaseClient.GenericExecuteSpec.class);
        @SuppressWarnings("unchecked")
        RowsFetchSpec<TransferJob> rows = Mockito.mock(RowsFetchSpec.class);
        Row row = Mockito.mock(Row.class);
        RowMetadata metadata = Mockito.mock(RowMetadata.class);

        when(database.sql(any(String.class))).thenReturn(query);
        when(query.bind(any(String.class), any())).thenReturn(query);
        when(query.map(Mockito.<BiFunction<Row, RowMetadata, TransferJob>>any())).thenAnswer(invocation -> {
            BiFunction<Row, RowMetadata, TransferJob> mapper = invocation.getArgument(0);
            TransferJob mapped = mapper.apply(row, metadata);
            when(rows.all()).thenReturn(Flux.just(mapped));
            return rows;
        });
        when(row.get("id", UUID.class)).thenReturn(id);
        when(row.get("direction", String.class)).thenReturn("EXPORT");
        when(row.get("status", String.class)).thenReturn("FAILED");
        when(row.get("issuer", String.class)).thenReturn(OWNER.issuer());
        when(row.get("requested_by", String.class)).thenReturn(OWNER.subject());
        when(row.get("tenant", String.class)).thenReturn(OWNER.tenant());
        when(row.get("relation_name", String.class)).thenReturn("mock_orders");
        when(row.get("format", String.class)).thenReturn("CSV");
        when(row.get("error_class", String.class)).thenReturn("CLIENT");
        when(row.get("error_code", String.class)).thenReturn("CANCELLED");
        when(row.get("error_message", String.class)).thenReturn("cancelled by the old worker");
        when(row.get("created_at")).thenReturn(CREATED);

        JobRepository repository = new JobRepository(database,
                Mockito.mock(TransactionalOperator.class), TestProps.defaults());

        var jobs = java.util.Objects.requireNonNull(
                repository.listOwned(OWNER, null, null, 50).collectList().block());

        assertEquals(1, jobs.size());
        assertEquals(id, jobs.getFirst().id());
        assertNull(jobs.getFirst().errorClass());
        assertEquals(ErrorCode.CANCELLED, jobs.getFirst().errorCode());
        assertEquals("cancelled by the old worker", jobs.getFirst().errorMessage());
    }

    @Test
    void jobsListStillMapsCurrentDiagnosticEnums() {
        UUID id = UUID.randomUUID();
        DatabaseClient database = Mockito.mock(DatabaseClient.class);
        DatabaseClient.GenericExecuteSpec query = Mockito.mock(DatabaseClient.GenericExecuteSpec.class);
        @SuppressWarnings("unchecked")
        RowsFetchSpec<TransferJob> rows = Mockito.mock(RowsFetchSpec.class);
        Row row = Mockito.mock(Row.class);
        RowMetadata metadata = Mockito.mock(RowMetadata.class);

        when(database.sql(any(String.class))).thenReturn(query);
        when(query.bind(any(String.class), any())).thenReturn(query);
        when(query.map(Mockito.<BiFunction<Row, RowMetadata, TransferJob>>any())).thenAnswer(invocation -> {
            BiFunction<Row, RowMetadata, TransferJob> mapper = invocation.getArgument(0);
            TransferJob mapped = mapper.apply(row, metadata);
            when(rows.all()).thenReturn(Flux.just(mapped));
            return rows;
        });
        when(row.get("id", UUID.class)).thenReturn(id);
        when(row.get("direction", String.class)).thenReturn("EXPORT");
        when(row.get("status", String.class)).thenReturn("FAILED");
        when(row.get("issuer", String.class)).thenReturn(OWNER.issuer());
        when(row.get("requested_by", String.class)).thenReturn(OWNER.subject());
        when(row.get("tenant", String.class)).thenReturn(OWNER.tenant());
        when(row.get("relation_name", String.class)).thenReturn("mock_orders");
        when(row.get("format", String.class)).thenReturn("CSV");
        when(row.get("error_class", String.class)).thenReturn(" transient ");
        when(row.get("error_code", String.class)).thenReturn(" upstream_unavailable ");
        when(row.get("created_at")).thenReturn(CREATED);

        JobRepository repository = new JobRepository(database,
                Mockito.mock(TransactionalOperator.class), TestProps.defaults());

        TransferJob job = java.util.Objects.requireNonNull(
                repository.listOwned(OWNER, null, null, 50).blockFirst());

        assertEquals(ErrorClass.TRANSIENT, job.errorClass());
        assertEquals(ErrorCode.UPSTREAM_UNAVAILABLE, job.errorCode());
    }
}
