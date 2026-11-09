---
title: The shared sync server merges like the server
date: 2026-11-04
status: accepted
tags: [testing, sync, fixtures]
---

# The shared sync server merges like the server

## Context

`SharedSyncServer` was an envelope-only event log. A push was recorded as an
event whose `data` carried the patch's operations, and every patch was answered
`ok` with a version that was the log position. It could prove that a push on one
device reached another device's pull cursor, and nothing else.

The carrier tests for the three sync scenarios need to assert a real cross-device
merge: two devices signed into one account editing the same entity, both edits
surviving, and the losing edit reported as lost rather than as applied. An event
log cannot answer that, because it never holds a row.

The server the app really talks to is captured in
`supabase/migrations/2026-10-07-sync_schema.sql`, and its merge is per field: a
field is written when the patch's clock beats the clock stored for *that field*
(`sync_hlc_newer`), the row's version moves only when at least one operation won
(`sync_apply_ops`), a patch whose operations all name a field outside the
allowlist is refused with `field_not_writable` (`sync_writable_op_count`), a
patch id is an idempotency key (`sync_applied_patches`), the feed is scoped to
the owner (`sync_events_since`), and the event carries the merged row rather
than the operations that changed it.

## Idea

Make the fixture a merge server: hold the rows it is given, merge each patch
into them field by field, and serve the merged row back.

## Decision

- **The account a client is authenticated as is the owner.** Rows and events are
  scoped by it, and another account sees nothing. The fixture used to serve
  every account's events to every client. That contradicts the SQL, where the
  owner comes from the session, and `SyncApiClient`'s own contract: "the scope
  is not an argument: it is whoever the transport is authenticated as". An
  account that cannot be seen by the wrong client is a test fixture modelling
  something; an account that names a shared namespace is a label.
- **The answer carries the row's version, not the number of operations
  applied.** The SQL answers `newVersion` with the latter, which the schema
  file itself records as a defect (#207). The client stores that number as the
  row's server version, so the fixture must not hand it a number the server
  would not.
- **A patch that loses every field is answered `ok` with `lost = true` and puts
  nothing on the feed**, and the version does not move. A patch that changes
  nothing is not a delivery.
- **The writable fields are the contract's own list**
  (`SyncContract.serverFieldsFor`), the same list the allowlist seed is
  generated from — not a second list written beside it.
- **A patch without a clock is refused with `field_not_writable`**, as the
  server refuses the legacy shape whose absent document expands into zero
  operations.
- **A deletion of a row the server never held still creates the row and delivers
  its tombstone.** The deleting device holds a copy, so the other devices of the
  account hold one too; answering `ok` with no event would leave those copies
  alive and settle the sender's outbox row with nothing to retry.

## Rationale

A carrier test that asserts a merge is only worth writing if the server it runs
against merges. Every rule the fixture skips is a rule a carrier test would
have to re-invent, and the version the client records makes the difference
between a fixture that merges and one that merely records.

The two divergences from the SQL — the account as the owner and the version as
the row's version — are both cases where the SQL's own behaviour is a known
defect or a session the fixture does not have. Reproducing them would make the
fixture a worse copy, not a better one.

## Consequences

- The fixture can now assert a merge: two clients of one account editing
  different fields end up with one document carrying both.
- The old self-check asserted that a push on one account was visible to
  another account. That assertion was the leak, and it is gone; the check now
  asserts that the second device of the *same* account sees the merged row.
- The fixture is still a fixture: no allowlist table, no row-level security, no
  session, no clock of its own. It is enough to assert that two devices merge,
  and it is not a replacement for Supabase.
- The two-instance desktop harness (#2074) and the carrier tests for the three
  sync scenarios are separate work. This change is what they need; it is not a
  part of them.

## Links

- Issue #1441
- `supabase/migrations/2026-10-07-sync_schema.sql` — the merge being mirrored
- `shared/src/jvmTest/.../core/sync/SharedSyncServer.kt` — the fixture
- `shared/src/jvmTest/.../core/sync/SharedSyncServerMergeTest.kt` — the carriers
- `docs/architecture/sync.md` — "Conflict resolution: per-field LWW against a shadow"
