package com.omniflux.exchange.writer;

import com.omniflux.exchange.job.ErrorCode;
import com.omniflux.exchange.job.ExportException;
import com.omniflux.exchange.meta.ColumnDescriptor;

import java.math.BigDecimal;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Set;

/**
 * Renders one cell value from its column's {@link com.omniflux.exchange.meta.LogicalType},
 * never {@code Object.toString()} — otherwise {@code byte[]} becomes
 * {@code [B@1f2e3d}, timestamps vary by Java type and zone, and numeric scale
 * is uncontrolled (spec §6.1). The same formatter feeds XLSX, so both formats
 * agree on a value's wire representation.
 * <p>
 * Plain static utility: the writer package carries no Spring dependency (D26)
 * so it can move packages later; a later task wires the configured limits in.
 */
public final class CellValueFormatter {

    private CellValueFormatter() { }

    /** {@code limits.allowed-control-chars} as shipped: tab, CR, LF. */
    private static final Set<Integer> TAB_CR_LF = Set.of((int) '\t', (int) '\r', (int) '\n');

    /*
     * A {@link java.nio.charset.CharsetEncoder} is NOT thread-safe, and export
     * threads share one formatter. Each call therefore encodes with its own
     * short-lived encoder: a shared instance produces silently wrong lengths
     * under concurrency, and a ThreadLocal would hold state on whatever pooled
     * threads the JVM reuses long after the export that created it.
     */

    /** Formats with the shipped default allowed-control-chars (tab, CR, LF). */
    public static String format(ColumnDescriptor column, Object value) {
        return format(column, value, TAB_CR_LF);
    }

    /** @param allowedControlChars code points the configuration permits inside
     *                              TEXT values (limits.allowed-control-chars).
     *  @return the cell's wire string, or {@code null} when the value is SQL
     *          NULL — the encoder renders that as an unquoted empty field. */
    public static String format(ColumnDescriptor column, Object value, Set<Integer> allowedControlChars) {
        if (value == null) return null;
        return switch (column.logicalType()) {
            case BINARY -> value instanceof byte[] bytes ? hex(bytes) : String.valueOf(value);
            case DECIMAL -> value instanceof BigDecimal decimal ? decimal.toPlainString() : String.valueOf(value);
            case TIMESTAMP -> formatTimestamp(value);
            case TEXT -> requireRepresentable(
                    value instanceof String s ? s : String.valueOf(value), allowedControlChars);
            default -> String.valueOf(value);                 // INTEGER, BOOLEAN, DATE, UUID, UNSUPPORTED
        };
    }

    /** One UTC wire representation regardless of the arriving temporal type. */
    private static String formatTimestamp(Object value) {
        if (value instanceof OffsetDateTime shifted) return shifted.toInstant().toString();
        if (value instanceof Instant instant) return instant.toString();
        return String.valueOf(value);
    }

    /**
     * The UTF-8 length of {@code s} in bytes, the quantity the byte limits are
     * defined on. Encoded with REPORT so an unencodable value fails loudly
     * instead of being measured with replacement characters.
     */
    public static int utf8Length(String s) {
        try {
            return StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(CharBuffer.wrap(s)).remaining();
        } catch (CharacterCodingException e) {
            throw new ExportException(ErrorCode.CHARACTER_NOT_REPRESENTABLE,
                    "value is not encodable as UTF-8: " + e);
        }
    }

    /** C0/C1 control characters other than the allowed set, and unpaired
     *  surrogates, have no representation the dialect can round-trip; they are
     *  rejected, not mangled. XLSX cannot represent them either, and a raw
     *  0x07 in a CSV field is not consumable data — that is the reason this
     *  rule exists now that the wire-envelope factor no longer needs it. */
    private static String requireRepresentable(String s, Set<Integer> allowed) {
        int index = 0;
        for (int cp : s.codePoints().toArray()) {
            if (Character.isSupplementaryCodePoint(cp)) {
                index += 2;                     // a valid surrogate pair, representable
            } else if (Character.isHighSurrogate((char) cp)) {
                throw notRepresentable("unpaired high surrogate at index " + index);
            } else if (Character.isLowSurrogate((char) cp)) {
                throw notRepresentable("unpaired low surrogate at index " + index);
            } else {
                if (isControl(cp) && !allowed.contains(cp)) {
                    throw notRepresentable("U+%04X".formatted(cp));
                }
                index++;
            }
        }
        return s;
    }

    /** C0 (U+0000–U+001F), DEL (U+007F) and C1 (U+0080–U+009F). */
    private static boolean isControl(int cp) {
        return cp <= 0x1F || cp == 0x7F || (cp >= 0x80 && cp <= 0x9F);
    }

    private static ExportException notRepresentable(String detail) {
        return new ExportException(ErrorCode.CHARACTER_NOT_REPRESENTABLE, detail);
    }

    /** {@code 0x0a1b}, lowercase — deterministic across locales and JVMs. */
    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2 + 2).append("0x");
        for (byte b : bytes)
            sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        return sb.toString();
    }
}
