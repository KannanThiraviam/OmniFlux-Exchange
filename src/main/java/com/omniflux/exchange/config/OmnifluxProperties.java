package com.omniflux.exchange.config;

import com.omniflux.exchange.security.PrincipalKey;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.ConfigurationPropertiesBinding;
import org.springframework.core.convert.converter.Converter;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The single authoritative configuration schema — spec §12's {@code omniflux:}
 * YAML block, bound exactly. Every byte quantity is a {@link DataSize}, every
 * duration a {@link Duration}.
 *
 * <p>A record tree so binding failures surface as a missing/mistyped YAML key
 * rather than a silently-null field read much later. A {@code @ConfigurationProperties}
 * record must NOT carry {@code @Component}: the ordinary component scan would
 * then constructor-AUTOWIRE it — Spring looks for a bean of type {@code Limits}
 * to inject the first canonical-constructor parameter — instead of value-BINDING
 * it, and every context fails with {@code UnsatisfiedDependencyException}.
 * {@code @ConfigurationPropertiesScan} on {@code OmniFluxApplication} registers
 * the record for constructor binding, which is the one mechanism Boot offers for
 * immutable configuration. This is the only {@code @ConfigurationProperties}
 * type in the application, so the unparameterized scan covers the whole schema.
 *
 * <p>{@code export.max-page-bytes} does NOT exist — see Task 3 Step 4 of the plan.
 * Three values were tried (an 8 MiB buffer tripped on one legal maximum-width
 * row; a 64 MiB counter still summed into the rollup produced 448.5 MiB and
 * refused to boot; a 64 MiB counter correctly excluded from the sum still
 * cannot work, because a REST page of 5,000 rows already exceeds it at sixteen
 * 4 MiB rows). The key sits between {@code max-row-bytes} (bounds each row) and
 * {@code max-object-bytes} (bounds the export cumulatively); for a streaming
 * decoder the page is never resident, so it protected nothing those two do not.
 *
 * <p>{@code export.chunk-size} does NOT exist either — it duplicated
 * {@code source.rest.page-size} / {@code source.r2dbc.page-size} with no
 * consumer of its own.
 *
 * <p>Canonical constructors of nested records are invoked reflectively by
 * Boot's constructor binding; the compiler-visible "unused" warnings on them
 * are expected.
 */
@ConfigurationProperties(prefix = "omniflux")
public record OmnifluxProperties(
        Limits limits,
        Export export,
        Storage storage,
        Source source,
        Queue queue,
        Cache cache,
        Xlsx xlsx,
        Security security,
        Seed seed,
        Observability observability,
        Resources resources,
        Ui ui
) {

    public record Limits(
            DataSize maxFieldBytes,
            DataSize maxRowBytes,
            List<String> allowedControlChars
    ) { }

    public record Export(
            DataSize fetchBufferBudget,
            DataSize bridgeChunkSize,
            DataSize maxObjectBytes,
            String defaultFormat,
            String csvMode,
            int csvDialectVersion,
            int prefetch,
            Scheduler scheduler
    ) {
        @SuppressWarnings("unused") // canonical ctor invoked reflectively by Boot binding
        public record Scheduler(int threads, int queueCapacity) { }
    }

    public record Storage(
            String endpoint,
            String publicEndpoint,
            Duration bucketExpiry,
            String region,
            String bucket,
            String accessKey,
            String secretKey,
            boolean pathStyle,
            DataSize partSize,
            DataSize uploadBuffer,
            Duration apiCallTimeout,
            Duration presignTtl,
            String exportPrefix
    ) { }

    public record Source(
            String defaultAdapter,
            Rest rest,
            R2dbc r2dbc
    ) {
        @SuppressWarnings("unused") // canonical ctor invoked reflectively by Boot binding
        public record Rest(
                String baseUrl,
                Auth auth,
                int pageSize,
                Duration connectTimeout,
                Duration responseTimeout,
                int maxRetries,
                DataSize wireEnvelopeHeadroom,
                double wireEnvelopeFactor,
                DataSize maxInMemorySize
        ) {
            @SuppressWarnings("unused") // canonical ctor invoked reflectively by Boot binding
            public record Auth(String tokenUrl, String clientId, String clientSecret, String scope) { }
        }

        @SuppressWarnings("unused") // canonical ctor invoked reflectively by Boot binding
        public record R2dbc(int pageSize) { }
    }

    public record Queue(
            Duration pollInterval,
            int maxConcurrent,
            int maxDepth,
            Duration leaseDuration,
            Duration leaseRenewInterval,
            Duration leaseRenewDeadline,
            int maxAttempts,
            Duration sweepInterval,
            Duration drainTimeout
    ) {
        public Queue {
            if (drainTimeout == null || drainTimeout.isZero() || drainTimeout.isNegative()
                    || drainTimeout.compareTo(Duration.ofSeconds(40)) > 0) {
                throw new IllegalArgumentException("queue.drain-timeout must be positive and at most 40s");
            }
        }
    }

    public record Cache(
            boolean enabled,
            Duration ttl,
            Map<String, Boolean> relations
    ) {
        /**
         * Spec §12 ships {@code relations: {}}. An empty flow map reaches the
         * binder as an EMPTY-STRING leaf (see {@link EmptyFlowMapConverter}),
         * which converts to an empty map; a profile that omits the key entirely
         * binds null, normalized here so callers can iterate without a guard.
         * Entries such as {@code mock_orders: true} bind with the key verbatim —
         * map keys keep their underscore; they are not run through relaxed-name
         * normalization the way component names are.
         */
        public Cache {
            if (relations == null) relations = Map.of();
        }
    }

    public record Xlsx(
            int maxDataRows,
            int maxCellChars,
            int compressionLevel,
            String dateMode,
            String illegalCharPolicy
    ) { }

    public record Security(
            String authMode,
            boolean allowNonJwtAuth,
            DevPrincipal devPrincipal,
            String authzContextVersion,
            String userHeaderPrefix,
            String mode,
            List<String> allowedRelations,
            Map<String, String> viewKeys,
            int maxColumns,
            int maxInValues,
            Duration queryTimeout
    ) {
        /**
         * NOT {@link PrincipalKey}: that type deliberately excludes roles (see
         * its Javadoc), and the dev principal ships with {@code roles: [ANALYST]}
         * so the demo profile has something to authorize against.
         */
        @SuppressWarnings("unused") // canonical ctor invoked reflectively by Boot binding
        public record DevPrincipal(String issuer, String subject, String tenant, List<String> roles) { }

        /** Same empty-flow-map situation as {@link Cache#relations}. */
        public Security {
            if (viewKeys == null) viewKeys = Map.of();
        }
    }

    public record Seed(
            DataSize batchBytes,
            int batchSize
    ) { }

    public record Observability(
            String fsWatchMode,
            List<String> watchPaths
    ) { }

    public record Resources(
            double javaExpansionFactor,
            DataSize measuredOverhead,
            DataSize jvmBaseline,
            DataSize maxHeapBudget,
            DataSize nonHeapReserve
    ) { }

    public record Ui(
            int pageSize,
            Duration pollInterval
    ) { }

    /**
     * YAML's empty flow maps — spec §12 ships {@code cache.relations: {}} and
     * {@code security.view-keys: {}} — flatten through
     * {@code YamlPropertySourceLoader} to a single property whose value is the
     * EMPTY STRING. (A NON-empty flow map flattens to one child property per
     * entry instead, so the string leaf exists precisely when the map is empty.)
     * The binder converts an empty string to an empty {@code Collection} for
     * list components but has no String-to-Map converter, so without this
     * converter the shipped defaults cannot bind at all — neither in the real
     * context nor in {@code TestProps}' hand-rolled {@code Binder} — and fail
     * with {@code BindException} / {@code ConverterNotFoundException} under
     * {@code omniflux.cache.relations}.
     *
     * <p>Registered for the application context by {@code @Component} +
     * {@code @ConfigurationPropertiesBinding}, which scopes it to
     * configuration-properties binding only, and installed by {@code TestProps}
     * on the {@code Binder} it builds — one converter, both paths, identical
     * results. The target is the RAW {@code Map}: converter selection is
     * element-type aware, so a {@code Map<String, Boolean>} declaration would
     * not match this schema's {@code Map<String, String>} component, while the
     * raw declaration serves both. It only ever accepts the empty string — a
     * non-blank string is refused loudly rather than silently parsed into
     * invented comma or colon semantics.
     */
    @Component
    @ConfigurationPropertiesBinding
    @SuppressWarnings("rawtypes") // raw Map is required — see the Javadoc above
    public static class EmptyFlowMapConverter implements Converter<String, Map> {

        @Override
        public Map convert(String source) {
            if (!source.isBlank()) {
                throw new IllegalArgumentException(
                        "Cannot parse a non-empty string as a map: [%s]".formatted(source));
            }
            return new LinkedHashMap<>();
        }
    }
}
