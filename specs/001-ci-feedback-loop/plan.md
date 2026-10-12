# Implementation Plan: CI Feedback Loop Acceleration

**Branch**: `001-ci-feedback-loop` | **Date**: 2026-10-12 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/001-ci-feedback-loop/spec.md`

## Summary

Improve GitHub Actions CI to reduce developer feedback loop time from 15–20 minutes to under 1 minute for failure diagnosis, 5 minutes for script-only PRs, and 5 seconds for environment setup errors. Four changes: (1) per-test GitHub annotations from JUnit XML, (2) HTML report artifacts, (3) path-filtered PR triggers, (4) a path-gated preflight composite action, and (5) desktop Compose UI test retry with visible retry logging and screenshot diagnostics.

## Technical Context

**Language/Version**: YAML (GitHub Actions workflows), Python 3.12 (CI scripts), Bash (static-gates.sh)

**Primary Dependencies**: GitHub Actions API (`@actions/core` for annotations), existing Gradle/JUnit XML output, existing `check-test-runs.py`, existing `retry.maxAttempts` Gradle property

**Storage**: N/A — CI artifacts only (JUnit XML, HTML reports, screenshots)

**Testing**: Manual CI verification; script unit tests via existing `scripts/tests/` unittest suite

**Target Platform**: Linux (ubuntu-24.04 GitHub Actions runners)

**Project Type**: CI/CD infrastructure — GitHub Actions workflow + composite action + Python scripts

**Performance Goals**: Preflight failure in <5 sec; script-only PR CI in <5 min; JUnit XML annotation parsing in <10 sec

**Constraints**: Must not break existing `static`, `tests`, `android`, `ci-gate` job structure; `check-test-runs.py` count validation must remain accurate with retry-adjusted test counts; path-filtered PR must not produce zero jobs

**Scale/Scope**: 1 new composite action (`.github/actions/setup-repo-tools/`), 1 new Python script (JUnit XML → GitHub annotations), modifications to `ci.yml` (path filters, annotation step, HTML artifact upload), modifications to `desktopApp/build.gradle.kts` (retry rule)

## Constitution Check

| Rule | Status | Notes |
|------|--------|-------|
| §I KMP Architecture | **N/A** | No KMP/app code changes |
| §II ADR Before Code | **Not required** | Pure CI infrastructure, no architectural patterns introduced; qualifies as "purely additive, low-risk changes following existing patterns" |
| §III Validate Early | **N/A** | No ADR changes |
| §IV Immutable Historical Record | **N/A** | No ADR changes |
| §V CI Gates | **Applies** | New gate step (JUnit XML → annotations) must be added to `check-gate-wiring.py` parity registry; preflight action must not conflict with existing gate registry |
| §VI MVI ViewModels | **N/A** | No app code changes |
| §VII Test Fakes | **N/A** | No app code changes |
| §VIII OpenSpec Workflow | **N/A** | Bug fixes / CI-only changes may skip OpenSpec — this is CI infrastructure improvement following existing patterns |

**Verdict**: CI infrastructure improvement; no ADR required. Gate parity must be maintained.

## Project Structure

### Documentation (this feature)

```text
specs/001-ci-feedback-loop/
├── plan.md              # This file
├── research.md          # Phase 0 output
├── data-model.md        # Phase 1 output
├── quickstart.md        # Phase 1 output
├── contracts/           # Phase 1 output (skipped — no external interfaces)
└── tasks.md             # Phase 2 output (/speckit-tasks — NOT created here)
```

### Source Code (repository root)

```text
.github/
├── workflows/
│   └── ci.yml           # [MODIFIED] — path filters, annotation step, HTML artifact upload
├── actions/
│   └── setup-repo-tools/  # [NEW] — composite action for preflight validation
│       └── action.yml

scripts/
├── ci/
│   └── static-gates.sh  # [MODIFIED] — migrate all `python3 scripts/...` gate calls to `sgate <command>` form; preflight gate integration
├── junit-annotations.py  # [NEW] — JUnit XML → GitHub annotations
└── check-test-runs.py   # [MODIFIED] — retry-adjusted count logic

desktopApp/
├── build.gradle.kts     # [MODIFIED] — add `reports.junitXml.mergeReruns.set(true)`; `retry.maxAttempts`/`retry.failOnPassedAfterRetry` already forwarded
└── src/jvmTest/kotlin/com/singularity/todo/test/
    └── RetryExtension.kt  # [NEW] — JUnit 5 InvocationInterceptor consuming retry.maxAttempts; NOT TestExecutionExceptionHandler (cannot restart test)
```

**Structure Decision**: New files are `.github/actions/setup-repo-tools/action.yml`, `scripts/junit-annotations.py`. Modified files are `.github/workflows/ci.yml`, `scripts/ci/static-gates.sh`, `scripts/check-test-runs.py`, `desktopApp/build.gradle.kts`. No changes to Android, JVM app code, or shared module.

## Phase 0: Unknowns & Research

### Unknown 7: sgate CLI migration completeness

**What**: How many gates in `static-gates.sh` still use raw `python3 scripts/...` when an `sgate <command>` shim already exists?

**What was chosen**: Migrate ALL gates from raw script invocations to `sgate <command>` form. Every Python script in `scripts/` that is called as a gate has a corresponding `sgate` command module in `scripts/src/sgate/commands/`.

**Migration table** (all gates currently using `python3 scripts/...` or shell function references):

| Gate name | Old command | New command (`sgate`) |
|---|---|---|
| version catalog has no literals | `python3 scripts/build-version-catalog-gate.py` | `sgate version_catalog_has_no_literals` |
| gate scripts' own unit tests | `bash -c 'python3 -m unittest...'` | *(keep as-is — unittest)* |
| test-task external inputs declared | `python3 scripts/check-test-task-inputs.py` | `sgate test_task_external_inputs_declared` |
| declared dependencies are used | `python3 scripts/check-dependency-usage.py` | `sgate declared_dependencies_are_used` |
| room schema integrity | `python3 scripts/check-room-schema-integrity.py` | `sgate room_schema_integrity` |
| gates are alive | `python3 scripts/check-gate-liveness.py` | `sgate gates_are_alive` |
| closed ADRs keep their body | `python3 scripts/check-adr-immutable.py` | `sgate closed_adrs_keep_their_body` |
| skills describe the tools that exist | `python3 scripts/check-skill-drift.py` | `sgate skills_describe_the_tools_that_exist` |
| git hooks path is per-worktree | `python3 scripts/check-hooks-path.py` | `sgate git_hooks_path_is_per_worktree` |
| unwired surfaces | `python3 scripts/find-unwired-surfaces.py` | `sgate unwired_surfaces` |
| unwired backlog refs | `python3 scripts/check-unwired-backlog-refs.py` | `sgate unwrapped_backlog_refs` |
| settings read by a feature | `python3 scripts/check-dead-settings.py` | `sgate settings_read_by_a_feature` |
| YAML has no duplicate keys | `python3 scripts/check-yaml-duplicate-keys.py` | `sgate yaml_has_no_duplicate_keys` |
| detekt rules config is current | `python3 scripts/gen-detekt-rules-config.py --check` | `sgate detekt_rules_config_is_current` |
| detekt rule inventory is current | `python3 scripts/gen-detekt-rule-table.py --check` | `sgate detekt_rule_inventory_is_current` |
| detekt rule registry | `./scripts/check-detekt-registrations.sh` | `sgate detekt_rule_registry` |
| detekt baseline ratchet | `python3 scripts/check-baseline-ratchet.py` | `sgate detekt_baseline_ratchet` |
| lint rules are declared decisions | `python3 scripts/check-rule-intent.py` | `sgate lint_rules_are_declared_decisions` |
| file-level suppressions explain themselves | `python3 scripts/check-suppression-intent.py` | `sgate file_level_suppressions_explain_themselves` |
| scenario specs valid | `traceability validate` | `sgate scenario_specs_valid` |
| coverage matrix current | `traceability coverage --check` | `sgate coverage_matrix_current` |
| coverage holes did not grow | `python3 scripts/check-traceability-ratchet.py` | `sgate coverage_holes_did_not_grow` |
| kiwi inventory did not regress | `python3 scripts/check-kiwi-inventory-ratchet.py` | `sgate kiwi_inventory_did_not_regress` |
| supabase schema is self-consistent | `python3 scripts/check-supabase-schema-integrity.py` | `sgate supabase_schema_is_self_consistent` |
| sync allowlist seed matches contract | `python3 scripts/check-sync-allowlist-regenerated.py` | `sgate sync_allowlist_seed_matches_contract` |
| maestro selectors valid | `python3 scripts/ci/validate-maestro-selectors.py` | `sgate maestro_selectors_valid` |
| skills catalog current | `./scripts/regen-skills-catalog.sh --check` | `sgate skills_catalog_current` |
| skill frontmatter | `./scripts/check-skill-frontmatter.sh` | `sgate skill_frontmatter` |
| decisions digest within budget | `digest_and_sizes` (bash fn) | `sgate decisions_digest_within_budget` |
| digest has no double suffix | `digest_no_double_suffix` (bash fn) | `sgate digest_has_no_double_suffix` |
| no dead doc refs | `python3 scripts/check-doc-dead-refs.py` | `sgate no_dead_doc_refs` |
| no dead skill symbols | `python3 scripts/check-doc-dead-refs.py --skill-symbols` | `sgate no_dead_skill_symbols` |
| publication hygiene | `python3 scripts/check-publication-hygiene.py` | `sgate publication_hygiene` |
| publication hygiene self-test | `python3 scripts/check-publication-hygiene.py --self-test` | `sgate publication_hygiene_self_test` |
| README claims | `python3 scripts/check-readme-claims.py` | `sgate readme_claims` |
| README claims self-test | `python3 scripts/check-readme-claims.py --self-test` | `sgate readme_claims_self_test` |
| ADR references are valid | `python3 scripts/check-adr-references.py` | `sgate adr_references_are_valid` |
| ADR status policy | `python3 scripts/check_adr_status.py` | `sgate adr_status_policy` |
| ADR corpus rules are shared | `python3 scripts/check-adr-config-sync.py` | `sgate adr_corpus_rules_are_shared` |
| light ADR modules stay light | `python3 scripts/check-adr-module-boundary.py --quiet` | `sgate light_adr_modules_stay_light` |
| openspec stale | `python3 scripts/check-openspec-stale.py` | `sgate openspec_stale` |
| spec-kit issue registry current | `python3 scripts/check-speckit-issue-registry.py` | `sgate spec_kit_issue_registry_current` |
| requirement identifiers are unique | `python3 scripts/check-req-id-uniqueness.py` | `sgate requirement_identifiers_are_unique` |
| apptracer upload policy encoded | `python3 scripts/check-apptracer-policy.py` | `sgate apptracer_upload_policy_encoded` |

**Gates already using sgate** (no change needed):
- `gate advisory "ADR frontmatter drift" sgate adr_frontmatter_drift`
- `gate advisory "backlog-issue-refs" sgate backlog_issue_refs`

**Rationale**: `sgate` is the project's canonical CLI for gate execution. Using it consistently means all gates go through one entry point, with consistent output formatting, logging, and error handling. The `sgate` shims call the same underlying scripts — no behavior change, only interface normalization. `sgate` is installed in CI via `pip install -e scripts/src/` in the `static` job.

### Unknown 1: GitHub Actions per-test annotation API

**What**: How to create per-test GitHub annotations from CI step (not a third-party action). Should use `echo "::error ::"` with `file`/`line`/`col` annotations, or the `gh api` calls to the Checks API. Need to confirm which approach supports all required fields (test class, method, message) and is usable from a Python script.

**How**: Research GitHub Actions `::error` annotation format limits and `gh api repos/{owner}/{repo}/check-runs/{id}/annotations` POST API.

### Unknown 2: JUnit XML → annotation mapping for desktop tests

**What**: The `desktopApp/build/test-results/test/*.xml` JUnit XML has a specific schema. Need to confirm how to extract test class name, method name, and failure message from it, and how the existing `check-flaky-tests.py` parses it for comparison.

**How**: Read a sample desktop JUnit XML from a previous CI run artifact (via `gh run download` or by examining `desktopApp/build/test-results/test/` in a local test run).

### Unknown 3: Desktop Compose UI screenshot/semantics capture on retry failure

**What**: The `DesktopAppHarness.kt` or Gradle test configuration for desktop already has screenshot capture. Need to confirm where screenshots are saved, whether semantics tree is already captured, and whether the existing `retry.maxAttempts` mechanism triggers retries at the Gradle level or JUnit level.

**How**: Examine `desktopApp/src/jvmMain/kotlin/` for harness and test configuration.

### Unknown 4: Path-filtered CI job matrix behavior

**What**: When GitHub Actions `paths:` filter results in zero matching jobs in a matrix or workflow, what is the behavior? Need to confirm whether `CI gate` job should have a default fallback or whether `ci-gate` job must be conditionally required.

**How**: GitHub Actions documentation + examination of existing multi-path workflows in this repo.

### Unknown 5: `check-test-runs.py` retry-adjusted count logic

**What**: When a test retries and passes on second attempt, the JUnit XML records one `<testcase>` (the final attempt). When it fails both times, JUnit XML may record two entries or one. The count gate must only count the final outcome. Need to confirm JUnit XML schema for retries with the existing `-Dretry.maxAttempts` setup.

**How**: Examine existing JUnit XML from a flaky desktop test run, or test with a deliberately flaky test.

### Unknown 6: HTML report generation for test artifacts

**What**: Gradle test tasks produce HTML reports at `build/reports/tests/test/index.html`. Need to confirm the upload step syntax and whether existing artifact upload already covers this path.

**How**: Examine `ci.yml` Upload Evidence step and existing `desktopApp/build/reports/tests/test/` location.

## Phase 1: Design & Contracts

### Data Model

No persistent data entities. The feature operates on transient CI artifacts:

- **JUnit XML**: consumed, parsed, not stored. Output: GitHub annotations.
- **HTML Test Report**: uploaded as artifact. No data model change.
- **Screenshot/Semantics Tree**: uploaded as artifact on persistent desktop test failure.
- **Retry Event**: logged as GitHub annotation note on passing retry.
- **Preflight Result**: exit code + error message, no persistent storage.

### Interface Contracts

No external interfaces exposed by this project. The CI pipeline is a consumer of:
- Gradle test task output (JUnit XML, HTML reports)
- GitHub API (annotations, artifacts)
- External tools (`singularity-adr`, `openspec`, Python 3.12)

No contracts directory needed — this is a single-project CI configuration with no library or service interfaces.

### Quickstart Validation Guide

See `quickstart.md` for end-to-end validation scenarios.

## Complexity Tracking

> Not applicable — no constitution violations.

## Open Issues for /speckit-tasks

The following were resolved by research (see `research.md` for full details):

| # | Issue | Resolution |
|---|-------|-----------|
| 1 | ADR required? | **No ADR needed** — "purely additive, low-risk CI change following existing patterns" |
| 2 | Path filter scoping | **Job-level filtering via `dorny/paths-filter`** — workflow-level `paths:` would leave required checks in Pending; job-level `needs.changes.outputs.*` pattern is correct |
| 3 | Preflight action location | **Separate composite action** (`setup-repo-tools/`) — cleaner for reuse, testability, and gate parity registry clarity |
| 4 | `checks: write` permission | **Not needed** — use `::error` workflow commands (no extra permissions beyond default `GITHUB_TOKEN`) |
| 5 | Retry rule implementation | **Resolved** — `retry.maxAttempts` is forwarded but not consumed. Custom `TestExecutionExceptionHandler` extension in `desktopApp/src/jvmTest/kotlin/com/singularity/todo/test/RetryExtension.kt` following the exact same pattern as `FailureContextExtension` (shared). `mergeReruns = true` added to `desktopApp/build.gradle.kts`. |
