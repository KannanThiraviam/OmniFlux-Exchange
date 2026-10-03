package com.omniflux.exchange.config;

import com.omniflux.exchange.adapter.r2dbc.PgDialect;
import com.omniflux.exchange.source.SqlDialect;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class R2dbcConfig {
    @Bean
    public SqlDialect sqlDialect() {
        return new PgDialect();
    }
}
