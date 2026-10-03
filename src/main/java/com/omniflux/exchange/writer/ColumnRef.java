package com.omniflux.exchange.writer;

/** Converts a zero-based column number to the A1 column reference used by XLSX. */
public final class ColumnRef {

    private ColumnRef() { }

    /**
     * Returns the Excel column name for a zero-based column number.
     *
     * @param zeroBasedColumn zero for {@code A}, 25 for {@code Z}, and 26 for
     *                        {@code AA}
     * @throws IllegalArgumentException when the column is negative
     */
    public static String of(int zeroBasedColumn) {
        return fromZeroBased(zeroBasedColumn);
    }

    public static String fromZeroBased(int zeroBasedColumn) {
        if (zeroBasedColumn < 0)
            throw new IllegalArgumentException("column must be zero-based and non-negative: " + zeroBasedColumn);

        long value = (long) zeroBasedColumn + 1;
        var result = new StringBuilder();
        while (value > 0) {
            int remainder = (int) ((value - 1) % 26);
            result.append((char) ('A' + remainder));
            value = (value - 1) / 26;
        }
        return result.reverse().toString();
    }

    /** Returns the Excel column name for a one-based column number. */
    public static String fromOneBased(int oneBasedColumn) {
        if (oneBasedColumn < 1)
            throw new IllegalArgumentException("column must be one-based and positive: " + oneBasedColumn);
        return fromZeroBased(oneBasedColumn - 1);
    }
}
