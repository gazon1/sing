---
title: "A corrupt payload is skipped and shelved, not skipped silently"
date: 2026-11-04
tags: [sync, offline-sync, dead-letter, pull]
---

## Context

#1407 asked for fail-closed verification of sync payloads and left one question open:
what happens to a payload this client cannot read. #1422 is that question.

The pull path answers it in one place. `PullEventHandler` classifies what an event did,
and a payload that does not decode is classified as `ApplyOutcome.Skipped` — the cursor
advances past it, the event is counted as dropped, and a log line says why. That part
was settled by `2026-10-06-a-transient-failure-is-not-a-permanent-one.md`: the bytes
on the wire are identical on every delivery, so waiting cannot make them decode, and a
cursor held on one undecodable row would stop the account syncing anything else, ever.

What was never settled is the second half. A skipped event leaves no trace beyond a
counter that is reset on the next cycle and a log line nobody reads. The server holds a
change; this device does not; nothing says so.

The dead-letter store exists and is the repository's designated place for "something
gave up" — but it was built for the other direction. `sync_dead_letter` holds *outbound*
patches this device could not deliver, with an attempt count, a backoff that ends in
the shelf, and a contract (REQ-OS-010) that the shelved patch is visible to the user
and can be discarded or retried explicitly. An inbound event this device could not read
is neither of those things: it is not the device's work, and it cannot be retried,
because the server sends the same bytes on every delivery.

## Idea

1. **Skip only.** Keep the cursor advancing and the log line, and change nothing else.
2. **Hold the cursor.** Treat the undecodable event as `Failed` and wait for the sender
   to fix it. This wedges the account on one bad row, forever.
3. **Skip and shelve.** Advance the cursor *and* write the event to the dead-letter
   store, in a row that says it came in and could not be read.
4. **A separate store.** Give inbound undecodable events their own table, so the shelf
   keeps its single meaning and its retry contract.

## Decision

The question as posed is a false dichotomy: "skipped" and "recorded" are not
alternatives. They answer two different questions — *does the cursor move?* and *does
anyone find out later?* — and the answers do not conflict.

**A corrupt payload is skipped and shelved.** The cursor advances, exactly as it does
today, because the same bytes arrive on every delivery and holding the cursor would
wedge the account. And the event is recorded in the dead-letter store, because a remote
change this device dropped is a divergence between the server's state and this device's
state, and a divergence nobody can see is a divergence nobody can fix.

The record is the existing shelf, not a new table. The two kinds of row live together
and are told apart by their key: an outbound patch keeps its patch id and can be put
back on the outbox; an inbound event's key is `pull:<profile>:<lsn>:<type>:<id>` and
cannot be retried. A new table would have cost a schema migration, a second
owner-scoped erase, and a second thing for a reader to check — for a distinction the
key already carries.

Only a payload that was *present and unusable* is corrupt: it is not a document, or it
does not decode into one. A payload the server never sent, an event from a newer
protocol version, and a document from a build that predates a model this build has are
not corruption — nothing was misread, and a shelf full of them would be noise. The
legacy-document case is worth naming: it decodes, and the sender's next push carries
the fix, so it heals itself.

## Rationale

The cursor question was never open. What was open is the silence, and the repository has
a settled position on silence: `2026-10-19-retriable-push-failure-defers-the-patch.md`
holds that a failure which is only logged and then forgotten is worse than a failure
somebody can see, and `sync_dead_letter`'s own KDoc says the same thing — a change a
user made must not be destroyed because the system could not deliver it. This is the
mirror image: not a change destroyed, but a change *not received*, and the symmetry is
the point. The push side shelved its failures; the pull side dropped its failures.

The shelf was the obvious home because it already answers the questions a shelf has to
answer: who owns this row (`owner_id`, so an account-scoped erase takes it), when it
happened, and why it failed (`last_error`). An inbound event answers all three the same
way an outbound patch does.

The retry contract is the one thing the two rows do not share, and it is kept explicit
rather than smoothed over: an inbound row is not retryable, because there is nothing to
retry — the device would send its own copy of somebody else's change. The key says
which kind of row it is, and the entity's KDoc says what follows from that.

A new table was rejected because it buys a purity that the key already provides, and
costs a migration, a second erase path, and a second thing to look at. The one thing
the reuse does not give is a profile column: the shelf is owner-scoped, as it was
before, so the profile is carried in the key instead.

## Consequences

- A payload that is present but cannot be decoded is skipped (the cursor advances) and
  written to the dead-letter store, with the payload as the evidence and the reason in
  `last_error`.
- `ApplyOutcome.Skipped` carries a `corrupt` flag. The cursor arms stay three: the
  flag says what was wrong with the payload, not what the cursor should do.
- The dead-letter shelf holds two kinds of row, told apart by the key. A reader that
  means to retry a row must check which kind it is.
- A shelf row for an inbound event is never re-queued. Deleting it discards the record.
- "Corrupt" means present and unusable. An absent payload, a newer protocol version and
  a legacy document are not corrupt and are not shelved.
- A failure to write the row does not fail the pull. The event is lost either way, and
  a storage error must not wedge the account over one unreadable row.

## Links

- Issue #1422 (follow-up to #1407)
- `shared/src/commonMain/kotlin/com/singularity/todo/core/sync/SyncEngine.kt` —
  `ApplyOutcome.Skipped` and the `corrupt` flag
- `shared/src/commonMain/kotlin/com/singularity/todo/core/sync/PullEventHandler.kt` —
  the classification of an undecodable payload
- `shared/src/commonMain/kotlin/com/singularity/todo/core/sync/PullPhase.kt` —
  the shelving of a corrupt event, and the cursor that still advances
- `shared/src/commonMain/kotlin/com/singularity/todo/core/sync/SyncDeadLetter.kt` —
  the two kinds of row and the key that tells them apart
- `shared/src/commonMain/kotlin/com/singularity/todo/core/di/SyncDiModule.kt` —
  the production pull phase, which has to be handed the shelf: the engine's own default
  is bypassed there, so a dependency left out of that construction is not a compile error
- Tests: `PullEventHandlerTest`, `PullPhaseTest`, `ApplyOutcomeArmsTest`
- Related decisions: `2026-10-06-a-transient-failure-is-not-a-permanent-one.md`,
  `2026-10-19-retriable-push-failure-defers-the-patch.md`
