package com.omniflux.exchange.contract;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.buffer.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Vendor-behavior tripwires for the export bridge (evidence:
 * docs/evidence/spike-results.md Q3, Q4, Q4b — the takeUntilOther coupling is
 * mandatory, not an implementation detail). Pure JVM: no infrastructure.
 */
class ReactorCancellationContractTest {

    private static final DataBufferFactory FACTORY = new DefaultDataBufferFactory();
    private static final int CHUNK = 8 * 1024;

    private ExecutorService exec;

    @BeforeEach void setUp() {
        exec = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "contract-export-scheduler-1");
            t.setDaemon(true);
            return t;
        });
    }

    @AfterEach void tearDown() { exec.shutdownNow(); }

    @Test
    void cancellingWhileTheConsumerIsParkedAwaitingARowUnblocksIt() throws Exception {
        var parkedAwaitingRow = new CountDownLatch(1);
        var consumerExited = new CompletableFuture<String>();
        var sourceCancelledOrCompleted = new AtomicBoolean();

        Sinks.Empty<Void> cancelSignal = Sinks.empty();

        Flux<byte[]> stalledSource = Flux.concat(
                        Flux.just("first-row\n".getBytes()),
                        Flux.never())
                .doFinally(sig -> sourceCancelledOrCompleted.set(true))
                .takeUntilOther(cancelSignal.asMono());

        Flux<DataBuffer> bytes = Flux.from(DataBufferUtils.outputStreamPublisher(
                        out -> {
                            try (Stream<byte[]> s = stalledSource.toStream(1)) {
                                var it = s.iterator();
                                out.write(it.next());
                                out.flush();
                                parkedAwaitingRow.countDown();
                                while (it.hasNext()) out.write(it.next());
                                consumerExited.complete("completed-normally");
                            } catch (Throwable t) {
                                consumerExited.complete(t.getClass().getSimpleName() + ": " + t.getMessage());
                                throw new RuntimeException(t);
                            }
                        },
                        FACTORY, exec, CHUNK))
                .doOnCancel(() -> cancelSignal.tryEmitEmpty());

        var sub = bytes.subscribe(DataBufferUtils::release, err -> {}, () -> {},
                s -> s.request(Long.MAX_VALUE));

        // Readiness BEFORE cancelling: the consumer must be observed parked
        // awaiting the SECOND row, or we cancel before the risky state exists.
        assertTrue(parkedAwaitingRow.await(5, TimeUnit.SECONDS),
                "consumer never reached the parked-awaiting-row state");

        sub.dispose();

        Awaitility.await("consumer unwind after cancellation")
                .atMost(Duration.ofSeconds(6))
                .untilAsserted(() -> assertTrue(consumerExited.isDone(),
                        "consumer still parked — the takeUntilOther coupling failed"));

        assertEquals("completed-normally", consumerExited.getNow("pending"),
                "the parked consumer must exit the loop, not die blowing up");
        assertTrue(sourceCancelledOrCompleted.get(), "row subscription must terminate");
    }

    @Test
    void theSchedulerThreadIsReusableAfterACancelledExport() throws Exception {
        Sinks.Empty<Void> cancelSignal = Sinks.empty();
        var parked = new CountDownLatch(1);
        var consumerExited = new CountDownLatch(1);

        Flux<byte[]> stalled = Flux.concat(Flux.just("x\n".getBytes()), Flux.<byte[]>never())
                .takeUntilOther(cancelSignal.asMono());

        Flux<DataBuffer> bytes = Flux.from(DataBufferUtils.outputStreamPublisher(
                        out -> {
                            try (Stream<byte[]> s = stalled.toStream(1)) {
                                var it = s.iterator();
                                out.write(it.next());
                                out.flush();
                                parked.countDown();
                                while (it.hasNext()) out.write(it.next());
                            } catch (Throwable ignored) {
                                // exit path under cancellation; liveness is asserted below
                            } finally {
                                consumerExited.countDown();
                            }
                        },
                        FACTORY, exec, CHUNK))
                .doOnCancel(cancelSignal::tryEmitEmpty);

        var sub = bytes.subscribe(DataBufferUtils::release, e -> {}, () -> {},
                s -> s.request(Long.MAX_VALUE));
        assertTrue(parked.await(5, TimeUnit.SECONDS), "consumer never parked awaiting a row");
        sub.dispose();
        Awaitility.await("consumer exited").atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertEquals(0, consumerExited.getCount()));

        // The single scheduler thread must be free to run another export.
        var second = new CompletableFuture<String>();
        Flux.from(DataBufferUtils.outputStreamPublisher(
                        out -> {
                            try { out.write("second-export".getBytes()); second.complete("ran"); }
                            catch (Exception e) { throw new RuntimeException(e); }
                        },
                        FACTORY, exec, CHUNK))
                .doOnNext(DataBufferUtils::release)
                .blockLast(Duration.ofSeconds(5));

        assertEquals("ran", second.get(5, TimeUnit.SECONDS),
                "scheduler thread must be reusable after a cancelled export");
    }

    @Test
    void cancellingWhileTheConsumerIsBlockedInWriteFailsTheWriteInsteadOfLeaking() throws Exception {
        var delivered = new AtomicInteger();
        var writeFailure = new AtomicReference<Throwable>();
        var consumerExited = new CountDownLatch(1);
        var sourceCancelledOrCompleted = new AtomicBoolean();

        Flux<byte[]> rows = Flux.range(0, 50_000).map(i -> ("row-" + i + "\n").getBytes());

        Flux<DataBuffer> bytes = Flux.from(DataBufferUtils.outputStreamPublisher(
                        out -> {
                            try (Stream<byte[]> s = rows.toStream(1)) {
                                s.forEach(b -> {
                                    try {
                                        out.write(b);
                                    } catch (IOException e) {
                                        throw new UncheckedIOException(e);
                                    }
                                });
                            } catch (Throwable t) {
                                writeFailure.set(t);
                                consumerExited.countDown();
                            }
                        },
                        FACTORY, exec, CHUNK))
                .doFinally(sig -> sourceCancelledOrCompleted.set(true));

        var sub = bytes.subscribe(
                b -> { DataBufferUtils.release(b); delivered.incrementAndGet(); },
                err -> {}, () -> {},
                s -> s.request(2));   // exactly two buffers of demand: the next write() parks

        // Readiness: both granted buffers delivered — the consumer's next
        // write() is parked awaiting downstream demand, the exact Q3 state.
        Awaitility.await("two buffers delivered").atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertEquals(2, delivered.get()));

        sub.dispose();

        Awaitility.await("blocked write unwound").atMost(Duration.ofSeconds(6))
                .untilAsserted(() -> assertEquals(0, consumerExited.getCount()));

        var failure = writeFailure.get();
        assertNotNull(failure, "the parked write must fail loudly on cancellation");
        assertInstanceOf(UncheckedIOException.class, failure);
        assertTrue(((UncheckedIOException) failure).getCause() instanceof IOException,
                "expected the Subscription-has-been-terminated IOException, got " + failure);
        assertTrue(sourceCancelledOrCompleted.get(), "byte subscription must terminate");
    }
}
