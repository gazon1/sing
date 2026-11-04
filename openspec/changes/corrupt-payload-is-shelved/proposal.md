# corrupt-payload-is-shelved

Issues: #1422 · Follows: #1407
Spec deltas: `offline-sync` (REQ-OS-032)

## What

A payload that arrives from the server and cannot be read is skipped **and** recorded.

Today the pull path skips it: the cursor advances, the event is counted as dropped, and
a log line says why. The event leaves no other trace, so the server holds a change this
device does not have, and nothing says so.

This change adds the second half: the event is written to the dead-letter store, with
the payload as the evidence and the reason the decode failed. The cursor still advances
— the same bytes arrive on every delivery, so waiting cannot make them decode.

The decision and its reasoning are recorded in
`docs/decisions/2026-11-04-a-corrupt-payload-is-skipped-and-shelved.md`.

## Why

The question was filed as a choice between *skipped* and *dead-lettered*, and it is not
one: "skipped" answers *does the cursor move?* and "dead-lettered" answers *does anyone
find out later?*. The first was already settled — a cursor held on one undecodable row
would wedge the account's sync forever, over a change that can never apply anyway. The
second was open, and the repository's position on silence is settled too: a failure that
is only logged and then forgotten is worse than a failure somebody can see.

The push side already shelved its failures. The pull side dropped its failures, and the
symmetry is what was missing.

## Which payloads count as corrupt

A payload is corrupt when it was **present and unusable**: it is not a document, or it
does not decode into one. Those are the two cases where bytes arrived and could not be
read.

Not corrupt, and not shelved:

- a payload the server never sent — there is nothing to read and nothing to point at;
- an event from a newer protocol version — the bytes are fine, this build is old;
- a document from a build that predates a model this build has — it decodes, and the
  sender's next push carries the fix.

## Where the record lives

The existing dead-letter store, not a new table. It already answers the questions a
shelf has to answer: who owns the row, when it happened, and why it failed. A row for an
inbound event is told apart from a row for an outbound patch by its key — the outbound
patch keeps its patch id and can be put back on the outbox; the inbound event's key
cannot be retried, because there is nothing to retry.

A new table would have cost a schema migration, a second owner-scoped erase and a second
thing for a reader to check, for a distinction the key already carries.

## Scope

### In scope

- The pull path's classification of an undecodable payload.
- The dead-letter row that records it: the payload, the reason, the owning account.
- The rule that the cursor still advances, and that a failure to write the row does not
  fail the pull.

### Out of scope

- The push side's dead-letter policy, which is unchanged.
- A user-facing screen for the shelf. There is none today, for either kind of row.
- A profile column on the shelf. It is owner-scoped, as it was before; the profile is
  carried in the row's key instead.
- Whether a newer client should be told that this one could not read its change.

## Verification

- `:shared:jvmTest` — the handler classifies a corrupt payload; the pull phase shelves
  it and still advances the cursor; the outcome shape is pinned.
- `detekt` green.
- `openspec validate --all --strict` exit 0.
