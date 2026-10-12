# Data Model: CI Feedback Loop Acceleration

**Feature**: `001-ci-feedback-loop`
**Date**: 2026-10-12

## Overview

This feature operates entirely on transient CI artifacts produced and consumed within a single CI run. No persistent data is stored. The "data model" here describes the shape of the artifacts that are produced, consumed, and transformed by the new CI steps.

---

## CI Artifact Schemas

### 1. JUnit XML (Input)

Produced by: Gradle test tasks
Consumed by: `scripts/junit-annotations.py` (via `junitparser` library)

Schema (JUnit 5 / Gradle with `mergeReruns = true`):
```xml
<testsuite name="..." tests="N" failures="M" errors="0" skipped="0">
  <!-- Pass on retry: merged into single testcase with flakyFailure -->
  <testcase name="<methodName>"
            classname="<fully.qualified.ClassName>"
            time="0.042">
    <flakyFailure message="..." type="...">
      <![CDATA[<stacktrace from first attempt>]]>
    </flakyFailure>
  </testcase>

  <!-- Persistent failure: single testcase with failure -->
  <testcase name="<methodName>"
            classname="<fully.qualified.ClassName>"
            time="0.084">
    <failure message="<message>" type="<ExceptionType>">
      <![CDATA[<stacktrace>]]>
    </failure>
  </testcase>
</testsuite>
```

Key fields:
- `testcase/@name` — method name
- `testcase/@classname` — fully-qualified class name
- `flakyFailure/@message` — failure from first attempt (retry-passed test)
- `failure/@message` — persistent failure reason
- `failure/text()` — full stack trace (used for annotation body)

**No deduplication needed**: `mergeReruns = true` ensures one `<testcase>` per test method. `flakyFailure` child identifies retry-passes for the annotation script. `check-test-runs.py` counts `<testcase>` elements directly without deduplication.

---

### 2. GitHub Annotation (Output)

Produced by: `scripts/junit-annotations.py`
Consumed by: GitHub Actions (check run display)

Format — `::error` workflow command:
```
::error file={source_file}:{line}::title={Class.method}::{failure_message}
```

Since test source files are not available in CI artifacts, the annotation uses:
- `file=...` — path to test source file if determinable, or `.` (root) as fallback
- `line=1` — default; actual line not available without parsing source
- `title=<Class.method>` — short, human-readable identifier
- `::<message>` — failure message body

For retry-passed events (warning annotation):
```
::warning title=Passed on retry::{N} test(s) passed on retry (class: ClassName)
```

---

### 3. HTML Test Report (Artifact)

Produced by: Gradle `test` task
Uploaded by: new CI step to `html-reports` artifact
Location in Gradle build: `**/build/reports/tests/test/**`

No schema documentation needed — this is a standard Gradle HTML report, uploaded as-is.

---

### 4. Screenshot / Semantics Tree (Artifact)

Produced by: `DesktopAppHarness.kt` on test failure
Uploaded by: existing `ci-evidence-${{ github.run_id }}` step
Location: `desktopApp/build/diagnostics/**`

Existing artifact upload path already covers this. No new artifact type needed.

---

### 5. Preflight Result (Runtime State)

Produced by: `.github/actions/setup-repo-tools/action.yml`
Consumed by: CI job continuation decision

Runtime environment variables set by the action:
- `REPO_TOOLS_PREFLIGHT_RC` — exit code (0 = pass, non-zero = fail)
- `REPO_TOOLS_PREFLIGHT_MISSING` — comma-separated list of missing tools (if any)

The action fails the CI step via `exit 1` when tools are missing. No data model persistence.

---

## Validation Rules

| Artifact | Rule |
|----------|------|
| JUnit XML | Must be parseable by `junitparser` library |
| JUnit XML | Malformed XML → script logs warning, exits 0 (graceful degradation) |
| JUnit XML | Missing file → script logs warning, exits 0 (graceful degradation) |
| GitHub Annotation | `::error` title limited to 64 chars by GitHub; long class names truncated |
| HTML Report | `actions/upload-artifact` must find at least one file; `if-no-files-found: warn` |
| Preflight | Missing tool → `exit 1` within 5 seconds |

## New Python Dependency

`junitparser` library — add to CI Python environment in `ci.yml` `static` job setup step:
```bash
python -m pip install --disable-pip-version-check -e scripts/src/ pyyaml junitparser
```
