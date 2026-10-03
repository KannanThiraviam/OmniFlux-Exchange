package com.omniflux.exchange.config;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.util.List;

class ScratchYamlProbeTest {
    @Test
    void probe() throws Exception {
        var loader = new YamlPropertySourceLoader();
        List<PropertySource<?>> list = loader.load("application.yml", new ClassPathResource("application.yml"));
        var relationSettings = list.stream().map(EnumerablePropertySource.class::cast)
                .flatMap(source -> java.util.Arrays.stream(source.getPropertyNames())
                        .filter(name -> name.contains("relations") || name.contains("view-keys"))
                        .map(source::getProperty))
                .filter(java.util.Objects::nonNull).toList();
        assertFalse(relationSettings.stream().anyMatch(value -> value.toString().contains("mock_orders")),
                "the base application config must not allow demo relations");
        var demo = loader.load("application-demo.yml", new ClassPathResource("application-demo.yml"));
        assertTrue(demo.stream().map(EnumerablePropertySource.class::cast)
                        .flatMap(source -> java.util.Arrays.stream(source.getPropertyNames())
                                .filter(name -> name.contains("allowed-relations"))
                                .map(source::getProperty))
                        .anyMatch(value -> value.toString().contains("mock_orders")),
                "the demo profile should allow mock_orders");
    }
}
