---
title: "Log writer redaction gaps — cause chain and tag fields not redacted"
date: 2026-10-03
tags: [logging, security, deferred]
status: deferred
---

## Context

`RedactingLogWriter` wraps Kermit log writers and redacts credential-shaped substrings before they reach the underlying sink. The redaction operates on the `message` field only.

Two fields are not currently redacted:

1. **`cause` chain in exceptions.** When a `Throwable` is passed to the logger, `RedactingLogWriter` formats it via `stackTraceToString()`. This captures the full exception message including any embedded credentials. The `cause` chain is included. The redaction pass runs **after** the stack trace is formatted, so any credential-shaped substrings in the message, type name, or stack frame strings are not caught.

2. **`tag` field.** The `tag` string passed to `log(severity, message, tag, throwable)` is not redacted. Tags are typically class names like `FileLogWriter`, not credentials, but in edge cases (e.g., user-provided content in a tag) this could leak.

## Decision

**Deferred.** Fixing the cause chain requires intercepting the exception before `stackTraceToString()` is called — either by pre-processing the throwable or by modifying the formatting logic. This requires a deeper change to the logging pipeline.

Tag redaction is straightforward but low priority — no instance of credential-shaped content in a tag has been observed.

## Consequences

- Exported log bundles may contain credential-shaped substrings in exception stack traces.
- Tags are not currently inspected for sensitive content.

## Links

- `core/log/RedactingLogWriter.kt`
- `core/log/FileLogWriter.kt`
- `deferred-backlog.md`: `log-writer-redaction-gaps`

## Resolution (deferred)

Confirmed deferred 2026-10-05; revisit trigger recorded.

Verified still outstanding: `core/log/FileLogWriter.kt` contains no redaction call. The
cause chain is unfixed and tag redaction is still absent. Revisit when a credential-bearing
value is first observed reaching the log (the trigger this ADR named), or as part of the
log-export change, which will make the redaction gap externally visible.
