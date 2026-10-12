# Feature Specification: CI Feedback Loop Acceleration

**Feature Branch**: `001-ci-feedback-loop`

**Created**: 2026-10-12

**Status**: Draft

**Input**: User description: "хочу улучшить git cicd workflow/actions чтобы не тратить в кучу времени на отлов и отладку ошибок а максимально сократить время в петле обратной связи"

## Clarifications

### Session 2026-10-12

- Q: Preflight validation missing tool → fail check or warn? → A: **Required** — CI check fails immediately (red X), no gate execution proceeds
- Q: Desktop retry count → fixed 1 or configurable via -Dretry.maxAttempts? → A: **Configurable** — use existing `-Dretry.maxAttempts` Gradle property (shared module pattern), defaulting to 2 total attempts (1 original + 1 retry)
- Q: GitHub annotation granularity → per-test or summary per job? → A: **Per-test annotation** — one GitHub annotation per individual failing test case (class + method + message)
- Q: Test passes on retry → clean pass or visible retry trace? → A: **Visible retry logging** — check reports "passed" but a note/annotation is added "N test(s) passed on retry" so retry events are tracked over time without blocking merges
- Q: Preflight tool checks → always run or path-gated? → A: **Path-gated** — preflight validates `singularity-adr`, `openspec`, and Python version only when the PR touches `scripts/**`, `.github/**`, or `docs/**`; pure app code PRs skip this check to avoid false failures on machines without these tools installed

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Instant failure diagnosis in PR summary (Priority: P1)

Developer opens a red PR and immediately sees which test failed and why, without clicking through to logs. The failure annotation appears in the PR conversation tab alongside the check run status. This eliminates the 15–20 minute local reproduction loop where the developer tries to recreate the exact build state.

**Why this priority**: This is the single most time-consuming pain point described — multiple 5–20 minute iterations just to learn the name of a failing test. Each iteration blocks on a full CI run before diagnosis can begin.

**Independent Test**: Can be validated by creating a deliberately failing test and verifying the failure appears as a GitHub annotation in the PR summary within the same CI run that produced the JUnit XML.

**Acceptance Scenarios**:

1. **Given** a test suite produces a JUnit XML with at least one failure, **When** CI completes the test step, **Then** a GitHub annotation is created with the failing test class and method name visible in the PR check summary.
2. **Given** a test suite passes completely, **When** CI completes, **Then** no failure annotation is created and the check reports success.
3. **Given** HTML reports are generated from a test run, **When** CI uploads the report artifact, **Then** a link to the HTML report appears in the CI artifact section and is accessible without log navigation.

---

### User Story 2 - Path-filtered PR gating (Priority: P1)

When a developer changes only files in `scripts/`, `.github/`, or `docs/` directories, CI runs only the static gate checks (linting, style, script validation) and completes in under 5 minutes. Full platform builds and test matrices run only on changes that actually affect them or on the main branch.

**Why this priority**: 8 of 10 recent iterations involved only script or workflow changes but still triggered the full 19-minute CI matrix. This wastes the most time on a per-change basis across all future work.

**Independent Test**: Can be validated by opening a PR that touches only `scripts/*.sh` and confirming only static gate jobs run and complete within 5 minutes.

**Acceptance Scenarios**:

1. **Given** a PR modifies only `scripts/**` files, **When** CI is triggered, **Then** only static gate jobs run and no Android or JVM test jobs are triggered.
2. **Given** a PR modifies `shared/src/**/*.kt`, **When** CI is triggered, **Then** the full test matrix runs including JVM and Android tests.
3. **Given** a PR merges into `main`, **When** CI is triggered, **Then** the full matrix runs regardless of which paths changed.

---

### User Story 3 - Preflight environment validation (Priority: P1)

Before any gate scripts run, CI validates that all required tools are available on PATH and meet minimum version requirements. A missing tool causes the CI check to fail immediately (Required) with a clear message identifying which command is absent — no gate scripts are attempted.

**Why this priority**: Two recent sessions had 127 errors from missing tools (`singularity-adr`, `openspec`) that were caught only after minutes of gate execution. Catching these at startup saves the full gate duration.

**Independent Test**: Can be validated by running the preflight action with a modified PATH that excludes one required tool and confirming it fails within 5 seconds with the tool name in the error message.

**Acceptance Scenarios**:

1. **Given** a PR modifies only `scripts/**`, `.github/**`, or `docs/**` files and `singularity-adr` is not on PATH, **When** preflight action runs, **Then** CI fails within 5 seconds with an error mentioning `singularity-adr` is not found.
2. **Given** a PR modifies only `scripts/**`, `.github/**`, or `docs/**` files and `openspec` is not installed, **When** preflight action runs, **Then** CI fails within 5 seconds with an error mentioning `openspec` is not found.
3. **Given** a PR modifies only `scripts/**`, `.github/**`, or `docs/**` files and Python version is incompatible, **When** preflight action runs, **Then** CI fails within 5 seconds identifying the Python version mismatch.
4. **Given** a PR modifies only `scripts/**`, `.github/**`, or `docs/**` files and all required tools are present and valid, **When** preflight action runs, **Then** CI proceeds to gate execution without error.
5. **Given** a PR modifies only `shared/src/**/*.kt` files (pure app code), **When** preflight action runs, **Then** preflight skips tool validation and CI proceeds directly to build and test jobs.

---

### User Story 4 - Flaky desktop test resilience (Priority: P2)

Desktop Compose UI tests that occasionally fail due to timing or rendering issues are retried automatically. A transient failure does not block a merge; instead, the retry count is logged and visible. If a test consistently fails, the failure and any captured diagnostic screenshot is attached to the check run for faster diagnosis.

**Why this priority**: Currently the only blocked-required-check is the flaky desktop test suite, forcing manual `gh run rerun` on ~10–15% of runs. This is the only unresolved required check blocking merges.

**Independent Test**: Can be validated by running the desktop test suite multiple times and confirming transient failures are retried at least once and consistent failures produce a screenshot artifact.

**Acceptance Scenarios**:

1. **Given** a desktop Compose test fails due to a transient timing issue, **When** the test is retried (up to the configured `-Dretry.maxAttempts` limit, defaulting to 2 total attempts), **Then** the second attempt result is used as the final result.
2. **Given** a desktop Compose test fails on both attempts, **When** CI completes, **Then** a screenshot and semantics tree of the failure state is uploaded as an artifact.
3. **Given** `check-test-runs.py` validates test counts, **When** a retry occurs, **Then** the script counts the test correctly based on the final attempt, not the total across all attempts.

---

### Edge Cases

- What happens when the JUnit XML is malformed or missing — CI should not crash; it should report the missing XML as a warning and continue.
- What happens when GitHub annotation creation fails due to API limits — CI should not fail the check; it should log the failure and continue.
- What happens when path filters cause zero jobs to run on a PR — this should be prevented by requiring at least one job to run or a default fallback.
- What happens when a flaky test passes on retry but the retry count exceeds the limit — the final outcome should reflect the pass.
- What happens when preflight runs locally via `just gate` vs in CI — behavior should be identical, neither should have an advantage.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: CI MUST parse JUnit XML output immediately after each test step completes and produce one GitHub annotation per individual failing test case (class name, method name, and failure message). Multiple test failures in the same run produce multiple annotations.
- **FR-002**: CI MUST upload HTML test reports as CI artifacts alongside JUnit XML files, with direct artifact links visible in the PR check summary.
- **FR-003**: CI MUST apply path filters to PR workflow triggers so that changes to `scripts/**`, `.github/**`, and `docs/**` trigger only static gate jobs and skip Android build, JVM test, and desktop test jobs.
- **FR-004**: CI MUST run a preflight environment validation action when the PR touches `scripts/**`, `.github/**`, or `docs/**`, checking for required tools on PATH (`singularity-adr`, `openspec`), Python version compatibility, and .githooks presence. The check fails the CI run immediately (Required) with a descriptive error if any check fails. For PRs touching only app code (`shared/src/**` etc.), preflight is skipped entirely.
- **FR-005**: CI MUST retry desktop Compose UI tests up to one additional attempt on failure by using the configurable `-Dretry.maxAttempts` Gradle property (matching the existing shared module pattern), defaulting to 2 total attempts (1 original + 1 retry). When a test passes on retry, the check reports "passed" but a visible note/annotation is added indicating "N test(s) passed on retry" to build flakiness history. On persistent failure, screenshot artifacts are attached.

### Key Entities *(include if feature involves data)*

- **CI Workflow**: The GitHub Actions workflow configuration that orchestrates build, test, and gate jobs.
- **Static Gate Jobs**: A set of CI jobs that run linting, style checks, and script validation without compiling or running application code.
- **Test Artifact**: JUnit XML and HTML report files produced by test execution, consumed by downstream parsing and upload steps.
- **Preflight Action**: A composite GitHub Action that validates the build environment before gates run.
- **Retry Attempt**: A record of how many times a test was re-executed, used to determine final outcome and log transient failures.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A developer can identify the failing test name and message from the PR summary without opening CI logs, reducing diagnosis time from 15–20 minutes to under 1 minute per failure.
- **SC-002**: A PR that changes only `scripts/**`, `.github/**`, or `docs/**` completes CI in under 5 minutes, compared to the current 19-minute full matrix.
- **SC-003**: A missing required tool causes CI to fail within 5 seconds of startup with a descriptive error, compared to the current behavior of failing deep inside gate execution.
- **SC-004**: Desktop Compose UI test flakiness no longer blocks merges requiring manual rerun — transient failures are retried and consistent failures include screenshot diagnostics.
- **SC-005**: The local command `just gate` produces identical results to CI gate execution, eliminating environment divergence as a source of failures.

## Assumptions

- GitHub Enterprise or cloud with annotation API support — assumed available since existing CI already uses GitHub Actions.
- The JUnit XML schema produced by Gradle test tasks is stable and parseable by standard XML parsers.
- The 5000-line output cap on gate logs is retained as-is — this spec does not change log verbosity settings.
- Python version pinning via `uv run --python 3.12` inside scripts is sufficient to ensure CI=local parity for Python-based gates.
- The existing `check-test-runs.py` can be extended to handle retry-adjusted test counts without breaking its current validation logic.
