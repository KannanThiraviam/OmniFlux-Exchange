package com.omniflux.exchange.web;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Keeps the small static dashboard aligned with the API capabilities it is
 * meant to demonstrate. The UI is deliberately framework-free, so a focused
 * asset contract gives us a fast regression check without inventing a second
 * frontend build system.
 */
class UiAssetContractTest {
    @Test
    void dashboardContainsTheOperatorActionsAndPagingControls() throws IOException {
        String index = asset("static/index.html");
        String app = asset("static/app.js");

        // 2026-09-20 data-first restructure: the standalone Export form merged
        // into the Data view, so the filtered-export control is the one primary
        // button (id btn-export) and CSV/XLSX guidance moved to the estimate line.
        assertTrue(index.contains("id=\"btn-prev-page\""));
        assertTrue(index.contains("id=\"btn-next-page\""));
        assertTrue(index.contains("id=\"btn-prev-jobs\""));
        assertTrue(index.contains("id=\"btn-next-jobs\""));
        assertTrue(index.contains("id=\"filter-column\""));
        assertTrue(index.contains("id=\"filter-operator\""));
        assertTrue(index.contains("id=\"btn-export\""));
        assertTrue(index.contains("id=\"estimate-line\""));
        assertFalse(index.contains("id=\"sel-csv-mode\""));
        assertTrue(app.contains("/download"));
        assertTrue(app.contains("/retry"));
        assertTrue(app.contains("setTimeout"));
        assertTrue(app.contains("pollDelay"));
        assertTrue(app.contains("byteCount"));
        assertTrue(app.contains("cacheHitRate"));
        assertTrue(app.contains("csvMode: 'SPREADSHEET_SAFE'"));
        assertTrue(app.contains("filters: state.filters"));
        assertTrue(app.contains("/api/meta/relations/"));
    }

    private static String asset(String name) throws IOException {
        try (var stream = UiAssetContractTest.class.getClassLoader().getResourceAsStream(name)) {
            if (stream == null) throw new IOException("missing test asset: " + name);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
