---
title: "Android cold-start ANR: SettingsDataStoreMigration blocking main thread"
status: accepted
date: 2026-10-05
---

# Android Cold-Start ANR: Root Cause and Fix

## Context

During investigation of an ANR blocking Android instrumented tests on emulator cold start, subagent analysis (2026-10-05) identified the precise blocking call chain.

### Call chain (synchronous, main thread)

```
SingularityApp.onCreate()                  // androidApp/src/main/kotlin/.../SingularityApp.kt:102
  → startKoin()                            // Koin initialization
    → create()                              // org.koin.core KoinApplication
      → Koin.doInit(modules)               // initializes all module singletons
        → SettingsDataStoreMigration.<init>()    // PlatformModule.android.kt:180-192
          → migration.runBlockingForStartup()    // PlatformModule.android.kt:190
            → runBlocking { run() }              // SettingsDataStoreMigration.kt:92
              → stateDataStore.data.first()      // SettingsDataStoreMigration.kt:111  ← BLOCKING DISK READ
              → legacyDataStore.data.first()     // SettingsDataStoreMigration.kt:114  ← BLOCKING DISK READ
              → userSettingsDataStore.edit { }  // SettingsDataStoreMigration.kt:127  ← BLOCKING DISK WRITE
              → stateDataStore.edit { }          // SettingsDataStoreMigration.kt:164  ← BLOCKING DISK WRITE
```

### Why this blocks the main thread

`runBlocking {}` **blocks the current thread** (the main/UI thread) until all child coroutines complete. `run()` is a `suspend fun` that performs blocking I/O via DataStore's `DataStore.data.first()` and `DataStore.edit {}`. These are file-system operations on `SharedPreferences` or Protobuf files — not truly async on the threads DataStore uses internally.

### Why Koin does not defer this

`single { ... }` in Koin evaluates its lambda **eagerly** at initialization time. The `runBlockingForStartup()` call is not inside a `coroutineScope` or `start` block — it runs inline during the `startKoin()` call in `Application.onCreate()`.

### File locations

| File | Line | Content |
|------|------|---------|
| `androidApp/src/main/kotlin/.../SingularityApp.kt` | ~102 | `startKoin()` call in `onCreate()` |
| `shared/src/androidMain/kotlin/.../PlatformModule.android.kt` | 190 | `migration.runBlockingForStartup()` |
| `shared/src/commonMain/kotlin/.../SettingsDataStoreMigration.kt` | 91-92 | `runBlockingForStartup()` definition |
| `shared/src/commonMain/kotlin/.../SettingsDataStoreMigration.kt` | 109-170 | `run()` — blocking I/O |

## Decision

**Root cause confirmed**: `SettingsDataStoreMigration` blocks the main thread with `runBlocking { run() }`, performing DataStore file I/O synchronously during `Application.onCreate()` → `startKoin()`.

**Fix implemented**: Both Android and JVM platform modules now use `runDeferred(scope)` to launch the migration on `Dispatchers.IO` without blocking. `runBlockingForStartup()` has been removed from the codebase.

### Android (`PlatformModule.android.kt:190-197`)

```kotlin
val bgScope = CoroutineScope(Dispatchers.IO)
migration.runDeferred(bgScope)
bgScope.launch { AiApiKeyMigration.run(legacyDs, secureStorage) }
```

### JVM (`PlatformModule.jvm.kt:138-143`)

```kotlin
CoroutineScope(Dispatchers.IO).let { bgScope ->
    SettingsDataStoreMigration(settingsLegacyDs, userSettingsDs, stateDs).runDeferred(bgScope)
}
```

Both migrations run in parallel on `Dispatchers.IO` without blocking the main thread. The `runDeferred()` method launches the suspend `run()` on `Dispatchers.IO` via `scope.launch`.

## Consequences

- Fix enables Android instrumented tests to launch on emulator cold start without ANR
- JVM startup is no longer blocked by synchronous migration I/O
- Both platforms now use the same non-blocking pattern
- `runBlockingForStartup()` has been removed; `runDeferred(scope)` is the only entry point

## Links

- ADR `2026-09-28-androidApp-smoke-tests-enabled.md` — original ANR observation
- `PlatformModule.android.kt:190-197` — Android fix
- `PlatformModule.jvm.kt:138-143` — JVM fix
- `SettingsDataStoreMigration.kt` — `runDeferred(scope)` definition
