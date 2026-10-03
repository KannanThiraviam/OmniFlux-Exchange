package com.omniflux.exchange;

import com.omniflux.exchange.config.OmnifluxProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;

/**
 * `new OmnifluxProperties()` does not work: the tree is immutable records bound
 * by Boot, and records have no no-argument constructor. Nor should a test build
 * nine nested records by hand — that is how a test ends up asserting against a
 * shape that no longer matches the shipped defaults.
 *
 * <p>These bind the REAL application.yml, so a default that changes in the file
 * changes here too.
 */
public final class TestProps {

    private TestProps() { }

    public static OmnifluxProperties defaults() { return with(Map.of()); }

    /** `TestProps.with("omniflux.queue.max-concurrent", 8)` — one override,
     *  every other value still the shipped default. */
    public static OmnifluxProperties with(String key, Object value) { return with(Map.of(key, value)); }

    public static OmnifluxProperties with(Map<String, Object> overrides) {
        var sources = new MutablePropertySources();
        sources.addFirst(new MapPropertySource("overrides", overrides));
        for (var ps : loadYaml()) sources.addLast(ps);
        // The real context's configuration-properties binding converts through
        // ApplicationConversionService plus the @ConfigurationPropertiesBinding
        // beans — of which the schema registers exactly one, for YAML's empty
        // flow maps (`relations: {}`, `view-keys: {}`). The same pair is given
        // to this hand-rolled Binder so a configuration binds IDENTICALLY here
        // and in the real Spring context: without the converter, the empty
        // flow map flattens to an empty-STRING leaf and the bind fails with
        // ConverterNotFoundException under 'omniflux.cache.relations'.
        var conversion = new ApplicationConversionService();
        conversion.addConverter(new OmnifluxProperties.EmptyFlowMapConverter());
        return new Binder(ConfigurationPropertySources.from(sources), null, conversion)
                .bind("omniflux", OmnifluxProperties.class).get();
    }

    private static List<PropertySource<?>> loadYaml() {
        try { return new YamlPropertySourceLoader().load("application.yml", new ClassPathResource("application.yml")); }
        catch (IOException e) { throw new UncheckedIOException(e); }
    }
}
