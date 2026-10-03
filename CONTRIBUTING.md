# Contributing

Thanks for improving OmniFlux Exchange. Check the current implementation and documentation before changing behavior; the architecture guides and ADRs explain design intent.

## Local setup

1. Install Docker Compose and PowerShell 7 on Windows, then start the stack with `pwsh -File scripts/up.ps1 -Build` (or use the direct Compose instructions in [Getting started](docs/GETTING_STARTED.md)).
2. For host-side Java work, use the checked-in wrapper and JDK 25: `./mvnw -B verify` or `./mvnw.cmd -B verify`.
3. Read [quality gates](docs/QUALITY_GATES.md) for hook installation, CI, and repository checks. Run relevant gates before requesting review.

## Change expectations

- Keep source access allowlisted and filter values parameterized.
- Preserve bounded demand, global admission, lease renewal, and claim-token fencing when touching export or job code.
- Add/update tests for changed behavior and run them with the documented service prerequisites.
- Update the relevant API, architecture, operations, or security docs when behavior/configuration changes. Keep Mermaid diagrams aligned with actual routes and configuration.
- Add a forward Flyway migration; do not edit already-applied migrations to change existing installations.
- Do not commit `.env`, credentials, production data, generated build output, agent specifications/plans/reviews, raw review artifacts, or presigned URLs.
- Keep changes focused and record verification performed and known limitations in the commit or relevant review document.

## Git workflow

This repository uses local `main` and remote `origin/main`. Commit reviewed changes directly to `main` and push with `git push origin main`. Run the local quality gates before pushing; GitHub Actions verifies every push to `main`. Record purpose, behavioral impact, gate results, and configuration/migration notes. The initial publication contains one root commit with the complete project.
