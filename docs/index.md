# OmniFlux Exchange documentation

Stream large exports. Keep resource use bounded. Deliver files through object storage.

OmniFlux Exchange is a Java 25 / Spring Boot service for exporting allowlisted data to CSV and XLSX. Requests become durable jobs. Workers stream the result into S3-compatible storage, and callers download through signed URLs.

## Start here

| I want to... | Read |
|---|---|
| Learn the terminology | [Key concepts](concepts.md) |
| Run an export locally | [Getting started](GETTING_STARTED.md) |
| Understand the problem and solution | [Overview](architecture/overview.md) |
| See components and deployment | [Architecture](architecture/architecture.md) |
| Follow the execution and failure paths | [Low-level design](architecture/low-level-design.md) |
| Call the service | [API reference](API.md) |
| Deploy and troubleshoot | [Operations](OPERATIONS.md) |

## Architecture reading path

1. **[Overview](architecture/overview.md)** - the system context, lifecycle, benefits, and trade-offs.
2. **[Architecture](architecture/architecture.md)** - component ownership, streaming, threading, and deployment.
3. **[Low-level design](architecture/low-level-design.md)** - focused sequences, state transitions, resource budgets, and recovery.
4. **[Principles and patterns](architecture/principles-and-patterns.md)** - the reasoning mapped to implementation.
5. **[Decision records](adr/README.md)** - one short document per architectural choice.

Every architecture diagram has an **Open full-size diagram** link. Use it to read the labels at their original size. The Pages site serves pre-rendered SVGs, so reading diagrams does not depend on an external Mermaid script.

## Development baseline

The service includes bounded streaming writers, a PostgreSQL job queue, leases and fencing, multipart upload, owner-scoped job actions, a local dashboard, and metrics.

Native JWT validation and distributed tracing are deferred. [Implementation status](IMPLEMENTATION_STATUS.md) records the current scope and unresolved correctness gaps.

## Guides and reference

| Area | Documents |
|---|---|
| Develop | [Quality gates](QUALITY_GATES.md), [documentation maintenance](DOCUMENTATION.md), [API reference](API.md) |
| Operate | [Operations](OPERATIONS.md), [threat model](THREAT_MODEL.md), [implementation status](IMPLEMENTATION_STATUS.md) |
| Design | [Data model](data-model.md), [ADRs](adr/README.md), [principles](architecture/principles-and-patterns.md) |
| Track changes | [Changelog](CHANGELOG.md) |

## Evidence and rationale

The [decision log](architecture/decision-log.md) and [proof evidence](evidence/) preserve earlier rationale and measurements. Current architecture guides and implementation status describe the shipped behavior.
