package com.omniflux.exchange.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

@Configuration
public class SchedulerConfig {
    @Bean(destroyMethod = "shutdown")
    public ExecutorService exportExecutor(OmnifluxProperties props) {
        var scheduler = props.export().scheduler();
        ThreadFactory threads = runnable -> {
            Thread thread = new Thread(runnable, "omniflux-export-" + THREADS.incrementAndGet());
            thread.setDaemon(false);
            return thread;
        };
        return new ThreadPoolExecutor(
                scheduler.threads(), scheduler.threads(), 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(scheduler.queueCapacity()),
                threads, new ThreadPoolExecutor.AbortPolicy());
    }

    private static final AtomicInteger THREADS = new AtomicInteger();
}
