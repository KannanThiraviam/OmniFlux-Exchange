package com.omniflux.exchange.meta;

/**
 * One resolved column.
 *
 * <p>The accessor is `logicalType()`, not `type()`, and `primaryKey` exists,
 * because Task 9 calls `d.column("note").logicalType()` and Task 12 calls
 * `meta.column("id").primaryKey()`. An earlier shape had neither, which would
 * have failed to compile two phases later — exactly the class of defect the
 * compile-order contract exists to prevent, arriving through an accessor NAME
 * rather than through a missing type.
 */
public record ColumnDescriptor(String name, LogicalType logicalType, boolean nullable,
                               boolean primaryKey) {

    /** Convenience for the common case: an ordinary, nullable, non-key column. */
    public static ColumnDescriptor of(String name, LogicalType logicalType) {
        return new ColumnDescriptor(name, logicalType, true, false);
    }

    /** octet_length() is defined for character and binary strings ONLY;
     *  applying it to a numeric or date column is a runtime error. */
    public boolean needsByteGuard() { return logicalType.variableWidth(); }
}
