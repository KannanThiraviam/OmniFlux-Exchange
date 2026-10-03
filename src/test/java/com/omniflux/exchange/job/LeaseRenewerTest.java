package com.omniflux.exchange.job;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LeaseRenewerTest {
    @Test
    void aFailedFenceCompletesTheLossSignal() {
        AtomicInteger renewals = new AtomicInteger();
        try (LeaseRenewer renewer = new LeaseRenewer(
                UUID.randomUUID(), UUID.randomUUID(),
                new LeaseRenewer.LeaseTiming(Duration.ofMillis(5), Duration.ofMillis(5), Duration.ofMillis(40)),
                Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), null,
                (id, token, duration) -> Mono.fromSupplier(() -> {
                    renewals.incrementAndGet();
                    return false;
                }))) {
            renewer.lost().block(Duration.ofSeconds(2));
        }
        assertTrue(renewals.get() >= 1);
    }

    @Test
    void oneTransientRenewalErrorIsRetriedBeforeTheDeadline() {
        AtomicInteger renewals = new AtomicInteger();
        try (LeaseRenewer renewer = new LeaseRenewer(
                UUID.randomUUID(), UUID.randomUUID(),
                new LeaseRenewer.LeaseTiming(Duration.ofMillis(5), Duration.ofMillis(5), Duration.ofMillis(100)),
                Clock.systemUTC(), null,
                (id, token, duration) -> renewals.incrementAndGet() == 1
                        ? Mono.error(new RuntimeException("temporary database failure"))
                        : Mono.just(false))) {
            renewer.lost().block(Duration.ofSeconds(2));
        }
        assertTrue(renewals.get() >= 2);
    }

    @Test
    void renewalsExtendByTheLeaseDurationRatherThanThePollingInterval() {
        var requested = new java.util.concurrent.atomic.AtomicReference<Duration>();
        try (LeaseRenewer renewer = new LeaseRenewer(
                UUID.randomUUID(), UUID.randomUUID(),
                new LeaseRenewer.LeaseTiming(Duration.ofSeconds(60), Duration.ofMillis(5), Duration.ofMillis(100)),
                Clock.systemUTC(), null,
                (id, token, duration) -> {
                    requested.set(duration);
                    return Mono.just(false);
                })) {
            renewer.lost().block(Duration.ofSeconds(2));
        }
        assertEquals(Duration.ofSeconds(60), requested.get());
    }
}
