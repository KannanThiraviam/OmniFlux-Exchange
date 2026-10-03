package com.omniflux.exchange.config;

import com.omniflux.exchange.meta.DataApiMetadataProvider;
import com.omniflux.exchange.meta.PgMetadataProvider;
import com.omniflux.exchange.meta.RelationMetadataProvider;
import com.omniflux.exchange.meta.SchemaCatalog;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Map;

/** Adapter-specific metadata wiring for the request-facing schema catalog. */
@Configuration(proxyBeanMethods = false)
public class MetadataConfig {

    @Bean("pgMetadataProvider")
    RelationMetadataProvider pgMetadataProvider(DatabaseClient database,
                                                OmnifluxProperties properties) {
        return new PgMetadataProvider(database, "public", properties.security().viewKeys());
    }

    // The two DataApiCredentials beans are @Profile-disjoint; IntelliJ cannot
    // model profile conditions, so the autowire inspection is suppressed here.
    @Bean("dataApiMetadataProvider")
    @SuppressWarnings("SpringJavaInjectionPointsAutowiringInspection")
    RelationMetadataProvider dataApiMetadataProvider(WebClient.Builder builder,
                                                     OmnifluxProperties properties,
                                                     com.omniflux.exchange.adapter.rest.DataApiCredentials credentials) {
        return new DataApiMetadataProvider(builder, properties.source().rest().baseUrl(),
                properties.security().viewKeys(), credentials,
                properties.source().rest().responseTimeout());
    }

    @Bean
    SchemaCatalog schemaCatalog(OmnifluxProperties properties,
                                @Qualifier("pgMetadataProvider") RelationMetadataProvider pg,
                                @Qualifier("dataApiMetadataProvider") RelationMetadataProvider rest) {
        return new SchemaCatalog(Map.of("r2dbc", pg, "rest", rest), properties);
    }
}
