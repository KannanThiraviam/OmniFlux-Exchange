# Local checks and CI quality gates

Install the repository Git hooks with PowerShell 7 (once per clone):

```powershell
pwsh -File scripts/install-hooks.ps1
```

Both `pre-commit` and `pre-push` run the same full local quality gate: repository
checks, Java unit and Docker-backed integration tests, Checkstyle, PMD, the
coverage gate, and a strict documentation build. These required local checks block
the operation on failure. Available Snyk, SonarCloud, and Dependabot checks then
run with the explicit skip policy below. GitHub Actions runs Maven
verification and a full-stack smoke on every push to `main`. It runs validation
only and contains no deployment job. OpenShift deployment remains the
responsibility of the platform pipeline. A separate documentation workflow builds
and publishes the [documentation site](https://kannanthiraviam.github.io/OmniFlux-Exchange/)
to GitHub Pages without creating a publishing branch. See
[documentation maintenance](DOCUMENTATION.md) for the diagram and site checks.

Run that same gate manually without committing or pushing:

```powershell
pwsh -File scripts/quality-gate.ps1
```

Three local hooks are installed by `scripts/install-hooks.ps1`:

- `.githooks/pre-commit` - staged whitespace and the full gate with `-Staged`.
- `.githooks/pre-push` - the full gate with `-ForPush`; agent pushes require explicit
  approval.
- `.githooks/reference-transaction` - protects trunk ref updates from agents;
  feature branch creation and updates are allowed.

Agent safety: when Git runs inside an AI-agent context (detected via
`CLAUDECODE`, `CLAUDE_PROJECT_DIR`, `CODEX_THREAD_ID`, `CODEX_CI`,
`CODEX_PROJECT_DIR`, or forced with `OFX_GIT_AGENT_CONTEXT=1`), the hooks block
the operation unless the matching approval variable is set:
`OFX_ALLOW_GIT_COMMIT=1` (an approved
commit also needs `OFX_ALLOW_GIT_REF_UPDATE=1` for the ref write itself),
`OFX_ALLOW_GIT_PUSH=1`. Manual operator commits and pushes need no approval.

## What runs where

Both hooks require PowerShell 7 (`pwsh`), Git, Java 25, Node.js, a reachable Docker Engine, and the MkDocs dependencies described in [Documentation maintenance](DOCUMENTATION.md#preview-locally). Install the documentation environment before committing. Missing prerequisites or a failed test/build stop the commit or push.

| Operation | What is verified | Required checkout state |
|---|---|---|
| `git commit` | Staged whitespace, repository checks, Maven `verify -Pcode-audit`, and strict MkDocs build, followed by available external security checks | Stage all intended changes. The gate rejects unstaged tracked changes and non-ignored untracked files so Maven tests the commit contents. Product/tooling changes must include a current documentation update. |
| `git push` | Repository checks, the same Maven suite/audits/coverage, and the same docs build, followed by available external security checks | Index and working tree must be clean, including non-ignored untracked files. Every non-deletion ref being pushed must resolve to `HEAD`; check out and push other revisions separately. |
| `pwsh -File scripts/quality-gate.ps1` | The same full gate on the current working tree | Dirty files are allowed for development verification; this does not verify a particular staged commit or pushed revision. |
| GitHub Actions | Maven verification plus a separate full-stack Compose smoke; the docs workflow builds/publishes the site | Runs on the workflow triggers below, independently of local hooks. |

There is no automatic reuse of a previous result. An ordinary push after a successful commit runs the gate again. To deliberately skip that repeated local run immediately after a verified commit:

```powershell
git commit -m "Describe the change"
git push --no-verify
```

Use the skipped push only while the committed revision is the one that just passed verification. `--no-verify` bypasses the entire local pre-push hook, including its approval guard and tests. GitHub Actions still runs after the push. This operator shortcut does not authorize an agent to commit or push.

The reference-transaction hook continues to protect agent updates to `main`/`master`; feature branches remain supported. Hook installation is local Git configuration: contributors must run the installer in each clone. The installer also makes the hooks executable on Unix. Local hooks can be bypassed, so use GitHub branch protection/rulesets to enforce required CI checks remotely.

It checks repository hygiene, common credential patterns, conflict markers,
file size/newline rules, XML/JSON and PowerShell syntax, dashboard JavaScript
syntax (`node --check`), dashboard download-origin regression checks, and local Markdown link targets. It also requires a
current docs update alongside staged production/configuration/tooling changes. Then it
runs `./mvnw -B verify -Pcode-audit` (or the Windows Maven wrapper),
including Checkstyle, the PMD unused-code audit, dependency analysis, the full
test suite, and the 85% instruction-coverage gate for the deterministic export
core. The dependency analysis report is advisory because Spring Boot starters,
service-loader drivers, and auto-configuration produce expected structural
findings. It then builds the documentation with `mkdocs build --strict`, including diagram source-hash validation. Each build writes to a fresh ignored directory under `.tmp_logs/gate-docs-*`.

The local verification script requires PowerShell 7, Git, Java 25, and Node.js
(for the dashboard JavaScript syntax check). The Maven integration tests also
require a running Docker Engine, as explained below. Maven dependencies and
container images may require network access on the first run.

## Integration tests and Testcontainers

**Testcontainers uses Docker.** It is a Java library that creates and manages
Docker containers for tests. It does not replace Docker Engine.

| Component | What it does in this repository |
|---|---|
| Docker Desktop or Docker Engine | Runs the test dependency containers. On Windows, Docker Desktop must be running in Linux containers mode. |
| Testcontainers | Starts a PostgreSQL 18.6 container and a pinned SeaweedFS 4.44 container for the test JVM, discovers their mapped ports, and manages container cleanup after the run. |
| Maven/JUnit | Runs Java unit and integration tests on the host JVM, using those dependency containers. It does not build or start the application Docker image for these tests. |
| Docker Compose | Starts the separate local application/demo stack. Maven tests do not invoke Compose or connect to that stack. |

The setup is implemented in [Containers.java](../src/test/java/com/omniflux/exchange/Containers.java).
Its static initialization starts both containers, which are shared within the
test JVM. [PostgresTestBase](../src/test/java/com/omniflux/exchange/PostgresTestBase.java)
and [S3TestBase](../src/test/java/com/omniflux/exchange/S3TestBase.java) register
their generated connection settings with Spring through `@DynamicPropertySource`.
The helper also creates the test S3 bucket. Flyway migration tests use the same
PostgreSQL container through JDBC. Test data can be shared between tests in the
same JVM; this is not a fresh database for every test method.

No `.env` file or manually supplied `OMNIFLUX_*` application variables are
required. Test credentials and endpoints are supplied by the helper. It sets
container environment variables internally; "no application environment
configuration required" does not mean the containers have no environment
variables. A standard local Docker installation needs no `DOCKER_HOST` setting;
remote or customized Docker setups may need Docker/Testcontainers configuration.

Run from the repository root with Java 25 installed:

```powershell
docker info
.\mvnw.cmd -B verify -Pcode-audit
```

On Unix, use `./mvnw -B verify -Pcode-audit`. Run `docker info` first to confirm
that the daemon is reachable. Starting the Compose stack is unnecessary. If it
is already running, it remains separate: Testcontainers uses its own containers
and assigned host ports. Without a reachable Docker Engine, container-backed
tests fail to initialize; this is not a supported way to run full verification.

To run only the regression tests for the five correctness fixes:

```powershell
.\mvnw.cmd -B "-Dtest=TransferJobTest,SchemaCatalogTest,JobWorkerTest,JobRepositoryCancellationRaceTest,R2dbcRowSourceTimeoutTest,StartupValidatorTest" test
```

This focused command does not run the complete suite or the verification-phase
audit and coverage gates. Use the full `verify -Pcode-audit` command before
releasing the change. Logs and JUnit XML results are under
`target/surefire-reports/`; coverage is under `target/site/jacoco/`.

If Docker is unreachable, start Docker Desktop/Engine and repeat `docker info`.
If image downloads fail, check the Docker registry connection and proxy settings.
If startup fails after containers launch, inspect the Maven output and test
reports for the PostgreSQL/SeaweedFS error. Testcontainers cleans up its own
containers; `docker compose down` controls only the separate demo stack.

## GitHub Actions verification

The repository uses local `main` and remote `origin/main`. GitHub Actions runs on pushes to `main` and any submitted pull requests. The
`.github/workflows/verify.yml` job runs Maven `verify -Pcode-audit`, builds the app image,
starts the full Compose stack with `.env.example`, waits for health, and runs
CSV/XLSX smoke exports. It displays Compose logs on failure and removes the
stack and volumes afterward. The workflow is the shared CI check; repository
branch protection/rulesets must be configured on GitHub if merge enforcement is
required.

For a fast repository-only diagnostic, run
`pwsh -File scripts/quality-gate.ps1 -RepositoryOnly`. It checks hygiene,
syntax, and local Markdown link targets but skips Maven. Markdown link checks
ignore external URLs and do not validate anchor fragments. The credential scan
is a pattern guard, not an exhaustive secret audit.
These checks cannot prove prose is correct. Review behavior changes against the
relevant guide.

## Optional external security checks in both hooks

Both hooks call `scripts/security-gate.ps1` after the required local gates. Contributors do not need Snyk, SonarCloud, or GitHub security-alert access to commit or push. Missing tools, missing/invalid credentials, insufficient permissions, network/service errors, or unsupported scan input produce an explicit `SKIPPED <scanner>: <reason>` message and setup guidance or a diagnostic report path. A skipped scan is not a passing security result. The final summary says that verification is incomplete whenever any external check was skipped.

| Check | When available | If it cannot run | What blocks the hook |
|---|---|---|---|
| Snyk Maven dependencies | Snyk CLI is installed and authenticated with `snyk auth` or `SNYK_TOKEN` | `SKIPPED` with authentication/service/project-setup guidance | A completed dependency scan reports vulnerabilities. |
| Snyk docs dependencies | Snyk plus the checkout-local docs Python environment, including pip, installed from `docs-requirements.lock`; scans `docs-requirements.txt` using that environment | `SKIPPED` with setup or diagnostic guidance | A completed pip scan reports vulnerabilities. |
| Snyk Code | Snyk CLI is authenticated and Code scanning is enabled for the account | `SKIPPED` with setup/diagnostic guidance | A completed scan reports findings. Documenting a false-positive review does not automatically suppress the scanner or bypass the gate. |
| SonarCloud | `SONAR_TOKEN` is set and permits analysis of the configured project | `SKIPPED` if analysis/result access is unavailable | The server quality gate fails, or its issue inventory still contains unresolved issues. The hook waits for the server result, not just a successful upload. |
| Dependabot | GitHub CLI (`gh`) is authenticated and can read this repository's security alerts | `SKIPPED` if the API or a local-version comparison cannot be completed | An open alert's vulnerable range includes a version resolved from the current Maven dependency tree or pinned docs lockfile. |

Dependabot is a GitHub alert service, not a local scanner. Its alerts describe the published repository state. The hook compares supported alerts with the current checkout; a dependency fixed locally is reported as `RESOLVED LOCALLY`, and GitHub can close the remote alert after the fix is pushed and reevaluated. Unsupported manifests or advisory range formats print `SKIPPED` rather than claiming a comparison succeeded.

`--no-verify` skips all pre-push checks, including these optional scans. Without that flag, both hooks run them every time. Reports are written to ignored `.tmp_logs/security-*` directories. Tokens are read from the user's own environment/CLI authentication; they are not committed or shared with contributors. A fork without access to the configured SonarCloud project or the upstream security alerts receives `SKIPPED` messages.

Run just the external checks manually:

```powershell
pwsh -NoProfile -File scripts/security-gate.ps1
```

Run hook and skip-policy regression tests without committing or pushing:

```powershell
pwsh -NoProfile -File scripts/test-quality-gate.ps1
pwsh -NoProfile -File scripts/test-git-hooks.ps1
pwsh -NoProfile -File scripts/test-security-gate.ps1
```

## Controlled performance comparison

`scripts/compare-performance.ps1` compares two built application JARs against
the same existing Compose database, Data API, and object store. Build an exact
committed baseline in an ignored checkout-local archive and the latest working
tree before starting. Compile the latest test sources too: the same
`StreamingRunner` verifies both application builds.
Set `-BaselineRef <commit>` when the baseline was built from an older revision;
the recorded reference must match the source used for `-BaselineJar`.

The fixture must contain consecutive `mock_customers` IDs from 1 through at
least 1,000,000. The script does not seed or change those rows. It pauses the
existing Compose application to prevent its worker from claiming benchmark
jobs, then restores it in `finally`. Run with no other export producers. Both
builds use the same Java runtime image, two CPUs, 512 MiB container limit,
320 MiB heap, 64 MiB direct-memory limit, read-only root, and 16 MiB tmpfs.
Export caching is disabled.

```powershell
pwsh -File scripts/compare-performance.ps1 `
  -BaselineJar .tmp_logs/baseline/target/omniflux-exchange-0.1.0-SNAPSHOT.jar `
  -LatestJar target/omniflux-exchange-0.1.0-SNAPSHOT.jar `
  -OutputDirectory docs/evidence/runs/<new-run-name>
pwsh -File scripts/summarize-performance.ps1 `
  -EvidenceDirectory docs/evidence/runs/<new-run-name>
```

For each adapter the block order is baseline, latest, latest, baseline. Each
fresh application warms each workload once, then measures it three times,
giving six measured samples per build and workload. Workloads are a one-million-row
CSV, a 100,000-row XLSX, and an additional four-job one-million-row R2DBC CSV case.
Every download is streamed and checked for consecutive row keys and the
worker's stored whole-object checksum. The proof also checks memory limits,
admission overlap/ceiling, OOM counters, and filesystem events.

The primary comparison is median worker `exportMillis`; end-to-end time,
sample ranges, heap and container memory are reported separately. This is a
warm-fixture comparison on a shared local host, not proof of identical
performance or production capacity. The manifest pins both JAR hashes and
the shared runtime image ID. Memory peaks are cumulative container/JVM
high-water marks, including warmups. Raw samples and summary are publishable evidence;
container logs and reused local runtime credentials stay under ignored
`.tmp_logs/`. Evidence directories must be fresh and are never overwritten.

## SonarCloud server scan

The `sonar` Maven profile uploads code, tests, and coverage to SonarCloud after
a local `verify`. It complements the local gates with the server ruleset
(bugs, smells, security hotspots, taint analysis) and the project quality gate.

One-time setup per contributor:

1. Sign in at <https://sonarcloud.io> with GitHub, open **My Account ->
   Security**, and generate a *User Token* (for example named
   `omniflux-local`).
2. Give the token to the scanner through the `SONAR_TOKEN` environment
   variable, which the scanner reads automatically:
   - one session: `$env:SONAR_TOKEN = "<token>"` (bash: `export`), or
   - persistently on Windows:
     `[Environment]::SetEnvironmentVariable('SONAR_TOKEN','<token>','User')`,
     then open a new terminal - processes started before the variable existed
     do not see it.
3. Organization and project keys already live in the `sonar` profile in
   `pom.xml` and must match SonarCloud exactly; both are case-sensitive.

Scan and upload in one step (PowerShell 7; use `./mvnw` in bash):

```powershell
.\mvnw.cmd verify sonar:sonar -Psonar
```

Results appear at
<https://sonarcloud.io/dashboard?id=KannanThiraviam_OmniFlux-Exchange> once
the server finishes processing ("ANALYSIS SUCCESSFUL"). To re-upload without
re-running tests (coverage report still under `target/`), run
`.\mvnw.cmd sonar:sonar -Psonar`.

Reading the result: the **Quality Gate judges new code only** - the delta
since the previous analysis - so pre-existing findings stay visible on the
dashboard without failing the gate ("clean as you code"). Changed files with
uncovered lines can fail `new_coverage` even when the project as a whole is
healthy; the fix is a test over the changed code, which is how the first
bug-fix pass ended up adding lifecycle tests for the three scheduler classes.

Troubleshooting:

- `Organization key '...' does not exist.` - the organization or project key
  spelling/casing does not match; copy both from the SonarCloud project page,
  not from memory.
- `Not authorized or project not found` - the token is missing or revoked, or
  the scan was launched while the previous report was still processing; wait
  for the dashboard to settle and retry.
- SonarCloud automatic analysis stays off for this project: it only attaches
  to supported CI builds; all analysis comes from this profile.

## Dependency vulnerability scanning (OWASP dependency-check and Snyk)

Snyk dependency checks run in both hooks when available, as described above.
OWASP dependency-check remains a separate manual scan: it needs a usable NVD
feed/API configuration and is not silently treated as passing when that update fails.

- OWASP dependency-check is the local, explicit command:
  `./mvnw -B -Psecurity-scan verify` (use `.\mvnw.cmd` on Windows). It fails
  at the configured CVSS threshold of 7 and writes
  `target/dependency-check-report.html`. Reviewed suppressions are maintained
  in `config/dependency-check-suppressions.xml`; the first run downloads the
  NVD database and is slow.
- Snyk is used for triage against the same tree (`snyk auth` once, then
  `snyk test`). Findings are fixed by raising the `dependencyManagement`
  version floors in `pom.xml` - as already done for `scram` and
  `jackson-databind` - and confirmed by rerunning both scanners.

## Static analysis of source (Snyk Code)

`snyk code test` (SAST over main and test sources) runs in both hooks when Snyk is available. The following existing reviews explain the findings; unresolved scanner findings still block the gate until formally resolved or narrowly reviewed in Snyk. Current findings and their reviewed dispositions - re-verify each if
the surrounding design changes:

- **Path Traversal, StreamingRunner:183 (test harness)** - false positive:
  `verifyXlsx` reads the downloaded workbook strictly in memory (StAX over
  the stream); no file is extracted and no filesystem path is constructed.
  The "remote input" is the runner's own presigned download of the export it
  just verified.
- **Open Redirect, dashboard download handler** - hardened in code: before
  navigation the dashboard reads the storage configuration from the same-origin
  `/api/system/resources` endpoint. It accepts only absolute HTTP(S) URLs without
  embedded credentials, with the exact configured public storage origin or,
  when virtual-hosted storage is enabled, its configured bucket subdomain.
  The signed path and query remain intact. Different hosts, ports, and schemes
  fail closed, as does unavailable configuration. `scripts/test-download-target.mjs`
  executes the dashboard helper against allowed downloads and malicious targets
  in both hooks. Any residual scanner finding still requires a scoped review;
  the gate does not suppress it automatically.
- **Spring CSRF, Export/NaiveExport/Seed controllers** - deployment-dependent:
  the app does not use Spring Security and delegates authentication to the
  gateway (see [ADR-0009](adr/0009-gateway-identity-jwt-deferred.md)).
  `X-Auth-*` headers are trusted only because the gateway must replace incoming
  values and block direct access; browsers and other clients can supply those
  headers themselves. JSON request bodies and the absence of permissive CORS
  limit ordinary cross-site requests, but they do not certify the gateway's
  authentication or CSRF policy. If the gateway authenticates using browser
  cookies or sessions, it must enforce CSRF protection now, even though the
  service itself consumes headers. Before resolving these findings, verify
  the actual gateway authentication, CORS, CSRF, and network-isolation settings.
  Demo-only controllers also require a review of how the demo is exposed.

`.snyk` policy files do not support Snyk Code ignores (only scan
exclusions). The supported mechanism is Consistent Ignores:
`snyk ignore create --finding-id=<ID> --ignore-type=not-vulnerable
--expiration=never --reason="..."`, where the finding ID is the GUID from
`snyk code test` output (the same value appears in JSON output under
`fingerprints["snyk/asset/finding/v1"]`). The feature is Early Access and
requires Consistent Ignores to be enabled for the organization.
