package com.omniflux.exchange;

import com.omniflux.exchange.config.LegacyTimeZones;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class OmniFluxApplication {

    public static void main(String[] args) {
        LegacyTimeZones.normalizeJvmDefault();   // before any JDBC connection; see the class
        SpringApplication.run(OmniFluxApplication.class, args);
    }
}
