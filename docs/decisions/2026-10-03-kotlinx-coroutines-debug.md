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

### Known issues & follow-ups (post-phase ritual)

**CC fix — final:**
The `-javaagent` argument must be passed via `jvmArgs("-javaagent:$path")` with the path
resolved **eagerly as a plain `String`** at configuration time. `CommandLineArgumentProvider`
(anonymous class) captures an implicit `this` reference to the Gradle DSL script, which breaks
CC regardless of where the `Configuration` lookup is placed. The correct pattern:

```kotlin
val coroutinesDebugAgentPath: String = configurations
    .named("coroutinesDebugAgent").get()
    .resolve()
    .single { it.name.contains("debug") && it.name.endsWith(".jar") }
    .absolutePath
jvmArgs("-javaagent:$coroutinesDebugAgentPath")
```

**MR-2 findings:**
- `FailureContextExtension` file was present in `shared/src/jvmTest/` (inherited from `main` at commit `5c0c2e9d`) but was **not auto-registered** — its `META-INF/services/org.junit.jupiter.api.extension.Extension` file was added in commit `7985c234` on branch `refactor/test-coverage-ratchet` which was never merged to `main`. Recreated the service registration file to enable auto-registration.
- In `jvmTest.dependencies {}` (KMP DSL), use `implementation(...)`, not `testImplementation(...)` — the latter is for Gradle non-KMP modules.
- `ExtensionContext.root["buildDir"]` returns `null` — `ExtensionContext` API does not expose the build directory. Workaround: pass the build directory as `systemProperty("shared.build.dir", layout.buildDirectory.get().asFile.absolutePath)` in Gradle and read it via `System.getProperty()` in the extension.
- `CoroutineDiagnostics` is duplicated in desktopApp and shared (intentional — no shared test-fixtures module warranted); shared copy lacks tests.

**Follow-ups:**
- `FailureContextExtension` META-INF service file was orphaned since `7985c234` — add a Konsist test to verify `META-INF/services/org.junit.jupiter.api.extension.Extension` exists for every `*Extension` class in jvmTest.
- Global test timeout extension (`CoroutinesTimeout` / `@Timeout`) so a hard-hanging test still produces a bundle. Open a separate issue; do not conflate with this change's issue #25.
- Proposal line 49 misreferenced issue #25 as the follow-up for hard-hang timeouts — issue #25 is this change's own issue; a separate issue is needed for the timeout follow-up.

## Links

- [Coroutines debugging | Kotlin Documentation](https://kotlinlang.org/docs/coroutines-debugging.html)
- `2026-09-30-testscope-background-work-semantics.md`
- `2026-10-03-merge-regression-fixes.md`
- `2026-09-30-desktop-test-diagnostics.md`
