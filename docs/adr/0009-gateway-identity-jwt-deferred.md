# ADR-0009: Gateway-supplied identity in v1; JWT validation deferred

- **Status:** Accepted for v1, to be superseded by native JWT validation
- **Detail:** [decision log](../architecture/decision-log.md); [threat model](../THREAT_MODEL.md); [security policy](../../SECURITY.md)

## Context

The organisation's gateway already authenticates users. Building a JWT
resource server needs issuer, JWKS, audience, and claim-mapping decisions that
were not available for v1. Accepting `auth-mode=JWT` without validating
anything would be worse than having no JWT mode at all.

## Decision

- `HEADER` mode: the gateway authenticates the caller, strips inbound
  `X-Auth-*` headers, and sets `X-Auth-Issuer`, `X-Auth-Subject`,
  `X-Auth-Tenant`, `X-Auth-Roles`, and `X-Auth-Authz-Version`.
  `HeaderPrincipalProvider` validates presence, uniqueness, length, and the
  absence of control characters.
- `DISABLED` mode with a fixed principal is for local demos only.
- Both modes require the explicit opt-in `security.allow-non-jwt-auth=true`.
  `auth-mode=JWT` refuses to start.
- The deployment's NetworkPolicy admits ingress only from the gateway
  namespace; the Service is ClusterIP with no Route.
- Ownership is the `(issuer, subject, tenant)` principal key; every job query
  is scoped to it. Roles are kept out of the key, so a role change does not
  change ownership.

## Consequences

- Security depends on the gateway contract and on network isolation. Any path
  to the pod that bypasses the gateway allows impersonation.
- Before the service is ever exposed directly, implement Spring Security
  OAuth2 resource-server validation and keep `HEADER` as an explicit mode.
