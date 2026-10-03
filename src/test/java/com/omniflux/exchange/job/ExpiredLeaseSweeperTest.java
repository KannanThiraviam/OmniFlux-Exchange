package com.omniflux.exchange.job;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** The sweeper's scheduled lifecycle: interval validation, scheduling,
 *  cancelation, and plain delegation of sweep() to the repository. */
class ExpiredLeaseSweeperTest {

    @Test
    void startSchedulesSweepingAtTheGivenInterval() {
        var scheduler = mock(ScheduledExecutorService.class);
        when(scheduler.scheduleWithFixedDelay(any(Runnable.class), anyLong(), anyLong(), any()))
                .thenReturn(mock(ScheduledFuture.class));
        var sweeper = new ExpiredLeaseSweeper(mock(JobRepository.class), scheduler);

        sweeper.start(Duration.ofSeconds(5));

        verify(scheduler).scheduleWithFixedDelay(any(Runnable.class), eq(0L),
                eq(5000L), eq(TimeUnit.MILLISECONDS));
    }

    @Test
    void startRejectsNonPositiveInterval() {
        var sweeper = new ExpiredLeaseSweeper(mock(JobRepository.class),
                mock(ScheduledExecutorService.class));

        assertThrows(IllegalArgumentException.class, () -> sweeper.start(Duration.ZERO));
    }

    @Test
    void stopCancelsTheScheduledTask() {
        var scheduler = mock(ScheduledExecutorService.class);
        var future = mock(ScheduledFuture.class);
        when(scheduler.scheduleWithFixedDelay(any(Runnable.class), anyLong(), anyLong(), any()))
                .thenReturn(future);
        var sweeper = new ExpiredLeaseSweeper(mock(JobRepository.class), scheduler);

        sweeper.start(Duration.ofSeconds(5));
        sweeper.stop();

        verify(future).cancel(false);
    }

    @Test
    void sweepDelegatesToTheRepository() {
        var repository = mock(JobRepository.class);
        when(repository.sweepExpired()).thenReturn(Mono.just(3L));

        assertEquals(3L, new ExpiredLeaseSweeper(repository, mock(ScheduledExecutorService.class))
                .sweep().block());
    }
}
