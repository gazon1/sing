---
title: JVM coroutine diagnostics via kotlinx-coroutines-debug
date: 2026-10-03
status: accepted
tags: [testing, jvm, desktop, coroutines, tooling]
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

- Agent: `-javaagent` via named Gradle configuration + `jvmArgs("-javaagent:$path")` with the
  path resolved eagerly as a plain `String` (configuration-cache-safe; the earlier
  `jvmArgumentProviders` approach captured the DSL script and broke CC — see "CC fix" below).
- Dependency: `kotlinx-coroutines-debug` via version catalog (version ref = coroutines = "1.11.0").
- `CoroutineDiagnostics.dump(testClass, attempt)` — test-only utility object, returns String, no DI.
- Fourth FailureBundle artifact: `coroutines.txt`, independent `runCatching` block.
- Capture order: db-state → kermit → coroutine dump → screenshot (hang-proof first).
- shared:jvmTest: hook into existing `FailureContextExtension` (auto-registered JUnit5 extension).

## Artifacts on failure

```
build/diagnostics/<TestClass>/
    failure-details.txt  ← exception type, message, full stacktrace, cause chain, thread state
    coroutines.txt       ← coroutine snapshot (active coroutines, state, job, stack traces)
    coroutines-timeout.txt  ← (only on timeout exceptions)
    hs_err_pid<pid>.log  ← JVM-level error files from -XX:ErrorFile
```

## Important limitations

- **Hard-hang test produces no bundle** — catch block never fires. Follow-up: CoroutinesTimeout / @Timeout global rule.
- Coroutine dump is a **snapshot** — it does not show execution history.
- Dump does **not** explain TestScheduler semantics (advanceUntilIdle vs runCurrent).
- Dump does **not** prove a coroutine is leaked.
- Does **not** replace TestScopeSemanticsTest — different problem domain.
- Overhead: DebugProbes can significantly reduce performance because it captures a creation stack trace for each coroutine. Mitigation: `DebugProbes.enableCreationStackTraces = false`.

## Canonical investigation order

1. `failure-details.txt` — exception type, message, full stacktrace, cause chain, thread info
2. `coroutines.txt` — coroutine snapshot; look for application frames before kotlinx.coroutines internals
3. `coroutines-timeout.txt` (if present) — additional context for timeout hangs
4. Group repeated stack traces (evidence, not verdict — identical stacks may be normal)
5. Check coroutine state: RUNNING, SUSPENDED, CREATED
6. Compare the coroutine's dispatcher/scope with expected wiring
7. For backgroundScope tests: consult TestScopeSemanticsTest rules before changing production code
8. TestScheduler semantics (advanceUntilIdle vs runCurrent vs runTest virtual time)
9. Production code

## Consequences

### Positive
- Full exception details (type, message, stacktrace, cause chain) available in CI artifacts on failure
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

**Follow-ups (all implemented unless noted):**
- ✅ `TestFailureDetailsWriter` added — writes `failure-details.txt` with exception type, message, full stacktrace, cause chain, thread name/state on every test failure (including non-AssertionError exceptions); complements `coroutines.txt`**
- ✅ `ExtensionServiceRegistrationTest` added — architecture test verifying every extension in `META-INF/services` resolves to a real loadable class; prevents orphaned registration.
- ✅ `CoroutinesTimeoutExtension` added — writes `coroutines-timeout.txt` when a timeout exception is thrown; closes the hard-hang gap. A global `@Timeout` or `CoroutinesTimeout` configuration is still needed to make this fire (see below).
- OpenSpec CI gate (`|| true` + hardcoded `/home/max/.nvm` path) was fixed — now uses `npx @fission-ai/openspec` without swallow.
- `FailureBundle.prepareOutputDir` KDoc corrected — it creates directories, not deletes prior content.
- build-logic/convention wiring: the `jvmArgs("-javaagent:...")` + eager String pattern should be extracted into a convention plugin once `includeBuild("build-logic/convention")` is wired (currently blocked by README criteria; revisit when 6th module needs JVM test agent).
- Global `@Timeout` configuration: `CoroutinesTimeoutExtension` catches timeout exceptions but does not cause them. A JUnit `Timeout` configuration or `@Timeout` annotation on every test class is still needed for the extension to fire. Consider a Jupiter `Configuration` that sets `junit.jupiter.timeout.default` globally.
- `CoroutineDiagnostics` is intentionally duplicated between desktopApp and shared (no shared test-fixtures module warranted); both copies are now tested.
- Proposal line 49 misreference fixed: timeout follow-up is NOT issue #25.

## Links

- [Coroutines debugging | Kotlin Documentation](https://kotlinlang.org/docs/coroutines-debugging.html)
- `2026-09-30-testscope-background-work-semantics.md`
- `2026-10-03-merge-regression-fixes.md`
- `2026-09-30-desktop-test-diagnostics.md`
