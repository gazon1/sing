---
title: "Redaction patterns are order-dependent — specific before generic"
date: 2026-09-30
status: accepted
tags: [logging, security, kermit]
---

## Context

MR-3 added `RedactingLogWriter`, a `LogWriter` decorator that regex-replaces
credential-shaped substrings before delegating to a real writer. It is
installed twice per platform (once around the console writer, once around the
file writer).

Implementation hit a non-obvious failure: the first version ordered the
pattern list with the generic JWT pattern first. Three tests failed in a way
that looked like a regex bug but was not.

Given `"Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIx...In0.abcdefghijklmnop"`,
the generic pattern `eyJ...{3,}\.{3,}\.{3,}` matches the whole token and
rewrites it to `[JWT]`. The result is `"Authorization: Bearer [JWT]"`. The
`bearer\s+...` pattern, which runs *after*, now has nothing to match — the
`Bearer ` prefix survives verbatim.

Same for `anonKey=<jwt>` → `anonKey=[JWT]`: the value is redacted (no leak),
but the *specific* placeholder never applies and the credential label stays
in the log.

The security goal was met in all three cases. What was wrong is the
placeholder quality and the fact that a reader of the log sees a
half-redacted line that invites the question "what was this?".

The same class of bug exists in reverse: the email pattern
`[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}` would match the
`user:pass@host` tail of a credentialed Supabase URL, so the Supabase URL
pattern must stay ahead of it.

## Idea

Three ways to handle overlapping patterns:

1. **Single alternation regex.** One pattern with named groups and a
   `replace` that picks the placeholder per group. Fixes ordering by
   construction, but the regex becomes unreadable and untestable.
2. **Negative lookarounds on the generic pattern.** Make the JWT pattern
   refuse to match when preceded by `Bearer `, `anonKey=`, or `service_role=`.
   Pushes the context into the regex, and silently breaks the moment someone
   adds a new contextual prefix.
3. **Keep the ordered list, document the constraint, and test through the
   real writer.** The ordering is a documented invariant; the test file
   exercises `RedactingLogWriter` itself rather than a copy of the pattern
   list.

## Decision

Approach (3), plus a `KDoc` on the companion object stating that contextual
patterns (`supabase url`, `bearer`, `anonKey`/`service_role`) must precede the
generic `JWT` pattern, and that the `email` pattern must stay last.

The pattern list is ordered deliberately:

1. `https://user:pass@host` → `[supabase url=redacted]`
2. `Bearer <token>` → `[bearer token]`
3. `anonKey=` / `service_role=` → `[$1=redacted]`
4. `eyJ…` → `[JWT]` (generic — must stay after every contextual pattern)
5. `sk-…` → `[sk-redacted]`
6. email → `[email]` (must stay last)

## Rationale

Approach (1) is correct but unreadable; a 6-alternative regex with back-
references is untestable in a way that will be discovered at the worst
possible time. Approach (2) couples the generic pattern to a list of known
prefixes — every new prefix silently leaks, which is exactly the failure mode
we are trying to eliminate.

Approach (3) makes the ordering a visible, reviewable property of a short
list, and — critically — makes the *tests* exercise the shipped code. The
first version of `RedactingLogWriterTest` had its own private copy of the
pattern list; that copy silently drifted from the source (the test had
`[A-Za-z0-9_\-]` where the writer had `[A-Za-z0-9_\-.]`) and produced three
failures that looked like writer bugs. Every test now writes through
`RedactingLogWriter` into a `FakeLogWriter` and asserts on what the delegate
received. There is no second copy of the patterns to keep in sync.

## Consequences

- **Order of `REDACTION_PATTERNS` is load-bearing.** Adding a new pattern
  means deciding where it goes; appending to the end is only correct if the
  pattern cannot overlap with one already in the list. The KDoc says so.
- **A JWT-shaped token is redacted as `[JWT]` when no context matches** — the
  generic pattern is the safety net, so removing it would be a regression even
  though each contextual pattern works on its own.
- **Minimum-length thresholds were lowered** (JWT segments 10→3, bearer/anonKey
  20→10, sk- 20→16) so that short but structurally valid tokens in tests and
  in error paths are caught. The JWT pattern remains the backstop, so a real
  token is redacted regardless of length.
- **Redaction is best-effort, not a guarantee.** A credential logged in a
  non-standard shape (e.g. a raw `anonKey` value with no `anonKey=` label and
  a non-`eyJ` prefix) is not caught. The decorator is a second line of
  defence; call sites must still avoid interpolating secrets — see below.
- **The throwable path rebuilds the exception** with a redacted `message`,
  keeping the original as `cause`. Stack frames are preserved, but the
  original throwable object is still reachable via `cause`, so a delegate that
  walks the cause chain would see unredacted text. The two delegates in use
  (`platformLogWriter`, `FileLogWriter`) do not.

## Open items (non-critical, not fixed here)

- **`SyncBootstrapper` still logs `event.entityId` on error/skip paths**
  (lines 98, 111, 118, 143, 151) while the `applied`/`deleted` debug lines
  no longer do. Entity ids are UUIDs, not credentials, so this is not a
  redaction gap — but the asymmetry is unintentional and should be settled
  deliberately: either log the id everywhere (it is genuinely the most useful
  field when diagnosing a failed apply) or nowhere.
- **Other call sites may still interpolate user content into log messages.**
  `RedactingLogWriter` catches the credential shapes it knows about; it does
  not catch task titles, note bodies, or AI prompt fragments. `ChatViewModel`
  was fixed in this MR; a repo-wide sweep of `log.{d,i,w,e} { "...$var..." }`
  for user-derived values has not been done.
- **No severity filtering change.** `Warn` and above still deliver in release,
  so all 48 currently-safe call sites ship to the file writer. Fine today,
  but it means "the log file is redacted" is a claim about *patterns*, not
  about *what call sites print*.

## Links

- `shared/src/commonMain/kotlin/com/singularity/todo/core/log/RedactingLogWriter.kt`
- `shared/src/commonTest/kotlin/com/singularity/todo/core/log/RedactingLogWriterTest.kt`
- Related: `2026-09-23-file-logging-and-exporter.md` (file writer + unwired exporter port)
- Related: `2026-09-30-file-logging-wired.md` (wiring into Android + JVM)
