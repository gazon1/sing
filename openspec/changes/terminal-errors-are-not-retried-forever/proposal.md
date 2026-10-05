# terminal-errors-are-not-retried-forever

**Status:** proposed · **Issue:** #180

## What

Two error codes the server returns were being retried for the full budget, with the
backoff policy's cap of one hour between attempts, before being parked. Requirement
REQ-OS-024.

`not_found` — the row the patch targets is not there — and `too_large` — the patch
exceeds a server limit — cannot become acceptable by waiting. Neither was in the
terminal set, which named only two codes.

## Why

The classifier was a two-item denylist, so a code nobody thought about inherited "retry".
That is the right default for a *transient* failure and the wrong one for a permanent
refusal, and the cost is not a delay: the retry budget is spent across hours, and the
row ends in a dead letter store where nothing can requeue it. For `too_large` the
content is the user's, so it can never be fixed by a resend.

The whole vocabulary is now named, and the test is a table over it rather than two
examples. That is the part that matters: a new code has to be classified deliberately,
because a code that is not classified cannot be noticed at all.

The default for an **unrecognised** code is still to retry, and that is a decision rather
than an omission. Retrying a code that turns out permanent costs a retry budget and ends
in the dead letter store, where the change is visible and recoverable. Dropping a
transient one loses the user's edit with no trace. The asymmetry is deliberate: being
wrong in one direction produces a delayed failure somebody can see, and in the other a
silent loss nobody can.

## Scope

**In scope:** the terminal set, and the table test that pins it.

**Out of scope:**

- **The dead-letter row's fate.** Nothing can requeue from it (#180, and the plan's
  PU-07). A change parked there today is effectively lost, which weakens the argument
  above considerably and is worth its own change.
- **Per-code messages.** The user's view of a parked change says which code, and every
  code has the same wording. Which wording each deserves is a product call.
- **`newVersion` and delete patches** (#178). The same file, the same push path, but a
  separate piece of work: the first needs a column and a migration, the second needs a
  signal from the repository that a row was removed.
