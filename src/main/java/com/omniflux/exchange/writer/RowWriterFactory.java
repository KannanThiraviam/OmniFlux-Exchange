package com.omniflux.exchange.writer;

import java.io.OutputStream;
import java.util.Objects;
import java.util.Set;

/**
 * Creates the {@link RowWriter} for a job's format and CSV mode over an
 * {@link OutputStream} (the upload bridge). Plain constructor injection — a
 * later task wires the configured limits as beans; this package stays
 * Spring-free (D26) so it can move packages later.
 */
public final class RowWriterFactory {

    /** XLSX writer limits wired from configuration. */
    public record XlsxLimits(int maxDataRows, int maxCellChars, int compressionLevel,
                             String dateMode, String illegalCharPolicy) {
        public static XlsxLimits defaults() {
            return new XlsxLimits(XlsxStreamWriter.DEFAULT_MAX_DATA_ROWS,
                    XlsxStreamWriter.DEFAULT_MAX_CELL_CHARS,
                    XlsxStreamWriter.DEFAULT_COMPRESSION_LEVEL,
                    XlsxStreamWriter.DEFAULT_DATE_MODE,
                    XlsxStreamWriter.DEFAULT_ILLEGAL_CHAR_POLICY);
        }
    }

    private final long maxFieldBytes;
    private final long maxRowBytes;
    private final Set<Integer> allowedControlChars;
    private final XlsxLimits xlsx;

    public RowWriterFactory(long maxFieldBytes, long maxRowBytes, Set<Integer> allowedControlChars) {
        this(maxFieldBytes, maxRowBytes, allowedControlChars, XlsxLimits.defaults());
    }

    public RowWriterFactory(long maxFieldBytes, long maxRowBytes, Set<Integer> allowedControlChars,
                            XlsxLimits xlsx) {
        this.maxFieldBytes = maxFieldBytes;
        this.maxRowBytes = maxRowBytes;
        this.allowedControlChars = allowedControlChars;
        this.xlsx = Objects.requireNonNull(xlsx, "xlsx");
    }

    public RowWriter create(String format, String csvMode, OutputStream downstream) {
        if ("XLSX".equalsIgnoreCase(format)) {
            return new XlsxStreamWriter(downstream, xlsx.maxDataRows(), xlsx.maxCellChars(),
                    xlsx.compressionLevel(), xlsx.dateMode(), xlsx.illegalCharPolicy());
        }
        if (!"CSV".equalsIgnoreCase(format))
            throw new IllegalArgumentException("unsupported format: " + format);
        return new CsvRowWriter(downstream, CsvDialect.from(csvMode),
                maxFieldBytes, maxRowBytes, allowedControlChars);
    }
}
