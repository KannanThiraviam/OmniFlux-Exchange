package com.omniflux.exchange.upload;

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Operators;

import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A publisher that protects an attempt-scoped upload body from SDK resubscription.
 * A second subscription is a protocol error and fails deterministically.
 */
public final class SingleSubscriptionPublisher implements Publisher<ByteBuffer> {
    private final Publisher<ByteBuffer> source;
    private final AtomicBoolean subscribed = new AtomicBoolean();
    private final AtomicReference<Subscription> activeSubscription = new AtomicReference<>();

    private SingleSubscriptionPublisher(Publisher<ByteBuffer> source) {
        this.source = Objects.requireNonNull(source, "source");
    }

    public static SingleSubscriptionPublisher of(Publisher<ByteBuffer> source) {
        return new SingleSubscriptionPublisher(source);
    }

    @Override
    public void subscribe(Subscriber<? super ByteBuffer> subscriber) {
        Objects.requireNonNull(subscriber, "subscriber");
        if (!subscribed.compareAndSet(false, true)) {
            subscriber.onSubscribe(Operators.emptySubscription());
            subscriber.onError(new IllegalStateException("single-subscription upload body"));
            return;
        }
        Flux.from(source)
                .doOnSubscribe(activeSubscription::set)
                .subscribe(subscriber);
    }

    public void cancel() {
        Subscription subscription = activeSubscription.get();
        if (subscription != null) {
            subscription.cancel();
        }
    }
}
