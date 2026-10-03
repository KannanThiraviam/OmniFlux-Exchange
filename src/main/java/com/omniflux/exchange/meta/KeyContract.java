package com.omniflux.exchange.meta;

/**
 * The integer primary key a relation is exported by: keyset pagination has
 * no `OFFSET`, so every exportable relation (table or view) must name exactly
 * one increasing integer column to page on. For a base table this is its
 * declared primary key; for a view it is the column named in
 * `security.view-keys` (guessing one is not acceptable — it is silent data
 * loss the moment the guess is wrong).
 */
public record KeyContract(String column) {
    public KeyContract {
        if (column == null || column.isBlank())
            throw new IllegalArgumentException("KeyContract column must not be blank");
    }
}
