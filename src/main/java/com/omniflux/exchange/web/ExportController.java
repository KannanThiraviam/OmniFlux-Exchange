package com.omniflux.exchange.web;

import com.omniflux.exchange.export.ExportService;
import com.omniflux.exchange.security.CurrentUserProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/api/exports")
public final class ExportController {
    /** Custom header; IntelliJ flags unknown literals, so it is a constant. */
    public static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";
    private final ExportService exports;
    private final CurrentUserProvider users;

    public ExportController(ExportService exports, CurrentUserProvider users) {
        this.exports = exports;
        this.users = users;
    }

    @PostMapping
    // Idempotency-Key is the de-facto draft standard header; Spring's HttpHeaders
    // ships no constant for it.
    @SuppressWarnings("UnknownHttpHeader")
    public Mono<ResponseEntity<?>> submit(@RequestBody ExportService.SubmitCommand command,
                                           ServerWebExchange exchange) {
        String clientIp = clientIp(exchange);
        String idempotencyKey = exchange.getRequest().getHeaders().getFirst(IDEMPOTENCY_KEY_HEADER);
        ExportService.SubmitCommand effectiveCommand =
                exports.withIdempotencyKey(command, idempotencyKey);
        return users.currentAuth(exchange.getRequest())
                .flatMap(auth -> exports.submit(auth, effectiveCommand, clientIp)
                        .flatMap(job -> {
                            if (!job.cacheHit()) {
                                return Mono.just(ResponseEntity.accepted()
                                        .location(URI.create("/api/jobs/" + job.id()))
                                        .body((Object) job));
                            }
                            return exports.download(job.id(), auth, clientIp)
                                    .map(download -> ResponseEntity.ok((Object) new ExportResponse(
                                            job.id(), job.status(), job.relation(), job.format(),
                                            job.rowCount(), job.byteCount(), true,
                                            download.url().toString(), download.expiresAt())));
                        }));
    }

    private static String clientIp(ServerWebExchange exchange) {
        var address = exchange.getRequest().getRemoteAddress();
        return address == null || address.getAddress() == null
                ? null : address.getAddress().getHostAddress();
    }

    public record ExportResponse(UUID id, String status, String relation, String format,
                                 long rowCount, long byteCount, boolean cacheHit,
                                 String downloadUrl, java.time.Instant expiresAt) { }
}
