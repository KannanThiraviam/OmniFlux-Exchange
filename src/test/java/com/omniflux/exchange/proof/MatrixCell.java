package com.omniflux.exchange.proof;

import java.util.List;

/** One reproducible cell in the streaming scaling experiment. */
public record MatrixCell(String id, long rows, String format, String adapter,
                         String shape, int concurrency) {
    public MatrixCell {
        if (id == null || id.isBlank() || rows < 0 || concurrency < 1) {
            throw new IllegalArgumentException("invalid matrix cell");
        }
        if (format == null || !List.of("CSV", "XLSX").contains(format.toUpperCase())) {
            throw new IllegalArgumentException("format must be CSV or XLSX");
        }
        if (adapter == null || !List.of("rest", "r2dbc").contains(adapter.toLowerCase())) {
            throw new IllegalArgumentException("adapter must be rest or r2dbc");
        }
        if (shape == null || !List.of("narrow", "wide").contains(shape.toLowerCase())) {
            throw new IllegalArgumentException("shape must be narrow or wide");
        }
        format = format.toUpperCase();
        adapter = adapter.toLowerCase();
        shape = shape.toLowerCase();
    }

    public boolean expectedFailure() {
        return id.startsWith("S9-") || ("XLSX".equals(format) && rows > 1_000_000);
    }

    public static List<MatrixCell> fullMatrix() {
        return List.of(
                new MatrixCell("S1-10k", 10_000, "CSV", "r2dbc", "narrow", 1),
                new MatrixCell("S1-100k", 100_000, "CSV", "r2dbc", "narrow", 1),
                new MatrixCell("S1-1m", 1_000_000, "CSV", "r2dbc", "narrow", 1),
                new MatrixCell("S1-10m", 10_000_000, "CSV", "r2dbc", "narrow", 1),
                new MatrixCell("S2-10k", 10_000, "CSV", "rest", "narrow", 1),
                new MatrixCell("S2-100k", 100_000, "CSV", "rest", "narrow", 1),
                new MatrixCell("S2-1m", 1_000_000, "CSV", "rest", "narrow", 1),
                new MatrixCell("S2-10m", 10_000_000, "CSV", "rest", "narrow", 1),
                new MatrixCell("S3-10k", 10_000, "XLSX", "r2dbc", "narrow", 1),
                new MatrixCell("S3-100k", 100_000, "XLSX", "r2dbc", "narrow", 1),
                new MatrixCell("S3-1m", 1_000_000, "XLSX", "r2dbc", "narrow", 1),
                new MatrixCell("S4-10k", 10_000, "XLSX", "rest", "narrow", 1),
                new MatrixCell("S4-1m", 1_000_000, "XLSX", "rest", "narrow", 1),
                new MatrixCell("S5-100", 100, "CSV", "r2dbc", "wide", 1),
                new MatrixCell("S5-400", 400, "CSV", "r2dbc", "wide", 1),
                new MatrixCell("S6-100", 100, "XLSX", "r2dbc", "wide", 1),
                new MatrixCell("S7-1m", 1_000_000, "CSV", "r2dbc", "narrow", 4),
                new MatrixCell("S8-100", 100, "CSV", "r2dbc", "wide", 4),
                new MatrixCell("S9-xlsx-limit", 1_000_001, "XLSX", "r2dbc", "narrow", 1),
                new MatrixCell("S9-xlsx-limit-rest", 1_000_001, "XLSX", "rest", "narrow", 1),
                new MatrixCell("S10-10k", 10_000, "CSV", "r2dbc", "narrow", 4));
    }
}
