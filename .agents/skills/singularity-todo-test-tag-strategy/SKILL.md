---
name: singularity-todo-test-tag-strategy
description: 'Decide which JUnit tag a test class carries, and how the fast/slow split is applied in Gradle, CI and local runs. Use when adding or changing @Tag usage, when a test silently stops being selected, or when -Ptest.tags is not running what you expect.'
---

# Test Tag Strategy

## What the two tags mean

- **`@Tag("fast")`** — the default loop. The class stays inside the JVM process and
  uses fakes, the virtual clock, or in-memory collaborators.
- **`@Tag("slow")`** — the class crosses a process boundary in a way that can block
  or hang: a Compose UI harness, a real database file, the real filesystem or clock,
  a Konsist scan whose cost grows with the repository, or a spawned process.

**The test is whether the class touches something that can block — not how long it
takes to run.** Cost is a symptom; the kind of resource is the reason.

Measured over a full run of all three host source sets (2026-10-04): median class
0.19s, p90 0.55s, slowest 4.4s (`McpServerEndToEndTest`, which spawns a JVM). Before
the rule was recalibrated, 56 of 74 `slow` classes contained no `delay`, no
`withTimeout`, no Room driver, no Compose harness and no `ProcessBuilder` — they
were `runTest` on the virtual clock, the fastest kind of test there is. The
consequence was that `./gradlew :shared:jvmTest` ran 142 of 190 classes and **no
navigation test at all**, so those tests only ever executed in CI.

Guessing produces exactly that failure. When a class does not obviously fit, measure
it: `shared/build/test-results/<sourceSet>/TEST-*.xml` carries a per-suite `time`.

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

### Gradle Configuration (shared/build.gradle.kts, desktopApp/build.gradle.kts)

```kotlin
tasks.withType<Test>().configureEach {
    // Discovering zero tests is a misconfiguration, not a pass.
    failOnNoDiscoveredTests = true

    useJUnitPlatform {
        val tags = (project.findProperty("test.tags") as String?)
            ?.split(",")?.orEmpty() ?: emptyList()
        if (tags.isNotEmpty()) {
            includeTags(*tags.toTypedArray())
        } else {
            // Default: run everything EXCEPT @Tag("slow")
            excludeTags("slow")
        }
    }
}
```

**The two modes are opposites, and only one of them tolerates a missing tag:**

| | filter | an untagged class |
|---|---|---|
| default (no `-Ptest.tags`) | `excludeTags("slow")` | **runs** |
| CI (`-Ptest.tags=…`) | `includeTags(…)` | **silently skipped** |

JUnit matches tags **per class**. So under `includeTags(...)` a class with no `@Tag` is
dropped from the run with no error, and Gradle still reports `BUILD SUCCESSFUL`. This is
not theoretical: with only 16 of 218 classes tagged, CI ran 16 classes in `shared` and
**zero** in `desktopApp` for months, and no navigation or desktop-flow test had ever run.

**Every test class must carry `@Tag("fast")` or `@Tag("slow")`.** Enforced twice:

- `TestTagCoverageTest` (arch test) fails the build on an untagged class that declares `@Test`;
- `scripts/check-test-runs.py` fails when a source set executes fewer classes/tests than
  `config/docs/test-runs-baseline.txt` — the only check that catches a *partial* skip.

Both are blocking. See ADR `2026-10-04-test-execution-integrity`.

### The tag is engine-specific

`includeTags` sees **Jupiter** annotations only. A JUnit 4 class (`org.junit.Test`) on the
Vintage engine carries no Platform tag even when annotated `@Tag`, so it stays invisible to
the filter — the tag is on the class and the class still does not run. This is how 23 of 28
`desktopApp` classes stayed unrun after they had been tagged.

`desktopApp` is now Jupiter-only (`kotlin.test.Test`) and the Vintage engine is removed, so
a JUnit 4 test there is not discovered at all — which `failOnNoDiscoveredTests` reports
rather than silently ignoring. `shared/src/androidHostTest` still carries the Vintage
engine for Robolectric; anything in that source set is outside the tag filter's reach, so
do not rely on a tag to schedule it.

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

## Enforced, not documented (2026-10-04)

Three gates now make the convention mechanical. All of them are blocking.

| Gate | What it catches |
|---|---|
| `TestTagCoverageTest` (`:shared:jvmTest`) | a class with no `@Tag` at all |
| `scripts/check-test-runs.py` | a source set running fewer classes/tests than the floor in `config/docs/test-runs-baseline.txt` |
| the same gate, skipped column | **any** skipped test — `@Disabled` or a failing assumption guard |

The skipped ceiling is the one that surprises people. JUnit counts a skipped testcase
inside `tests=` exactly like a passing one, so a count floor cannot see a disabled
class disappear. `TaskOutgoingLinksTest` sat `@Disabled` for a month behind a fully
green task. Do not raise the ceiling to make the gate pass — re-enable the test.

Re-tagging moves the floors. Regenerate with
`python3 scripts/check-test-runs.py --update-baseline` in the same commit, and only
when the delta is intentional. A drop means a suite stopped being selected:
investigate, never regenerate.

See ADR `2026-10-04-test-execution-integrity` (addendum: the tags were not
calibrated to cost) and ADR `2026-10-04-measurement-integrity`.
