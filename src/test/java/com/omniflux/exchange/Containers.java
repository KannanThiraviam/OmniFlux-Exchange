package com.omniflux.exchange;

import org.springframework.test.context.DynamicPropertyRegistry;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.net.URI;

/**
 * A fresh Postgres and SeaweedFS pair shared by the test JVM. The containers are
 * discarded after the Maven run, keeping the suite independent of Compose and
 * preventing demo data from leaking into tests.
 */
public final class Containers {

    private Containers() { }

    private static final String PG_USER = "omniflux_test";
    private static final String PG_PASS = "omniflux_test_password";
    private static final String S3_ACCESS_KEY = "omniflux_test";
    private static final String S3_SECRET_KEY = "omniflux_test_password";
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("postgres:18.6"))
            .withDatabaseName("omniflux_test").withUsername(PG_USER).withPassword(PG_PASS);
    private static final GenericContainer<?> OBJECT_STORE = new GenericContainer<>(
            DockerImageName.parse("chrislusf/seaweedfs:4.44@sha256:"
                    + "e67e8c385484120b78bff47ba5f4debbca47fbd27ed1a39f016f47e8baea615b"))
            .withExposedPorts(8333, 9333)
            .withEnv("AWS_ACCESS_KEY_ID", S3_ACCESS_KEY)
            .withEnv("AWS_SECRET_ACCESS_KEY", S3_SECRET_KEY)
            .withCommand("mini", "-dir=/data")
            .waitingFor(org.testcontainers.containers.wait.strategy.Wait.forHttp("/cluster/healthz")
                    .forPort(9333));
    private static final String S3_ENDPOINT;

    static {
        POSTGRES.start();
        OBJECT_STORE.start();
        S3_ENDPOINT = "http://" + OBJECT_STORE.getHost() + ":"
                + OBJECT_STORE.getMappedPort(8333);
    }

    public static final String BUCKET = System.getProperty("omniflux.test.bucket", "omniflux");

    private static boolean bucketChecked;

    public static synchronized void registerPostgres(DynamicPropertyRegistry r) {
        normalizeJvmTimeZoneForPostgres();
        r.add("spring.r2dbc.url", () -> "r2dbc:postgresql://" + POSTGRES.getHost() + ":"
                + POSTGRES.getMappedPort(5432) + "/omniflux_test");
        r.add("spring.r2dbc.username", () -> PG_USER);
        r.add("spring.r2dbc.password", () -> PG_PASS);
        r.add("spring.flyway.url", POSTGRES::getJdbcUrl); // startup only
        r.add("spring.flyway.user", () -> PG_USER);
        r.add("spring.flyway.password", () -> PG_PASS);
        r.add("omniflux.security.allow-non-jwt-auth", () -> "true");
    }

    /**
     * The suite-owned Testcontainers PostgreSQL over JDBC, for tests that manage
     * their own schema or Flyway lifecycle instead of booting the Spring context. Same reachability
     * contract (and same one-line message) as {@link #registerPostgres}.
     */
    public static String postgresJdbcUrl() {
        normalizeJvmTimeZoneForPostgres();
        return POSTGRES.getJdbcUrl();
    }

    /** Credentials paired with {@link #postgresJdbcUrl()}. */
    public static String postgresUser()     { return PG_USER; }

    /** Credentials paired with {@link #postgresJdbcUrl()}. */
    public static String postgresPassword() { return PG_PASS; }

    /** Endpoint for tests that build their own S3 clients (contract tests). */
    public static String s3Endpoint() { return S3_ENDPOINT; }

    /** Access key paired with {@link #s3Endpoint()}. */
    public static String s3AccessKey() { return S3_ACCESS_KEY; }

    /** Secret key paired with {@link #s3Endpoint()}. */
    public static String s3SecretKey() { return S3_SECRET_KEY; }

    public static synchronized void registerObjectStore(DynamicPropertyRegistry r) {
        URI endpoint = URI.create(S3_ENDPOINT);
        ensureBucket(endpoint);
        r.add("omniflux.storage.endpoint", () -> S3_ENDPOINT);
        r.add("omniflux.storage.bucket", () -> BUCKET);
        r.add("omniflux.storage.access-key", () -> S3_ACCESS_KEY);
        r.add("omniflux.storage.secret-key", () -> S3_SECRET_KEY);
        r.add("omniflux.storage.path-style", () -> "true");
    }

    /**
     * Creates the test bucket on demand so the suite does not depend on a
     * separately started local Compose stack or one-shot setup container.
     */
    private static void ensureBucket(URI endpoint) {
        if (bucketChecked) return;
        try (S3Client s3 = S3Client.builder()
                .endpointOverride(endpoint)
                .region(Region.US_EAST_1).forcePathStyle(true)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(S3_ACCESS_KEY, S3_SECRET_KEY)))
                .build()) {
            try {
                s3.headBucket(b -> b.bucket(BUCKET));
            } catch (RuntimeException notThere) {
                s3.createBucket(b -> b.bucket(BUCKET));
            }
        }
        bucketChecked = true;
    }

    /**
     * pgjdbc advertises the JVM's default zone in the connection's startup
     * packet, and this Postgres 18.6 build's tzdata carries no legacy spellings
     * ({@code SELECT * FROM pg_timezone_names} has no Asia/Calcutta). The JDK's
     * own tzdb predates the rename and still calls IST exactly that, so every
     * JDBC connection — Flyway is the only one this service makes — dies with
     * {@code FATAL: invalid value for parameter "TimeZone"} before a single
     * query. {@code ZoneId.normalized()} cannot fix this (to the JDK the legacy
     * spelling IS canonical); the translation has to be explicit. The modern
     * spelling is the same zone by rules and offset — verified against the
     * server — so no test's view of time changes.
     */
    private static void normalizeJvmTimeZoneForPostgres() {
        com.omniflux.exchange.config.LegacyTimeZones.normalizeJvmDefault();
    }

}
