package com.omniflux.exchange;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * A CONVENIENCE over Containers, not a second copy of it. Constructing its own
 * PostgreSQLContainer here would start a SECOND database — one for tests that
 * extend this base and another for tests that call Containers directly — and
 * they would disagree about what has been seeded.
 */
public abstract class PostgresTestBase {

    @DynamicPropertySource
    static void postgres(DynamicPropertyRegistry r) {
        Containers.registerPostgres(r);
    }
}
