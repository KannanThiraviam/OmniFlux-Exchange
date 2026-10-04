# OmniFlux Exchange documentation

Stream large exports. Keep resource use bounded. Deliver files through object storage.

OmniFlux Exchange is a Java 25 / Spring Boot service for exporting allowlisted data to CSV and XLSX. Requests become durable jobs. Workers stream the result into S3-compatible storage, and callers download through signed URLs.

## Start here

| I want to... | Read |
|---|---|
| See the technologies and tools | [Technology and tools](TECH_STACK.md) |
| Inspect backend data and files | [Local data and storage](LOCAL_DATA.md) |
| Learn the terminology | [Key concepts](concepts.md) |
| Run an export locally | [Getting started](GETTING_STARTED.md) |
| Understand the problem and solution | [Overview](architecture/overview.md) |
| See components and deployment | [Architecture](architecture/architecture.md) |
| Follow the execution and failure paths | [Low-level design](architecture/low-level-design.md) |
| Call the service | [API reference](API.md) |
| Deploy and troubleshoot | [Operations](OPERATIONS.md) |

## First-time user path

1. **[Getting started](GETTING_STARTED.md)**: install the required tools, clone, start, and confirm the ready message.
2. **[Dashboard walkthrough](GETTING_STARTED.md#3-open-the-application)**: preview three customers, submit CSV/XLSX, and download the completed result.
3. **[Local data and storage](LOCAL_DATA.md)**: open endpoints, view backend JSON, query PostgreSQL, and inspect storage.
4. **[Technology and tools](TECH_STACK.md)**: understand Spring Boot, PostgreSQL, Flyway, Docker, and verification tools.

The documentation site is hosted on GitHub Pages. The runnable app is a separate local stack; `localhost` links work after you start it on your computer.

## Architecture reading path

1. **[Overview](architecture/overview.md)** - the system context, lifecycle, benefits, and trade-offs.
2. **[Architecture](architecture/architecture.md)** - component ownership, streaming, threading, and deployment.
3. **[Low-level design](architecture/low-level-design.md)** - focused sequences, state transitions, resource budgets, and recovery.
4. **[Principles and patterns](architecture/principles-and-patterns.md)** - the reasoning mapped to implementation.
5. **[Decision records](adr/README.md)** - one short document per architectural choice.

Every architecture diagram has an **Open full-size diagram** link. Use it to read the labels at their original size. The Pages site serves pre-rendered SVGs, so reading diagrams does not depend on an external Mermaid script.

## Development baseline

The service includes bounded streaming writers, a PostgreSQL job queue, leases and fencing, multipart upload, owner-scoped job actions, a local dashboard, and metrics.

Native JWT validation and distributed tracing are deferred. [Implementation status](IMPLEMENTATION_STATUS.md) records the current scope, completed correctness fixes, and remaining limitations.

## Guides and reference

| Area | Documents |
|---|---|
| Develop | [Quality gates](QUALITY_GATES.md), [documentation maintenance](DOCUMENTATION.md), [API reference](API.md) |
| Operate | [Operations](OPERATIONS.md), [threat model](THREAT_MODEL.md), [implementation status](IMPLEMENTATION_STATUS.md) |
| Design | [Data model](data-model.md), [ADRs](adr/README.md), [principles](architecture/principles-and-patterns.md) |
| Track changes | [Changelog](CHANGELOG.md) |

## Evidence and rationale

The [decision log](architecture/decision-log.md) and [proof evidence](evidence/) preserve earlier rationale and measurements. Current architecture guides and implementation status describe the shipped behavior.
