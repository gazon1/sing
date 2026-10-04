# log-export-surface

**Status:** proposed · **Issue:** #37 · **Backlog:** `docs/decisions/deferred-backlog.md#log-export-has-no-surface`

## What

Give a user a way to attach logs to a bug report. File logging already works on
both platforms, so a developer can read logs off a device — but a user cannot
send them anywhere.

## Why

`LogExporter` was deleted in MR-2 instead of being implemented, and the surface it
would have plugged into was never built. `FileLogWriter` was originally wanted for
exactly this reason (`2026-09-23`); the write half was delivered and the read half
was not, so the feature is half-built in a way that reads as complete.

This is not a wiring bug. There is no implementation and no consumer to re-wire.

## Decision required before implementation

**The trigger surface is the open question, and it is a product decision.**
The obvious candidate is a "Share logs" row in Settings → Developer/Debug, but
that is a proposal, not a finding. Options worth weighing:

- Settings row (developer-gated) — discoverable, but ships debug UI to every user.
- A crash screen that offers to attach the bundle — the trigger is automatic, so
  nobody has to remember to look.
- Both, with the settings row for the case where nothing crashed.

## Relationship to the redaction question

**This must be decided together with #log-messages-user-content-sweep.** Redaction
currently covers credentials and secrets, not user content: task titles, note
bodies and search queries reach the log. If a share surface ships, whatever is in
the log ships with it. Designing the surface first and redaction second produces a
report that leaks.

## Out of scope

- Any Android-specific implementation. The write side already works on both
  platforms; this change is about the read/share side.
- Log rotation or retention policy. Separate concern, and the failure bundle
  already overwrites per attempt.
- Redacting user content. Tracked in #log-messages-user-content-sweep, which this
  change blocks on but does not subsume.

## How

Suggested order:

1. Land the redaction inventory so the log is safe to hand out.
2. Choose the trigger surface, and write down why.
3. Add the export action using the existing `FileSharePort` / `SharePort` ports.
4. Verify the failure-bundle path still works for a developer, since the two now
   share a file.

## OpenSpec artifacts

- `specs/log-export/spec.md` — observable behaviour of a shared log bundle
