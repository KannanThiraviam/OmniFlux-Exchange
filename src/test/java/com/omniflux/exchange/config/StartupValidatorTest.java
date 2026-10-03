package com.omniflux.exchange.config;

import com.omniflux.exchange.TestProps;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.*;

class StartupValidatorTest {

    // REGRESSION. An earlier version supplied the SAME heap figure it asserted
    // against, so it passed an implementation that never consulted the runtime.
    // The heap is now an injectable LongSupplier, defaulting to Runtime::maxMemory.
    private static OmnifluxProperties ok(Map<String, Object> extra) {
        var m = new java.util.HashMap<String, Object>(
                Map.of("omniflux.security.allow-non-jwt-auth", true));
        m.putAll(extra);
        return TestProps.with(m);
    }

    @Test void aRollupThatFitsTheBUDGETButNotTheRUNTIMEHeapIsRejected() {
        // REGRESSION, and revision 6 got it wrong. It declared a 512MB budget
        // against a 384MB runtime heap and expected rejection — but the rollup
        // was 248.5MB, which fits BOTH, so the correct implementation passes and
        // the test fails. The test has to put the rollup in the GAP.
        //
        //   max-concurrent 7 -> 59.125 x 7 + 64 = 477.875 MB
        //   fits the declared 512MB budget; does NOT fit the 384MB runtime heap.
        // An implementation that consults only max-heap-budget accepts it.
        var v = new StartupValidator(
                ok(Map.of("omniflux.resources.max-heap-budget", "512MB",
                          "omniflux.queue.max-concurrent", 7)),
                () -> 384L << 20);
        var ex = assertThrows(IllegalStateException.class, v::validate);
        assertTrue(ex.getMessage().contains("477"), "must name the rollup");
        assertTrue(ex.getMessage().contains("384"),
                "must name the RUNTIME heap it actually compared against");
    }

    @Test void theSameRollupIsACCEPTEDWhenTheRuntimeHeapIsLarge() {
        // The other side, or the test above passes an implementation that always
        // rejects max-concurrent 7 for some unrelated reason.
        assertDoesNotThrow(new StartupValidator(
                ok(Map.of("omniflux.resources.max-heap-budget", "512MB",
                          "omniflux.queue.max-concurrent", 7)),
                () -> 512L << 20)::validate);
    }

    @Test void theShippedDefaultsFitTheShippedContainer() {
        // If the defaults cannot satisfy the validator, every `docker compose up`
        // fails to boot. The rollup is 300.5MB against a 384MB heap.
        assertDoesNotThrow(new StartupValidator(ok(Map.of()), () -> 384L << 20)::validate);
    }

    @Test void theRollupArithmeticIsTHISAndNotSomethingElse() {
        // Pin the number. "It did not throw" passes a validator that computes a
        // rollup of zero, and the whole guarantee rests on this sum.
        //   REST per job = 25 (codec = 4MB x 6.0 factor + 1MB envelope headroom)
        //                  + 10 (decoded row = 4MB x 2.5 java-expansion-factor)
        //                  + 0.125 (bridge x2) + 16 (upload) + 8 (overhead)  = 59.125
        //   rollup       = 59.125 x 4 + 64 (jvm-baseline)                    = 300.5 MiB
        //
        // Compare EXACT BYTES. `>> 20` accepts anything from 252.0 to 252.999 MB,
        // a 1 MB band — wide enough to hide a term. (Revision 7 also asserted
        // 260,642,816, which is 248.568 MiB, not the 248.5 MiB it claimed: the
        // shift hid a wrong constant, which is exactly the failure mode.)
        assertEquals(315_097_088L,               // 300.5 MiB = 300.5 x 1048576, exactly
                new StartupValidator(ok(Map.of()), () -> 384L << 20).computedRollupBytes());
    }

    @Test void refusesNonJwtAuthWithoutTheExplicitOptIn() {
        var ex = assertThrows(IllegalStateException.class, () -> new StartupValidator(
                TestProps.with(Map.of("omniflux.security.auth-mode", "DISABLED",
                                      "omniflux.security.allow-non-jwt-auth", false)),
                () -> 384L << 20).validate());
        assertTrue(ex.getMessage().contains("allow-non-jwt-auth"));
    }

    @Test void rejectsAnObjectCeilingAbove10000Parts() {
        assertThrows(IllegalStateException.class, () -> new StartupValidator(
                ok(Map.of("omniflux.storage.part-size", "8MB",
                          "omniflux.export.max-object-bytes", "200GB")),
                () -> 384L << 20).validate());
    }

    @Test void rejectsAnXlsxLimitAboveTheProductMaximum() {
        var ex = assertThrows(IllegalStateException.class, () -> new StartupValidator(
                ok(Map.of("omniflux.xlsx.max-data-rows", 1_000_001)),
                () -> 384L << 20).validate());
        assertTrue(ex.getMessage().contains("product maximum"));
    }

    @Test void refusesToStartInJwtModeBecauseV1HasNoJwtProvider() {
        // REGRESSION. Accepting auth-mode=JWT while no JWT provider exists is the
        // worst possible failure: the operator believes the service validates
        // tokens, and it does not. Refuse loudly instead.
        var ex = assertThrows(IllegalStateException.class, () -> new StartupValidator(
                TestProps.with("omniflux.security.auth-mode", "JWT"), () -> 384L << 20).validate());
        assertTrue(ex.getMessage().contains("auth-mode=JWT is not implemented in v1"));
    }

    @Test void headerModeAlsoRequiresTheExplicitOptIn() {
        // HEADER mode trusts X-Auth-* completely. It is safe ONLY behind a gateway
        // that strips those headers inbound, which this service cannot verify.
        // The opt-in flag is the only place that fact can be asserted.
        var ex = assertThrows(IllegalStateException.class, () -> new StartupValidator(
                TestProps.with(Map.of("omniflux.security.auth-mode", "HEADER",
                                      "omniflux.security.allow-non-jwt-auth", false)),
                () -> 384L << 20).validate());
        assertTrue(ex.getMessage().contains("allow-non-jwt-auth"));
    }

    @Test void rejectsAContainerLimitSmallerThanHeapPlusNonHeap() {
        // REGRESSION. The heap check alone approved a configuration that OOM-KILLS:
        // the cgroup counts direct buffers, thread stacks, metaspace and code cache,
        // none of which are in -Xmx. 384MB heap in a 400MB container is a kill.
        var ex = assertThrows(IllegalStateException.class, () -> new StartupValidator(
                ok(Map.of()), () -> 384L << 20, () -> OptionalLong.of(400L << 20)).validate());
        assertTrue(ex.getMessage().contains("container limit"));
    }

    @Test void anAbsentCgroupLimitSkipsTheContainerCheckRatherThanPassingIt() {
        // Development is on Windows. An OptionalLong.empty() must SKIP, never
        // read as zero and never read as "fits".
        assertDoesNotThrow(new StartupValidator(
                ok(Map.of()), () -> 384L << 20, OptionalLong::empty)::validate);
    }

    @Test void rejectsACodecLimitBelowMaxRowBytesTimesTheEnvelopeFactor() {
        // Binding both properties is not the same as checking their RELATIONSHIP.
        // Set independently, they drift, and the symptom is that the largest
        // legal row fails as a decoder error rather than a typed limit.
        //
        // The required minimum is max-row-bytes(4) x factor(6.0) + headroom(1) = 25MB.
        // Probe the gap between the CURRENT rule and an earlier one: 13MB
        // satisfies the old 3.0-factor rule and violates the 6.0-factor rule, so
        // a validator left on the old factor fails this. A value like 4MB would
        // be rejected by BOTH and prove nothing.
        var ex = assertThrows(IllegalStateException.class, () -> new StartupValidator(
                ok(Map.of("omniflux.source.rest.max-in-memory-size", "13MB")),
                () -> 384L << 20).validate());
        assertTrue(ex.getMessage().contains("max-in-memory-size"));
        assertTrue(ex.getMessage().contains("25"), "must name the required minimum");

        // …and 25MB is ACCEPTED, or the test passes against a validator that
        // rejects every value.
        assertDoesNotThrow(new StartupValidator(
                ok(Map.of("omniflux.source.rest.max-in-memory-size", "25MB")),
                () -> 384L << 20)::validate);
    }

    @Test void rejectsACacheTtlThatOutlivesTheBucketObjectExpiry() {
        // A cache hit references an object owned by an EARLIER job. If the TTL
        // outlives the bucket's expiry, hits point at deleted objects.
        var ex = assertThrows(IllegalStateException.class, () -> new StartupValidator(
                ok(Map.of("omniflux.cache.enabled", true,
                          "omniflux.cache.ttl", "8d",
                          "omniflux.storage.bucket-expiry", "7d")),
                () -> 384L << 20).validate());
        assertTrue(ex.getMessage().contains("cache.ttl"));
    }

    @Test void theCacheTtlCheckIsSKIPPEDWhenTheCacheIsDisabled() {
        // Otherwise the shipped default (cache off, TTL 1h, expiry unset) refuses
        // to boot, and every `docker compose up` fails on a disabled feature.
        assertDoesNotThrow(new StartupValidator(
                ok(Map.of("omniflux.cache.enabled", false)), () -> 384L << 20)::validate);
    }
}
