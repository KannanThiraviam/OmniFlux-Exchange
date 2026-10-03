package com.omniflux.exchange.config;

import com.omniflux.exchange.adapter.rest.ClientCredentialsDataApiCredentials;
import com.omniflux.exchange.adapter.rest.DataApiClient;
import com.omniflux.exchange.adapter.rest.DataApiCredentials;
import com.omniflux.exchange.adapter.rest.StaticDataApiCredentials;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.web.reactive.function.client.WebClient;

/** Wiring for the service-owned Data API credential and HTTP client. */
@Configuration(proxyBeanMethods = false)
public class DataApiConfig {

    @Bean
    @Profile("demo")
    DataApiCredentials demoDataApiCredentials(
            @Value("${DATA_API_TOKEN:demo-token}") String token) {
        return new StaticDataApiCredentials(token);
    }

    @Bean
    @Profile("!demo")
    DataApiCredentials productionDataApiCredentials(
            OmnifluxProperties properties, WebClient.Builder webClientBuilder) {
        var auth = properties.source().rest().auth();
        return new ClientCredentialsDataApiCredentials(webClientBuilder,
                auth.tokenUrl(), auth.clientId(), auth.clientSecret(), auth.scope());
    }

    // The two DataApiCredentials beans are @Profile-disjoint; IntelliJ cannot
    // model profile conditions, so the autowire inspection is suppressed here.
    @Bean
    @SuppressWarnings("SpringJavaInjectionPointsAutowiringInspection")
    DataApiClient dataApiClient(OmnifluxProperties properties,
                                DataApiCredentials credentials) {
        return new DataApiClient(properties, credentials);
    }
}
