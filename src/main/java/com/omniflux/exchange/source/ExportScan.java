package com.omniflux.exchange.source;

import com.omniflux.exchange.meta.ColumnDescriptor;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * ONE prepared scan. Three separate methods let high-water be captured twice,
 * and let an adapter prepend the PK to values while the header carried only the
 * requested columns — a header/data width mismatch on every export.
 *
 * <p>highWater is null for an empty relation. rowCount is populated by XLSX
 * sources after an exact filtered count and stays null for CSV scans, where
 * counting would add cost without affecting correctness.
 */
public record ExportScan(List<ColumnDescriptor> columns, Long highWater,
                         Flux<RowRecord> records, Long rowCount) {

    public ExportScan(List<ColumnDescriptor> columns, Long highWater, Flux<RowRecord> records) {
        this(columns, highWater, records, null);
    }

    public ExportScan {
        columns = List.copyOf(columns == null ? List.of() : columns);
                if (records == null) throw new IllegalArgumentException("records is required");
            }

    /**
     * Wraps a Flux so a second subscription throws IllegalStateException
     * instead of silently re-running the whole export. A cold Flux stored in a
     * record can otherwise be subscribed twice — different bytes, doubled
     * load, no error.
     */
    public static <T> Flux<T> singleSubscription(Flux<T> source) {
        AtomicBoolean subscribed = new AtomicBoolean(false);
        return Flux.defer(() -> {
            if (!subscribed.compareAndSet(false, true)) {
                throw new IllegalStateException("this scan's records() has already been subscribed to");
            }
            return source;
        });
    }
}
