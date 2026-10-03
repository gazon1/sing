# jvm-coroutine-diagnostics

## What

Attach kotlinx-coroutines-debug JVM agent to desktopApp:test and shared:jvmTest tasks. On any test failure, write coroutine dump as the fourth FailureBundle artifact (coroutines.txt), alongside screenshot.png, db-state.txt, kermit.log.

## Why

Tests contain long-running collectors (SyncEngine, todayFlow, backgroundScope work, debounce pipelines) and lifecycle-owned scopes. Coroutine bugs — silent death before first emission, scheduler leaks, infinite loops — are invisible from test output alone. The existing FailureBundle captures screenshot/DB/Kermit but not coroutine state. A real incident (TaskDetailCoordinator NPE, coroutine dies before first emission, screen spins forever) was diagnosed only by manually invoking DebugProbes; automating this removes the manual step and makes the evidence available in CI artifacts.

## How

### Agent

- Add kotlinx-coroutines-debug as a JVM-test-only dependency.
- Attach via `-javaagent` JVM argument (not DebugProbes.install()) — required for JDK 21+ compatibility; dynamic agent loading emits a warning on JDK 21 and fails on JDK 22+.
- gradle/configuration: named configuration `coroutinesDebugAgent` + `jvmArgumentProviders` (configuration-cache-safe).
- Scope: desktopApp:test only in PR1; shared:jvmTest added in PR2.

### CoroutineDiagnostics utility (test-only, no DI)

```kotlin
object CoroutineDiagnostics {
    fun dump(testClass: String?, attempt: Int): String
    private fun formatCoroutine(info: CoroutineInfo, index: Int): String
}
```

- Returns String; files are written only by FailureBundle.
- Guard: `if (!DebugProbes.isInstalled) return "coroutines debug agent is not attached"`.
- Members of CoroutineInfo fixed by compilation against coroutines 1.11.0.
- Timestamp: `java.time.Instant.now()` (NoDirectClockSystemRule bans Clock.System in all source sets).

### FailureBundle — 4th artifact

- Lazy accessor `coroutines.txt`.
- Independent `runCatching` block (failure to write does not affect other artifacts).
- Capture order: db-state → kermit → dump → screenshot (hang-proof first; screenshot is the only hang-risk path via EventQueue.invokeAndWait).
- shared:jvmTest: hook into existing FailureContextExtension (auto-registered JUnit5 extension, already handles AssertionError prefixing).

### CI

- No new upload step — existing glob `desktopApp/build/diagnostics/**` already covers the new file.
- shared/build/diagnostics/** added to the same upload glob.
- Artifact used for manual investigation after CI failure.

## Known limitations (documented in ADR, not fixed here)

- Hard-hang test (no exception thrown) produces no bundle at all — the catch block never fires. Follow-up: CoroutinesTimeout / @Timeout global rule (separate issue #25).
- Coroutine dump is a snapshot — it does not show execution history.
- Dump does not explain TestScheduler semantics (advanceUntilIdle vs runCurrent).
- Dump does not prove a coroutine is leaked.
- Does not replace TestScopeSemanticsTest — different problem domain.

## References

- `docs/decisions/2026-10-03-kotlinx-coroutines-debug.md` — ADR
- `docs/decisions/2026-09-30-testscope-background-work-semantics.md` — backgroundScope contract
- `docs/decisions/2026-10-03-merge-regression-fixes.md` — real incident diagnosed with DebugProbes
- `docs/decisions/2026-09-30-desktop-test-diagnostics.md` — FailureBundle rationale
