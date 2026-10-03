package com.omniflux.exchange.web;

import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;

/**
 * Demo-only endpoint showing why collecting all rows is the counter-example.
 *
 * <p>Packaged in the artifact but registered only under the {@code demo}
 * profile — the same demo-feature exception as {@code SeedController}. The
 * deliberate materialization below is the point: it is the memory behavior
 * the streaming path exists to avoid. Do not "fix" it.
 */
@RestController
@Profile("demo")
@RequestMapping("/api/exports/naive")
public final class NaiveExportController {
    @PostMapping
    public Mono<NaiveResponse> run(@RequestBody NaiveRequest request) {
        int rows = Math.clamp(request.rows(), 0, 2_000_000);
        return Mono.fromSupplier(() -> {
            var materialized = new ArrayList<Object[]>(rows);
            for (int i = 0; i < rows; i++) materialized.add(new Object[]{(long) i + 1});
            return new NaiveResponse(materialized.size(), NaiveExportRunner.estimatedRetainedBytes(rows, 1_100L));
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /** Instantiated reflectively by Jackson for the {@code @RequestBody} binding. */
    @SuppressWarnings("unused")
    public record NaiveRequest(int rows) { }
    public record NaiveResponse(int rows, long estimatedRetainedBytes) { }
}
