package com.omniflux.exchange.source;

import java.util.Arrays;
import java.util.Objects;

/** Separates the continuation key from exported values: the PK is selected
 *  internally for keyset continuation and appears in values() only when the
 *  caller actually requested it. */
public record RowRecord(long key, Object[] values) {
    // records compare array components by reference; this is a value object,
    // so equality must look at the contents (Sonar java:S6218). getClass()
    // rather than instanceof: the record is final, and the explicit cast form
    // keeps this parseable by the Checkstyle grammar (no record patterns).
    @Override public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        RowRecord other = (RowRecord) o;
        return key == other.key && Arrays.equals(values, other.values);
    }

    @Override public int hashCode() {
        return Objects.hash(key, Arrays.hashCode(values));
    }

    @Override public String toString() {
        return "RowRecord[key=" + key + ", values=" + Arrays.toString(values) + ']';
    }
}
