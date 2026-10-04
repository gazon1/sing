---
title: "A green test run is a number, not a pass/fail: gate the executed count"
status: accepted
date: 2026-10-04
tags: [testing, ci, gradle, junit, process]
---

# Context

Closing out the `navigation-open-policy` epic (ADR `2026-10-04-navigation-policy`)
required running the suite the way CI runs it. That run exposed a defect in the
epic's own B0 step, and in the process around it.

B0 recorded a "green baseline" before making any change. It was green. It was
also nearly empty:

| Command | Classes executed | Tests |
|---|---|---|
| `:shared:jvmTest -Ptest.tags=fast,slow` (what CI runs) | **16** of 203 | 109 |
| `:desktopApp:test -Ptest.tags=fast,slow` | **0** of 41 | 0 |
| `:shared:jvmTest` (no `-Ptest.tags`) | 176 | 1426 |
| `:desktopApp:test` (no `-Ptest.tags`) | 27 | 77 |

Both CI invocations printed `BUILD SUCCESSFUL`. `NavigationPolicyTest`,
`ScreenFamilyTest`, `Nav3StateReselectTest`, `NavSavedStateConfigTest` and every
desktop flow test had never run in CI. A "baseline" that records pass/fail
without recording *how much ran* cannot detect that its own verification is
vacuous.

Two independent causes, and the second was not obvious:

1. **JUnit matches tags per class.** `includeTags("fast","slow")` selects only
   classes carrying one of those tags; an untagged class is dropped without a
   warning. 16 of 218 test classes were tagged.

2. **Tag filters are engine-specific.** 23 of 28 `desktopApp` classes were JUnit
   4 (`org.junit.Test`) running on the Vintage engine, where
   `org.junit.jupiter.api.Tag` is *invisible* to `includeTags(...)`. Tagging
   those classes changed nothing: they still did not run. The tag was on the
   class and the class was still invisible, because Vintage does not map Jupiter
   annotations onto Platform tags.

Cause 2 generalises: any test engine that is not Jupiter is a blind spot for
every tag-based gate in the build, silently.

# Idea

Three different mechanisms fail at three different granularities, and the build
needs all three:

- **Nothing ran** — `failOnNoDiscoveredTests = true` on the test tasks. A Gradle
  task that discovers zero tests is a misconfiguration, not a pass.
- **A class has no tag** — `TestTagCoverageTest`, a source-level arch test. Fails
  the build if any class declaring `@Test` lacks `@Tag`. Per *class*, not per
  file: a file with two test classes and one tag still loses the other.
- **A run got smaller than it should be** — `scripts/check-test-runs.py`, which
  compares executed class/test counts against a committed floor. This is the
  only one that catches a *partial* skip, which neither of the others can: a run
  of 1500 out of 1500 and one of 1400 out of 1500 both satisfy "all classes
  tagged" and "tests discovered".

The floor records the **smallest count any legitimate run produces** — the
default local run executes only `@Tag("fast")` classes, and CI's
`fast,slow` run is a superset. Recording the CI numbers instead would make every
plain local run look like a regression.

# Decision

- Tag every test class per ADR `2026-09-25-test-suite-tag-defaults`; migrate the
  `desktopApp` JUnit 4 classes to Jupiter and drop the now-unused Vintage engine
  so a JUnit 4 test cannot reappear and hide.
- `failOnNoDiscoveredTests = true` on the `shared` and `desktopApp` test tasks.
- `TestTagCoverageTest` (in `shared/src/jvmTest/.../arch/`) enforces the tag at
  the source level. Verified by stripping a tag and watching it fail.
- `scripts/check-test-runs.py` + `config/docs/test-runs-baseline.txt` enforce
  the executed-count floor, wired into all three CI jobs that run tests. It
  takes `--require` so a job asserts the source sets it is responsible for
  instead of failing on the ones that belong to another job.
- The `shared:testAndroidHostTest` floor lives in the kover job, because kover is
  the only thing that pulls in the Robolectric/Android source set.

# Rationale

Every one of these is a claim that a specific thing is true. "Tests passed" is
not a claim that the tests ran. Recording the executed count converts an
assumption into a checked fact, and the floor makes the check cheap: comparing
two integers is nothing next to running the suite, so there is no excuse not to
do it on every run.

The floor is a floor, not an equality check, deliberately. A suite that grows is
fine and should not need a baseline edit; a suite that shrinks is a defect by
definition, because the only ways a class stops running are an untagged class, a
non-Jupiter class, a renamed module or a narrowed filter — none of which are
ever intentional.

# Consequences

- Adding tests needs no baseline update. Removing or deselecting one fails CI
  with a message naming the source set and the size of the drop.
- `--update-baseline` exists, and the baseline header says plainly: a rise means
  tests were added, a **drop means investigate, do not regenerate**.
- The `desktopApp` Vintage removal means a future JUnit 4 test there fails to be
  discovered rather than silently passing — which `failOnNoDiscoveredTests`
  catches, and which is the correct failure mode for a forbidden construct.
- `testAndroidHostTest` is now covered by a floor. It previously had no gate of
  its own, which is how a profile-isolation failure could sit there red
  (see `2026-10-04-derived-identity-flows`).
- The lesson generalises past tests: **any gate that reports a boolean where it
  could report a magnitude should report the magnitude.** A coverage percentage,
  a migration count, a schema version — all of them have the same failure mode,
  where the check passes because the thing it measures is smaller than it was.

# Links

- ADR `2026-09-25-test-suite-tag-defaults` — the fast/slow convention being enforced
- ADR `2026-09-30-testtag-registry-honesty` — the sibling "declared but never applied" contract
- ADR `2026-10-04-navigation-policy` — the epic whose B0 baseline this invalidated
- `scripts/check-test-runs.py`, `config/docs/test-runs-baseline.txt`
- `shared/src/jvmTest/kotlin/com/singularity/todo/arch/TestTagCoverageTest.kt`

# Addendum (2026-10-04): the tags themselves were not calibrated to cost

This ADR enforced that every class carries a `@Tag` and that the executed count
matches a floor. It did not ask whether the two tags mean anything — and they did
not.

Measured over a full `fast,slow` run of all three host source sets: median class
**0.19s**, p90 **0.55s**, p95 **0.77s**, slowest **4.4s** (`McpServerEndToEndTest`,
which spawns a JVM). Total test time across 193 classes: 58s. The Android host set
is the same shape: p90 0.92s, max 2.1s, 39s total.

Seventy-four classes were tagged `slow`. Of those, **56 contain no `delay`, no
`withTimeout`, no Room driver, no Compose harness and no `ProcessBuilder`** — they
are `runTest` on the virtual clock with fakes, the fastest kind of test that
exists. `NavigationPolicyTest` (296 lines of pure policy logic), `SlugTest`,
`ScreenFamilyTest`, `DestinationKindTest` and `MimeTypesTest` were all in that
group.

The consequence was concrete: `./gradlew :shared:jvmTest` without `-Ptest.tags`
runs 142 of 190 classes, and **not one navigation test from this branch** —
`NavigationPolicyTest`, `ScreenFamilyTest`, `DestinationKindTest` and
`NavSavedStateConfigTest` only ever ran in CI. A developer had no way to see them
locally, and the count floor recorded the small number as correct rather than
noticing that the small number was the wrong one.

**The rule now:** `slow` means the class crosses the process boundary in a way that
can block or hang — a Compose UI harness, a real database file, the real filesystem
or clock, a Konsist scan whose cost grows with the repository, or a spawned
process. Everything else is `fast`, whatever it measures. Cost is a symptom; the
kind of resource the test touches is the reason.

41 classes moved `slow` → `fast`, leaving 33. The result: 184 fast, 33 slow, and the
default local loop now runs essentially the whole suite. The floors in
`config/docs/test-runs-baseline.txt` move with it — upward, which is the only
direction a baseline should move without an investigation.

Calibrating the tags also removed a flake source rather than hiding one.
`TaskDetailCoordinatorGraphTest` waited on `withTimeout(10.seconds)` against real
dispatchers and a real Room driver, and failed roughly one run in three when three
instrumented modules compiled in parallel. The wait cannot become virtual time — the
emissions genuinely come from other threads — so the budget was raised to one
minute and reframed as what it actually is: a **hang detector**, not a latency
assertion. A `combine` that dies before its first emission hangs forever, so a
generous bound still catches it; it just costs 60 seconds instead of 10 when the
code is broken. The failure message now prints the sequence of states it observed
instead of a bare `TimeoutCancellationException`.

Two flakes are recorded in `config/docs/flaky-baseline.txt` with their reasons, and
`check-flaky-tests.py` compares consecutive runs to find more. See ADR
`2026-10-04-measurement-integrity`.
