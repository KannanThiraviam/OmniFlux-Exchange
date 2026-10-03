# ADR-0005: Governed REST source, with R2DBC as a direct adapter

- **Status:** Accepted
- **Detail:** [decision log](../architecture/decision-log.md)

## Context

In the target organisation, the Data API is the governed access path to the
source data (Db2 behind it): it validates input and applies row filtering. Some
controlled deployments, and the benchmarks, also need a direct PostgreSQL path.

## Decision

- A `RowSource` port with two adapters: `RestRowSource`, which pages the Data
  API with keyset query parameters and decodes JSON incrementally, and
  `R2dbcRowSource`, which runs reactive SQL with `octet_length` guards.
- REST is the default. The service authenticates to the Data API with its own
  machine-to-machine credential (client credentials), because a queued job may
  run minutes later on another pod, after the user's token has expired.
- JDBC is used only for Flyway at startup, never on the streaming path, where
  blocking reads would break back-pressure.
- Locally, PostgREST stands in for the Data API, so the demo exercises the
  production adapter.

## Consequences

- New sources (Db2 directly, Snowflake, another API) are new adapters.
- Relations need an integer unique key for keyset paging; views declare theirs
  in `security.view-keys`.
- REST adds a JSON hop per page. Compare `sourceReadMillis` and
  `cosUploadMillis` in proof evidence before tuning.
