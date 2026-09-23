---
title: "FileLogWriter + LogExporter: persistent rolling logs and user-facing export"
date: 2026-09-23
tags: [logging, observability, android, jvm]
status: accepted
---

## Context

Kermit logs in Singularity Todo go only to Logcat (Android) or stdout (JVM). There is no way to inspect historical logs, and users cannot share logs with developers. When a bug report comes in, the developer has no log file to attach.

Tasks.org's `FileLogWriter` solves exactly this: rolling files (10 × 20 MB) with graceful drain, plus a `LogExporter` interface for sharing logs via system intent / clipboard.

## Idea

1. Add `FileLogWriter` — a Kermit `LogWriter` that persists logs to rolling files under a platform-specific log directory.
2. Register it as the first writer in Kermit's `Logger.setLogWriters(...)` alongside the existing `ColorizedWriter` (JVM) / `platformLogWriter` (Android).
3. Expose `LogExporter` interface with platform implementations: `IntentLogExporter` (Android: `ACTION_SEND` via FileProvider) and `SaveToFileLogExporter` (JVM: copy to timestamped export dir + clipboard).
4. Add `DebugInfo` / `logStartup` helpers for human-readable app metadata in logs and bug reports.

## Decision

### FileLogWriter

`core/log/FileLogWriter.kt` — `class FileLogWriter(logDirectory, fileSystem, fileSizeLimit)`:
- 10 files, 20 MB each, rotation on size limit.
- Single-threaded `CoroutineScope(Dispatchers.IO.limitedParallelism(1))` — no locks.
- Graceful drain: `beginShutdown()` waits up to 2 s for buffered writes.
- Format: `2024-01-23T14:32:01.234Z ClassName I message\n`.
- Tag truncated/padded to 23 chars (greppable, visually aligned).

`expect fun logDirectory(): Path` — Android: `context.filesDir / "logs"`; JVM: `~/.local/share/singularity/logs`.

### LogExporter

`core/log/LogExporter.kt` — `interface LogExporter { suspend fun export() }`.

- **Android**: `IntentLogExporter` reads last 5 MB from `log.0.txt`, writes to cache, launches `ACTION_SEND` via FileProvider (authority: `${packageName}.fileprovider`). Requires `<files-path name="logs" path="logs/" />` in FileProvider paths XML.
- **JVM**: `SaveToFileLogExporter` copies `log.0.txt` to `~/.local/share/singularity/logs-export/<timestamp>/singularity-logs.txt`, copies path to clipboard.

### Init bootstrap

`initLogging(isDebug, version)` calls `logStartup(version, isDebug)` after writers are registered, so the first log entry captures app identity.

### Dependencies

Added `okio = "3.10.0"` to `gradle/libs.versions.toml`. All log writer code uses `okio.Path` and `okio.FileSystem.SYSTEM` — the same stack used by Ktor and Coil.

## Rationale

- Rolling files are the standard approach (Timber's `FileLoggingTree`, Logback `RollingFileAppender`). 10 × 20 MB = 200 MB max — acceptable for a mobile/desktop app.
- `FileLogWriter` is registered as the **first** writer, meaning it always receives every log entry even if a later writer throws. The pipeline is always: `FileLogWriter → ColorizedWriter/platformLogWriter`.
- `LogExporter` is a port interface — real crash-reporting SDK (Sentry, Crashlytics) can replace the no-op implementation by swapping the Koin binding.
- `okio` is already the I/O backbone of Ktor and Coil in this project. No new transitive dependencies introduced.

## Consequences

- `initLogging` must be called **before** `startKoin` (unchanged from previous behavior).
- On Android, `logDirectory()` lazily resolves `Context` from Koin. The `Context` is available by the time the first log entry is written (after Koin starts), so this is safe.
- The `FileLogWriter` instance is **not** exposed via Koin — it is created inside `initLogging` and lives as a global. This is intentional: Kermit's `Logger` holds it, and we don't want DI to manage it.
- `LogExporter` **is** a Koin singleton (`single<LogExporter>`) so screens can `koinInject<LogExporter>()` for a "Send logs" button.
- No redaction layer added. Access tokens and profile IDs are not written to logs today. When they are, a `RedactingLogWriter` decorator must be added before this layer.
- `DebugInfo` uses `version: String` and `isDebug: Boolean` passed from the app entry point (Android: `BuildConfig`, JVM: Gradle property). No global `BuildConfig` in shared.

## Links

- `shared/src/commonMain/.../core/log/FileLogWriter.kt`
- `shared/src/commonMain/.../core/log/LogExporter.kt`
- `shared/src/commonMain/.../core/log/LogDirectory.kt`
- `shared/src/commonMain/.../core/log/DebugInfo.kt`
- `shared/src/androidMain/.../core/log/IntentLogExporter.android.kt`
- `shared/src/jvmMain/.../core/log/SaveToFileLogExporter.jvm.kt`
- `shared/src/androidMain/.../core/log/LogBootstrap.android.kt`
- `shared/src/jvmMain/.../core/log/LogBootstrap.jvm.kt`
- `androidApp/src/main/res/xml/file_paths.xml` (added `<files-path name="logs" path="logs/" />`)
- `gradle/libs.versions.toml` (added `okio = "3.10.0"`)
