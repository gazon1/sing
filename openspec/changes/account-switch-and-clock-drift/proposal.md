# account-switch-and-clock-drift

**Status:** proposed · **Issues:** #179, #181, #182 · **ADRs:** `2026-10-05-anonymous-is-local-only-and-signing-in-offers-a-choice`, `2026-10-05-switching-users-delivers-then-erases-the-departing-user`, `2026-10-05-hybrid-clock-is-merged-and-drift-stops-the-write`

## What

Three product decisions, taken together because they are the same decision three times:
what happens when an assumption the app was built on stops holding.

- **A session with no account is local only.** It does not contact the provider, it is
  persisted so the work survives a restart, and signing in asks the user whether to keep
  what was made or throw it away.
- **Switching users delivers, then erases.** The departing account's queued changes reach
  the server, and only then does its local data go — and the erase does not happen at all
  if the delivery could not.
- **A clock that disagrees by more than five minutes stops the write.** Received changes
  always advance the local clock; a reading that far out is reported, not adopted.

Requirements: REQ-UA-015…018 (`user-authentication`), REQ-OS-019 (`offline-sync`).

## Why

Each of these was an open question in the auth/sync test plan — Q1, Q4, Q7 — and each was
blocking work that could not be written until it was answered. The audit that produced
the questions also showed what happens in the meantime, which is the reason to answer
them now rather than one at a time.

**The account-less session loses its owner on every cold start.** It is never persisted,
so the next launch finds no stored token and starts signed out. A row written yesterday
belongs to an identity that does not exist today, and nothing reports it: no error, no
log, and the tasks are still on screen. This was arguable while the data was disposable.
Under the decision below it is the user's real work, offered to them for keeping, which
makes it plainly a defect rather than a design choice.

**A push samples the session once.** It reads `currentSession` before the request and
applies the response with no second look, so a sign-out during an in-flight push still
deletes outbox rows and settles shadows. Switching users therefore marks one account's
work delivered on behalf of another. The switch policy fixes this as a consequence: a
switch cannot start until delivery finishes, and a response that arrives with no session
behind it has nothing to apply to.

**The second half of a hybrid clock was never wired.** `HlcFactory.tock` has no caller in
production, and `SyncEvent` carries no clock, so there was nothing to merge even if there
were. A device whose clock is a day behind loses every field conflict to the other device
forever, silently. The industry answer is a five-minute drift bound and a loud refusal,
which is what REQ-OS-019 states — and it is not implementable until the server hands out
a clock reading, because using one event's `createdAt` would confound "the server's clock
is wrong" with "this account has been quiet for a while".

## Scope

**In scope:** the account-less session's persistence and its lack of any server call; the
keep-or-discard choice; the distinction between signing out and switching users; the
ordering guarantee on a switch and its refusal; the session re-check on a push response;
the clock merge and the drift bound.

**Out of scope, deliberately:**

- **The wire format change REQ-OS-019 needs.** Echoing a clock on the event row is a
  server change and a protocol version bump. The requirement is written; the transport
  is not part of this change, and until it lands the drift half of the requirement is
  inert. The `tock` merge alone is still worth landing and does not depend on it.
- **What the keep-or-discard screen looks like.** The behaviour is specified; the design
  is not, and it is a first-of-its-kind irreversible choice in the app, so it deserves
  its own attention rather than a byproduct of this change.
- **Archiving `supabase-auth-and-sync`.** Three requirements are superseded and OpenSpec
  cannot express that against an unarchived delta — see the note at the top of the spec.
  Archiving that change is a separate decision, and the audit found its server half
  unverified, so it should not be done as cleanup.

## What this changes in the test plan

Rows AN-01…AN-04, SO-04, SO-05, OB-05, PU-14, TK-01, CV-09, CV-10, BP-06 and questions
Q1, Q2, Q4, Q7 become implementable tests. MG-01…MG-07 do not: there is no server-side
transfer, because the re-attribution is local.
