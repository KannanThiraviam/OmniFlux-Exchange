package com.omniflux.exchange.meta;

/** The set of column types the export pipeline understands. Every relation
 *  column is mapped to exactly one of these before it can be exported. */
public enum LogicalType {
    INTEGER, DECIMAL, TEXT, BINARY, BOOLEAN, DATE, TIMESTAMP, UUID,
    /** BLOB/CLOB/LONGVARBINARY/LONGVARCHAR — rejected at validation. */
    UNSUPPORTED;

    public boolean variableWidth() { return this == TEXT || this == BINARY; }
}
