---
title: Switching users delivers what the departing user had queued, then erases their local data
date: 2026-10-05
status: accepted
---

# Switching users delivers what the departing user had queued, then erases their local data

## Context

REQ-UA-006 requires that signing out "SHALL NOT discard locally queued changes that have
not yet been delivered", with a scenario that says the queued changes "remain on the
device and are delivered after the next sign-in".

The auth/sync test plan asked the product question behind that as Q2, and asked the
adjacent one as Q4: what happens when a *different* user signs in. Both were open, and
the code had no answer — it happened to keep everything, which is untested and therefore
not a decision.

The audit of the plan (issue #181) found a related defect on the sync side: a push
samples the session once before the request and applies the response with no second look
(`SyncEngine` reads `currentSession` at entry, then deletes outbox rows and settles
shadows). A sign-out or a switch during an in-flight push therefore still applies that
response, under a session that no longer exists. For a switch, the previous user's rows
get marked delivered against the new user's push.

## Idea

1. **Keep everything, deliver later.** The REQ-UA-006 mechanism. Nothing is ever erased
   locally, so no decision is needed — and the device accumulates every account the user
   has ever signed into, invisible to all of them.
2. **Discard silently.** Erase the departing user's local data on sign-out. Simple, and
   it destroys work that may exist nowhere else.
3. **Deliver, then erase.** Push what the departing user had queued, and once the server
   has it, remove that user's local data. The device ends the switch holding exactly the
   incoming account.

## Decision

**Switching users delivers the departing user's queued changes to the server, then
deletes that user's entire local database. The erase happens only if the delivery
succeeded.**

The word doing the work in the second sentence is *switch*, not sign-out:

- **Signing out and staying signed out keeps local data.** Credentials are cleared and
  the rows stay, so the user can sign back in and pick up where they left off. This is
  the existing behaviour, it is what REQ-UA-006 describes, and it is already covered by
  a passing test for signing out with the server unreachable.
- **Signing in as somebody else is a switch, and the switch erases.** The departing
  user's tasks, notes, projects, tags, sync position and shadows are removed by owner.

The erase is conditional on the delivery, which is not a detail. "Deliver, then erase"
describes an ordering; if the delivery fails and the erase proceeds anyway, the ordering
was never enforced and the work is gone from the only place it existed. So a switch
whose delivery cannot complete is **refused**, with a message saying why. The user
retries when the network is back. Nothing is lost, and the device is never left holding
a half-switched state.

## Rationale

Option 1 was rejected because it is not actually free. A device that keeps every account
holds every account's data, none of it visible, none of it ever garbage-collected, and
none of it distinguishable from a corrupted row when the storage layer needs to reason
about who owns what. The plan's own OB-05 and the audit's PU-14 both sit in this gap.

Option 2 was rejected outright: it is the one outcome the sync design exists to prevent.

Option 3 was chosen because it is the only one where the device's contents are a function
of the current account. That property is what makes the ownership checks elsewhere —
per-profile scoping, cursor isolation, the identity mapper — provable rather than
hopeful.

Making the erase conditional is the part worth arguing for. The natural reading of the
instruction is "deliver, then erase" as two steps, and the failure mode of implementing
it that way is invisible: no error, no log line the user sees, and the tasks are gone. A
rule whose failure destroys data must be the one that stops.

The refusal is a deliberate cost. A user who wants to sign in as a colleague on a train
cannot. That is a real inconvenience, accepted in exchange for never losing someone's
work, and it is a smaller price than the alternative.

## Consequences

- **REQ-UA-006 is reconciled, not satisfied.** The old text says queued changes remain
  on the device and are delivered after the next sign-in; this delivers them at the
  switch. The guarantee is the same — nothing is discarded undelivered — and the
  mechanism is not. Both are superseded by a requirement that says which applies when.
  The conflict is recorded in the change that carries the replacement, because
  `user-authentication` is still a delta inside an unarchived change and OpenSpec will
  not accept a `MODIFIED` requirement for it.
- Sign-out and switch become genuinely different operations with different code paths.
  They are easy to conflate, so the distinction needs its own tests rather than a
  comment.
- The wipe is by **owner**, so it takes every profile the departing user had, not just
  the active one. That is intended and needs stating, because a user with a personal and
  a work profile loses both.
- The switch path makes a network call before it can complete. That is a visible cost on
  an action that previously was instant, and the screen has to say what it is waiting
  for.
- #181's defect is fixed as a consequence rather than separately: with a session
  re-checked before the response is applied, and a switch that cannot start until
  delivery finishes, a response arriving after a session change no longer has a state to
  apply to.
- The anonymous "discard" choice in
  `2026-10-05-anonymous-is-local-only-and-signing-in-offers-a-choice.md` is the same
  operation on a locally-generated owner, and should share the code.

## Links

- Issue #181, and the audit rows SO-04, SO-05, OB-05, PU-14, UX-I.2, UX-C.5.
- Test plan §12 Q2 and Q4.
- `2026-10-04-sync-state-keyed-by-owner-and-profile.md` — the owner-scoped rows a wipe has to cover.
  (`2026-09-23-sync-state-model` also exists and is a valid ADR, but it documents the sync
  API shape — public API, `Result<T>`, the repository facade — not owner-scoping. Both
  sides of a merge independently repaired this same dangling reference; this is the
  semantically correct target, so it is the one kept.)
