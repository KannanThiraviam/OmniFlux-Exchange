package com.omniflux.exchange.security;

import com.omniflux.exchange.config.OmnifluxProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.util.Locale;

/** Selects the one configured v1 identity provider. */
@Configuration
public class SecurityProviderConfiguration {

    @Bean
    @Primary
    public CurrentUserProvider currentUserProvider(OmnifluxProperties properties) {
        return switch (properties.security().authMode().toUpperCase(Locale.ROOT)) {
            case "DISABLED" -> new FixedPrincipalProvider(properties.security());
            case "HEADER" -> new HeaderPrincipalProvider(properties.security());
            case "JWT" -> throw new IllegalStateException(
                    "auth-mode=JWT is not implemented in v1 — use DISABLED or HEADER behind a gateway");
            default -> throw new IllegalStateException(
                    "unsupported auth-mode=" + properties.security().authMode());
        };
    }
}
