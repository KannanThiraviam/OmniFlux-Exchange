# Technology and tools

This page explains what the project uses and where each tool fits. Versions below describe the checked-in configuration, rather than the latest upstream release. [pom.xml](../pom.xml), [docker-compose.yml](../docker-compose.yml), and [docs-requirements.txt](../docs-requirements.txt) are the version sources.

## Application stack

| Technology | What it is | How OmniFlux uses it |
|---|---|---|
| Java 25 | The language and runtime for the backend. | API, job coordination, source adapters, and streaming writers. Docker supplies Java for the local walkthrough. |
| Spring Boot 4.1.1 | Application configuration, dependency wiring, and startup framework. | Builds the running service and selects demo/production configuration profiles. |
| Spring WebFlux and Reactor | Reactive HTTP and stream-processing libraries. | Serve requests and pass bounded demand through database/network operations. Blocking writers use a dedicated executor. |
| R2DBC PostgreSQL driver | A reactive SQL client. | Read the job store and, for the direct source adapter, PostgreSQL data without JDBC reads on the export path. |
| PostgreSQL 18.6 | A relational database. | Store durable jobs, attempts, and admission state; also hold sample source tables locally. |
| Flyway 13.5.0 | Versioned database migration tool. | Update the schema during application startup, through a separate JDBC connection. See [Flyway explained](#flyway-explained). |
| AWS SDK for Java 2.55.0 | Client libraries for AWS-compatible APIs. | Send S3 multipart uploads and sign temporary download URLs; the storage service can be SeaweedFS, IBM COS, or AWS S3. |
| HTML, CSS, and plain JavaScript | Browser presentation and interaction. | Dashboard Data, Jobs, and System tabs. There is no Node.js build step for the dashboard. |
| Project CSV and OOXML writers | Row-by-row output encoders. | Produce CSV and single-sheet XLSX directly into an upload. Apache POI reads XLSX only in tests. |

**JDBC** is a traditional Java database interface used here by Flyway at startup. **R2DBC** is the reactive interface used by runtime database operations. They are two clients of PostgreSQL with different jobs.

## Local services

Docker Compose starts these from one repository configuration:

| Compose service | Tool | Purpose |
|---|---|---|
| `app` | OmniFlux Exchange | Dashboard, API, queue polling, and export execution. |
| `postgres` | PostgreSQL 18.6 | Database persistence and local sample data. |
| `postgrest` | PostgREST 12.2.3 | Expose PostgreSQL data as a REST API so the REST source adapter can run locally. |
| `seaweedfs` | SeaweedFS 4.44 | Local S3-compatible object storage. The master diagnostics and S3 API have separate ports. |
| `createbucket` | AWS CLI 2.27.41 | One-time bucket and lifecycle setup. A successful exit is expected. |

A **container** runs a service with its own packaged dependencies. **Compose** starts the containers together, connects them, and maps their ports to your computer. A **volume** keeps database/object data when containers stop. See [Local data and storage](LOCAL_DATA.md) for the addresses and inspection steps.

## Flyway explained

A database **schema** is the definition of its tables, columns, indexes, and constraints. A **migration** is a saved change to that definition. Flyway applies pending versioned migrations in order and records migration history and checksums, allowing later startup validation to detect changes to applied scripts. See [Flyway's versioned migration reference](https://documentation.red-gate.com/fd/versioned-migrations-273973333.html).

In this repository:

- SQL migration files live in [src/main/resources/db/migration](../src/main/resources/db/migration/), currently V1 through V7.
- A name such as `V6__job_attempt_history.sql` identifies version 6 and describes the change. Use the actual checked-in filenames when inspecting a migration.
- Spring Boot runs Flyway during application startup. No separate Flyway CLI installation or manual migration command is needed for the Docker walkthrough.
- `flyway_schema_history` records migration state; it is separate from the business/job tables.
- On the local Compose database, `init-db/01-schema.sql` creates an initial schema. The configured Flyway baseline marks version 1 as that starting point and applies later pending migrations. An empty application-managed database runs V1 onward.
- Add a new migration for a schema change. Editing an already-applied version can cause checksum validation to fail on later startup.

Read [Data model](data-model.md#flyway-and-startup-migrations) for the startup sequence, migration map, and inspection query. Demo tables currently live in V1/V2; moving them out remains open work.

## Development and verification tools

| Tool | What it does | Do I install it for a first run? |
|---|---|---|
| Git | Clone the repository and track source changes. | Yes. |
| Docker and Compose v2 | Run the local services. | Yes. |
| PowerShell 7 | Run Windows setup and quality scripts. | Yes on Windows. |
| OpenSSL, curl, Python 3 | Generate credentials and check API responses in Unix scripts. | Yes for the Unix setup path. |
| Maven Wrapper | Download the configured Maven version (3.9.16) and run Java build commands. | Already checked in; host-side builds also need JDK 25. |
| JUnit, Reactor Test, Testcontainers | Assert behavior and launch suite-owned database/storage containers. | Resolved by Maven; Docker is required to run the suite. |
| Checkstyle, PMD, JaCoCo | Check Java style, inspect unused code, and measure test coverage. | Maven-managed; see [Quality gates](QUALITY_GATES.md). |
| psql | PostgreSQL's command-line client for table/query inspection. | Already in the database container. |
| SpringDoc, OpenAPI, Swagger UI | Describe API requests/responses and provide a browser request interface. | Included; Swagger is enabled in the local demo. |
| Actuator, Micrometer, Prometheus format | Expose health and metrics for diagnosis/monitoring. | Included endpoints; an external collector is a deployment choice. |
| OpenShift manifests | Describe a production deployment template. | Not needed for the local walkthrough. |

**Testcontainers** starts temporary dependencies for the Java test suite; it does not reuse the local Compose services. **JaCoCo** measures which code the tests execute. Passing tests/coverage do not establish that every concurrency boundary is correct; current gaps are recorded in [Implementation status](IMPLEMENTATION_STATUS.md).

## Documentation and automation

Material for MkDocs 9.6.22 builds this searchable documentation site. Mermaid describes diagrams; committed SVGs make labels predictable and provide full-size viewing. Python builds the docs, and `uv` manages the checkout-local documentation environment in the maintenance guide. Node.js is needed only when regenerating diagrams or running repository JavaScript syntax checks.

GitHub Actions runs two remote workflows after a push to `main`: application verification and documentation publishing. Local Git hooks run on the developer's computer and perform the smaller checks described in [Quality gates](QUALITY_GATES.md#what-runs-where). Pages hosts documentation; it does not host the running OmniFlux backend.

For the first local run, follow [Getting started](GETTING_STARTED.md). For tool maintenance, use [Quality gates](QUALITY_GATES.md) and [Documentation maintenance](DOCUMENTATION.md).
