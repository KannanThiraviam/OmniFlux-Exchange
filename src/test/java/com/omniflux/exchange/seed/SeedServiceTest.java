package com.omniflux.exchange.seed;

import com.omniflux.exchange.TestProps;
import com.omniflux.exchange.job.ErrorCode;
import com.omniflux.exchange.job.ExportException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SeedServiceTest {
    @Test
    void seedRejectsRelationsOutsideTheConfiguredAllowlistBeforeDatabaseAccess() {
        var service = new SeedService(null, null, TestProps.defaults());

        var error = assertThrows(ExportException.class,
                () -> service.seed("pg_class", 10).block());

        assertEquals(ErrorCode.RELATION_NOT_ALLOWED, error.code());
    }
}
