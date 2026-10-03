package com.omniflux.exchange.web;

import com.omniflux.exchange.TestProps;
import com.omniflux.exchange.export.ExportService;
import com.omniflux.exchange.job.JobStatus;
import com.omniflux.exchange.job.TransferJob;
import com.omniflux.exchange.presign.PresignService;
import com.omniflux.exchange.security.AuthContext;
import com.omniflux.exchange.security.CurrentUserProvider;
import com.omniflux.exchange.security.PrincipalKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class JobControllerContractTest {
    private static final PrincipalKey OWNER = new PrincipalKey("issuer", "alice", "tenant");
    private static final AuthContext AUTH = new AuthContext(OWNER, List.of("ANALYST"), "v1");
    private static final Instant CREATED = Instant.parse("2026-01-01T00:00:00Z");

    private com.omniflux.exchange.job.JobRepository jobs;
    private PresignService presigns;
    private JobController controller;

    @BeforeEach
    void setUp() {
        jobs = mock(com.omniflux.exchange.job.JobRepository.class);
        presigns = mock(PresignService.class);
        CurrentUserProvider users = mock(CurrentUserProvider.class);
        var service = new ExportService(jobs, presigns, TestProps.defaults(), new ObjectMapper());
        controller = new JobController(service, users);
        when(users.currentAuth(any(org.springframework.http.HttpHeaders.class)))
                .thenReturn(Mono.just(AUTH));
        when(users.currentAuth(any(org.springframework.http.server.reactive.ServerHttpRequest.class)))
                .thenReturn(Mono.just(AUTH));
    }

    @Test
    void detailExposesThePersistedErrorMessageToTheJobsView() {
        UUID id = UUID.randomUUID();
        TransferJob failed = job(JobStatus.FAILED, id).toBuilder()
                .errorMessage("upstream timed out after 30 seconds")
                .build();
        when(jobs.findOwned(id, OWNER)).thenReturn(Mono.just(failed));

        var view = Objects.requireNonNull(controller.detail(id, exchange("/api/jobs/" + id)).block());

        assertEquals("FAILED", view.status());
        assertEquals("upstream timed out after 30 seconds", view.errorMessage());
        assertEquals(0, view.byteCount());
    }

    @Test
    void downloadReturnsThePresignedUrlAndRetryReturnsTheRequeuedView() {
        UUID id = UUID.randomUUID();
        var completed = job(JobStatus.COMPLETED, id).toBuilder()
                .objectKey("exports/one.csv").rowCount(25).byteCount(1024).build();
        var download = new PresignService.PresignedDownload(
                URI.create("http://localhost:9005/omniflux/exports/one.csv?signature=test"),
                CREATED, CREATED.plusSeconds(60), "text/csv; charset=utf-8", "mock_orders.csv");
        when(jobs.findOwned(id, OWNER)).thenReturn(Mono.just(completed));
        when(presigns.presign(eq(id), eq(AUTH), eq("127.0.0.1")))
                .thenReturn(Mono.just(download));
        TransferJob queued = job(JobStatus.QUEUED, id);
        when(jobs.retryOwned(id, OWNER)).thenReturn(Mono.just(queued));

        var exchange = exchange("/api/jobs/" + id + "/download");
        var response = Objects.requireNonNull(controller.download(id, exchange).block());
        var retried = Objects.requireNonNull(
                controller.retry(id, exchange("/api/jobs/" + id + "/retry")).block());

        assertEquals(download.url().toString(), response.url());
        assertEquals(download.expiresAt(), response.expiresAt());
        assertEquals("text/csv; charset=utf-8", response.contentType());
        assertEquals("mock_orders.csv", response.filename());
        assertEquals("QUEUED", retried.status());
        verify(presigns).presign(id, AUTH, "127.0.0.1");
        verify(jobs).retryOwned(id, OWNER);
    }

    private static MockServerWebExchange exchange(String path) {
        return MockServerWebExchange.from(MockServerHttpRequest.get(path)
                .remoteAddress(new InetSocketAddress("127.0.0.1", 54321)).build());
    }

    private static TransferJob job(JobStatus status, UUID id) {
        return TransferJob.builder().id(id).status(status).owner(OWNER)
                .relationName("mock_orders").format("CSV").createdAt(CREATED).build();
    }
}
