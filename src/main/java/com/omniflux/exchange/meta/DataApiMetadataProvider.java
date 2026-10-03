package com.omniflux.exchange.meta;

import com.omniflux.exchange.adapter.rest.DataApiClient;
import com.omniflux.exchange.adapter.rest.DataApiCredentials;
import com.omniflux.exchange.job.ErrorCode;
import com.omniflux.exchange.job.ExportException;
import com.omniflux.exchange.security.AuthContext;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Resolves Data API relations from the API's OpenAPI document.
 *
 * <p>The REST source may reach a database that is not the local PostgreSQL
 * instance. Its OpenAPI document is therefore the source of truth for column
 * names, types, and the primary-key extension. A 5xx or an unreachable API is
 * kept transient so a temporary metadata outage does not become
 * {@link ErrorCode#UNKNOWN_RELATION}.
 */
public class DataApiMetadataProvider implements RelationMetadataProvider {

    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][\\w]*");
    // OpenAPI field names this provider reads from several document shapes.
    private static final String FIELD_PROPERTIES = "properties";
    private static final String FIELD_SCHEMA = "schema";
    private final WebClient client;
    private final Duration responseTimeout;
    private final Map<String, String> configuredViewKeys;
    private final DataApiCredentials credentials;

    public DataApiMetadataProvider(WebClient client) {
        this(client, Duration.ZERO, Map.of(), null);
    }

    public DataApiMetadataProvider(WebClient client, Duration responseTimeout) {
        this(client, responseTimeout, Map.of(), null);
    }

    public DataApiMetadataProvider(WebClient client, Map<String, String> configuredViewKeys) {
        this(client, Duration.ZERO, configuredViewKeys, null);
    }

    public DataApiMetadataProvider(WebClient client, Duration responseTimeout,
                                   Map<String, String> configuredViewKeys) {
        this(client, responseTimeout, configuredViewKeys, null);
    }

    public DataApiMetadataProvider(WebClient client, Duration responseTimeout,
                                   Map<String, String> configuredViewKeys,
                                   DataApiCredentials credentials) {
        this.client = Objects.requireNonNull(client, "client");
        this.responseTimeout = responseTimeout == null ? Duration.ZERO : responseTimeout;
        this.configuredViewKeys = configuredViewKeys == null ? Map.of() : Map.copyOf(configuredViewKeys);
        this.credentials = credentials;
    }

    public DataApiMetadataProvider(WebClient.Builder builder, String baseUrl) {
        this(builder, baseUrl, Map.of());
    }

    public DataApiMetadataProvider(WebClient.Builder builder, String baseUrl,
                                   Map<String, String> configuredViewKeys) {
        this(Objects.requireNonNull(builder, "builder").baseUrl(baseUrl).build(),
                Duration.ZERO, configuredViewKeys, null);
    }

    public DataApiMetadataProvider(WebClient.Builder builder, String baseUrl,
                                   Map<String, String> configuredViewKeys,
                                   DataApiCredentials credentials,
                                   Duration responseTimeout) {
        this(Objects.requireNonNull(builder, "builder").baseUrl(baseUrl).build(),
                responseTimeout, configuredViewKeys, credentials);
    }

    @Override
    public Mono<RelationDescriptor> describe(String relation) {
        return describe(relation, null);
    }

    @Override
    public Mono<RelationDescriptor> describe(String relation, AuthContext auth) {
        if (!isIdentifier(relation)) {
            return Mono.error(new ExportException(ErrorCode.RELATION_NOT_ALLOWED,
                    "relation contains an unsafe identifier: " + relation));
        }

        Mono<String> token = credentials == null ? Mono.just("") : credentials.bearerToken()
                .switchIfEmpty(Mono.error(new ExportException(ErrorCode.UPSTREAM_CLIENT_ERROR,
                        "Data API credential returned no token")));
        return token.flatMap(value -> describeWithToken(relation, auth, value));
    }

    private Mono<RelationDescriptor> describeWithToken(String relation, AuthContext auth,
                                                        String token) {
        var request = client.get()
                .uri("/")
                .headers(headers -> {
                    if (token != null && !token.isBlank()) headers.setBearerAuth(token);
                    DataApiClient.addAssertedAuthHeaders(headers, auth);
                })
                .retrieve()
                .onStatus(HttpStatusCode::is5xxServerError, response ->
                        Mono.error(new ExportException(ErrorCode.UPSTREAM_UNAVAILABLE,
                                "Data API metadata endpoint returned " + response.statusCode())))
                .onStatus(HttpStatusCode::is4xxClientError, response ->
                        Mono.error(new ExportException(ErrorCode.UPSTREAM_CLIENT_ERROR,
                                "Data API metadata endpoint returned " + response.statusCode())))
                .bodyToMono(JsonNode.class);

        if (!responseTimeout.isZero() && !responseTimeout.isNegative()) {
            request = request.timeout(responseTimeout);
        }
        return request
                .switchIfEmpty(Mono.error(new ExportException(ErrorCode.UPSTREAM_CLIENT_ERROR,
                        "Data API returned an empty OpenAPI document")))
                .map(document -> describeDocument(relation, document))
                .onErrorMap(error -> !(error instanceof ExportException),
                        error -> new ExportException(ErrorCode.UPSTREAM_UNAVAILABLE,
                                "Data API metadata lookup failed for " + relation, error));
    }

    private RelationDescriptor describeDocument(String relation, JsonNode document) {
        var schemaNode = findRelationSchema(document, relation)
                .orElseThrow(() -> new ExportException(ErrorCode.UNKNOWN_RELATION,
                        "relation is absent from the Data API OpenAPI document: " + relation));
        var properties = schemaNode.path(FIELD_PROPERTIES);
        if (!properties.isObject() || properties.isEmpty()) {
            throw new ExportException(ErrorCode.UNKNOWN_RELATION,
                    "relation has no describable columns: " + relation);
        }

        var primaryKey = configuredViewKey(relation)
                .or(() -> primaryKey(schemaNode, properties));
        if (primaryKey.isEmpty()) {
            throw new ExportException(ErrorCode.UNSUPPORTED_PRIMARY_KEY,
                    "relation has no declared primary key: " + relation);
        }

        var required = names(schemaNode.path("required"));
        var columns = new ArrayList<ColumnDescriptor>();
        for (var entry : properties.properties()) {
            var property = entry.getValue();
            columns.add(new ColumnDescriptor(entry.getKey(), mapLogicalType(property),
                    nullable(property, required, entry.getKey()), entry.getKey().equals(primaryKey.get())));
        }

        var keyColumn = columns.stream()
                .filter(column -> column.name().equals(primaryKey.get()))
                .findFirst()
                .orElseThrow(() -> new ExportException(ErrorCode.UNSUPPORTED_PRIMARY_KEY,
                        "declared primary key is not a relation column: " + primaryKey.get()));
        if (keyColumn.logicalType() != LogicalType.INTEGER) {
            throw new ExportException(ErrorCode.UNSUPPORTED_PRIMARY_KEY,
                    "declared primary key must be an integer: " + primaryKey.get());
        }

        var schema = text(schemaNode, "x-schema")
                .or(() -> text(schemaNode, FIELD_SCHEMA))
                .orElse("public");
        var volatility = text(schemaNode, "x-volatility")
                .map(value -> value.toUpperCase(Locale.ROOT))
                .flatMap(DataApiMetadataProvider::volatility)
                .orElse(Volatility.MUTABLE);
        return new RelationDescriptor(schema, relation, columns,
                new KeyContract(primaryKey.get()), volatility);
    }

    private Optional<String> configuredViewKey(String relation) {
        return Optional.ofNullable(configuredViewKeys.get(relation))
                .or(() -> configuredViewKeys.entrySet().stream()
                        .filter(entry -> entry.getKey().endsWith("." + relation))
                        .map(Map.Entry::getValue)
                        .findFirst());
    }

    private static Optional<JsonNode> findRelationSchema(JsonNode document, String relation) {
        for (String container : List.of("definitions", "schemas")) {
            var node = document.path(container);
            if (node.isObject() && node.has(relation)) return Optional.of(node.get(relation));
        }
        var components = document.path("components").path("schemas");
        if (components.isObject() && components.has(relation)) return Optional.of(components.get(relation));

        // Some Data API implementations publish a schema beside the path
        // operation instead of under definitions/components.
        var paths = document.path("paths");
        if (paths.isObject()) return findSchemaBesideOperation(paths, relation);
        return Optional.empty();
    }

    private static Optional<JsonNode> findSchemaBesideOperation(JsonNode paths, String relation) {
        for (String path : List.of("/" + relation, relation)) {
            var operation = paths.get(path);
            if (operation == null || !operation.isObject()) continue;
            var response = operation.path("get").path("responses").path("200");
            var inlineSchema = response.path(FIELD_SCHEMA);
            if (inlineSchema.isObject() && inlineSchema.has(FIELD_PROPERTIES)) {
                return Optional.of(inlineSchema);
            }
            for (var media : response.path("content").properties()) {
                var responseSchema = media.getValue().path(FIELD_SCHEMA);
                if (responseSchema.isObject() && responseSchema.has(FIELD_PROPERTIES)) {
                    return Optional.of(responseSchema);
                }
            }
        }
        return Optional.empty();
    }

    private static Optional<String> primaryKey(JsonNode relation, JsonNode properties) {
        for (String field : List.of("pk", "x-pk", "x-primary-key", "primaryKey", "primary_key",
                "x-primary-key-columns")) {
            var value = relation.get(field);
            var key = primaryKeyValue(value);
            if (key.isPresent()) return key;
        }

        for (var field : properties.properties()) {
            for (String marker : List.of("pk", "x-pk", "x-primary-key", "primaryKey", "primary_key")) {
                if (field.getValue().path(marker).asBoolean(false)) return Optional.of(field.getKey());
            }
        }
        return Optional.empty();
    }

    private static Optional<String> primaryKeyValue(JsonNode value) {
        if (value == null || value.isMissingNode() || value.isNull()) return Optional.empty();
        if (value.isString()) return Optional.of(value.stringValue());
        if (value.isArray() && value.size() == 1) return primaryKeyValue(value.get(0));
        if (value.isObject()) {
            for (String field : List.of("column", "name", "field")) {
                if (value.has(field)) return primaryKeyValue(value.get(field));
            }
            if (value.has("columns")) return primaryKeyValue(value.get("columns"));
        }
        return Optional.empty();
    }

    private static LogicalType mapLogicalType(JsonNode property) {
        var dbType = text(property, "x-db-type")
                .or(() -> text(property, "dbType"))
                .or(() -> text(property, "sqlType"))
                .or(() -> text(property, "format_type"));
        if (dbType.isPresent()) return PgMetadataProvider.mapLogicalType(dbType.get());

        var type = property.path("type").asString("").toLowerCase(Locale.ROOT);
        var format = property.path("format").asString("").toLowerCase(Locale.ROOT);
        if ("integer".equals(type) || "int32".equals(format) || "int64".equals(format)) {
            return LogicalType.INTEGER;
        }
        if ("number".equals(type) || "decimal".equals(format) || "double".equals(format)
                || "float".equals(format)) {
            return LogicalType.DECIMAL;
        }
        if ("boolean".equals(type)) return LogicalType.BOOLEAN;
        if ("string".equals(type)) return stringFormatToLogicalType(format);
        if (type.isBlank()) return LogicalType.TEXT;
        return LogicalType.UNSUPPORTED;
    }

    /** Maps an OpenAPI string schema's format onto a logical type. */
    private static LogicalType stringFormatToLogicalType(String format) {
        if ("date".equals(format)) return LogicalType.DATE;
        if ("date-time".equals(format) || "timestamp".equals(format)) return LogicalType.TIMESTAMP;
        if ("uuid".equals(format)) return LogicalType.UUID;
        if ("byte".equals(format) || "binary".equals(format)) return LogicalType.BINARY;
        return LogicalType.TEXT;
    }

    private static boolean nullable(JsonNode property, List<String> required, String name) {
        if (property.has("nullable")) return property.path("nullable").asBoolean();
        return !required.contains(name);
    }

    private static List<String> names(JsonNode node) {
        if (!node.isArray()) return List.of();
        var values = new ArrayList<String>();
        node.forEach(value -> { if (value.isString()) values.add(value.stringValue()); });
        return List.copyOf(values);
    }

    private static Optional<String> text(JsonNode node, String field) {
        var value = node.get(field);
        return value != null && value.isString() && !value.stringValue().isBlank()
                ? Optional.of(value.stringValue()) : Optional.empty();
    }

    private static Optional<Volatility> volatility(String value) {
        try { return Optional.of(Volatility.valueOf(value)); }
        catch (IllegalArgumentException _) { return Optional.empty(); }
    }

    private static boolean isIdentifier(String value) {
        return value != null && IDENTIFIER.matcher(value).matches();
    }
}
