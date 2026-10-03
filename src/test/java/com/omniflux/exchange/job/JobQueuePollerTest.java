package com.omniflux.exchange.job;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Lifecycle of the DB-queue polling seam: the scheduled task must stay
 *  cancelable, a double start must not double-schedule, and pollOnce must
 *  become a noop once the poller has stopped. */
class JobQueuePollerTest {
    private static final Duration INTERVAL = Duration.ofMillis(250);

    @Test
    void startSchedulesPollingAtTheConfiguredInterval() {
        var scheduler = mock(ScheduledExecutorService.class);
        when(scheduler.scheduleWithFixedDelay(any(Runnable.class), anyLong(), anyLong(), any()))
                .thenReturn(mock(ScheduledFuture.class));

        poller(scheduler).start();

        verify(scheduler).scheduleWithFixedDelay(any(Runnable.class), eq(0L),
                eq(INTERVAL.toMillis()), eq(TimeUnit.MILLISECONDS));
    }

    @Test
    void doubleStartSchedulesOnlyOnce() {
        var scheduler = mock(ScheduledExecutorService.class);
        when(scheduler.scheduleWithFixedDelay(any(Runnable.class), anyLong(), anyLong(), any()))
                .thenReturn(mock(ScheduledFuture.class));
        var poller = poller(scheduler);

        poller.start();
        poller.start();

        verify(scheduler, times(1)).scheduleWithFixedDelay(any(Runnable.class),
                anyLong(), anyLong(), any());
    }

    @Test
    void pollOnceClaimsAJobAndHandsItToTheWorker() {
        var repository = mock(JobRepository.class);
        var worker = mock(JobWorker.class);
        var job = mock(TransferJob.class);
        when(repository.claim("w-1")).thenReturn(Mono.just(job));
        when(worker.run(job)).thenReturn(Mono.empty());

        new JobQueuePoller(repository, worker, "w-1", INTERVAL, mock(ScheduledExecutorService.class))
                .pollOnce().block();

        verify(worker).run(job);
    }

    @Test
    void claimFailuresArePropagatedForThePollerToLog() {
        var repository = mock(JobRepository.class);
        when(repository.claim("w-1")).thenReturn(Mono.error(new IllegalStateException("gate mismatch")));
        var poller = new JobQueuePoller(repository, mock(JobWorker.class), "w-1", INTERVAL,
                mock(ScheduledExecutorService.class));

        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> poller.pollOnce().block());
    }

    @Test
    void closeCancelsTheTaskAndLeavesTheInjectedSchedulerRunning() {
        var scheduler = mock(ScheduledExecutorService.class);
        var future = mock(ScheduledFuture.class);
        when(scheduler.scheduleWithFixedDelay(any(Runnable.class), anyLong(), anyLong(), any()))
                .thenReturn(future);
        var poller = poller(scheduler);
        poller.start();

        poller.close();

        verify(future).cancel(false);
        verify(scheduler, never()).shutdown();          // the poller does not own this scheduler
    }

    private static JobQueuePoller poller(ScheduledExecutorService scheduler) {
        return new JobQueuePoller(mock(JobRepository.class), mock(JobWorker.class),
                "w-1", INTERVAL, scheduler);
    }
}
