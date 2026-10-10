---
title: "File Log Writer And Log Exporter Are Never Installed"
date: 2000-01-01
status: CLOSED
tags: ["deferred"]
---

**Status (re-verified 2026-10-04):** CLOSED and verified 2026-10-04. `LogBootstrap.jvm.kt:18` and `.android.kt:20` both install `RedactingLogWriter(FileLogWriter(...))`; `grep -rn "LogExporter\|LoggerHolder"` returns zero hits, so the dead ports are gone. The residual ("logs have no way to leave the device") is a separate entry, #37.

**Status: RESOLVED** by the logging epic (MR-1 … MR-5, 2026-09-30). The writer
is installed on both platforms, `LogExporter` and `LoggerHolder` were deleted
as dead ports, and the fifth script form (`log-writer`) now catches a
`LogWriter` that never reaches `setLogWriters`. See
`2026-09-30-file-logging-wired.md` and
`2026-09-30-dead-code-deleted-and-oauth-kept.md`.

What remains from this entry is item (1) of the original "try next" list, which
was a product question rather than a refactor: **logs still have no way to
leave the device.** See `log-export-has-no-surface` below.

**Original entry follows.**

---

**Found in:** MR-3, while implementing the plan's "severity per writer" step —
which turned out to have no writers to configure.

**Symptom:** `LogBootstrap.kt`'s KDoc states that both platforms "add
`FileLogWriter` for persistent rolling logs", and that severity filtering is
global (`Verbose` debug / `Warn` release). Neither `actual fun initLogging`
implements the first half. `desktopApp`/`androidApp`/`SingularityApp` never
mention `FileLogWriter` or `LogExporter`; neither symbol has a single call site
outside its own file and its test.

So **the app writes no logs to disk at all**, while documenting that it does.
`FileLogWriter` is fully built — rolling files, size limit, rotation, a
single-threaded `Dispatchers.IO` writer — and `FileLogWriterTest` covers it.
That is the exact shape `find-unwired-surfaces.py` exists to catch: implemented,
tested, never invoked.

**Why the script did not flag it:** the detector recognises four shapes only —
`screen`, `default-noop`, `di-binding`, `navigation`. A fully-implemented class
wired to nothing is not among them. This is a gap in the script, not an
exemption.

**Try next:**

1. Decide whether persistent logging is a requirement. ADR
   `2026-09-26-observability-production` describes an export path, so the
   answer is probably yes — but confirm before wiring, because adding a writer
   that writes 20 MB of rotating files on every device is a product decision
   (retention, opt-out, battery) and not a refactor.
2. If yes: add `FileLogWriter` to both `actual fun initLogging` bodies and
   `LogExporter` to whatever surface is meant to hand logs off (a share
   intent? a settings screen? — grep finds no caller, so the trigger point has
   to be identified; it may itself be missing).
3. If no: delete `FileLogWriter`, `LogExporter` and `FileLogWriterTest`, and fix
   the KDoc on `LogBootstrap` that promises them. Carrying a tested but unused
   subsystem is worse than not having it: the next reader assumes the logs are
   there.
4. Either way, **fix the KDoc** — it currently describes behaviour that does not
   exist, which is how this was missed for as long as it was.
5. Widening `find-unwired-surfaces.py` to a fifth shape — a `LogWriter`
   subclass with no `setLogWriters` call — is a natural companion fix.

**Related:** the plan's own §6 assumed "in release there is already a
`FileLogWriter` + `LogExporter`" and built on it. That assumption was wrong,
which is why the step produced a finding instead of a change.

---
