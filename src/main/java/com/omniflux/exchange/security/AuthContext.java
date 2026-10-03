package com.omniflux.exchange.security;

import java.util.List;

/**
 * WHAT THE CALLER WAS ALLOWED TO SEE, at submit time — the authorization
 * snapshot that travels with the job. Separate from PrincipalKey precisely so
 * that entitlement changes affect CACHEABILITY without affecting OWNERSHIP.
 * <p>
 * `authzContextVersion` is the value the cache fingerprint hashes, so revoking
 * a role invalidates every cached object minted under the old entitlements.
 * Its source per auth mode:
 *   DISABLED -> `security.authz-context-version` (a constant; the demo has no
 *               entitlement system, and pretending otherwise would be a lie
 *               told by a config key)
 *   HEADER   -> the gateway's `X-Auth-Authz-Version` header, required
 *   JWT      -> a token claim (v2; `auth-mode=JWT` is refused at startup in v1)
 * It is persisted to `transfer_jobs.authz_context_version` at submit and read
 * back from there by the worker, never recomputed.
 */
public record AuthContext(PrincipalKey key, List<String> roles, String authzContextVersion) {
    public AuthContext {
        if (key == null || authzContextVersion == null)
            throw new IllegalArgumentException("AuthContext key and authzContextVersion are required");
        roles = List.copyOf(roles == null ? List.of() : roles);
    }
}
