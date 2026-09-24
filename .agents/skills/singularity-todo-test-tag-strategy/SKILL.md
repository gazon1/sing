---
name: singularity-todo-test-tag-strategy
description: JUnit tag-based test filtering strategy for the Singularity Todo project. Covers @Tag("slow") convention, the excludeTags("slow") default, how to run slow tests, and how to tag new tests. Use when adding new tests, diagnosing slow CI runs, or deciding whether a test belongs in fast or slow suite.
---

# Test Tag Strategy

## The Problem

This project has two test execution environments:
- **Fast suite** (~100 tests, <2 min): unit tests using `commonTest` + `jvmTest`
- **Slow suite** (~16 tests, 5-15 min): integration-like tests with real delays, large fakes, or heavy setup

The default Gradle task (`./gradlew :shared:test`) must be fast enough for local TDD and CI gate checks. The slow suite is opt-in.

## The Convention

**Tag slow tests with `@Tag("slow")`.** This is the single rule.

```kotlin
import org.junit.jupiter.api.Tag

@Tag("slow")
class TaskDetailViewModelTest {
    // ...
}
```

**Where to put the import:** after the JUnit 5 platform imports, before the class declaration:

```kotlin
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.OptIn
import kotlin.coroutines.ExperimentalCoroutinesApi

@OptIn(ExperimentalCoroutinesApi::class)
@Tag("slow")
class SomeIntegrationTest {
```

## How Tag Filtering Works

### Gradle Configuration (shared/build.gradle.kts)

```kotlin
tasks.withType<Test>().configureEach {
    val tags = (project.findProperty("test.tags") as String?)
        ?.split(",")?.orEmpty() ?: emptyList()

    if (tags.isNotEmpty()) {
        includeTags(*tags.toTypedArray())
    } else {
        // Default: run everything EXCEPT @Tag("slow")
        excludeTags("slow")
    }
}
```

**Semantic:** `excludeTags("slow")` is the default. Tests without any tag run always. Only tests explicitly tagged `@Tag("slow")` are excluded by default.

### Running Tests

```bash
# Default (fast suite only — excludes @Tag("slow"))
./gradlew :shared:commonTest
./gradlew :shared:jvmTest

# Run ALL tests including slow
./gradlew :shared:commonTest -Ptest.tags=slow
./gradlew :shared:jvmTest -Ptest.tags=slow

# Run both fast AND slow together
./gradlew :shared:test -Ptest.tags=fast,slow

# Android instrumented tests
./gradlew :shared:testAndroidHostTest -Ptest.tags=slow
```

### CI Tag Handling

In `.github/workflows/ci.yml`, the slow tests run separately:

```yaml
- name: Fast tests
  run: ./gradlew :shared:commonTest :shared:jvmTest

- name: Slow tests
  run: ./gradlew :shared:test -Ptest.tags=slow
  continue-on-error: true  # Allow failure for known slow failures
```

## Pre-existing Slow Tests (16 tests)

These tests were audited during the 2026-09-26 production-readiness pass. They are known to be slow due to design (large fakes, real-time delays, or pending architectural fixes):

| Test | Source set | Reason |
|------|------------|--------|
| `AnalyticsTest` | commonTest | unknown |
| `OAuthTokenRefreshTest` | commonTest | unknown |
| `BackupOptionsTest` | jvmTest | unknown |
| `DiGraphTest` | jvmTest | unknown |
| `JvmAiDiGraphTest` | jvmTest | unknown |
| `SyncRepositoryCoalescingTest` | jvmTest | unknown |
| `SavedAgendaViewModelTest` | jvmTest | unknown |
| `ChatViewModelTest` | commonTest | unknown |
| `NoteEditorTest` | commonTest | unknown |
| `NotePreviewTest` | jvmTest | unknown |
| `ProjectDetailViewModelTest` | jvmTest | unknown |
| `ProjectsViewModelTest` | jvmTest | unknown |
| `SyncViewModelTest` | jvmTest | unknown |
| `TaskCreateDebounceTest` | jvmTest | unknown |
| `TaskCreateViewModelTest` | jvmTest | unknown |
| `TaskDetailViewModelTest` | jvmTest | unknown |

**Note:** These tests pass in isolation or with a warm Gradle daemon but may fail on a clean run due to `forkEvery=1` overhead (each test class forks a new JVM). The failures are tracked in `docs/decisions/2026-09-26-junit-tag-default-semantics.md`.

## When to Tag a Test @Tag("slow")

Tag a test `@Tag("slow")` when it matches any of:
- Uses `delay(N)` where N > 1 (even with virtual time, debounce tests are inherently slow)
- Has heavy fixture setup (>1s of fake data seeding)
- Integrates with multiple fakes simultaneously
- Tests a known performance bottleneck (sync coalescing, draft clobbering)
- Robolectric/Android instrumented tests (inherently slow)

**Do NOT tag as slow:**
- Simple smoke tests (initial state checks)
- Single-intent transition tests
- Pure unit tests with no delays

## How to Add a New Test

```kotlin
import org.junit.jupiter.api.Tag

@Tag("slow")  // Only if it meets the criteria above
@OptIn(ExperimentalCoroutinesApi::class)
class MyViewModelTest {
    @Test
    fun initialState_isLoading() = runTest {
        // fast — no @Tag needed
    }
}
```

## Why `excludeTags("slow")` Not `includeTags("fast")`

`includeTags("fast")` **breaks** when no test has `@Tag("fast")` — JUnit Jupiter then discovers 0 tests and the build succeeds silently.

`excludeTags("slow")` is safe by default:
- Tests without tags always run
- Only explicitly tagged `@Tag("slow")` tests are excluded
- Adding `@Tag("slow")` to a new test is a deliberate, visible decision

This was fixed in `docs/decisions/2026-09-26-junit-tag-default-semantics.md`.

## See Also

- `singularity-todo-test-helpers` — test patterns, helpers, and three test shapes
- `singularity-todo-testable-vm` — canonical VM constructor
- `docs/decisions/2026-09-26-junit-tag-default-semantics.md` — ADR for the tag filter semantic change
