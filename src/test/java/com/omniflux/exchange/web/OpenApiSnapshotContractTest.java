package com.omniflux.exchange.web;

import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Ensures the committed OpenAPI snapshot covers every controller route. */
class OpenApiSnapshotContractTest {
    private static final List<Class<?>> CONTROLLERS = List.of(
            DataController.class, ExportController.class, JobController.class,
            MetaController.class, NaiveExportController.class, SeedController.class,
            SystemController.class);

    @Test
    void snapshotContainsEveryControllerPathAndMethod() throws IOException {
        Map<String, Object> document;
        try (var input = Files.newInputStream(Path.of("api", "openapi.yaml"))) {
            document = new Yaml().load(input);
        }
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> paths = (Map<String, Map<String, Object>>) document.get("paths");
        Set<String> missing = new LinkedHashSet<>();
        for (Class<?> controller : CONTROLLERS) {
            RequestMapping base = AnnotatedElementUtils.findMergedAnnotation(controller, RequestMapping.class);
            String[] prefixes = base == null ? new String[]{""} : base.path();
            for (var method : controller.getDeclaredMethods()) {
                RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
                if (mapping == null) continue;
                String[] suffixes = mapping.path();
                RequestMethod[] methods = mapping.method();
                if (methods.length == 0) {
                    if (AnnotatedElementUtils.findMergedAnnotation(method, GetMapping.class) != null) {
                        methods = new RequestMethod[]{RequestMethod.GET};
                    } else if (AnnotatedElementUtils.findMergedAnnotation(method, PostMapping.class) != null) {
                        methods = new RequestMethod[]{RequestMethod.POST};
                    }
                }
                for (String prefix : prefixes) {
                    for (String suffix : suffixes) {
                        String path = normalize(prefix, suffix);
                        for (RequestMethod requestMethod : methods) {
                            if (!paths.containsKey(path)
                                    || !paths.get(path).containsKey(requestMethod.name().toLowerCase())) {
                                missing.add(requestMethod + " " + path);
                            }
                        }
                    }
                }
            }
        }
        assertTrue(missing.isEmpty(), "OpenAPI snapshot is missing controller routes: " + missing);
        @SuppressWarnings("unchecked")
        Map<String, Object> components = (Map<String, Object>) document.get("components");
        @SuppressWarnings("unchecked")
        Map<String, Object> schemas = (Map<String, Object>) components.get("schemas");
        assertTrue(schemas.containsKey("ProblemDetail"), "ProblemDetail response schema must remain documented");
    }

    private static String normalize(String prefix, String suffix) {
        String path = (prefix + "/" + suffix).replaceAll("/+", "/");
        return path.length() > 1 && path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
    }
}
