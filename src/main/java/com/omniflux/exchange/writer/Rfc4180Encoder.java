package com.omniflux.exchange.writer;

import java.io.IOException;
import java.io.Writer;

/**
 * Export-only RFC 4180 encoder (spec §6.1) — it emits, it never interprets.
 * Not Apache Commons CSV: under {@code QuoteMode.MINIMAL} Commons force-quotes
 * an empty token only when it is FIRST in the record, so an empty third field
 * would be emitted unquoted and become indistinguishable from NULL,
 * destroying the dialect v1 grammar the round-trip guarantee rests on.
 * <p>
 * Grammar (dialect v1, spec §6.5):
 * <pre>
 *   SQL NULL      unquoted empty field
 *   empty string  "" (quoted empty)
 *   otherwise     quoted only if the value contains , " CR or LF;
 *                 embedded quotes doubled
 * </pre>
 * Line terminator is CRLF per the RFC — inside quoted fields a bare LF is
 * data and is written through unchanged.
 */
public final class Rfc4180Encoder {

    private Rfc4180Encoder() { }

    /** Writes one record (header or data) — {@code null} cell means SQL NULL.
     *  Every cell, header names included, passes the dialect's neutralization
     *  and the same quoting decision. */
    public static void writeRecord(Writer out, String[] cells, CsvDialect dialect) throws IOException {
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) out.write(',');
            field(out, cells[i], dialect);
        }
        out.write('\r');
        out.write('\n');
    }

    private static void field(Writer out, String cell, CsvDialect dialect) throws IOException {
        if (cell == null) return;                              // NULL: unquoted empty
        String s = dialect.neutralize(cell);
        if (s.isEmpty()) {
            out.write("\"\"");                                 // empty string: quoted empty
            return;
        }
        if (s.indexOf(',') < 0 && s.indexOf('"') < 0
                && s.indexOf('\r') < 0 && s.indexOf('\n') < 0) {
            out.write(s);                                      // nothing to escape
            return;
        }
        out.write('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"') out.write("\"\"");                   // embedded quote doubled
            else out.write(c);
        }
        out.write('"');
    }
}
