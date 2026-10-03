---
title: "Android log tail may be lost — beginShutdown never called"
date: 2026-10-03
tags: [logging, android, deferred]
status: open
---

## Context

`FileLogWriter` has a `beginShutdown()` method that drains the write buffer within 2 seconds before returning. On Android, `Application.onTerminate()` is never called by the OS — the process is killed without notice.

`initLogging` is called in `Application.onCreate()` **before** `startKoin()`:

```kotlin
// SingularityApp.kt:64-69
initLogging(...)
startKoin { ... }
```

This ordering means the logging pipeline is live during Koin initialization, but there is no lifecycle hook to drain it on exit.

## Decision

**Deferred.** Moving `initLogging` after `startKoin` would allow the `LogBundleExporter` to be injected into the `FileLogWriter` as a listener, enabling a clean shutdown. However, this loses all logs from the Koin initialization phase itself (DI setup, module resolution). The trade-off is non-trivial.

Alternative: expose the log directory path via a `LogDirectoryProvider` port, allowing the exporter to read files without coupling to the writer.

## Consequences

- On Android, log entries written between the last write buffer flush and process termination are not in `log.0.txt`.
- The log export feature (`LogBundleExporter`) reads whatever is on disk, which may be missing the tail.

## Links

- `core/log/FileLogWriter.kt`
- `core/log/LogBootstrap.android.kt`
- `core/log/LogBundleExporter.kt`
- `deferred-backlog.md`: `android-log-shutdown-drain`
