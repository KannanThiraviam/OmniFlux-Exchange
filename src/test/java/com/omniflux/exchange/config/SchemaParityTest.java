package com.omniflux.exchange.config;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * init-db/01-schema.sql and V1__schema.sql are the same schema told twice:
 * once for the compose database's first boot (so PostgREST sees relations
 * before it caches the schema) and once for Flyway (so an empty database
 * reaches the same state). Byte identity is asserted — not "equivalent" —
 * because every kind of drift that matters is a byte: a column renamed in
 * one file, a constraint dropped in the other, a comment that explains
 * different behavior. The local demo and CI would silently test different
 * schemas, and no test of the engine would notice.
 */
class SchemaParityTest {

    @Test void initDbAndFlywayV1AreByteIdentical() throws Exception {
        // Two consumers, one authoritative text. Drift means the local demo and
        // CI silently test different schemas.
        assertEquals(sha256(Path.of("init-db/01-schema.sql")),
                     sha256(Path.of("src/main/resources/db/migration/V1__schema.sql")));
    }

    /**
     * A digest rather than {@code assertEquals} on the contents, so a failure
     * names the two files' hashes instead of printing both 100-line files.
     * Surefire's working directory is the project base directory, so the
     * relative paths need no ceremony.
     */
    private static String sha256(Path p) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(p)));
    }
}
