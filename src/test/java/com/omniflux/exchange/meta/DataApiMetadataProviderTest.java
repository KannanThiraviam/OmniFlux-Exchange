package com.omniflux.exchange.meta;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;

class DataApiMetadataProviderTest {

    private MockWebServer server;
    private DataApiMetadataProvider provider;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        provider = new DataApiMetadataProvider(WebClient.builder()
                .baseUrl(server.url("/").toString()).build());
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    void derivesColumnsTypesAndThePrimaryKeyFromOpenApi() {
        // noinspection SpellCheckingInspection "openapi" is the OpenAPI field name.
        server.enqueue(json("""
                {"openapi":"3.0.0","components":{"schemas":{"mock_orders":{
                  "type":"object","pk":"id","required":["id"],
                  "properties":{
                    "id":{"type":"integer","format":"int64"},
                    "note":{"type":"string"},
                    "price":{"type":"number"},
                    "purchase_date":{"type":"string","format":"date"},
                    "created_at":{"type":"string","format":"date-time"}
                  }
                }}}}
                """));

        var descriptor = Objects.requireNonNull(provider.describe("mock_orders").block());
        assertEquals(LogicalType.INTEGER, descriptor.column("id").logicalType());
        assertEquals(LogicalType.TEXT, descriptor.column("note").logicalType());
        assertEquals(LogicalType.DECIMAL, descriptor.column("price").logicalType());
        assertEquals(LogicalType.DATE, descriptor.column("purchase_date").logicalType());
        assertEquals(LogicalType.TIMESTAMP, descriptor.column("created_at").logicalType());
        assertEquals("id", descriptor.keyContract().column());
        assertTrue(descriptor.column("note").nullable());
    }

    @Test
    void rejectsADataApiRelationWithoutAnIntegerPrimaryKeyAtDescribeTime() {
        server.enqueue(json("""
                {"definitions":{"mock_uuid_pk":{
                  "pk":"id","properties":{"id":{"type":"string","format":"uuid"}}
                }}}
                """));

        var ex = assertThrows(com.omniflux.exchange.job.ExportException.class,
                () -> provider.describe("mock_uuid_pk").block());
        assertEquals(com.omniflux.exchange.job.ErrorCode.UNSUPPORTED_PRIMARY_KEY, ex.code());
    }

    @Test
    void acceptsAnExplicitViewKeyWhenTheDataApiDoesNotPublishPkExtensions() {
        var hinted = new DataApiMetadataProvider(WebClient.builder()
                .baseUrl(server.url("/").toString()).build(), Map.of("mock_customers", "id"));
        server.enqueue(json("""
                {"definitions":{"mock_customers":{
                  "properties":{"id":{"type":"integer","format":"int64"},"name":{"type":"string"}}
                }}}
                """));

        var descriptor = Objects.requireNonNull(hinted.describe("mock_customers").block());
        assertEquals("id", descriptor.keyContract().column());
        assertEquals(LogicalType.INTEGER, descriptor.column("id").logicalType());
    }

    @Test
    void mapsAnUnavailableDataApiToATransientError() {
        server.enqueue(new MockResponse().setResponseCode(503));
        var ex = assertThrows(com.omniflux.exchange.job.ExportException.class,
                () -> provider.describe("mock_orders").block());
        assertEquals(com.omniflux.exchange.job.ErrorCode.UPSTREAM_UNAVAILABLE, ex.code());
        assertTrue(ex.isTransient());
    }

    private static MockResponse json(String body) {
        return new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(body);
    }
}
