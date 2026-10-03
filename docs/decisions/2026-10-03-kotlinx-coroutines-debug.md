---
title: JVM coroutine diagnostics via kotlinx-coroutines-debug
tags: [testing, jvm, desktop, coroutines, tooling]
created: 2026-10-03
status: draft
---

# Context

Tests contain long-running collectors (SyncEngine, todayFlow infinite loop, backgroundScope work, debounce pipelines) and lifecycle-owned scopes. Coroutine bugs — silent death before first emission, scheduler leaks, infinite loops — are invisible from test output alone.

The existing FailureBundle captures screenshot, DB state, and Kermit log on test failure. Coroutine state is not captured. A real incident (TaskDetailCoordinator NPE: coroutine dies before first emission, screen spins forever) was diagnosed only by manually calling DebugProbes.dumpCoroutinesInfo(). Automating this makes the evidence available in CI artifacts without manual intervention.

## Decision

Use kotlinx-coroutines-debug as a JVM-test-only Java agent.

The agent is attached through Gradle `-javaagent` JVM argument (not DebugProbes.install()) — required for JDK 21+ compatibility; dynamic agent loading emits a warning on JDK 21 and fails on JDK 22+.

FailureBundle captures a coroutine dump on every test failure.

## Scope

- `desktopApp:test` — PR1
- `shared:jvmTest` — PR2

Not Android. Not production. Not commonMain.

## Implementation

- Agent: `-javaagent` via named Gradle configuration + `jvmArgumentProviders` (configuration-cache-safe).
- Dependency: `kotlinx-coroutines-debug` via version catalog (version ref = coroutines = "1.11.0").
- `CoroutineDiagnostics.dump(testClass, attempt)` — test-only utility object, returns String, no DI.
- Fourth FailureBundle artifact: `coroutines.txt`, independent `runCatching` block.
- Capture order: db-state → kermit → coroutine dump → screenshot (hang-proof first).
- shared:jvmTest: hook into existing `FailureContextExtension` (auto-registered JUnit5 extension).

## Artifacts on failure

```
build/diagnostics/<TestClass>/attempt-N/
    screenshot.png
    db-state.txt
    kermit.log
    coroutines.txt   ← new
```

## Important limitations

- **Hard-hang test produces no bundle** — catch block never fires. Follow-up: CoroutinesTimeout / @Timeout global rule.
- Coroutine dump is a **snapshot** — it does not show execution history.
- Dump does **not** explain TestScheduler semantics (advanceUntilIdle vs runCurrent).
- Dump does **not** prove a coroutine is leaked.
- Does **not** replace TestScopeSemanticsTest — different problem domain.
- Overhead: DebugProbes can significantly reduce performance because it captures a creation stack trace for each coroutine. Mitigation: `DebugProbes.enableCreationStackTraces = false`.

## Canonical investigation order

1. FailureBundle (screenshot, db, kermit, coroutines.txt)
2. Coroutine dump — look for application frames before kotlinx.coroutines internals
3. Group repeated stack traces (evidence, not verdict — identical stacks may be normal)
4. Check coroutine state: RUNNING, SUSPENDED, CREATED
5. Compare the coroutine's dispatcher/scope with expected wiring
6. For backgroundScope tests: consult TestScopeSemanticsTest rules before changing production code
7. TestScheduler semantics (advanceUntilIdle vs runCurrent vs runTest virtual time)
8. Production code

## Consequences

### Positive
- Coroutine state available in CI artifacts on failure
- Silent coroutine death becomes diagnosable without manual DebugProbes invocation
- Evidence for scheduler semantics issues (backgroundScope, debounce, todayFlow)

### Known issues & follow-ups

<!-- populated by post-phase ritual after each MR -->

## Links

- [Coroutines debugging | Kotlin Documentation](https://kotlinlang.org/docs/coroutines-debugging.html)
- `2026-09-30-testscope-background-work-semantics.md`
- `2026-10-03-merge-regression-fixes.md`
- `2026-09-30-desktop-test-diagnostics.md`
