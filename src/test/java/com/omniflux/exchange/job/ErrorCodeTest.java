package com.omniflux.exchange.job;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Map;

import static com.omniflux.exchange.job.ErrorCode.*;
import static java.util.Map.entry;
import static java.util.function.Function.identity;
import static java.util.stream.Collectors.toMap;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class ErrorCodeTest {

    @Test void everyCodeDeclaresItsRetryClass() {
        // Classifying by message substring was a defect: a wording change silently
        // turns a terminal failure into an infinite retry loop.
        for (ErrorCode c : ErrorCode.values()) assertNotNull(c.errorClass());
    }

    @Test void deterministicCodesAreNeverRetried() {
        assertEquals(ErrorClass.DETERMINISTIC, ErrorCode.ROW_TOO_LARGE.errorClass());
        assertEquals(ErrorClass.DETERMINISTIC, ErrorCode.UNKNOWN_COLUMN.errorClass());
        assertEquals(ErrorClass.DETERMINISTIC, ErrorCode.XLSX_ROW_LIMIT.errorClass());
        assertEquals(ErrorClass.DETERMINISTIC, ErrorCode.UPSTREAM_CLIENT_ERROR.errorClass());
    }

    @Test void transientCodesAreRetried() {
        assertEquals(ErrorClass.TRANSIENT, ErrorCode.UPSTREAM_UNAVAILABLE.errorClass());
        assertEquals(ErrorClass.TRANSIENT, ErrorCode.STORAGE_UNAVAILABLE.errorClass());
    }

    @Test void everyCodeMapsToAnHttpStatus() {
        // REGRESSION. Spot-checking four codes lets the others
        // eighteen carry a wrong status while a test called "every" passes.
        // Assert the WHOLE map, so adding a code forces a deliberate choice.
        assertEquals(Map.ofEntries(
                entry(UNKNOWN_RELATION, 400),  entry(UNKNOWN_COLUMN, 400),
                entry(DUPLICATE_COLUMN, 400),  entry(RELATION_NOT_ALLOWED, 400),
                entry(UNSUPPORTED_PRIMARY_KEY, 400), entry(UNSUPPORTED_COLUMN_TYPE, 400),
                entry(TOO_MANY_COLUMNS, 400),  entry(TOO_MANY_IN_VALUES, 400),
                entry(FIELD_TOO_LARGE, 400),   entry(ROW_TOO_LARGE, 400),
                entry(EXPORT_TOO_LARGE, 400),  entry(XLSX_ROW_LIMIT, 400),
                entry(CHARACTER_NOT_REPRESENTABLE, 400),
                entry(PRINCIPAL_UNRESOLVED, 401), entry(CANCELLED, 409), entry(JOB_NOT_FOUND, 404),
                entry(IDEMPOTENCY_KEY_CONFLICT, 409), entry(QUEUE_FULL, 429),
                entry(VALIDATION_ERROR, 400), entry(INTERNAL_ERROR, 500),
                entry(UPSTREAM_CLIENT_ERROR, 502), entry(QUERY_TIMEOUT, 504),
                entry(UPSTREAM_UNAVAILABLE, 503), entry(STORAGE_UNAVAILABLE, 503),
                entry(LEASE_LOST, 500)),
            Arrays.stream(ErrorCode.values())
                  .collect(toMap(identity(), ErrorCode::httpStatus)));
    }
}
