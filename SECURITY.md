# Security policy

OmniFlux Exchange is an implementation under active development. Do not expose the local Compose or `demo` profile to an untrusted network. The v1 application does not authenticate JWT bearer tokens: `auth-mode=JWT` is explicitly rejected during startup.

## Supported identity modes

- `DISABLED` resolves a configured development principal. It does not authenticate the caller.
- `HEADER` trusts configured `X-Auth-*` headers. By default, these are `X-Auth-Issuer`, `X-Auth-Subject`, `X-Auth-Tenant`, `X-Auth-Roles`, and `X-Auth-Authz-Version`. It is safe only behind a trusted gateway that strips client-supplied identity headers, overwrites them with authenticated identity, and is the only network path to the app.
- Both non-JWT modes require `security.allow-non-jwt-auth=true`; this is an explicit operator decision, not proof of authentication.

Production forwarded-header support is enabled only in the production profile. The ingress must prevent direct pod access and overwrite forwarded headers before the application trusts them. The supplied deployment manifests are templates; review [the threat model](docs/THREAT_MODEL.md) and [operations runbook](docs/OPERATIONS.md) before adapting them.

## Data and secrets

Export jobs contain relation/column/filter details, a caller identity snapshot, client IP, status, and object metadata. Export objects are stored in the configured S3-compatible service and expire according to bucket lifecycle configuration. Presigned URLs are bearer capabilities: treat them as secrets and share only with the intended recipient. Local `.env` credentials are for throwaway development only.

Production database, Data API, and storage credentials must be supplied by the deployment's secret manager. Do not put credentials, production data, or valid presigned URLs in source control, issue reports, screenshots, or logs.

## Reporting

For a suspected vulnerability, report privately to the repository maintainers with a concise description, affected version/commit, reproduction steps, and impact. Do not publish exploitable details before coordinating a fix. This repository does not yet define a supported-version or response-time commitment.
