# Local checks and CI quality gates

Install the optional Git hooks with PowerShell 7:

```powershell
pwsh -File scripts/install-hooks.ps1
```

The pre-commit hook only runs `git diff --cached --check` so commits stay fast
and the hook works in Git Bash, macOS, and Linux. GitHub Actions runs Maven
verification and a full-stack smoke on every push to `main`. It runs validation
only and contains no deployment job. OpenShift deployment remains the
responsibility of the platform pipeline. A separate documentation workflow builds
and publishes the [documentation site](https://kannanthiraviam.github.io/OmniFlux-Exchange/)
to GitHub Pages without creating a publishing branch. See
[documentation maintenance](DOCUMENTATION.md) for the diagram and site checks.

The more extensive local quality script also runs repository checks and the
`code-audit` profile:

```powershell
pwsh -File scripts/quality-gate.ps1
```

Three local hooks are installed by `scripts/install-hooks.ps1`:

- `.githooks/pre-commit` - whitespace checks for staged changes.
- `.githooks/pre-push` - permits branch pushes; agent pushes require explicit
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

**The installed `pre-commit` hook is intentionally small:** it runs only
`git diff --cached --check` for staged whitespace errors. It does not run the
repository hygiene checks, Maven, Docker, or tests. The `pre-push` hook is a
safety boundary for agent pushes; it does not impose a trunk-only branch rule.
The reference-transaction hook blocks agent updates to `main`/`master` unless
explicitly approved; feature branches are supported.

Run the full local gate explicitly before pushing to `main`:

```powershell
pwsh -File scripts/quality-gate.ps1
```

It checks repository hygiene, common credential patterns, conflict markers,
file size/newline rules, XML/JSON and PowerShell syntax, dashboard JavaScript
syntax (`node --check`), and local Markdown link targets. It also requires a
current docs update alongside production/configuration/tooling changes. Then it
runs `./mvnw -B clean verify -Pcode-audit` (or the Windows Maven wrapper),
including Checkstyle, the PMD unused-code audit, dependency analysis, the full
test suite, and the 85% instruction-coverage gate for the deterministic export
core. The dependency analysis report is advisory because Spring Boot starters,
service-loader drivers, and auto-configuration produce expected structural
findings.

The local verification script is PowerShell 7 based. Its Maven suite uses
Testcontainers to start suite-owned PostgreSQL and SeaweedFS services; it does not
need the local Compose services already running. Docker Engine must be
available. Git and Java 25 are required; Node.js is needed for the dashboard
JavaScript syntax check. Maven dependencies may require network access.

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

Both scanners need live vulnerability databases, so neither runs in the
offline commit gate.

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

`snyk code test` (SAST over main and test sources) is a manual triage scanner
like the dependency scanners above; it does not run in the offline commit
gate. Current findings and their reviewed dispositions - re-verify each if
the surrounding design changes:

- **Path Traversal, StreamingRunner:183 (test harness)** - false positive:
  `verifyXlsx` reads the downloaded workbook strictly in memory (StAX over
  the stream); no file is extracted and no filesystem path is constructed.
  The "remote input" is the runner's own presigned download of the export it
  just verified.
- **Open Redirect, dashboard download handler** - hardened in code: the
  response URL is parsed and restricted to HTTP(S) before
  `window.location.assign`. The presigned URL is server-generated and points
  at object storage, so it is cross-origin by design and cannot be
  host-allowlisted in the client; the residual taint flag is the analyzer not
  recognizing that validation.
- **Spring CSRF, Export/NaiveExport/Seed controllers** - by design: the app
  deliberately does not use Spring Security (see the architecture decision
  log). Protection lives in the authenticating gateway (the only browser
  entry; ClusterIP service, no Route, gateway-namespace NetworkPolicy), the
  `X-Auth-*` header contract browsers cannot forge, and application/json-only
  state-changing endpoints, which cross-site forms cannot send. **Trigger
  for revisiting:** if auth ever moves to browser-held cookies (JWT-in-cookie),
  Spring Security CSRF or SameSite=Strict cookies becomes mandatory.

`.snyk` policy files do not support Snyk Code ignores (only scan
exclusions). The supported mechanism is Consistent Ignores:
`snyk ignore create --finding-id=<ID> --ignore-type=not-vulnerable
--expiration=never --reason="..."`, where the finding ID is the GUID from
`snyk code test` output (the same value appears in JSON output under
`fingerprints["snyk/asset/finding/v1"]`). The feature is Early Access and
requires Consistent Ignores to be enabled for the organization.
