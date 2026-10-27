---
title: "A backup delta is a mergeable payload of rows, and a removal costs the chain its base"
date: 2026-10-27
status: accepted
tags: [backup, cloud, architecture]
---

# A backup delta is a mergeable payload of rows, and a removal costs the chain its base

## Context

The chain of sealed deltas
(`docs/decisions/2026-10-26-incremental-backups-are-a-chain-of-deltas.md`) decided
the *shape* of an incremental backup: a container holds a base plus a sequence of
independently sealed entries, a restore reads the base and applies the entries in
order, and the chain is re-based when it grows past a bound.

It did not decide what a delta *contains*. That is the question this record answers,
because the container is only half the work: the bytes of an entry are whatever the
export decides to put in them, and the restore has to be able to apply them to the
base's payload and end up with the account as it is now.

The payload is a set of tables — tasks, projects, notes, tags, and the link tables
between them — serialized row by row. A delta therefore has to answer three
questions:

- **what is a row?** Two exports of the same account must agree on identity, or a
  delta cannot say "this row changed".
- **what does "changed" mean?** Something has to be compared between the previous
  export and this one, and that comparison has to be cheap enough to run on every
  export and stable enough that a false "unchanged" does not lose data.
- **what happens to a row that disappeared?** A delta that only carries rows cannot
  carry a deletion, and a restore that applies a delta to the base has no way to
  learn that a task is gone.

The third question is the one that decides the design. A delta could carry
tombstones, or the restore could diff the base against the delta, or a disappearance
could be made to cost something.

## Idea

1. **Tombstones.** The delta carries the keys of rows that vanished, and the restore
   deletes them. Exact, but it makes every delta a *diff* rather than a *payload*,
   and it puts the deletion logic in the restore path, where a partial chain or a
   wrong base produces a destructive outcome instead of a visible one.
2. **A delta is a payload, and the restore merges payloads.** The delta is a
   `BackupPayload` like the base, holding only the rows that changed; applying it
   means merging it into the base's payload row by row, keyed by row identity. A
   row that vanished is simply not in the merged result, and the restore writes the
   merged payload — so the delete is a consequence of the restore's existing
   "write what is in the payload" step, not new logic.
3. **Re-derive the base from the delta.** Keep the base and the deltas, and recompute
   the base at restore time. This makes the base disposable, which is the opposite of
   what the chain is for.

## Decision

**A delta is a `BackupDelta` — a schema version, a cursor and a payload — and
`merge(base, delta)` merges it into the base's payload row by row, keyed by row
identity. A row that vanished is not in the delta, so it is not in the merged
payload, so the restore does not write it — and, because a delta cannot express a
deletion, an export that finds a vanished row starts a fresh base instead of
writing a delta.**

Concretely:

- **Row identity is a key per table.** Most tables are keyed by `id`. The link
  tables have no `id` of their own and are keyed by their pair: `TaskTagDto` by
  `(taskId, tagId)`, `TaskDependencyDto` by `(taskId, dependsOnTaskId)`,
  `ProjectTagGroupDto` by `(projectId, tagGroupId)`.
- **A link table is carried whole in every delta.** The link tables are small, they
  are the ones whose membership changes most often, and a merged delta that carried
  only the changed links would still have to know which of the base's links to drop.
  Carrying them whole keeps the merge a pure upsert.
- **"Changed" is decided by a fingerprint of the row's serialized form.** The
  fingerprint is a small local hash (FNV-1a over the row's JSON) held in the device's
  own cursor state. It is **not** a cryptographic hash and **not** an archive
  checksum: it is a change detector between two exports of the same account on the
  same device. The archive's own authentication is the GCM tag, unchanged.
- **A vanished row costs the chain its base.** The export cannot express a deletion,
  so it writes a fresh base and starts the chain again. A removal is rare, and a
  rare full snapshot is cheaper than a restore that deletes the wrong things.
- **An attachment that changed also costs the chain its base.** Attachment bytes live
  in the snapshot, not in the delta; a delta that referenced changed bytes it did not
  carry would restore a broken attachment. The delta carries no attachment rows at
  all.
- **The cursor is the backup's own, and it is device-local.** The cursor state — the
  cursor, the base archive's path, the chain's entries and the row fingerprints —
  is kept in a per-user file through the `FileSystem` port. It is **never uploaded**
  and never part of an archive: it is this device's memory of what it last exported,
  and it is meaningless to another device.
- **The chain state travels beside the archive, not inside it.** The export writes
  the delta file next to the archive it belongs to, and the cursor state names both.
  The push seals the base and the entries into one container, so the uploaded bytes
  do **not** shrink versus a full snapshot; what shrinks is what the export *computes*
  and what a future blob-shaped backup would upload.

## Rationale

**A payload-shaped delta keeps the restore's code path single.** The base payload
and every delta payload are applied by the same function, and the merged payload is
what the restore writes. There is no second application path to keep in step with
the first, and the counts the restore reports are the counts of the merged state —
the state the account is actually in, not the counts of a base that was superseded
three entries ago.

**The upsert is the honest operation for a payload.** A payload says what is there.
Merging two payloads by row identity is the only merge that composes: base + delta1
+ delta2 is the same as base + merge(delta1, delta2), and the order the entries are
applied in does not change the outcome as long as the later ones win.

**A removal is worth a snapshot.** A deletion that cannot be expressed cheaply is a
deletion worth paying for once. Re-basing keeps the failure mode visible — the user
gets a full snapshot — instead of silent, and the bound on the chain means the cost
is bounded.

**The fingerprint stays local and honest.** Calling it a fingerprint and not a hash
of record keeps it out of the security conversation: it never crosses a trust
boundary, it never authenticates anything, and the container's tag does that job.

## Consequences

- `BackupDelta`, `merge` and the row-key rules are one code path, exercised by unit
  tests that do not need a container, a bucket or a database.
- The exporter still writes a full snapshot archive on every export. The incremental
  part is the *chain* that travels with it, and the byte savings of a blob-shaped
  backup remain a separate decision (ADR option 2 of the chain record).
- A removal or a changed attachment re-bases the chain, so a device that deletes
  tasks often pays for full snapshots often. The row-identity rules make this
  measurable if it turns out to hurt.
- The cursor state is a file on the device. Losing it costs a fresh base, not a
  broken restore.
- `RestoreResult`'s entity counts are counts of the merged payload. A caller that
  expected the base's counts sees the account's counts.

## Links

- `docs/decisions/2026-10-26-incremental-backups-are-a-chain-of-deltas.md` — the
  chain this delta belongs to; the shape and the bounds.
- `docs/decisions/2026-10-25-the-archive-format-has-versions.md` — the version rule
  the container follows.
- `docs/decisions/2026-09-18-backup-format.md` — the payload's shape and the
  "no incremental backup" cost this work removes.
- `shared/src/commonMain/kotlin/com/singularity/todo/core/backup/BackupDelta.kt` —
  the delta and the merge.
- `shared/src/commonMain/kotlin/com/singularity/todo/core/backup/BackupRowIndex.kt` —
  row identity, the link keys and the fingerprint.
- `shared/src/commonMain/kotlin/com/singularity/todo/core/backup/BackupCursor.kt` —
  the device-local cursor state.
- `shared/src/commonMain/kotlin/com/singularity/todo/core/backup/BackupImporter.kt` —
  the one application path base and deltas share.
- Issue: #1184
