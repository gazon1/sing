---
title: "Kermit logging: Koin-injected Logger, per-class tags, ANSI colors on JVM"
date: 2026-09-06
tags: [logging, koin, kermit, debugging]
---

## Context

The project had ~25-30 catch blocks that swallowed exceptions without logging. Any error surfaced only as a UI-state string — no stacktrace, no tag, no platform log. The `AppLog.kt` facade existed (Kermit 2.1.0 already wired) but was never called, had a single global tag, no error level, and no platform-specific initialization.

Additional problems:
- Ktor HTTP client logged `LogLevel.BODY` — auth tokens and note content visible in logs.
- No way to distinguish debug vs release log verbosity.
- No Koin-to-Kermit integration — Koin startup logs went to its own default logger.

## Idea

Wire Kermit 2.1.0 + `kermit-koin` 2.1.0 properly:
- `Logger` injected via Koin (`kermitLoggerModule()` + `getWith("ClassName")`).
- `expect/actual initLogging()` sets global severity and (on JVM) a colorizing writer.
- Per-class tags: `getWith("SyncEngine")` creates a `Logger.withTag("SyncEngine")` through DI.
- Structured fields via `[key=value]` convention in message strings — greppable, no JSON dependency.
- Ktor redirected to Kermit at `LogLevel.HEADERS` (not `BODY`).
- Stacktraces printed automatically by Kermit's `CommonWriter`.

## Decision

### 1. `coreLoggingModule()` in `Modules.kt`

```kotlin
fun coreLoggingModule() = module {
    includes(kermitLoggerModule())
    single { LoggerHolder(getWith("App")) }
}
```

`kermitLoggerModule()` (from `kermit-koin`) provides a factory for `Logger` via `getWith(tag)`. `LoggerHolder` wraps a global `"App"`-tagged logger for ad-hoc use (root composables, top-level helpers).

### 2. `expect/actual initLogging(isDebug, version)` in `core/log/`

- **JVM** (`LogBootstrap.jvm.kt`): replaces the default writer with `ColorizedWriter` — ANSI-escape codes for severity (gray/green/yellow/red), OS-aware detection.
- **Android** (`LogBootstrap.android.kt`): leaves `platformLogWriter` untouched — Logcat handles colors natively.
- Severity: `Verbose` in debug, `Warn` in release.

### 3. Entry points

```kotlin
// MainActivity (before startKoin)
initLogging(BuildConfig.DEBUG, versionName = "0.1.0")
startKoin {
    logger(KermitKoinLogger(Logger.withTag("koin")))
    modules(...)
}

// desktopApp/main.kt (same pattern, System.getProperty for isDebug)
initLogging(System.getProperty("singularity.debug") == "true", version = "0.1.0")
```

### 4. Constructor injection in feature classes

```kotlin
class SyncEngine(
    private val log: Logger,  // tag "SyncEngine" via getWith("SyncEngine")
    private val api: SyncApiClient,
    ...
)
```

Koin binding: `single { SyncEngine(getWith("SyncEngine"), get(), get(), get(), get()) }`

### 5. Ktor logging fix

Both `Network.jvm.kt` and `Network.android.kt`:
```kotlin
install(Logging) {
    logger = object : io.ktor.client.plugins.logging.Logger {
        private val kermit = Logger.withTag("HttpClient")
        override fun log(message: String) { kermit.i { message } }
    }
    level = LogLevel.HEADERS  // not BODY — auth tokens must not leak
}
```

### 6. `LoggedResult.kt` helper

```kotlin
inline fun <T> runCatchingLogged(log: Logger, context: () -> String, block: () -> T): Result<T>
```

Zero-overhead in release: lazy message never evaluated when severity < min.

## Rationale

**Why Kermit + kermit-koin (not Timber or custom)?**
Kermit 2.1.0 is already in `libs.versions.toml` and already had a dead `AppLog` facade. `kermit-koin` provides idiomatic DI integration (`kermitLoggerModule()` + `getWith()`). No new dependencies needed.

**Why expect/actual for `initLogging`?**
Because JVM and Android differ: JVM replaces the writer for ANSI colors, Android does not. This is the correct use of expect/actual (platforms have different behavior, per `singularity-todo-kmp-platform-specific` skill).

**Why constructor injection over global `object AppLog`?**
Kermit docs explicitly recommend: "We prefer injecting logger instances rather than using the global Logger instance, especially when we know we'll be unit testing a section of code." Constructor injection makes per-class tags the default, not an option.

**Why severity-based filtering?**
Kermit's lazy lambdas mean strings are never constructed when severity is below minimum. In release (`minSeverity = Warn`), all `log.d { "..." }` calls are zero-cost. This is why `() -> String` (not `String`) is required on all message parameters.

**Why not JSON / structured logging?**
Overkill for local debugging. The `[key=value]` convention in message strings is greppable (`adb logcat | grep "userId=u456"`) and works in both Logcat and JVM stdout without a custom `LogWriter`. JSON writer can be added later if Sentry/Datadog is connected.

**Why not `addTagFilter`?**
That API does not exist in Kermit 2.1.0. Min severity filtering is sufficient.

## Consequences

- All new `catch` blocks in ViewModels, repositories, and use cases should inject `Logger` and call `log.e(e) { "..." }` or use `runCatchingLogged`.
- Existing silent `catch (_: Exception)` (e.g., in `ToolFactories.kt` lines 58, 126, 181) remain unfixed — these require separate investigation (some appear to be copy-paste bugs, not intentional suppression).
- `BuildConfig.DEBUG` requires `buildConfig = true` in `androidApp/build.gradle.kts`. No BuildConfig is available in `shared` jvm target.
- On JVM, `ColorizedWriter` uses `\u001B` ANSI escapes. Older Windows terminals (pre-10) will print escape sequences literally. `NO_COLOR` env var is respected.
- Koin logs (`NoDefinitionFoundException`, etc.) now appear in Kermit's output via `KermitKoinLogger`.
- `RefineTaskTool.kt:34-38` has identical try and catch branches (copy-paste bug) — not fixed in this PR.

## Links

- [Kermit GitHub](https://github.com/touchlab/Kermit)
- [kermit-koin integration docs](https://kermit.touchlab.co/docs/integrations#koin-integration)
- Skill: `singularity-todo-kmp-platform-specific`
