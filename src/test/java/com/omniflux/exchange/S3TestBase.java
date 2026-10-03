package com.omniflux.exchange;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * A THIN DELEGATE over Containers, exactly like PostgresTestBase.
 *
 * <p>The container image pin and bucket creation live in {@link Containers},
 * not here. Tests use the same suite-owned PostgreSQL and SeaweedFS instances.
 *
 * <p>There is exactly one PostgreSQLContainer and one SeaweedFS container in
 * the JVM, and every base and explicit registration resolves to those two.
 */
public abstract class S3TestBase {


    @DynamicPropertySource
    static void objectStore(DynamicPropertyRegistry r) {
        Containers.registerPostgres(r);
        Containers.registerObjectStore(r);
    }
}
