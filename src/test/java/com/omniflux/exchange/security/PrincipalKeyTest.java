package com.omniflux.exchange.security;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PrincipalKeyTest {

    private static final PrincipalKey KEY = new PrincipalKey("local", "dev@local", "local");

    @Test void aRoleChangeDoesNotChangeOWNERSHIPIdentity() {
        // REGRESSION. With roles inside PrincipalKey, granting a role made the
        // user stop owning their own running jobs — and the symptom was a 404,
        // which is the DESIGNED response for a foreign job, so nothing logged.
        //
        // THE FIXTURE IS THE TEST. An earlier version shared ONE PrincipalKey
        // instance between both AuthContexts, so `before.key()` and `after.key()`
        // were the same reference and compared equal no matter what the record
        // declared — verified empirically: with roles put back INSIDE
        // PrincipalKey, that version still passed 3/3.
        //
        // Each side must construct its OWN key, so that equality is decided by
        // the record's components rather than by identity.
        var before = new AuthContext(new PrincipalKey("local", "dev@local", "local"),
                                     List.of("ANALYST"), "v1");
        var after  = new AuthContext(new PrincipalKey("local", "dev@local", "local"),
                                     List.of("ANALYST", "ADMIN"), "v2");

        assertNotSame(before.key(), after.key(), "distinct instances, or this proves nothing");
        assertEquals(before.key(), after.key());
        assertEquals(before.key().hashCode(), after.key().hashCode());
    }

    @Test void aPrincipalKeyHasEXACTLYThreeComponents() {
        // The structural half: roles cannot creep back in without failing here.
        assertEquals(3, PrincipalKey.class.getRecordComponents().length);
        assertEquals(List.of("issuer", "subject", "tenant"),
                java.util.Arrays.stream(PrincipalKey.class.getRecordComponents())
                        .map(java.lang.reflect.RecordComponent::getName).toList());
    }

    // Note what this pair does and does not establish. It fixes the SHAPE:
    // ownership is the key, entitlement is the context. It cannot establish that
    // a real entitlement change bumps the version — that is a property of
    // whatever supplies AuthContext, and it is asserted where those live:
    // Task 16 (each provider populates the version) and Task 20 (a changed
    // version invalidates a cache entry while the owner is unchanged).

    @Test void anyNullComponentIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new PrincipalKey(null, "dev@local", "local"));
        assertThrows(IllegalArgumentException.class, () -> new PrincipalKey("local", null, "local"));
        assertThrows(IllegalArgumentException.class, () -> new PrincipalKey("local", "dev@local", null));
    }

    @Test void authContextRequiresAnAuthorizationContextVersion() {
        // a null would hash to a shared bucket
        assertThrows(IllegalArgumentException.class, () -> new AuthContext(KEY, List.of("ANALYST"), null));
        assertThrows(IllegalArgumentException.class, () -> new AuthContext(null, List.of("ANALYST"), "v1"));
    }
}
