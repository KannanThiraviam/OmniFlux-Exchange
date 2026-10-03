package com.omniflux.exchange.export;

import com.omniflux.exchange.TestProps;
import com.omniflux.exchange.config.OmnifluxProperties;
import com.omniflux.exchange.job.JobRepository;
import com.omniflux.exchange.job.JobStatus;
import com.omniflux.exchange.job.TransferJob;
import com.omniflux.exchange.presign.PresignService;
import com.omniflux.exchange.security.AuthContext;
import com.omniflux.exchange.security.PrincipalKey;
import com.omniflux.exchange.source.FilterSpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ExportServiceTest {
    private static final PrincipalKey OWNER = new PrincipalKey("issuer", "alice", "tenant");
    private static final AuthContext AUTH = new AuthContext(OWNER, List.of("ANALYST"), "v1");
    private static final Instant CREATED = Instant.parse("2026-01-01T00:00:00Z");

    private JobRepository jobs;
    private PresignService presigns;
    private OmnifluxProperties properties;
    private ExportService service;

    @BeforeEach
    void setUp() {
        jobs = Mockito.mock(JobRepository.class);
        presigns = Mockito.mock(PresignService.class);
        properties = TestProps.with("omniflux.security.allowed-relations", List.of("mock_orders"));
        service = new ExportService(jobs, presigns, properties, new ObjectMapper());
    }

    @Test
    void submitNormalizesDefaultsAndPreservesTheAuthorizationSnapshot() {
        var stored = job(JobStatus.QUEUED, UUID.randomUUID());
        when(jobs.submit(any(TransferJob.class))).thenReturn(Mono.just(stored));
        var command = new ExportService.SubmitCommand(" mock_orders ", List.of("id"),
                List.of(FilterSpec.eq("country", "IN")), null, null, null, " request-1 ");

        var view = Objects.requireNonNull(service.submit(AUTH, command, "127.0.0.1").block());

        assertEquals(stored.id(), view.id());
        var captured = ArgumentCaptor.forClass(TransferJob.class);
        verify(jobs).submit(captured.capture());
        var candidate = captured.getValue();
        assertEquals("mock_orders", candidate.relationName());
        assertEquals("CSV", candidate.format());
        assertEquals("SPREADSHEET_SAFE", candidate.csvMode());
        assertEquals("request-1", candidate.idempotencyKey());
        assertEquals(OWNER, candidate.owner());
        assertEquals(List.of("ANALYST"), candidate.roles());
        assertEquals("v1", candidate.authzContextVersion());
        assertEquals(Fingerprint.of(AUTH,
                        new com.omniflux.exchange.source.ExportRequest("mock_orders", List.of("id"),
                                List.of(FilterSpec.eq("country", "IN"))), properties),
                candidate.requestFingerprint());
    }

    @Test
    void submitRejectsMissingAuthInvalidRelationsAndMalformedCommands() {
        var command = new ExportService.SubmitCommand("mock_orders", List.of(), List.of(),
                "CSV", "RAW", 1, null);
        StepVerifier.create(service.submit(null, command, null))
                .expectErrorMessage("PRINCIPAL_UNRESOLVED: an authenticated principal is required")
                .verify();
        StepVerifier.create(service.submit(AUTH,
                        new ExportService.SubmitCommand("not_allowed", List.of(), List.of(),
                                "CSV", "RAW", 1, null), null))
                .expectErrorMatches(error -> error.getMessage().contains("RELATION_NOT_ALLOWED"))
                .verify();
        assertThrows(IllegalArgumentException.class, () ->
                new ExportService.SubmitCommand("mock_orders", List.of(), List.of(),
                        "BAD", "RAW", 1, null).normalized(properties));
        StepVerifier.create(service.submit(AUTH, null, null))
                .expectErrorMatches(error -> error instanceof com.omniflux.exchange.job.ExportException ex
                        && ex.code() == com.omniflux.exchange.job.ErrorCode.VALIDATION_ERROR
                        && ex.code().httpStatus() == 400)
                .verify();
        StepVerifier.create(service.submit(AUTH,
                        new ExportService.SubmitCommand("mock_orders", List.of(), List.of(),
                                "BAD", "RAW", 1, null), null))
                .expectErrorMatches(error -> error instanceof com.omniflux.exchange.job.ExportException ex
                        && ex.code() == com.omniflux.exchange.job.ErrorCode.VALIDATION_ERROR)
                .verify();
    }

    @Test
    void unreadablePersistedTimingIsAnInternalErrorNotAnUpstreamFailure() {
        UUID id = UUID.randomUUID();
        when(jobs.findOwned(id, OWNER)).thenReturn(Mono.just(job(JobStatus.COMPLETED, id)
                .toBuilder().timingJson("{invalid").build()));

        StepVerifier.create(service.timing(id, AUTH))
                .expectErrorMatches(error -> error instanceof com.omniflux.exchange.job.ExportException ex
                        && ex.code() == com.omniflux.exchange.job.ErrorCode.INTERNAL_ERROR)
                .verify();
    }

    @Test
    void listUsesBoundedKeysetPagesAndEncodesTheNextCursor() {
        var first = job(JobStatus.COMPLETED, UUID.randomUUID());
        var second = job(JobStatus.FAILED, UUID.randomUUID()).toBuilder()
                .createdAt(CREATED.minusSeconds(1)).build();
        when(jobs.listOwned(eq(OWNER), any(), any(), eq(3)))
                .thenReturn(Flux.just(first, second));

        var page = Objects.requireNonNull(service.list(AUTH, 2, null).block());

        assertEquals(2, page.items().size());
        assertNull(page.nextCursor());
        verify(jobs).listOwned(eq(OWNER), eq(null), eq(null), eq(3));
    }

    @Test
    void listRejectsMalformedCursorsAndClampsLimits() {
        when(jobs.listOwned(eq(OWNER), isNull(), isNull(), eq(2))).thenReturn(Flux.empty());
        assertThrows(IllegalArgumentException.class, () -> service.list(AUTH, 0, "bad cursor").block());
        assertNotNull(service.list(AUTH, 0, null).block());
    }

    @Test
    void detailDownloadRetryAndCancelStayOwnerScoped() {
        UUID id = UUID.randomUUID();
        var completed = job(JobStatus.COMPLETED, id).toBuilder()
                .objectKey("exports/one.csv").build();
        when(jobs.findOwned(id, OWNER)).thenReturn(Mono.just(completed));
        var download = new PresignService.PresignedDownload(
                URI.create("http://storage/signed"), CREATED, CREATED.plusSeconds(60),
                "text/csv; charset=utf-8", "mock_orders.csv");
        when(presigns.presign(id, AUTH, "127.0.0.1")).thenReturn(Mono.just(download));
        when(jobs.retryOwned(id, OWNER)).thenReturn(Mono.just(
                job(JobStatus.QUEUED, id)));
        when(jobs.cancel(id, OWNER)).thenReturn(Mono.just(true));

        assertEquals(id, Objects.requireNonNull(service.detail(id, AUTH).block()).id());
        assertEquals(download, Objects.requireNonNull(service.download(id, AUTH, "127.0.0.1").block()));
        assertEquals("QUEUED", Objects.requireNonNull(service.retry(id, AUTH).block()).status());
        assertEquals("COMPLETED", Objects.requireNonNull(service.cancel(id, AUTH).block()).status());
        verify(jobs, times(3)).findOwned(id, OWNER);
        verify(presigns).presign(id, AUTH, "127.0.0.1");
        verify(jobs).retryOwned(id, OWNER);
        verify(jobs).cancel(id, OWNER);
    }

    @Test
    void downloadRequiresACompletedObjectAndIdempotencyHeaderWins() {
        UUID id = UUID.randomUUID();
        when(jobs.findOwned(id, OWNER)).thenReturn(Mono.just(job(JobStatus.FAILED, id)));
        StepVerifier.create(service.download(id, AUTH, null))
                .expectErrorMatches(error -> error.getMessage().contains("JOB_NOT_FOUND"))
                .verify();
        var body = new ExportService.SubmitCommand("mock_orders", List.of(), List.of(),
                "CSV", "RAW", 1, "body");
        assertEquals("header", service.withIdempotencyKey(body, "header").idempotencyKey());
        assertEquals("body", service.withIdempotencyKey(body, " ").idempotencyKey());
    }

    private static TransferJob job(JobStatus status, UUID id) {
        return TransferJob.builder().id(id).status(status).owner(OWNER)
                .relationName("mock_orders").format("CSV").createdAt(CREATED).build();
    }
}
