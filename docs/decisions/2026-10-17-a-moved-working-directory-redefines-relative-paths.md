---
title: "A moved working directory redefines every relative path in a test"
date: 2026-10-17
status: accepted
tags: [testing, build, ci]
---

# A moved working directory redefines every relative path in a test

## Context

`main` went red on run 39005284790 with two failures in one step:

```
> Task :shared:detekt FAILED          — 36 issues
> Task :shared:jvmTest FAILED         — 3020 tests completed, 7 failed
GenuiSurfaceCorpusTest[jvm] > everyCorpusFileExists()[jvm] FAILED
    java.io.FileNotFoundException at GenuiSurfaceCorpusTest.kt:118
GenuiFirstAttemptReplayTest[jvm] > aRecordedAnswerStillNeedsOnlyOneAttempt()[jvm] FAILED
    java.io.FileNotFoundException at GenuiFirstAttemptReplayTest.kt:137
```

The cause is not in either test. `shared/build.gradle.kts` sets

```kotlin
workingDir = allureDir.also { it.mkdirs() }   // shared/build/allure-results
```

on the `jvmTest` forked JVM, so that Allure's listener — which computes its
results path relative to the working directory and ignores
`allure.results.directory` — writes where the CI upload step looks. Correct
fix, correct reason. But a relative `File(...)` in a test resolves against the
*working directory*, so every such path in `:shared:jvmTest` silently changed
meaning the day it landed. Nothing in the build said so.

Two symptoms, and the quiet one is the expensive one:

- `GenuiSurfaceCorpusTest` and `GenuiFirstAttemptReplayTest` read a known file,
  so they failed loudly with `FileNotFoundException`.
- `SharedHarnessConventionsTest` **walks** `src/jvmTest/kotlin/com/singularity/todo`
  and enforces four harness rules over the result. The directory no longer
  existed, the walk returned nothing, and every rule reported green over zero
  files. It had not been checking anything since the change landed.

## Idea

- **A.** Revert the Allure `workingDir`.
- **B.** Leave it, and make the three tests robust.
- **C.** Leave it, make the tests robust, and make the class of defect
  self-announcing.

## Decision

**B**, with the announcement part of C that is cheap and real.

- `jvmTest` now passes `jvmTestSources.root` and `jvmTestResources.root`, beside
  the `commonMain.root` and `desktopAppJvmTest.root` properties already there for
  exactly this reason — a property that says where the tree is, rather than an
  assumption about where the JVM was started.
- `SourceScan.jvmTestSourcesRoot()` / `jvmTestResourcesRoot()` read them, and
  `error(...)` when a property is unset, so a test that needs the tree fails with
  "the property is not set" instead of scanning nothing.
- The three call sites resolve through those helpers.

**Not** reverted: the Allure fix is right, and its stated purpose — results where
the uploader reads them — is unaffected by any of this.

## Rationale

- The loud failures were the easy half. `SharedHarnessConventionsTest` was a
  source-scanning gate reporting green over zero files, which is the same
  measurement-integrity defect this repository keeps finding: a measurement over
  state it does not own. Fixing only the failures would have left it.
- A property whose absence is an `error` cannot be "unset and fine". That is the
  difference between a path that must resolve and a path that may not.
- Verified rather than assumed. The three classes pass locally with the CI tag set
  (`-Ptest.tags=fast,slow`) after the change and 7 of 8 failed before it; and the
  harness gate's negative control now fires — a planted
  `while (!ready) { advanceUntilIdle() }` makes
  `awaitState is used instead of raw advanceUntilIdle polling loops` fail, which
  it did not before.

## Consequences

- Any future test in `:shared` that reads a file has a documented way to find it,
  and a property error if it forgets.
- The `:shared:detekt` failure from the same run is a separate defect and is not
  addressed here.

## Links

- `shared/build.gradle.kts` (`workingDir`, the two new system properties)
- `shared/src/jvmTest/kotlin/com/singularity/todo/arch/SourceScan.kt`
- `GenuiSurfaceCorpusTest`, `GenuiFirstAttemptReplayTest`, `SharedHarnessConventionsTest`
- Run: <https://github.com/gazon1/sing/actions/runs/39005284790>
- [2026-10-04-configuration-cache-hardening.md](2026-10-04-configuration-cache-hardening.md)