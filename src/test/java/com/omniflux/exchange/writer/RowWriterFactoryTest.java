package com.omniflux.exchange.writer;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RowWriterFactoryTest {
    @Test
    void createsBothStreamingFormatsAndRejectsUnknownFormats() {
        var factory = new RowWriterFactory(1024, 4096, Set.of(9, 10, 13));
        assertInstanceOf(CsvRowWriter.class, factory.create("CSV", "RAW", new ByteArrayOutputStream()));
        assertInstanceOf(XlsxStreamWriter.class, factory.create("XLSX", "RAW", new ByteArrayOutputStream()));
        assertThrows(IllegalArgumentException.class,
                () -> factory.create("JSON", "RAW", new ByteArrayOutputStream()));
    }
}
