package com.omniflux.exchange.writer;

import com.omniflux.exchange.meta.ColumnDescriptor;

import java.io.Closeable;
import java.io.IOException;
import java.util.List;

/**
 * One output container being written (spec §4.4).
 * <p>
 * {@code writeRow} does not throw {@code IOException} because the pipeline
 * calls it through {@code stream.forEach(writer::writeRow)}, whose
 * {@code Consumer} accepts no checked exceptions — IO failures surface as
 * {@link java.io.UncheckedIOException}. {@code writeHeader} and
 * {@code finishContainer} are called directly and may throw.
 */
public interface RowWriter extends Closeable {
    /** Descriptors, not names: the writer needs each column's LogicalType to
     *  format its cells. */
    void writeHeader(List<ColumnDescriptor> columns) throws IOException;

    void writeRow(Object[] row);

    /** Completes the format's container; does NOT close the downstream stream.
     *  For CSV this flushes; for XLSX it finishes the ZIP (central directory). */
    void finishContainer() throws IOException;
}
