package com.omniflux.exchange.writer;

import com.omniflux.exchange.job.ErrorCode;
import com.omniflux.exchange.job.ExportException;
import com.omniflux.exchange.meta.ColumnDescriptor;

import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Zero-disk, one-sheet XLSX writer backed by a streamed ZIP archive. */
public final class XlsxStreamWriter implements RowWriter {

    /** Product limit: keep XLSX exports below the physical one-sheet ceiling. */
    public static final int DEFAULT_MAX_DATA_ROWS = 1_000_000;
    public static final int DEFAULT_MAX_CELL_CHARS = 32_767;
    public static final int DEFAULT_COMPRESSION_LEVEL = 6;
    public static final String DEFAULT_DATE_MODE = "ISO_STRING";
    public static final String DEFAULT_ILLEGAL_CHAR_POLICY = "REJECT";

    private final ZipOutputStream zip;
    private final int maxDataRows;
    private final int maxCellChars;
    private final XMLOutputFactory xmlFactory = XMLOutputFactory.newFactory();

    private XMLStreamWriter sheet;
    private List<ColumnDescriptor> columns;
    private long dataRows;
    private boolean headerWritten;
    private boolean finished;
    private boolean closed;

    public XlsxStreamWriter(OutputStream downstream) {
        this(downstream, DEFAULT_MAX_DATA_ROWS, DEFAULT_MAX_CELL_CHARS,
                DEFAULT_COMPRESSION_LEVEL, DEFAULT_DATE_MODE, DEFAULT_ILLEGAL_CHAR_POLICY);
    }

    public XlsxStreamWriter(OutputStream downstream, int maxDataRows, int maxCellChars,
                            int compressionLevel, String dateMode, String illegalCharPolicy) {
        Objects.requireNonNull(downstream, "downstream");
        if (maxDataRows < 0) throw new IllegalArgumentException("maxDataRows must be non-negative");
        if (maxCellChars < 0) throw new IllegalArgumentException("maxCellChars must be non-negative");
        if (compressionLevel < 0 || compressionLevel > 9)
            throw new IllegalArgumentException("compressionLevel must be between 0 and 9");
        if (!DEFAULT_DATE_MODE.equalsIgnoreCase(Objects.requireNonNull(dateMode, "dateMode")))
            throw new IllegalArgumentException("unsupported XLSX date mode: " + dateMode);
        if (!DEFAULT_ILLEGAL_CHAR_POLICY.equalsIgnoreCase(Objects.requireNonNull(illegalCharPolicy,
                "illegalCharPolicy")))
            throw new IllegalArgumentException("unsupported XLSX illegal-char-policy: " + illegalCharPolicy);

        this.maxDataRows = maxDataRows;
        this.maxCellChars = maxCellChars;
        this.zip = new ZipOutputStream(new NonClosingOutputStream(downstream), StandardCharsets.UTF_8);
        this.zip.setLevel(compressionLevel);
    }

    @Override
    public void writeHeader(List<ColumnDescriptor> columns) throws IOException {
        requireWritable();
        if (headerWritten) throw new IllegalStateException("writeHeader may only be called once");
        this.columns = List.copyOf(Objects.requireNonNull(columns, "columns"));
        for (ColumnDescriptor column : this.columns) {
            Objects.requireNonNull(column, "columns cannot contain null descriptors");
            validateCell(column, column.name(), "header " + column.name());
        }

        writePart("[Content_Types].xml", OoxmlParts::contentTypes);
        writePart("_rels/.rels", OoxmlParts::packageRelationships);
        writePart("xl/workbook.xml", OoxmlParts::workbook);
        writePart("xl/_rels/workbook.xml.rels", OoxmlParts::workbookRelationships);
        writePart("xl/styles.xml", OoxmlParts::styles);
        beginSheet();
        try {
            writeRowInternal(1, headerValues());
        } catch (XMLStreamException e) {
            throw new IOException("could not write XLSX header", e);
        }
        headerWritten = true;
    }

    @Override
    public void writeRow(Object[] row) {
        requireWritable();
        if (!headerWritten) throw new IllegalStateException("writeHeader must be called before writeRow");
        Objects.requireNonNull(row, "row");
        if (row.length != columns.size())
            throw new IllegalArgumentException("row width " + row.length
                    + " does not match header width " + columns.size());
        if (dataRows >= maxDataRows)
            throw new ExportException(ErrorCode.XLSX_ROW_LIMIT,
                    dataRows + " data rows already written; limit is " + maxDataRows);

        String[] values = new String[row.length];
        for (int i = 0; i < row.length; i++)
            values[i] = formatCell(columns.get(i), row[i], columns.get(i).name());
        try {
            writeRowInternal((int) dataRows + 2, values);
            dataRows++;
        } catch (XMLStreamException e) {
            throw new UncheckedIOException(new IOException("could not write XLSX row", e));
        }
    }

    @Override
    public void finishContainer() throws IOException {
        if (finished) return;
        requireWritable();
        if (!headerWritten) throw new IllegalStateException("writeHeader must be called before finishContainer");
        try {
            sheet.writeEndElement(); // sheetData
            sheet.writeEndElement(); // worksheet
            sheet.writeEndDocument();
            sheet.flush();
            zip.closeEntry();
            zip.finish();
            zip.flush();
            finished = true;
        } catch (XMLStreamException e) {
            throw new IOException("could not finish XLSX worksheet", e);
        }
    }

    @Override
    public void close() throws IOException {
        if (closed) return;
        try {
            if (!finished && headerWritten) finishContainer();
            zip.close(); // the NonClosingOutputStream keeps the caller's stream open
        } finally {
            closed = true;
        }
    }

    private String[] headerValues() {
        var values = new String[columns.size()];
        for (int i = 0; i < columns.size(); i++) values[i] = columns.get(i).name();
        return values;
    }

    private String formatCell(ColumnDescriptor column, Object value, String fieldName) {
        if (value == null) return null;
        String formatted = CellValueFormatter.format(column, value);
        return validateCell(column, formatted, fieldName);
    }

    private String validateCell(ColumnDescriptor column, String value, String fieldName) {
        if (value == null)
            throw new IllegalArgumentException("XLSX header cannot be null: " + column.name());
        int charCount = value.codePointCount(0, value.length());
        if (charCount > maxCellChars)
            throw new ExportException(ErrorCode.FIELD_TOO_LARGE,
                    fieldName + ": " + charCount + " characters > " + maxCellChars);
        requireXml10(value, fieldName);
        return value;
    }

    private void beginSheet() throws IOException {
        putEntry("xl/worksheets/sheet1.xml");
        try {
            sheet = newXmlWriter(new NonClosingOutputStream(zip));
            sheet.writeStartDocument("UTF-8", "1.0");
            sheet.writeStartElement("worksheet");
            sheet.writeDefaultNamespace(OoxmlParts.MAIN_NS);
            sheet.writeStartElement("sheetData");
        } catch (XMLStreamException e) {
            throw new IOException("could not start XLSX worksheet", e);
        }
    }

    private void writeRowInternal(int rowNumber, String[] values) throws XMLStreamException {
        sheet.writeStartElement("row");
        sheet.writeAttribute("r", Integer.toString(rowNumber));
        for (int i = 0; i < values.length; i++) {
            String value = values[i];
            if (value == null) continue;
            sheet.writeStartElement("c");
            sheet.writeAttribute("r", ColumnRef.of(i) + rowNumber);
            sheet.writeAttribute("t", "inlineStr");
            sheet.writeStartElement("is");
            sheet.writeStartElement("t");
            if (needsXmlSpacePreserve(value))
                sheet.writeAttribute(OoxmlParts.XML_NS, "space", "preserve");
            sheet.writeCharacters(value);
            sheet.writeEndElement();
            sheet.writeEndElement();
            sheet.writeEndElement();
        }
        sheet.writeEndElement();
    }

    private void writePart(String name, XmlPartWriter part) throws IOException {
        putEntry(name);
        try (var _ = new CurrentEntry(zip)) {
            XMLStreamWriter xml = newXmlWriter(new NonClosingOutputStream(zip));
            part.write(xml);
            xml.flush();
        } catch (XMLStreamException e) {
            throw new IOException("could not write XLSX part " + name, e);
        }
    }

    /** AutoCloseable view of the CURRENT zip entry — closes the entry, never the archive. */
    private record CurrentEntry(ZipOutputStream zip) implements AutoCloseable {
        @Override public void close() throws IOException { zip.closeEntry(); }
    }

    private void putEntry(String name) throws IOException {
        var entry = new ZipEntry(name);
        entry.setMethod(ZipEntry.DEFLATED);
        zip.putNextEntry(entry);
    }

    private XMLStreamWriter newXmlWriter(OutputStream output) throws XMLStreamException {
        return xmlFactory.createXMLStreamWriter(output, StandardCharsets.UTF_8.name());
    }

    private void requireWritable() {
        if (closed) throw new IllegalStateException("writer is closed");
        if (finished) throw new IllegalStateException("writer is finished");
    }

    private static void requireXml10(String value, String fieldName) {
        for (int codePoint : value.codePoints().toArray()) {
            boolean legal = codePoint == 0x9 || codePoint == 0xA || codePoint == 0xD
                    || codePoint >= 0x20 && codePoint <= 0xD7FF
                    || codePoint >= 0xE000 && codePoint <= 0xFFFD
                    || codePoint >= 0x10000 && codePoint <= 0x10FFFF;
            if (!legal)
                throw new ExportException(ErrorCode.CHARACTER_NOT_REPRESENTABLE,
                        fieldName + " contains XML 1.0 character U+%04X".formatted(codePoint));
        }
    }

    private static boolean needsXmlSpacePreserve(String value) {
        return !value.isEmpty() && (isXmlWhitespace(value.charAt(0))
                || isXmlWhitespace(value.charAt(value.length() - 1)));
    }

    private static boolean isXmlWhitespace(char value) {
        return value == ' ' || value == '\t' || value == '\r' || value == '\n';
    }

    @FunctionalInterface
    private interface XmlPartWriter {
        void write(XMLStreamWriter xml) throws XMLStreamException;
    }
}
