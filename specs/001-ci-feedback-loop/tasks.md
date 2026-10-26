# Tasks: CI Feedback Loop Acceleration

**Feature**: `001-ci-feedback-loop`
**Spec**: [spec.md](./spec.md) | **Plan**: [plan.md](./plan.md) | **Research**: [research.md](./research.md)
**Status**: Ready for implementation

---

## Task Groups

### Group A — CI Infrastructure (Path Filtering, Preflight, Annotations, HTML Artifact)

These tasks implement FR-002, FR-003, FR-004 and User Story 2 + 3.

---

#### A1 · Create preflight composite action

**FR**: FR-004  
**Story**: US-3  
**Type**: new-file  
**Owner**: CI infrastructure

**File to create**: [`.github/actions/setup-repo-tools/action.yml`](../../.github/actions/setup-repo-tools/action.yml)

**What**: A composite GitHub Action that validates the build environment before gates run. Checks for `singularity-adr`, `openspec`, and Python version compatibility. Fails within 5 seconds with a descriptive error if any required tool is missing. Called by `ci.yml` only when the PR touches `scripts/**`, `.github/**`, or `docs/**`.

**Acceptance criteria**:
- `singularity-adr --version` exits 0 and prints a version string
- `uv tool list` shows `specify-cli` installed (or `openspec --version` works)
- `python3 --version` reports 3.12.x or higher
- On failure: `echo "::error ::Missing tool: <tool>" && exit 1` within 5 seconds
- On success: exits 0, sets `REPO_TOOLS_PREFLIGHT_RC=0`

**Implementation notes**:
- Use `bash -c 'command -v singularity-adr'` to detect presence without full version parse
- Python version check: `python3 -c 'import sys; sys.exit(0 if sys.version_info >= (3, 12) else 1)'`
- Use `with: {python-version: "3.12"}` in action if you need a specific runtime
- Path-gating is done by `ci.yml` job-level `if: needs.changes.outputs.scripts == 'true'`, NOT in the action itself

**Verification**:
```bash
# Should pass (all tools present)
./.github/actions/setup-repo-tools/action.yml

# Simulate missing tool
PATH=/usr/bin:/bin bash -c 'unset PATH; command -v singularity-adr || { echo "::error ::Missing: singularity-adr"; exit 1; }'
# Expected: error annotation + exit 1
```

---

#### A2 · Create JUnit XML → GitHub annotations script

**FR**: FR-001  
**Story**: US-1  
**Type**: new-file  
**Owner**: CI infrastructure

**File to create**: [`scripts/junit-annotations.py`](../../scripts/junit-annotations.py)

**What**: Python script that reads JUnit XML from `desktopApp/build/test-results/test/*.xml`, parses it with `junitparser`, and emits GitHub Actions `::error` workflow commands for each failing test case. Also emits `::warning` for retry-passed tests (tests that had a `flakyFailure` child).

**Acceptance criteria**:
- Parses all `**/test-results/test/*.xml` files recursively
- For each `<testcase>` with `<failure>`: emits `::error file={path}::title={Class.method}::{message}`
- For each `<testcase>` with `<flakyFailure>`: emits `::warning title=Passed on retry::{N} test(s) passed on retry`
- Handles malformed XML gracefully: logs warning, exits 0 (no crash)
- Handles missing XML files gracefully: logs warning, exits 0 (no crash)
- Uses `junitparser` library (add to pip install in `ci.yml`)

**Implementation notes**:
- Import: `import junitparser`
- Parse: `junitparser.JUnitXml.from_file(xml_path)` → iterate `.test_cases`
- Failure access: `tc.result` is a list; check `isinstance(r, junitparser.Failure)`
- Flaky failure: `tc.result` contains `junitparser.Failure` with a special `tc.result[0]._elem.tag == 'flakyFailure'` (check element tag directly since junitparser maps it to Failure)
- Annotation title: truncate class+method to ≤64 chars (GitHub limit)
- If source file not determinable, use `.` for file and `line=1`
- Exit 0 always — annotation failures are informational

**Verification**:
```bash
# Install dependency
pip install junitparser

# Create a sample JUnit XML with a failure
cat > /tmp/fail.xml << 'EOF'
<testsuite name="MyTest" tests="1" failures="1" errors="0" skipped="0">
  <testcase name="testDivision" classname="com.example.MathTest" time="0.01">
    <failure message="expected: <0> but was: <2>" type="AssertionError">
      <![CDATA[java.lang.AssertionError: expected: <0> but was: <2>]]>
    </failure>
  </testcase>
</testsuite>
EOF

# Run the script (in CI it would receive the actual XML path)
python scripts/junit-annotations.py desktopApp/build/test-results/test/
# Expected: ::error annotation printed to stdout
```

---

#### A3 · Update `ci.yml` — add `changes` job and path-filtered gating

**FR**: FR-003  
**Story**: US-2  
**Type**: modify  
**File**: `.github/workflows/ci.yml`

**What**: Add a `changes` job using `dorny/paths-filter` to detect which path categories the PR touches. Update the existing job structure (static, tests, android, ci-gate) to run conditionally based on `needs.changes.outputs.*`.

**Changes required**:

1. **Add `permissions`** to workflow root (if not present):
   ```yaml
   permissions:
     contents: read
   ```

2. **Add `changes` job** (no `paths:` filter on workflow trigger — that would cause zero jobs):
   ```yaml
   changes:
     runs-on: ubuntu-24.04
     outputs:
       scripts: ${{ steps.filter.outputs.scripts }}
       android: ${{ steps.filter.outputs.android }}
       shared: ${{ steps.filter.outputs.shared }}
       desktop: ${{ steps.filter.outputs.desktop }}
       all: ${{ steps.filter.outputs.all }}
     steps:
       - uses: actions/checkout@v4
       - uses: dorny/paths-filter@v3
         id: filter
         with:
           filters: |
             scripts:
               - 'scripts/**'
               - '.github/**'
               - 'docs/**'
             android:
               - 'androidApp/**'
               - 'shared/src/android*/**'
               - 'gradle.properties'
             shared:
               - 'shared/src/commonMain/**'
               - 'shared/src/androidMain/**'
               - 'shared/src/jvmMain/**'
             desktop:
               - 'desktopApp/**'
               - 'shared/src/jvmMain/**'
             all:
               - '**'
   ```

3. **Update `static` job**: add `needs: changes` and `if: needs.changes.outputs.scripts == 'true' || needs.changes.outputs.all == 'true' || github.ref == 'refs/heads/main'`  
   → This ensures `static` always runs on main, plus when scripts/docs change

4. **Update `tests` job** (JVM + Android): `needs: changes` + `if: needs.changes.outputs.android == 'true' || needs.changes.outputs.shared == 'true' || needs.changes.outputs.all == 'true' || github.ref == 'refs/heads/main'`

5. **Update `ci-gate` job**: `needs: changes` + `if: needs.changes.outputs.all == 'true' || github.ref == 'refs/heads/main'`

6. **Add `junit-annotations` step** to each test job (JVM and desktop) — runs after `collect-results`:
   ```yaml
   - name: Generate per-test annotations
     if: always()
     run: |
       python scripts/junit-annotations.py desktopApp/build/test-results/test/
     continue-on-error: true
   ```

7. **Add HTML artifact upload** after annotation step in desktop job:
   ```yaml
   - name: Upload HTML test reports
     if: always()
     uses: actions/upload-artifact@v4
     with:
       name: html-reports-${{ github.run_id }}
       path: desktopApp/build/reports/tests/test/**/*.html
       retention-days: 14
       if-no-files-found: warn
   ```

8. **Add preflight step** to `static` job (runs first, before any gate):
   ```yaml
   - name: Preflight environment check
     if: needs.changes.outputs.scripts == 'true'
     uses: ./.github/actions/setup-repo-tools
   ```

**Critical constraint**: The `CI gate` required check must always produce a job result (pass/fail). Main branch always runs all jobs. PRs with zero matching paths must still run `ci-gate` (via `all` filter or main branch fallback).

**Verification**:
- Open a PR touching only `docs/*.md` → confirm only `static` job runs
- Open a PR touching `shared/src/**/*.kt` → confirm `tests` job runs
- Merge to main → confirm all jobs run
- JUnit XML with failures → confirm annotations appear in PR summary

---

#### A4 · Migrate all gates in `static-gates.sh` to `sgate <command>` form

**FR**: (constitution §V gate hygiene)  
**Type**: modify  
**File**: `scripts/ci/static-gates.sh`

**What**: Replace every raw `python3 scripts/...` gate invocation with `sgate <command>` form. This is a pure interface normalization — the underlying script behavior is unchanged.

**Migration table** (from plan.md):

| Gate | Old | New |
|------|-----|-----|
| version catalog no literals | `python3 scripts/build-version-catalog-gate.py` | `sgate version_catalog_has_no_literals` |
| test task external inputs | `python3 scripts/check-test-task-inputs.py` | `sgate test_task_external_inputs_declared` |
| declared deps used | `python3 scripts/check-dependency-usage.py` | `sgate declared_dependencies_are_used` |
| room schema integrity | `python3 scripts/check-room-schema-integrity.py` | `sgate room_schema_integrity` |
| gates are alive | `python3 scripts/check-gate-liveness.py` | `sgate gates_are_alive` |
| closed ADRs immutable | `python3 scripts/check-adr-immutable.py` | `sgate closed_adrs_keep_their_body` |
| skills describe tools | `python3 scripts/check-skill-drift.py` | `sgate skills_describe_the_tools_that_exist` |
| git hooks path per-worktree | `python3 scripts/check-hooks-path.py` | `sgate git_hooks_path_is_per_worktree` |
| unwired surfaces | `python3 scripts/find-unwired-surfaces.py` | `sgate unwired_surfaces` |
| unwired backlog refs | `python3 scripts/check-unwired-backlog-refs.py` | `sgate unwrapped_backlog_refs` |
| settings read by feature | `python3 scripts/check-dead-settings.py` | `sgate settings_read_by_a_feature` |
| YAML no duplicate keys | `python3 scripts/check-yaml-duplicate-keys.py` | `sgate yaml_has_no_duplicate_keys` |
| detekt rules config current | `python3 scripts/gen-detekt-rules-config.py --check` | `sgate detekt_rules_config_is_current` |
| detekt rule inventory current | `python3 scripts/gen-detekt-rule-table.py --check` | `sgate detekt_rule_inventory_is_current` |
| detekt rule registry | `./scripts/check-detekt-registrations.sh` | `sgate detekt_rule_registry` |
| detekt baseline ratchet | `python3 scripts/check-baseline-ratchet.py` | `sgate detekt_baseline_ratchet` |
| lint rules declared decisions | `python3 scripts/check-rule-intent.py` | `sgate lint_rules_are_declared_decisions` |
| file-level suppressions explain | `python3 scripts/check-suppression-intent.py` | `sgate file_level_suppressions_explain_themselves` |
| scenario specs valid | `traceability validate` | `sgate scenario_specs_valid` |
| coverage matrix current | `traceability coverage --check` | `sgate coverage_matrix_current` |
| coverage holes not grow | `python3 scripts/check-traceability-ratchet.py` | `sgate coverage_holes_did_not_grow` |
| kiwi inventory not regress | `python3 scripts/check-kiwi-inventory-ratchet.py` | `sgate kiwi_inventory_did_not_regress` |
| supabase schema self-consistent | `python3 scripts/check-supabase-schema-integrity.py` | `sgate supabase_schema_is_self_consistent` |
| sync allowlist seed matches | `python3 scripts/check-sync-allowlist-regenerated.py` | `sgate sync_allowlist_seed_matches_contract` |
| maestro selectors valid | `python3 scripts/ci/validate-maestro-selectors.py` | `sgate maestro_selectors_valid` |
| skills catalog current | `./scripts/regen-skills-catalog.sh --check` | `sgate skills_catalog_current` |
| skill frontmatter | `./scripts/check-skill-frontmatter.sh` | `sgate skill_frontmatter` |
| decisions digest budget | `digest_and_sizes` (bash fn) | `sgate decisions_digest_within_budget` |
| digest no double suffix | `digest_no_double_suffix` (bash fn) | `sgate digest_has_no_double_suffix` |
| no dead doc refs | `python3 scripts/check-doc-dead-refs.py` | `sgate no_dead_doc_refs` |
| no dead skill symbols | `python3 scripts/check-doc-dead-refs.py --skill-symbols` | `sgate no_dead_skill_symbols` |
| publication hygiene | `python3 scripts/check-publication-hygiene.py` | `sgate publication_hygiene` |
| publication hygiene self-test | `python3 scripts/check-publication-hygiene.py --self-test` | `sgate publication_hygiene_self_test` |
| README claims | `python3 scripts/check-readme-claims.py` | `sgate readme_claims` |
| README claims self-test | `python3 scripts/check-readme-claims.py --self-test` | `sgate readme_claims_self_test` |
| ADR references valid | `python3 scripts/check-adr-references.py` | `sgate adr_references_are_valid` |
| ADR status policy | `python3 scripts/check_adr_status.py` | `sgate adr_status_policy` |
| ADR corpus rules shared | `python3 scripts/check-adr-config-sync.py` | `sgate adr_corpus_rules_are_shared` |
| light ADR modules light | `python3 scripts/check-adr-module-boundary.py --quiet` | `sgate light_adr_modules_stay_light` |
| openspec stale | `python3 scripts/check-openspec-stale.py` | `sgate openspec_stale` |
| spec-kit issue registry | `python3 scripts/check-speckit-issue-registry.py` | `sgate spec_kit_issue_registry_current` |
| requirement IDs unique | `python3 scripts/check-req-id-uniqueness.py` | `sgate requirement_identifiers_are_unique` |
| apptracer upload policy | `python3 scripts/check-apptracer-policy.py` | `sgate apptracer_upload_policy_encoded` |

**Already using `sgate`** (no change):
- `gate advisory "ADR frontmatter drift" sgate adr_frontmatter_drift`
- `gate advisory "backlog-issue-refs" sgate backlog_issue_refs`

**Bash functions kept as-is** (not Python scripts, no `sgate` module):
- `gate blocking "decisions digest within budget" digest_and_sizes`
- `gate advisory "digest has no double suffix" digest_no_double_suffix`

**Verification**:
```bash
# After migration, run this to confirm zero raw python3 scripts/... gate calls:
PYTHONPATH=scripts/src python3 -m sgate gates --json | python3 -c "
import json, sys
gates = json.load(sys.stdin)
python_gates = [g for g in gates if 'python3 scripts/' in g['command']]
print(f'Gates still using python3 scripts/: {len(python_gates)}')
for g in python_gates:
    print(f'  - {g[\"name\"]}: {g[\"command\"]}')
"
# Expected: 0
```

---

#### A5 · Add `junitparser` to CI pip install

**FR**: FR-001  
**Type**: modify  
**File**: `.github/workflows/ci.yml` (static job setup)

**What**: Add `junitparser` alongside the existing pip install line in the `static` job's Python setup step.

**Change**: Find the existing `pip install` line in the `static` job and append `junitparser`.

**Before**:
```bash
python -m pip install --disable-pip-version-check -e scripts/src/ pyyaml
```

**After**:
```bash
python -m pip install --disable-pip-version-check -e scripts/src/ pyyaml junitparser
```

**Verification**: After CI run, confirm `junitparser` is importable in the annotation script step.

---

#### A6 · Verify `check-test-runs.py` count logic with `mergeReruns=true`

**FR**: FR-005  
**Type**: investigate / confirm  
**File**: `scripts/check-test-runs.py`

**What**: Confirm that `check-test-runs.py` counts `<testcase>` elements directly without deduplication, which is correct when `mergeReruns=true` produces one `<testcase>` per test method (with `<flakyFailure>` as a child for retry-passes). No code change needed if the script already counts `<testcase>` elements.

**Verification**:
```bash
# Look for how check-test-runs.py counts tests — should count <testcase> elements
grep -n "testcase\|TestCase\|count\|total" scripts/check-test-runs.py | head -20

# If it deduplicates by name (double-counting logic), that needs fixing
# If it counts <testsuite @tests="N"> directly, that's also fine
# If it counts <testcase> elements, that's correct with mergeReruns=true
```

**Expected**: No change needed. Document the `mergeReruns=true` assumption in a comment in the script.

---

### Group B — Desktop Test Retry (InvocationInterceptor + mergeReruns)

These tasks implement FR-005 and User Story 4.

---

#### B1 · Add `mergeReruns` to `desktopApp/build.gradle.kts`

**FR**: FR-005  
**Type**: modify  
**File**: `desktopApp/build.gradle.kts`

**What**: Add `reports.junitXml.mergeReruns.set(true)` so that retry attempts are merged into a single `<testcase>` element with a `<flakyFailure>` child. This prevents `check-test-runs.py` from counting retries as separate tests.

**Change**: In the `test` task configuration block (near line 177 where `retry.maxAttempts` is already forwarded), add:
```kotlin
reports.junitXml.mergeReruns.set(true)
```

**Note**: `retry.maxAttempts` and `retry.failOnPassedAfterRetry` are already forwarded at line 177 — no change needed there.

**Verification**:
```bash
./gradlew :desktopApp:test --tests "*" -Dretry.maxAttempts=2 2>&1 | head -5
# Then inspect:
grep -c "flakyFailure" desktopApp/build/test-results/test/*.xml
# Should be > 0 if any retry occurred
```

---

#### B2 · Create `RetryExtension.kt` (JUnit 5 InvocationInterceptor)

**FR**: FR-005  
**Story**: US-4  
**Type**: new-file  
**Owner**: Desktop test infrastructure

**File to create**: [`desktopApp/src/jvmTest/kotlin/com/singularity/todo/test/RetryExtension.kt`](../../desktopApp/src/jvmTest/kotlin/com/singularity/todo/test/RetryExtension.kt)

**What**: A JUnit 5 `InvocationInterceptor` that consumes `retry.maxAttempts` (already forwarded by Gradle) and retries failed tests up to that limit. On retry success, logs a visible `::notice` annotation. On persistent failure after all retries, rethrows the last exception.

**Implementation** (based on the user's provided skeleton):

```kotlin
package com.singularity.todo.test

import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.api.extension.InvocationInterceptor
import org.junit.jupiter.api.extension.ReflectiveInvocationContext
import java.lang.reflect.Method

/**
 * Retries failed tests up to [retry.maxAttempts] total attempts.
 * - On success on attempt N > 1: logs a ::notice annotation ("passed on retry").
 * - On persistent failure: rethrows the last exception after all attempts.
 *
 * Consumes the `retry.maxAttempts` system property already forwarded by
 * [desktopApp/build.gradle.kts]. Default is 1 (no retry).
 */
class RetryExtension : InvocationInterceptor {

    private val maxAttempts: Int =
        System.getProperty("retry.maxAttempts")?.toIntOrNull() ?: 1

    override fun interceptTestMethod(
        invocation: InvocationInterceptor.Invocation<Void>,
        invocationContext: ReflectiveInvocationContext<Method>,
        extensionContext: ExtensionContext
    ) {
        if (maxAttempts <= 1) {
            invocation.proceed()
            return
        }

        var lastThrowable: Throwable? = null

        for (attempt in 1..maxAttempts) {
            try {
                invocation.proceed()
                if (attempt > 1) {
                    // Success on retry — test was flaky
                    println(
                        "[FLAKY] '${extensionContext.displayName}' passed on attempt $attempt of $maxAttempts"
                    )
                    println(
                        "::notice title=Passed on retry::Test '${extensionContext.displayName}' passed on attempt $attempt of $maxAttempts"
                    )
                }
                return
            } catch (t: Throwable) {
                lastThrowable = t
                println(
                    "[RETRY] '${extensionContext.displayName}' failed on attempt $attempt of $maxAttempts: ${t.message}"
                )
            }
        }

        // All attempts exhausted — propagate the last failure
        throw lastThrowable!!
    }
}
```

**Key decisions**:
- Uses `InvocationInterceptor`, NOT `TestExecutionExceptionHandler` — the latter cannot restart a test, only swallow or rethrow
- `invocation.proceed()` is called in a loop — each call runs the test fresh
- Retry-pass log uses `::notice` (not `::warning` or `::error`) to indicate informational flakiness tracking
- `println` to stdout for CI log visibility (::notice reaches GitHub annotation, println reaches logs)

**Verification**:
```bash
# Run a test that always fails
./gradlew :desktopApp:test --tests "com.singularity.todo.test.SomeFlakyTest" -Dretry.maxAttempts=2
# Expected: "[RETRY] ... failed on attempt 1..." + "[RETRY] ... failed on attempt 2..." + exception thrown
```

---

#### B3 · Register `RetryExtension` in JUnit 5 service loader

**FR**: FR-005  
**Type**: modify  
**File**: `desktopApp/src/jvmTest/resources/META-INF/services/org.junit.jupiter.api.extension.Extension`

**What**: Add `com.singularity.todo.test.RetryExtension` to the JUnit 5 extension service loader file so it is automatically discovered and applied to all desktop JVM tests.

**Change**: Append `com.singularity.todo.test.RetryExtension` to the existing file (or create the file if it doesn't exist with only that line).

**Note**: `FailureContextExtension` is already registered in this file. `RetryExtension` must be listed separately. JUnit 5 applies extensions in the order they appear in the service file.

**Verification**:
```bash
cat desktopApp/src/jvmTest/resources/META-INF/services/org.junit.jupiter.api.extension.Extension
# Should list both FailureContextExtension and RetryExtension
```

---

### Group C — Validation & Gate Parity

---

#### C1 · Run full static-gates.sh via sgate and confirm all pass

**FR**: (constitution §V)  
**Type**: validate  
**Files**: `scripts/ci/static-gates.sh`, gate scripts

**What**: After migrating all gates to `sgate` form (A4), run `bash scripts/ci/static-gates.sh` via `uv run --python 3.12 bash scripts/ci/static-gates.sh` and confirm all gates pass in the current codebase.

**Expected**: All gates pass (zero failures on main branch).

**Verification**:
```bash
uv run --python 3.12 bash scripts/ci/static-gates.sh
# Expected: exit 0
```

---

#### C2 · Verify path-filtered CI logic end-to-end (dry-run)

**FR**: FR-003  
**Type**: validate  
**File**: `.github/workflows/ci.yml`

**What**: Simulate the path-filtered job decisions locally using the `dorny/paths-filter` CLI or by manually tracing the filter logic with known PR file sets.

**Validation scenarios**:

| PR changes | Expected jobs |
|-------------|---------------|
| `docs/CONTEXT.md` | `static` only |
| `scripts/ci/static-gates.sh` | `static` only |
| `shared/src/commonMain/**/*.kt` | `static` + `tests` + `ci-gate` |
| `androidApp/src/**/*.kt` | `static` + `tests` + `ci-gate` |
| `desktopApp/src/**/*.kt` | `static` + `tests` + `ci-gate` |
| Mixed: `docs/` + `shared/` | `static` + `tests` + `ci-gate` |
| main branch push | All jobs (including `android` + `desktop`) |

**Verification**:
```bash
# Simulate a docs-only PR locally
git diff --name-only HEAD~1 HEAD | grep -v '^docs/' && echo "No non-docs files changed"
# Then verify ci.yml would route to static-only job
```

---

#### C3 · Verify JUnit annotation script against real XML

**FR**: FR-001  
**Type**: validate  
**File**: `scripts/junit-annotations.py`

**What**: Run `scripts/junit-annotations.py` against a real JUnit XML produced by a desktop test run. Generate a test failure, run the tests, then run the annotation script.

**Steps**:
```bash
# 1. Run desktop tests with retry enabled
./gradlew :desktopApp:test -Dretry.maxAttempts=2 --info 2>&1 | tail -20

# 2. Find the generated XML
find desktopApp/build/test-results -name "*.xml" -newer desktopApp/build -exec head -20 {} \;

# 3. Run annotation script
python scripts/junit-annotations.py desktopApp/build/test-results/test/

# 4. Verify output contains ::error or ::notice annotations
```

---

#### C4 · Run `check-test-runs.py` against retry-merged XML

**FR**: FR-005  
**Type**: validate  
**File**: `scripts/check-test-runs.py`

**What**: After running desktop tests with `mergeReruns=true` and retry enabled, verify that `check-test-runs.py` correctly counts tests (each `<testcase>` = 1 test, no double-counting from retries).

**Steps**:
```bash
# 1. Run desktop tests with retry
./gradlew :desktopApp:jvmTest -Dretry.maxAttempts=2

# 2. Count <testcase> elements in the XML
find desktopApp/build/test-results -name "*.xml" -exec grep -c "<testcase" {} \;

# 3. Verify check-test-runs.py passes with correct counts
python scripts/check-test-runs.py desktopApp/build/test-results/test/
# Expected: exit 0, correct test counts
```

---

#### C5 · Confirm preflight fails fast with missing tool

**FR**: FR-004  
**Type**: validate  
**File**: `.github/actions/setup-repo-tools/action.yml`

**What**: Test that the preflight action fails within 5 seconds when a required tool is absent.

**Steps**:
```bash
# Simulate missing singularity-adr
PATH=/usr/bin:/bin bash -c '
# This is a simplified local test; CI uses the actual action
echo "Testing missing singularity-adr..."
time bash -c "command -v singularity-adr || { echo \"::error ::Missing tool: singularity-adr\"; exit 1; }"
'
# Expected: error annotation + exit 1 + time < 5s
```

**Verification in CI** (after merging):
- Open a PR touching only `scripts/ci/static-gates.sh`
- CI should run preflight action first
- If preflight exits 1 → CI check fails with descriptive error

---

## Task Dependency Graph

```
A1 (preflight action)          ← no dependencies
A2 (junit-annotations.py)      ← no dependencies
A5 (pip install junitparser)   ← no dependencies
A6 (verify check-test-runs)    ← no dependencies
A4 (sgate migration)            ← no dependencies
A3 (ci.yml changes)             ← A1, A2, A5, A4

B1 (mergeReruns gradle)         ← no dependencies
B2 (RetryExtension.kt)          ← B1 (for local test verification)
B3 (register extension)         ← B2

C1 (gate parity)                ← A4
C2 (path filter validation)    ← A3
C3 (annotation script test)     ← A2, A3
C4 (check-test-runs vs mergeReruns) ← B1, B3
C5 (preflight fails fast)       ← A1
```

---

## Definition of Done

All tasks complete when:

- [ ] `scripts/ci/static-gates.sh` uses `sgate <command>` for all ~45 Python gates — zero raw `python3 scripts/` invocations remain
- [ ] `ci.yml` has a `changes` job with `dorny/paths-filter` and all 4 path categories
- [ ] Each existing job (`static`, `tests`, `ci-gate`) has `needs: changes` and a correct `if:` condition
- [ ] `static` job has preflight step gated by `needs.changes.outputs.scripts == 'true'`
- [ ] Desktop and JVM test jobs have `junit-annotations.py` step after test execution
- [ ] HTML test reports are uploaded as artifacts from desktop test job
- [ ] `RetryExtension.kt` uses `InvocationInterceptor` and consumes `retry.maxAttempts`
- [ ] `RetryExtension` is registered in JUnit 5 service loader
- [ ] `reports.junitXml.mergeReruns.set(true)` is present in `desktopApp/build.gradle.kts`
- [ ] `junitparser` is in CI pip install line
- [ ] `scripts/check-test-runs.py` correctly counts tests from `mergeReruns=true` XML
- [ ] `just gate` passes locally (gate parity)

---

## Files Summary

| File | Type | Group |
|------|------|-------|
| `.github/actions/setup-repo-tools/action.yml` | **new** | A |
| `scripts/junit-annotations.py` | **new** | A |
| `.github/workflows/ci.yml` | modify | A |
| `scripts/ci/static-gates.sh` | modify | A |
| `scripts/check-test-runs.py` | modify (comment only) | A |
| `desktopApp/build.gradle.kts` | modify | B |
| `desktopApp/src/jvmTest/kotlin/com/singularity/todo/test/RetryExtension.kt` | **new** | B |
| `desktopApp/src/jvmTest/resources/META-INF/services/org.junit.jupiter.api.extension.Extension` | modify | B |
