# Research: CI Feedback Loop Acceleration

**Feature**: `001-ci-feedback-loop`
**Date**: 2026-10-12

## Decision 1: GitHub Actions per-test annotation API

**What was chosen**: Use `echo "::error file=... line=... title=<Class.method>::<message>"` format for per-test annotations.

**Rationale**: GitHub Actions `::error` workflow commands require no additional permissions and work natively within CI steps. Each line emitted as `::error` becomes a separate annotation on the check run. The format supports `file`, `line`, `col` for location and `title` for the annotation title. This is sufficient for per-test annotations (class.method as title, failure message as body).

**Alternatives considered**:
- `gh api repos/{owner}/{repo}/check-runs/{id}/annotations` — requires `checks: write` permission and a multi-step process (create check run → upload annotations in batches). More powerful but significantly more complex for a single script.
- Third-party action like `ap力道/github-test-summary-action` — adds a dependency; the native `::error` approach requires no new dependency.

**Decision**: Native `::error` workflow commands from a Python script (`scripts/junit-annotations.py`). The script will parse JUnit XML and emit one `::error` line per failing test case.

---

## Decision 2: JUnit XML → annotation mapping for desktop tests

**What was chosen**: Parse JUnit XML using the `junitparser` Python library (6M+ monthly downloads) to handle schema variations robustly.

**Rationale**: The existing `check-flaky-tests.py` uses hand-rolled XML parsing. `junitparser` handles standard and custom JUnit schemas, supports merging multiple XML files, and correctly handles `<flakyFailure>` elements (see Decision 5). It is a well-maintained, widely-used library that removes the need for custom ElementTree code.

**Schema target**:
```xml
<testcase name="testMethod" classname="com.singularity.TestClass" time="0.042">
  <failure message="assertion failed" type="AssertionError">...</failure>
</testcase>
```
- `classname` + `name` → `TestClass.testMethod`
- `<failure>/@message` → annotation title suffix
- `<failure>/text()` → annotation body

**Alternatives considered**: Custom `xml.etree.ElementTree` parsing — rejected because `junitparser` handles schema variations, namespaces, and CDATA sections more robustly than hand-rolled code.

---

## Decision 3: Desktop Compose UI retry mechanism

**What was chosen**: Custom JUnit 5 `InvocationInterceptor` extension consuming the already-forwarded `retry.maxAttempts` system property.

**Why `TestExecutionExceptionHandler` is insufficient**: `TestExecutionExceptionHandler.handleTestExecutionException()` is called *after* a test method throws. It can only swallow or rethrow the exception — it cannot itself re-invoke the test method. For actual retry-on-failure, `InvocationInterceptor.interceptTestMethod()` is the correct JUnit 5 API: it wraps the test invocation and can call `invocation.proceed()` multiple times in a loop.

**Implementation**:
```kotlin
// desktopApp/src/jvmTest/kotlin/com/singularity/todo/test/RetryExtension.kt
class RetryExtension : InvocationInterceptor {
    private val maxAttempts = System.getProperty("retry.maxAttempts")?.toIntOrNull() ?: 1

    override fun interceptTestMethod(
        invocation: InvocationInterceptor.Invocation<Void>,
        invocationContext: ReflectiveInvocationContext<Method>,
        extensionContext: ExtensionContext,
    ) {
        if (maxAttempts <= 1) { invocation.proceed(); return }
        var lastThrowable: Throwable? = null
        for (attempt in 1..maxAttempts) {
            try {
                invocation.proceed()
                if (attempt > 1) println("[FLAKY] '${extensionContext.displayName}' passed on attempt $attempt")
                return
            } catch (t: Throwable) {
                lastThrowable = t
                println("[RETRY] '${extensionContext.displayName}' failed on attempt $attempt: ${t.message}")
            }
        }
        throw lastThrowable!!
    }
}
```

- Registered via `META-INF/services/org.junit.jupiter.api.extension.Extension`
- `retry.failOnPassedAfterRetry` controls whether a retry-pass is reported as flaky-info or as a failure (always `false` in PR runs per `RETRY_ARGS` in `ci.yml`)
- `mergeReruns = true` in `desktopApp/build.gradle.kts` — JUnit XML records one `<testcase>` per test with `<flakyFailure>` for retry-passes; `check-test-runs.py` counts directly without deduplication

**Existing wiring confirmed**:
- `retry.maxAttempts` already forwarded as `systemProperty` in `desktopApp/build.gradle.kts:177`
- `retry.failOnPassedAfterRetry` already forwarded at line 178

---

## Decision 4: Path-filtered CI job matrix behavior

**What was chosen**: Job-level path filtering using a lightweight `changes` job with `dorny/paths-filter`, gating expensive jobs with `needs.changes.outputs.*`.

**⚠️ Critical revision from initial analysis**: Workflow-level `paths:` filter on the `pull_request` trigger causes required checks to enter a "Pending" state when no paths match, blocking PR merges — even though the intent is to skip irrelevant jobs. The correct pattern is to keep the workflow trigger unconditional (always run on PR) and filter at the job level.

**Implementation**:
1. Add a `changes` job (fast, ~10 sec) that uses `dorny/paths-filter@v3` to detect which file categories changed.
2. Gate `tests` and `android` jobs with `if: needs.changes.outputs.app == 'true'`.
3. Gate preflight with `if: needs.changes.outputs.scripts == 'true'` (path-gated per FR-004 clarification).
4. `static` and `ci-gate` always run.

**Path categories**:
- `app`: `shared/**/*.kt`, `androidApp/**/*.kt`, `desktopApp/**/*.kt`, `mcp-server/**/*.kt`
- `scripts`: `scripts/**`, `.github/**`, `docs/**`
- (All other paths: both gates run)

**Why `dorny/paths-filter`**: Native git diff, no `actions: read` permission needed for file reading, fork-safe.

**Why not workflow-level `paths:`**: Would leave required checks in Pending state for script-only PRs — blocks merge. Workflow-level filtering is only appropriate when the entire workflow should be skipped, not when specific jobs within it should be conditional.

---

## Decision 5: `check-test-runs.py` retry count logic

**What was chosen**: Configure `mergeReruns = true` in the Gradle JUnit XML report task. This causes Gradle to record a retry-pass as a single `<testcase>` with a `<flakyFailure>` child, rather than two separate entries. No modification to `check-test-runs.py` count logic is needed.

**Rationale**: When `mergeReruns = true` (Gradle JUnit Report task configuration), JUnit XML produces one `<testcase>` per test method:
- **Pass on retry**: `<testcase><flakyFailure>...</flakyFailure></testcase>` — counted as 1 testcase (pass)
- **Fail twice**: `<testcase><failure>...</failure></testcase>` — counted as 1 testcase (failure)
- **Pass first time**: `<testcase/>` — counted as 1 testcase (pass)

This means `check-test-runs.py` counts the correct floor without deduplication logic. The `<flakyFailure>` element also makes it possible to distinguish retry-passes from clean passes in the annotation script.

**Configuration location**: `desktopApp/build.gradle.kts` (or a shared test configuration block) — add to the JUnit XML report task config:
```kotlin
tasks.withType<JUnitReportTask> {
    mergeReruns = true
}
```

**Still open**: Whether `repeat()` JUnit Jupiter extension is wired to `-Dretry.maxAttempts` in `desktopApp/build.gradle.kts`, or whether a JUnit `RetryRule` needs to be added. Need to inspect `desktopApp/build.gradle.kts` for the existing retry wiring.

---

## Decision 6: HTML report generation for test artifacts

**What was chosen**: Upload existing Gradle-generated HTML reports at `build/reports/tests/test/` alongside the existing JUnit XML artifact upload.

**Rationale**: Gradle's `test` task already generates HTML reports at `build/reports/tests/test/index.html` for every module. The existing `junit-results` artifact already uploads `**/test-results/**/*.xml`. Adding HTML report upload is a new `actions/upload-artifact@v7` step that adds `**/reports/tests/test/**` to the upload paths.

**Artifact naming**: Separate artifact (`html-reports`) or include in `junit-results`? Use a separate artifact named `html-reports` to keep XML and HTML concerns separate, allowing independent retention policies.
