# ADR-0007: WebFlux, with blocking writers on a dedicated executor

- **Status:** Accepted
- **Detail:** [threads and scheduling](../architecture/architecture.md#threads-and-scheduling)

## Context

The service mostly waits on I/O: database, Data API, object storage, and many
clients polling job status. The CSV and XLSX writers are naturally blocking
`OutputStream` code, and that blocking is harmless for memory but harmful on an
event loop.

## Decision

- Spring WebFlux only. The Maven enforcer bans `spring-boot-starter-web`,
  because with both stacks present Boot silently configures MVC.
- R2DBC and `WebClient` for non-blocking I/O.
- Writers run on a dedicated, bounded `ThreadPoolExecutor` (an
  `ArrayBlockingQueue`; tasks over capacity are rejected), never on Netty
  threads or the unbounded `boundedElastic` queue.

## Consequences

- A few event-loop threads serve many concurrent requests.
- Thread placement and bounded demand need their own tests (`DemandProbeTest`,
  executor-placement tests). BlockHound cannot yet instrument the Java 25
  runtime.
- Contributors must not introduce blocking calls on request handlers.
