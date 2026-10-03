package com.omniflux.exchange.security;

/**
 * WHO OWNS A JOB — exactly issuer + subject + tenant, and nothing else.
 * <p>
 * Roles are deliberately NOT here. A record's equals() covers every component,
 * so a PrincipalKey carrying roles changes identity the moment an
 * administrator grants a role: the user stops owning their own running jobs,
 * every `GET /api/jobs/{id}` starts returning 404, and no error is logged
 * anywhere because 404-for-foreign-jobs is the designed behavior.
 * <p>
 * A subject is unique only WITHIN an issuer, and a nullable component silently
 * breaks the idempotency unique index because NULLs do not collide in
 * PostgreSQL. All three are non-null by construction.
 */
public record PrincipalKey(String issuer, String subject, String tenant) {
    public PrincipalKey {
        if (issuer == null || subject == null || tenant == null)
            throw new IllegalArgumentException("PrincipalKey components must be non-null");
    }
}
