package com.omniflux.exchange.writer;

/**
 * The two CSV dialects. {@code SPREADSHEET_SAFE} is the product default for
 * user downloads because CSV files are normally opened in spreadsheet
 * software; {@code RAW} is an explicit opt-in for exact value preservation.
 * Neutralization permanently alters values and cannot be reversed — an
 * original apostrophe is legitimate data.
 */
public enum CsvDialect {
    RAW {
        @Override
        public String neutralize(String value) {
            return value;
        }
    },
    /** Prepends a single quote to any value beginning with one of the four
     *  spreadsheet formula triggers {@code = + - @}. Applies to DATA and to
     *  HEADER NAMES alike — headers are attacker-influenced too (a view's
     *  column aliases are just strings). */
    SPREADSHEET_SAFE {
        @Override
        public String neutralize(String value) {
            if (value.isEmpty()) return value;
            char first = value.charAt(0);
            return (first == '=' || first == '+' || first == '-' || first == '@')
                    ? "'" + value : value;
        }
    };

    /** Returns the value unchanged, or its neutralized form under this
     *  dialect. Called for every header name and every formatted cell. */
    public abstract String neutralize(String value);

    /** Parses the {@code omniflux.export.csv-mode} configuration value. */
    public static CsvDialect from(String csvMode) {
        for (CsvDialect d : values())
            if (d.name().equalsIgnoreCase(csvMode)) return d;
        throw new IllegalArgumentException("unknown csv-mode: " + csvMode);
    }
}
