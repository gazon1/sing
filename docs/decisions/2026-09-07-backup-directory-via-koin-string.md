---
title: "Delete throwing backupDirectoryPath; resolve backup directory via Koin get<String>()"
date: 2026-09-07
tags: [koin, di, backup, platform-module]
---

## Context

`BackupRepositoryImpl` requires a `backupDir: String` — the platform-specific path where zip archives are written.

The previous approach (commit `4c1f10d`) put a throwing top-level val in `core/backup/BackupDirectoryPath.kt`:

```kotlin
val backupDirectoryPath: String
    get() = throw IllegalStateException("backupDirectoryPath must be provided by platform module")
```

`CoreDiModule` consumed it directly:

```kotlin
single<BackupRepository> {
    BackupRepositoryImpl(..., backupDir = backupDirectoryPath, ...)
}
```

This compiles fine but throws at runtime during composition — any screen that depends on `BackupRepository` (including Settings → Backup) would crash silently with an unresponsive UI.

## Idea

Platform-specific string values should be Koin bindings, not throwing vals. The platform module (`PlatformModule.jvm.kt` / `PlatformModule.android.kt`) already provides `single<String> { ...path... }`. The domain module should consume it via `get<String>()`.

## Decision

1. **Delete** `core/backup/BackupDirectoryPath.kt` — the throwing val anti-pattern.
2. **Update** `CoreDiModule`: `backupDir = get<String>()` (reads the platform String binding).
3. **Remove duplicate** `single<BackupFileNamer>` that existed at two locations in `CoreDiModule.kt`.
4. **Add** `DiGraphTest` assertion `app.koin.get<BackupRepository>()` so the regression is caught in CI.

## Rationale

- Throwing vals are invisible at compile time; Koin bindings fail fast at container startup.
- `get<String>()` is idiomatic Koin for platform-provided primitives — the same pattern used for `DatabaseWrapper`, `DataStore`, etc.
- The DiGraphTest addition means this regression cannot land without a test failure.

## Consequences

- `BackupRepository` resolves correctly in all environments (JVM desktop, Android).
- Settings → Backup tab no longer crashes during composition.
- The `desktopApp/build.gradle.kts` change (adding `implementation(project(":shared"))` with kotlinJvmTask) was also part of the desktop build fix.

## Links

- `core/backup/BackupDirectoryPath.kt` — deleted
- `core/di/CoreDiModule.kt` — fixed
- `shared/src/jvmTest/.../DiGraphTest.kt` — regression test added
- Commit `4c1f10d` (original split) and `6112239` (this fix)
