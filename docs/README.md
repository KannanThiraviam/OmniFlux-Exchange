# OmniFlux Exchange documentation

Start with the [documentation home](index.md) for the reading path, or browse the [published documentation site](https://kannanthiraviam.github.io/OmniFlux-Exchange/). The [project README](../README.md) contains the quickstart.

## Understand the system

| Document | What it answers |
|---|---|
| [Key concepts](concepts.md) | Plain-language definitions for new readers |
| [Overview](architecture/overview.md) | What problem this solves, the benefits, C4-style diagrams, the export lifecycle, and the questions people ask (files vs object storage, SeaweedFS vs MinIO, why not Kafka) |
| [Principles and patterns](architecture/principles-and-patterns.md) | First principles and named patterns (cloud, distributed, GoF, hexagonal, SOLID), each mapped to the classes that implement it |
| [Architecture](architecture/architecture.md) | Components, threading model, back-pressure, resource containment, deployment, technology choices |
| [Low-level design](architecture/low-level-design.md) | Full export sequence, thread ownership, job state machine, lease and fencing, byte budget, identifier safety, cache, failure matrix |
| [ADRs](adr/README.md) | Nine short decision records |
| [Data model](data-model.md) | ER diagram and the V1-V7 migrations |
| [Decision log](architecture/decision-log.md) | The full append-only rationale, including reversals |

## Build, run, and operate

| Document | Purpose |
|---|---|
| [Getting started](GETTING_STARTED.md) | One-command local stack, first export, inspection, tests, teardown |
| [API reference](API.md) and [`api/openapi.yaml`](../api/openapi.yaml) | Routes, request conventions, error codes |
| [Operations runbook](OPERATIONS.md) | Health, metrics, queue/gate diagnosis, retries, drain, multipart cleanup |
| [Threat model](THREAT_MODEL.md) and [security policy](../SECURITY.md) | Trust boundaries, auth limitations, deployment controls, reporting |
| [Quality gates](QUALITY_GATES.md) | Hooks, CI, and local verification |
| [Implementation status](IMPLEMENTATION_STATUS.md) | What is implemented, what is deferred |
| [Changelog](CHANGELOG.md); [Contributing](../CONTRIBUTING.md) | History and workflow |

## Evidence

[Proof evidence](evidence/) preserves timestamped measurement runs. Read the current architecture and implementation status alongside historical measurements. Agent specifications, execution plans, and review working files are kept local.
