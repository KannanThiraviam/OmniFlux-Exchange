package com.omniflux.exchange.config;

import com.omniflux.exchange.TestProps;
import com.omniflux.exchange.job.JobRepository;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.model.ListMultipartUploadsRequest;
import software.amazon.awssdk.services.s3.model.ListMultipartUploadsResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;

import java.time.Clock;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/** Startup reconciliation schedules its periodic pass on the injected
 *  scheduler, and close() must cancel that pass and stop the scheduler. */
class StartupReconcilerTest {

    @Test
    void startupSchedulesPeriodicReconciliation() {
        var scheduler = mock(ScheduledExecutorService.class);
        when(scheduler.scheduleWithFixedDelay(any(Runnable.class), anyLong(), anyLong(), any()))
                .thenReturn(mock(ScheduledFuture.class));

        reconciler(scheduler).reconcileOnStartup();

        verify(scheduler).scheduleWithFixedDelay(any(Runnable.class), anyLong(), anyLong(), any());
    }

    @Test
    void closeCancelsTheTaskAndShutsDownTheScheduler() {
        var scheduler = mock(ScheduledExecutorService.class);
        var future = mock(ScheduledFuture.class);
        when(scheduler.scheduleWithFixedDelay(any(Runnable.class), anyLong(), anyLong(), any()))
                .thenReturn(future);
        var reconciler = reconciler(scheduler);

        reconciler.reconcileOnStartup();
        reconciler.close();

        verify(future).cancel(false);
        verify(scheduler).shutdownNow();
    }

    private static StartupReconciler reconciler(ScheduledExecutorService scheduler) {
        var storage = mock(S3AsyncClient.class);
        when(storage.listMultipartUploads(any(ListMultipartUploadsRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(
                        ListMultipartUploadsResponse.builder().build()));
        when(storage.listObjectsV2(any(ListObjectsV2Request.class)))
                .thenReturn(CompletableFuture.completedFuture(
                        ListObjectsV2Response.builder().build()));
        var jobs = mock(JobRepository.class);
        when(jobs.sweepExpired()).thenReturn(Mono.just(0L));
        return new StartupReconciler(storage, jobs, TestProps.defaults(), Clock.systemUTC(),
                scheduler);
    }
}
