package com.omniflux.exchange.writer;

import com.omniflux.exchange.job.ErrorCode;
import com.omniflux.exchange.job.ExportException;
import com.omniflux.exchange.meta.ColumnDescriptor;

import java.io.*;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

/**
 * Streaming CSV writer (spec §6.1). Fully pipelined — bytes reach storage
 * while rows are still being read. No disk, no row limit here.
 * <p>
 * The byte limits measure SOURCE VALUE bytes: the UTF-8 length of each value
 * as the database holds it, summed across the row — NOT the encoded output
 * (commas, quotes, CRLF), which belongs to {@code max-object-bytes}, and not
 * Java {@code char}s. A row of exactly {@code max-row-bytes} is legal; one
 * byte over is {@code ROW_TOO_LARGE}. A field over {@code max-field-bytes}
 * fails {@code FIELD_TOO_LARGE} even when the row as a whole is under its
 * limit, so the two guards never stand in for one another.
 */
public final class CsvRowWriter implements RowWriter {

    private final Writer out;
    private final CsvDialect dialect;
    private final long maxFieldBytes;
    private final long maxRowBytes;
    private final Set<Integer> allowedControlChars;
    private List<ColumnDescriptor> columns;
    private boolean closed;

    public CsvRowWriter(OutputStream downstream, CsvDialect dialect,
                        long maxFieldBytes, long maxRowBytes, Set<Integer> allowedControlChars) {
        // REPORT, never REPLACE: a character that cannot be represented is a
        // typed error, not a silent '?' in the export.
        var utf8 = StandardCharsets.UTF_8.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        this.out = new BufferedWriter(new OutputStreamWriter(
                new NonClosingOutputStream(downstream), utf8), 8 * 1024);
        this.dialect = dialect;
        this.maxFieldBytes = maxFieldBytes;
        this.maxRowBytes = maxRowBytes;
        this.allowedControlChars = allowedControlChars;
    }

    /** Header names run through the SAME neutralize-and-quote path as data —
     *  headers are attacker-influenced too (view aliases are just strings). */
    @Override
    public void writeHeader(List<ColumnDescriptor> columns) throws IOException {
        requireOpen();
        this.columns = List.copyOf(columns);
        String[] names = new String[this.columns.size()];
        for (int i = 0; i < names.length; i++)
            names[i] = this.columns.get(i).name();
        Rfc4180Encoder.writeRecord(out, names, dialect);
    }

    @Override
    public void writeRow(Object[] row) {
        requireOpen();
        if (columns == null)
            throw new IllegalStateException("writeHeader must be called before writeRow");
        if (row.length != columns.size())
            throw new IllegalArgumentException("row width " + row.length
                    + " does not match header width " + columns.size());
        String[] cells = new String[row.length];
        long rowBytes = 0;
        for (int i = 0; i < row.length; i++) {
            String cell = CellValueFormatter.format(columns.get(i), row[i], allowedControlChars);
            if (cell == null) {                                // SQL NULL contributes no source bytes
                cells[i] = null;
                continue;
            }
            int fieldBytes = CellValueFormatter.utf8Length(cell);
            if (fieldBytes > maxFieldBytes)
                throw new ExportException(ErrorCode.FIELD_TOO_LARGE,
                        columns.get(i).name() + ": " + fieldBytes + " > " + maxFieldBytes + " bytes");
            rowBytes += fieldBytes;
            cells[i] = cell;
        }
        if (rowBytes > maxRowBytes)
            throw new ExportException(ErrorCode.ROW_TOO_LARGE,
                    rowBytes + " > " + maxRowBytes + " bytes across " + row.length + " fields");
        try {
            Rfc4180Encoder.writeRecord(out, cells, dialect);
        } catch (IOException e) {
            throw new UncheckedIOException(e);                 // writeRow is a Consumer in the pipeline
        }
    }

    /** For CSV, completing the container is flushing it. */
    @Override
    public void finishContainer() throws IOException {
        requireOpen();
        out.flush();
    }

    /** Releases the encoder chain but never the downstream stream — that is
     *  wrapped in {@link NonClosingOutputStream} (spec §4.3). */
    @Override
    public void close() throws IOException {
        if (closed) return;
        closed = true;
        out.close();
    }

    private void requireOpen() {
        if (closed) throw new IllegalStateException("writer is closed");
    }
}
