package com.omniflux.exchange.config;

import com.omniflux.exchange.observability.ResourceProbe;
import com.omniflux.exchange.writer.XlsxStreamWriter;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.OptionalLong;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Refuses to boot on a configuration whose arithmetic cannot hold at runtime:
 * the auth mode must be one v1 actually implements and has been explicitly
 * opted into, the REST codec must cover the largest LEGAL row, the concurrent
 * export rollup must fit BOTH the declared heap budget and the heap the JVM
 * actually reserved, that heap plus the non-heap reserve must fit the cgroup
 * limit, the object ceiling must stay within S3's 10,000-part multipart cap,
 * and a cache hit must never presign an object the bucket has already expired.
 *
 * <p>The heap is injected as a {@link LongSupplier} defaulting to
 * {@code Runtime::maxMemory} in production wiring. A validator that compares
 * the rollup only against the DECLARED budget passes an operator who lowered
 * {@code -Xmx} without lowering {@code omniflux.resources.max-heap-budget}.
 * So the tests inject a runtime heap in the gap between the two and require
 * the rejection to name both figures. An ABSENT cgroup reading (development
 * is on Windows) skips the container check — it must never read as zero.
 */
@Component
public class StartupValidator {

    private final OmnifluxProperties p;
    private final LongSupplier heapSupplier;
    private final Supplier<OptionalLong> cgroupLimit;

    // Production wiring uses Runtime::maxMemory. Tests inject a smaller value,
    // which is what makes the regression tests discriminate: an implementation
    // that consults only the declared budget passes them.
    @Autowired
    public StartupValidator(OmnifluxProperties p, ResourceProbe probe) {
        this(p, Runtime.getRuntime()::maxMemory, probe::cgroupLimitBytes);
    }

    StartupValidator(OmnifluxProperties p, LongSupplier heapSupplier) {
        this(p, heapSupplier, OptionalLong::empty);
    }

    StartupValidator(OmnifluxProperties p, LongSupplier heapSupplier,
                     Supplier<OptionalLong> cgroupLimit) {
        this.p = p;
        this.heapSupplier = heapSupplier;
        this.cgroupLimit = cgroupLimit;
    }

    @PostConstruct
    public void validate() {
        assertAuthModeImplementedAndOptedIn();
        assertXlsxProductLimit();
        assertCodecCoversTheLargestLegalRow();     // must be CALLED, not merely written
        assertHeapFits();
        assertContainerLimitFits();
        assertObjectCeiling();
        assertCacheTtlUnderBucketExpiry();
    }

    /**
     * Exposed for the test that pins the arithmetic. {@link #assertHeapFits()}
     * throwing or not throwing cannot distinguish a correct rollup from one that
     * computes zero, and the whole guarantee rests on this sum.
     */
    public long computedRollupBytes() {
        return p.queue().maxConcurrent() * perJobBytes()
             + p.resources().jvmBaseline().toBytes();
    }

    /**
     * The per-adapter derivation. A queued job runs exactly one adapter, so the
     * rollup takes the LARGER of the two, never the sum.
     */
    private long perJobBytes() {
        long bridge = p.export().bridgeChunkSize().toBytes() * 2;   // toByteBuffer COPIES
        long upload = p.storage().uploadBuffer().toBytes();
        long over   = p.resources().measuredOverhead().toBytes();

        // The DECODED ROW term is common to both adapters — it is the Java
        // objects the writer receives, and it exists whatever produced them.
        long decodedRow = (long) (p.limits().maxRowBytes().toBytes()
                                  * p.resources().javaExpansionFactor());   // 4MB x 2.5 = 10MB

        long rest  = p.source().rest().maxInMemorySize().toBytes()   // 25MB wire element
                   + decodedRow + bridge + upload + over;            // = 59.125 MB

        // There is no page-bytes term. `export.max-page-bytes` was deleted (see
        // OmnifluxProperties' Javadoc): budgeting it as a buffer computed
        // 96.125MB per R2DBC job and a 448.5MB rollup, so the validator REFUSED
        // TO BOOT ON THE SHIPPED DEFAULTS — failing on arithmetic, not on memory
        // pressure. For a streaming decoder the page is never resident.
        long r2dbc = p.export().fetchBufferBudget().toBytes()         // 8MB driver buffer
                   + decodedRow + bridge + upload + over;             // = 42.125 MB

        return Math.max(rest, r2dbc);      // a job is one adapter, never both
    }

    /** The relationship, not the two values independently. Set apart, they drift. */
    private void assertCodecCoversTheLargestLegalRow() {
        long codecLimit = p.source().rest().maxInMemorySize().toBytes();
        long required = (long) (p.limits().maxRowBytes().toBytes()
                                * p.source().rest().wireEnvelopeFactor())
                      + p.source().rest().wireEnvelopeHeadroom().toBytes();
        if (codecLimit < required)
            throw new IllegalStateException(
                "source.rest.max-in-memory-size must be at least %dMB (max-row-bytes x %.1f + headroom); it is %dMB"
                    .formatted(required >> 20, p.source().rest().wireEnvelopeFactor(),
                               codecLimit >> 20));
    }

    private void assertXlsxProductLimit() {
        if (p.xlsx().maxDataRows() > XlsxStreamWriter.DEFAULT_MAX_DATA_ROWS) {
            throw new IllegalStateException(
                    "omniflux.xlsx.max-data-rows cannot exceed the product maximum of "
                            + XlsxStreamWriter.DEFAULT_MAX_DATA_ROWS);
        }
    }

    /**
     * The rollup must fit the ceiling formed by BOTH bounds: the DECLARED
     * {@code max-heap-budget} and the heap the JVM actually reserved. Checking
     * only the declared budget passes a configuration that lowers {@code -Xmx}
     * without lowering the budget — the exact failure the injectable supplier
     * exists to catch.
     */
    private void assertHeapFits() {
        long total   = computedRollupBytes();
        long perJob  = perJobBytes();
        long actual  = heapSupplier.getAsLong();
        long ceiling = Math.min(p.resources().maxHeapBudget().toBytes(), actual);
        if (total > ceiling)
            throw new IllegalStateException(
              ("Refusing to start: max-concurrent(%d) x per-job(%dMB) + baseline(%dMB) = %dMB "
             + "exceeds %dMB — min(max-heap-budget=%dMB, runtime heap=%dMB). Lower "
             + "max-concurrent, upload-buffer or max-row-bytes.")
              .formatted(p.queue().maxConcurrent(), perJob >> 20,
                         p.resources().jvmBaseline().toBytes() >> 20, total >> 20,
                         ceiling >> 20, p.resources().maxHeapBudget().toBytes() >> 20, actual >> 20));
    }

    /**
     * The heap check alone is not the container check. -Xmx bounds the Java
     * heap; the cgroup kills on RSS, which also carries direct buffers (the S3
     * upload buffer is one), thread stacks, metaspace and the code cache.
     * An ABSENT cgroup reading skips this check — it must never read as zero.
     */
    private void assertContainerLimitFits() {
        cgroupLimit.get().ifPresent(limit -> {
            long need = p.resources().maxHeapBudget().toBytes()
                      + p.resources().nonHeapReserve().toBytes();   // direct + stacks + metaspace
            if (need > limit)
                throw new IllegalStateException(
                    "Refusing to start: heap(%dMB) + non-heap reserve(%dMB) = %dMB exceeds the container limit(%dMB)"
                        .formatted(p.resources().maxHeapBudget().toBytes() >> 20,
                                   p.resources().nonHeapReserve().toBytes() >> 20,
                                   need >> 20, limit >> 20));
        });
    }

    /**
     * An S3 multipart upload caps at 10,000 parts, so the object ceiling must
     * be reachable with the configured part size or the largest legal export
     * fails mid-upload. Rounded UP: a single byte over 9,999 full parts needs a
     * 10,001st part.
     */
    private void assertObjectCeiling() {
        long part = p.storage().partSize().toBytes();
        long parts = (p.export().maxObjectBytes().toBytes() + part - 1) / part;
        if (parts > 10_000)
            throw new IllegalStateException(
                "export.max-object-bytes(%s) needs %d parts of storage.part-size(%s); a multipart upload caps at 10,000 — raise part-size or lower the ceiling"
                    .formatted(p.export().maxObjectBytes(), parts, p.storage().partSize()));
    }

    /** JWT is a configuration value with no implementation behind it in v1.
     *  Accepting it would tell an operator their tokens are validated. */
    private void assertAuthModeImplementedAndOptedIn() {
        if ("JWT".equals(p.security().authMode()))
            throw new IllegalStateException(
                "auth-mode=JWT is not implemented in v1 — use DISABLED or HEADER behind a gateway");
        if (!p.security().allowNonJwtAuth())
            throw new IllegalStateException(
                "auth-mode=" + p.security().authMode() + " requires security.allow-non-jwt-auth: true, "
              + "which no production manifest sets. This service does not authenticate callers.");
    }

    /** Only meaningful when the cache is ON; otherwise the shipped defaults
     *  would refuse to boot over a disabled feature. */
    private void assertCacheTtlUnderBucketExpiry() {
        if (p.cache().enabled() && !p.cache().ttl().minus(p.storage().bucketExpiry()).isNegative())
            throw new IllegalStateException(
                "cache.ttl(%s) must be shorter than storage.bucket-expiry(%s): a hit would presign a deleted object"
                    .formatted(p.cache().ttl(), p.storage().bucketExpiry()));
    }
}
