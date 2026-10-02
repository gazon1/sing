---
date: 2026-10-02
title: Log Message User-Content Classification Policy
status: proposed
deciders: Singularity Developer
---

# Log Message User-Content Classification Policy

## Context

The redaction decorator in `core/log` scrubs credential shapes (API keys, tokens, passwords).
It does not catch task titles, note bodies, or AI prompt fragments. A repo-wide sweep
of `log.{d,i,w,e} { "...$var..." }` for user-derived values has never been done.

The following call sites were identified as potentially printing user content:

| File | Line | Content | Classification |
|---|---|---|---|
| `NoteEditor.kt` | 201 | `e.message` | exception detail — may contain user-derived paths |
| `ProfileSwitcherViewModel.kt` | 98, 105 | `e.message` | exception detail |
| `SavedAgendaViewModel.kt` | 327 | `e.message` | exception detail |
| `AuthRepository.kt` | 57, 69 | `email.take(3)***` | hand-rolled redaction — should use shared helper |
| `ChatViewModel.kt` | (AI prompt) | prompt fragment in log | user input in AI prompt |
| `ProfileBootstrapper.kt` | (profile name) | entity id | fine — technical metadata |
| `SyncBootstrapper.kt` | (entity id) | entity id | fine — technical metadata |

Additionally, 33 `commonMain` log calls with interpolation and 6 platform-specific calls
(`AlarmReceiver`, `JvmSyncScheduler`, `AndroidSyncScheduler`, `SyncOutboxWorker`) were
flagged but not individually classified.

## Decision

**Classify every interpolated log value** using three tiers:

### Tier 1 — Always permitted (technical metadata, no classification needed)
- Entity IDs (`taskId`, `noteId`, `projectId`)
- Timestamps and durations
- HTTP status codes, URLs (without query params containing secrets)
- Stack trace class names and line numbers
- Memory addresses, thread names

### Tier 2 — User content, require explicit decision per call site
- Exception `.message` — may contain file paths, SQL, or user input depending on source
- AI prompt fragments — user input that was sent to the model
- Search queries — user-entered text
- Any value derived from `DraftState` or user-editable fields

**Default action for Tier 2:** `log.w { "..." }` (Warn level, not Info/Debug) and
truncate to 100 characters. If the value is sensitive, drop entirely.

### Tier 3 — Never log without explicit ADR
- Full email addresses (redact to `us***@example.com` via shared helper)
- Passwords, tokens, API keys (already handled by redaction decorator)
- Full note body / task description in non-debug builds

## Shared Email Redaction Helper

`AuthRepository.kt` has hand-rolled `email.take(3) + "***"` in two places.
Replace with a shared helper in `core/log`:

```kotlin
// core/log/Redaction.kt
fun redactEmail(email: String): String {
    val at = email.indexOf('@')
    return if (at > 1) "${email.take(3)}***${email.substring(at)}" else email
}
```

This consolidates the two `AuthRepository` call sites and makes the redaction policy
explicit and testable in one place.

## Exception Message Policy

The four `e.message` call sites should be reviewed individually:

1. **`NoteEditor.kt:201`** — likely a framework exception; truncate to 200 chars and
   log at Warn. If the exception contains note body fragments, drop entirely.
2. **`ProfileSwitcherViewModel.kt:98,105`** — profile bootstrap errors; these may
   include profile names as user content. Redact before logging.
3. **`SavedAgendaViewModel.kt:327`** — agenda computation errors; likely technical
   but may include filter parameters derived from user action.

## Trigger: FileLogWriter User-Facing Export

Before shipping `FileLogWriter` user-facing log export (the feature that lets a user
attach logs to a bug report), the classification must be complete. The export must not
include any Tier 2 or Tier 3 content. This ADR is a prerequisite for that feature.

## Consequences

- All 33+ flagged `commonMain` call sites need a one-time classification pass.
- `core/log/Redaction.kt` with `redactEmail()` is introduced.
- `AuthRepository.kt` is refactored to use the shared helper.
- `FileLogWriter` export feature is blocked on this ADR's completion.
- A future log-audit script can re-verify classification by checking for
  interpolation of known Tier 2 types (DraftState, note body, etc.).
