package com.omniflux.exchange.web;

import com.omniflux.exchange.export.ExportService;
import com.omniflux.exchange.security.CurrentUserProvider;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.UUID;

@RestController
@RequestMapping("/api/jobs")
public final class JobController {
    private final ExportService exports;
    private final CurrentUserProvider users;

    public JobController(ExportService exports, CurrentUserProvider users) {
        this.exports = exports;
        this.users = users;
    }

    @GetMapping
    public Mono<ExportService.JobPage> list(
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(required = false) String after,
            ServerWebExchange exchange) {
        return users.currentAuth(exchange.getRequest())
                .flatMap(auth -> exports.list(auth, limit, after));
    }

    @GetMapping("/{id}")
    public Mono<ExportService.JobView> detail(@PathVariable UUID id,
                                              ServerWebExchange exchange) {
        return users.currentAuth(exchange.getRequest())
                .flatMap(auth -> exports.detail(id, auth));
    }

    @GetMapping("/{id}/timing")
    public Mono<com.omniflux.exchange.export.ExportTiming.Snapshot> timing(
            @PathVariable UUID id, ServerWebExchange exchange) {
        return users.currentAuth(exchange.getRequest())
                .flatMap(auth -> exports.timing(id, auth));
    }

    @GetMapping("/{id}/attempts")
    public Mono<java.util.List<com.omniflux.exchange.job.JobAttempt>> attempts(
            @PathVariable UUID id, ServerWebExchange exchange) {
        return users.currentAuth(exchange.getRequest())
                .flatMap(auth -> exports.attempts(id, auth));
    }

    @GetMapping({"/{id}/download", "/{id}/download-url"})
    public Mono<DownloadResponse> download(@PathVariable UUID id,
                                           ServerWebExchange exchange) {
        return users.currentAuth(exchange.getRequest())
                .flatMap(auth -> exports.download(id, auth, clientIp(exchange)))
                .map(url -> new DownloadResponse(url.url().toString(), url.expiresAt(),
                        url.contentType(), url.filename()));
    }

    @PostMapping("/{id}/retry")
    public Mono<ExportService.JobView> retry(@PathVariable UUID id,
                                             ServerWebExchange exchange) {
        return users.currentAuth(exchange.getRequest())
                .flatMap(auth -> exports.retry(id, auth));
    }

    @PostMapping("/{id}/cancel")
    public Mono<ExportService.JobView> cancel(@PathVariable UUID id,
                                              ServerWebExchange exchange) {
        return users.currentAuth(exchange.getRequest())
                .flatMap(auth -> exports.cancel(id, auth));
    }

    private static String clientIp(ServerWebExchange exchange) {
        var address = exchange.getRequest().getRemoteAddress();
        return address == null || address.getAddress() == null
                ? null : address.getAddress().getHostAddress();
    }

    public record DownloadResponse(String url, java.time.Instant expiresAt,
                                   String contentType, String filename) { }
}
