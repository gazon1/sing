---
title: Patches carry field operations and a logical clock, not a snapshot and a checksum
date: 2026-10-04
status: accepted
---

# Patches carry field operations and a logical clock, not a snapshot and a checksum

## Context

`buildPatch` produced `ops = emptyList()` and a `shadowChecksum`: a SHA-256 over
the entity's JSON, computed by `ConflictResolver`, sent so the server could ask
"is the row you based this on still the row I have?".

That is a whole-row contract. The answer is yes-or-no, so the resolution is
last-writer-wins on the entire entity — and REQ-OS-003 requires something
finer: a field is overwritten only when the incoming patch's clock is newer
than the one stored for *that field*.

Two people editing two different fields of the same task cannot both win under
a row checksum. One of them loses data that the other never touched, and
neither is told. The checksum is not a cheap way to detect conflicts; it is the
thing that decides them, in the wrong granularity.

The checksum was also the only reason `ConflictResolver` existed, and it called
`java.security.MessageDigest` from `commonMain` — a JVM API in shared code,
which is what `CommonMainJvmApiTest` now exists to prevent.

## Decision

A patch carries the fields that changed, each with the entity's hybrid logical
clock. The base for the diff is a new table, `sync_shadow`.

**Two columns, not one.** `confirmed_json` is what the server has. `in_flight_json`
is what a queued patch will bring it to. A diff is taken against the in-flight
state when one exists. Diffing against the confirmed state instead would re-send
fields a pending patch is already carrying — every patch still applies correctly,
and the payload is quietly twice the size it needs to be for as long as the
outbox is non-empty, with no counter anywhere to show it.

**The shadow advances on acceptance, never optimistically.** A rejected patch
leaves it where it is; a dead-lettered or permanently-unpromotable patch
*releases* the in-flight marker and keeps the confirmed state at what the server
really holds, so the next local edit re-diffs and re-sends those fields. The dead
letter is the record that they were not delivered; re-sending them is the repair,
and it happens once per edit rather than in a retry loop.

**The promote is guarded on `in_flight_patch_id`.** A response for a patch that a
newer edit has superseded must not promote its state, or the next diff is taken
against a state the server does not have — a silent loss with nothing to report.

**The outbox coalesces per entity.** At most one patch per entity is in flight.
The replacement is built against the previous in-flight state, so it carries every
field its predecessor carried and nothing is lost; and it bounds the outbox, which
otherwise grows without limit under fast editing. Without coalescing the
`in_flight_patch_id` guard could never fire.

**A new shadow's confirmed state is the empty object.** Not the local state. An
entity with no shadow has never been uploaded, so the server holds nothing, and
seeding `confirmed_json` with the local state claims the opposite — the first edit
after the upgrade produces an empty diff and uploads a task with no title. This
is also why the v34 → v35 migration backfills nothing, and the two reasons are
the same one.

## Rationale

The diff itself is unremarkable. Everything above is about *which state the diff
is taken against*, because that is where the silent failures are: a diff computed
against a stale base is correct code producing a wrong patch, and it is invisible
in every metric the client keeps.

The alternative to a shadow table is to ask the server for the base on every
patch. That makes each edit a round trip, and it means the client cannot build a
patch while offline — which is the whole premise of the feature. The shadow is
the cost of offline, and it is the right one.

The clock comes from `HlcFactory`, not from `System.currentTimeMillis()`, because
two patches authored in the same millisecond still need a total order, and because
the hybrid part is what makes a device whose wall clock is minutes behind still win
against a field the ahead device never touched.

One thing deliberately **not** decided here: `FieldOp.APPEND` and `REMOVE` exist in
the protocol and are unused. No entity in this app has a list whose
order-independent merge is correct, so choosing them would be an invented semantic
rather than an implemented one. Lists are replaced wholesale and the field's own
clock picks the winner.

## Consequences

- `ConflictResolver` and `ConflictResolverTest` are deleted. `DeltaPatch` loses
  `shadowChecksum` and gains `hlc`. `isRetriable` still recognises the server's
  `shadow_mismatch`, because a server on the old contract can still answer with it
  during a rollout — the client's understanding of the error is not the same thing
  as its use of the mechanism.
- `PatchResult.serverState` is what the client would use to rebuild the shadow
  authoritatively, and nothing reads it yet. The client-side promote is
  `in_flight_json`, which is correct while the client is the only writer; a server
  that merges a concurrent remote patch into the same field invalidates it, and
  that is Phase 3's problem to solve.
- The outbox is now bounded per entity, which changes retry semantics slightly: a
  patch replaced by a newer edit is not retried, because its fields are in the
  replacement.
- `SyncPatchBuilder` is a separate class rather than a private method on
  `SyncEngine`, so the diff can be tested without a push path. The engine tests
  use a *real* builder over a fake shadow DAO rather than a stub — a stubbed
  builder would let every engine test stay green with a broken diff.
- Room 34 → 35, no backfill, for the reason in the third sub-decision.

## Links

- `shared/src/commonMain/kotlin/com/singularity/todo/core/sync/SyncShadow.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/core/sync/SyncPatchBuilder.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/core/sync/SyncProtocol.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/core/database/Migration34To35.kt`
- `shared/src/jvmTest/kotlin/com/singularity/todo/core/sync/BuildPatchDiffTest.kt`
- `openspec/changes/supabase-auth-and-sync/specs/offline-sync/spec.md` (REQ-OS-002, REQ-OS-003)
- `docs/decisions/2026-10-04-per-field-lww-conflict-policy.md`
