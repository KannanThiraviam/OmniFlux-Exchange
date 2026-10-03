package com.omniflux.exchange.web;

import com.omniflux.exchange.security.CurrentUserProvider;
import com.omniflux.exchange.seed.SeedService;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/** Demo-only data mutation endpoint. It is absent from production contexts. */
@RestController
@Profile("demo")
@RequestMapping("/api/data")
public final class SeedController {
    private final SeedService seed;
    private final CurrentUserProvider users;

    public SeedController(SeedService seed, CurrentUserProvider users) {
        this.seed = seed;
        this.users = users;
    }

    @PostMapping("/seed")
    public Mono<ResponseEntity<SeedResponse>> seed(@RequestBody SeedRequest request,
                                                    ServerWebExchange exchange) {
        return users.currentAuth(exchange.getRequest())
                .flatMap(ignored -> seed.seed(request.relation(), request.rows()))
                .map(count -> ResponseEntity.accepted()
                        .body(new SeedResponse(request.relation(), count)));
    }

    @SuppressWarnings("unused") // canonical ctor invoked reflectively by Jackson for @RequestBody
    public record SeedRequest(String relation, long rows) { }

    public record SeedResponse(String relation, long rows) { }
}
