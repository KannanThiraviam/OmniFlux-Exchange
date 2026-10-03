package com.omniflux.exchange.upload;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SingleSubscriptionPublisherTest {
    @Test
    void acceptsOneSubscriptionAndRejectsTheSecondDeterministically() {
        var subscriptions = new AtomicInteger();
        var publisher = SingleSubscriptionPublisher.of(
                Flux.defer(() -> {
                    subscriptions.incrementAndGet();
                    return Flux.just(ByteBuffer.wrap(new byte[]{1}), ByteBuffer.wrap(new byte[]{2}));
                }));

        StepVerifier.create(Flux.from(publisher))
                .expectNextCount(2).verifyComplete();
        StepVerifier.create(Flux.from(publisher))
                .expectErrorMessage("single-subscription upload body").verify();
        assertEquals(1, subscriptions.get());
    }

    @Test
    void cancelStopsTheActiveSubscription() {
        var cancelled = new AtomicInteger();
        var publisher = SingleSubscriptionPublisher.of(
                Flux.<ByteBuffer>never().doOnCancel(cancelled::incrementAndGet));
        var disposable = Flux.from(publisher).subscribe();
        publisher.cancel();
        disposable.dispose();
        assertTrue(cancelled.get() >= 1);
    }
}
