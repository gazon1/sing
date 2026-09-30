---
title: "File logging is wired into both apps; export deferred"
date: 2026-09-30
status: accepted
supersedes: 2026-09-23-file-logging-and-exporter
tags: [logging, observability, android, jvm]
---

## Context

`2026-09-23-file-logging-and-exporter` specified `FileLogWriter` and a
`LogExporter` port for user-facing log export. Two years of the plan
sitting on disk does not make it implemented:

- `FileLogWriter` existed but was never constructed anywhere. It was a
  perfectly good piece of code with zero call sites.
- `LogExporter` existed as an interface with no implementations and no
  consumers.
- `LoggerHolder` was registered in the Koin graph and never injected.
- `initLogging` was an `expect fun` that, on Android, resolved the log
  directory from `Context` — see the defect below.

So the observable state was: the app logged to Logcat/stdout, and nothing
else. The "file logging" in the ADR existed only as source.

## Idea

Three options for making the file writer real:

1. **Wire it up as specified.** Pass the log directory into `initLogging`,
   construct `FileLogWriter`, register it. Also build the two `LogExporter`
   implementations the 2026-09-23 ADR described.
2. **Wire it up, defer export.** Construct the file writer on both
   platforms; delete `LogExporter` as a dead port and re-introduce it when
   a UI consumer (a "Share logs" action in Settings) actually exists.
3. **Delete the whole subsystem.** Remove `FileLogWriter`, `LogExporter`,
   `LoggerHolder`, and the `initLogging` expect/actual pair, and log to
   Logcat only.

## Decision

Approach (2). Logging to a file is wired on both platforms; export is
deferred, and `LogExporter` is deleted as an unwired interface.

`initLogging` becomes:

```kotlin
expect fun initLogging(isDebug: Boolean, version: String, logDirectory: Path)
```

- **Android** — `SingularityApp` passes `filesDir.absolutePath.toPath() / "logs"`.
  Writers: `RedactingLogWriter(platformLogWriter())` and
  `RedactingLogWriter(FileLogWriter(logDirectory))`.
- **JVM desktop** — `main.kt` passes `~/.singularity-todo/logs`. Writers:
  `RedactingLogWriter(ColorizedWriter())` and
  `RedactingLogWriter(FileLogWriter(logDirectory))`. A JVM shutdown hook
  calls `fileWriter.beginShutdown()` so the buffer is drained on exit.

Both platform bootstraps are the only place `Logger.setLogWriters(...)` is
called.

## Rationale

**Why the directory is a parameter.** The previous `expect fun initLogging()`
resolved the log directory from a `Context` reference obtained inside the
logging module. On Android, `initLogging` runs from `Application.onCreate`
*before* `startKoin`, so that `Context` was not available — the lazy resolve
was on the crash path of the very first thing the process does. Making the
directory an explicit parameter moves the lookup to the call site, where
`filesDir` is legitimately available, and makes `initLogging` trivially
testable with a temp directory.

**Why delete `LogExporter` rather than implement it.** An interface with no
implementations and no consumers is the exact defect the
`find-unwired-surfaces.py` audit exists to catch. Building
`IntentLogExporter` + `SaveToFileLogExporter` now would mean shipping an
`ACTION_SEND` `FileProvider` path and a clipboard writer for a Settings
button that does not exist. When the button is built, the port is built with
it.

**Why `LoggerHolder` was deleted.** It was bound in `coreLoggingModule()` and
never injected. Its only apparent purpose — holding the `FileLogWriter` so
something could flush it — is served instead by the platform bootstrap
holding the writer as a local and registering a shutdown hook on JVM.

**XDG divergence.** The desktop log directory is `~/.singularity-todo/logs`,
which matches the app's existing convention but not the XDG base directory
spec (`~/.local/state` or `~/.local/share`). The project already stores its
database at `~/.local/share/singularity/databases/`, so the two halves of
the app already disagree. Normalising the log path to XDG is a separate
change that should move the database too.

## Consequences

- `FileLogWriter` is now exercised in production on both platforms, which is
  what surfaced the append-on-restart and counter-resume defects fixed in
  `2026-09-30-post-mr-1-findings`. Any future `LogWriter` subclass must be
  registered in one of the two `LogBootstrap` files or it silently receives
  nothing; `find-unwired-surfaces.py` gained a form for this in MR-4.
- Android cannot flush on termination — `Application.onTerminate()` is never
  called on a real device, so the buffer survives only until the next write.
  JVM is fine; the shutdown hook covers normal exit.
- Release builds write the same `Warn`-and-above stream as debug builds.
  Severity filtering was deliberately not changed: 48 call sites are currently
  considered safe to ship, and narrowing that is a per-call-site review.
- Log files are local-only, so **a bug report from a user still cannot come
  with a log attached.** That is the user-visible gap this defers; it is
  tracked in `deferred-backlog.md` as `log-export-has-no-surface`.

## Links

- `shared/src/commonMain/kotlin/com/singularity/todo/core/log/LogBootstrap.kt`
- `shared/src/androidMain/kotlin/com/singularity/todo/core/log/LogBootstrap.android.kt`
- `shared/src/jvmMain/kotlin/com/singularity/todo/core/log/LogBootstrap.jvm.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/core/log/FileLogWriter.kt`
- `2026-09-30-log-redaction-pattern-ordering.md` — the redaction decorator and its ordering invariant
- `2026-09-30-post-mr-1-findings.md` — the `FileLogWriter` defects that wiring exposed
- Supersedes `2026-09-23-file-logging-and-exporter.md`
