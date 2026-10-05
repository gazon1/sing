---
title: Per-field last-write-wins as the sync conflict policy
date: 2026-10-04
status: accepted
---

# Per-field last-write-wins as the sync conflict policy

## Context

The sync conflict policy was never decided; it was inherited from an implementation.
`SyncBootstrapper` registers one pull handler per document type and each handler ends in
an unconditional repository write. The comment above the dispatcher states the policy
outright:

> Conflict detection is deferred to Tier 4 (HLC comparison). Currently uses
> last-write-wins: remote always overwrites local.

So the rule is **last-write-wins on the whole row**. The server side that would have
enforced a check did not exist: the transport was a stub returning an empty result, and
`baseVersion` was sent to nobody.

`Hlc` and `HlcFactory` are implemented, tested, and called from nothing. The
`syncHlc` property on synchronised entities is nullable and always null. The `hlc`
column exists in the database and is never written. Someone designed for per-field
causal ordering and then shipped row-level last-write-wins.

This matters more than "eventually". A user with a phone and a laptop edits the same
task on both. Phone sets the due date. Laptop, holding a stale copy, renames it. The
renaming patch is written wholesale, so the due date reverts to whatever the laptop's
stale copy held — and the laptop never learns it was overwritten, because the pull
that would have told it is the pull that applies the reverted state. The user sees a
due date silently reappear in the past. There is no notification, no conflict counter,
no version bump that would surface it.

Three options were on the table:

- **Row LWW with a conflict prompt.** Show the user both values and let them choose.
- **Real CRDT** (Yjs, Automerge). Merge at the character level.
- **Per-field LWW.** Store a logical version per field; apply each field independently.

## Decision

**Per-field last-write-wins, ordered by hybrid logical clock.**

Each row carries a map of field name to logical clock reading. A patch carries the
fields that changed plus one reading for the patch. The server applies each field
independently: overwrite a field only if the incoming reading is newer than the reading
stored for that field.

This makes the merge a property of the data, not of a negotiation. Taking the maximum
is commutative, associative and idempotent, so the final state equals the maximum over
every value ever received for each field, independent of arrival order, duplication or
retries. There is no round trip to ask the user and no conflict state to recover.

Logical clock, not wall clock. `timestampMs` on two devices can differ by minutes, and
the "later" edit would lose. `Hlc.tock` merges a received reading with the local one,
so a device that has *seen* a write always emits a higher reading — ordering follows
causality rather than clock accuracy.

`baseVersion` and the row checksum stop being write gates. They become diagnostics. A
stale base can no longer block a patch, because a single stale field would otherwise
reject the whole batch — which is precisely the merge we are trying to get.

The honest boundary: two devices changing the *same* field still lose one value. That
is inherent, not a defect of this design — two values compete for one cell. What
changes is the blast radius. Under row LWW a due-date edit dies because of a race over
the title. Under per-field LWW only the field both devices touched dies, and that is
rare enough to be invisible in practice.

CRDT was rejected on cost. Character-level merge is not what a task list needs, and it
would force a binary payload and a separate storage model through every synchronised
entity and every pull handler. The conflict prompt was rejected on fit: for a personal
app, an occasional dialog is more annoying than a rare discarded keystroke, and the
prompt is the thing users learn to dismiss without reading.

## Rationale

The alternatives fail differently. A conflict prompt is a UI feature that must be
correct on every device, in every state, including background pulls where nobody is
looking — and it still loses data if the user picks the wrong side. CRDT buys
convergence nobody asked for at a cost paid by every entity class. Per-field LWW is
the only option that converges without a UI, without a coordinator, and without
changing the shape of the pull path.

It also revives dead code. `Hlc`, `HlcFactory` and the `hlc` column were written for
exactly this and then orphaned; the `ops` field on the patch — always empty, with
comment-only field operations — was written for exactly this.

## Consequences

- The server stores one logical clock per field per row. For a task with ~30 fields
  that is ~30 keys of bookkeeping. For free-text bodies this does not scale, so text
  bodies stay per-row; only scalar fields get per-field versions.
- A patch is now a diff against the last known uploaded state, not a snapshot. The
  client needs that last-known state locally to compute the diff. It is a local
  concern only — it is not part of the conflict protocol.
- A successful patch means "applied in full or in part", not "all your fields won".
  One field can lose the race while the patch reports success. The follow-up download
  reconciles, and the UI must not claim "saved" means "kept".
- `conflict` on the patch result is now nearly unreachable. It remains in the protocol
  for a patch that is entirely older than the row, and for a future policy change.
- Client-side row checksums are removed. They were a fast-reject for row LWW and have
  no meaning when the decision is made per field.
- Protocol version advances, with a fallback to wall-clock ordering for patches that
  carry no logical clock — so an old client degrades to row behaviour instead of
  failing.

## Links

- `openspec/changes/supabase-auth-and-sync/specs/offline-sync/spec.md` — REQ-OS-002,
  REQ-OS-003, REQ-OS-004
- `shared/src/commonMain/kotlin/com/singularity/todo/core/sync/Hlc.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/core/sync/SyncBootstrapper.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/core/sync/ConflictResolver.kt`
