---
title: "A delta carries the rows it removed"
date: 2026-10-27
status: accepted
tags: [backup, cloud, architecture]
---

# A delta carries the rows it removed

## Context

ADR `2026-10-27-a-backup-delta-is-a-mergeable-payload-of-rows.md` decided the shape
of a delta: a delta is a `BackupPayload` holding the rows that changed, and
`merge(base, delta)` merges it into the base's payload row by row, keyed by row
identity.

It also decided, in the same breath, that **a vanished row costs the chain its
base**: "a delta cannot express a deletion, so an export that finds a vanished row
starts a fresh base instead of writing a delta". The reasoning was that a removal
is rare, that a rare full snapshot is cheaper than a restore that deletes the wrong
things, and that re-basing keeps the failure mode visible.

The prediction was that removals would be rare. They are not. Deleting a task is
one of the most ordinary things a person does with a task list, and every one of
them ended the chain: the next export wrote a fresh base, and the chain that the
user's other device would have downloaded was replaced by a full snapshot. The cost
is not one snapshot — it is that a deletion makes the *whole* backup expensive
again, exactly as if the incremental backup did not exist.

The premise behind the old decision is still true and is the reason this record
exists: a delta that carries only rows cannot carry a deletion. But "a delta is a
payload of rows" and "a delta cannot say a row is gone" are two different claims,
and only the second one was ever necessary. The first one is what makes the
restore's code path single; the second one is what made the removal expensive.

There is a third option that the earlier record did not consider, and it is the one
the earlier record itself set up: the payload of a delta is a payload, and a
payload can say more than what is there.

## Idea

1. **A delta carries tombstones.** The payload grows a field that names the rows
   that are gone — by key, per kind. The merge takes them out, and the restore
   deletes them. This is the option the earlier record rejected, on the grounds
   that it "makes every delta a *diff* rather than a *payload*" and "puts the
   deletion logic in the restore path, where a partial chain or a wrong base
   produces a destructive outcome instead of a visible one".
2. **The restore diffs the base against the delta.** The restore compares the base's
   rows with the delta's and deletes whatever the base has and the delta does not.
   No new field, but the deletion becomes a guess: any row the base happens to
   miss is deleted too, and the base is the only witness of what the account
   looked like.
3. **A removal still costs a snapshot.** Unchanged.

## Decision

**A payload may carry the rows that were removed. The removals are the keys of the
gone rows, per kind, and they travel in the payload — the same payload the delta
already has.**

Concretely:

- **The field is optional and lives on the payload.** `BackupPayload.removals` is a
  map from the row index's kind to the keys of the rows that are gone. It is
  optional: a payload that does not have it says what it always said — the rows it
  holds are the whole account, and nothing was removed. The schema version moves to
  7 (`BackupFormat.SCHEMA_VERSION`), because a payload that *can* say a row is gone
  is a shape the v6 client does not know, and a v6 client that met one would
  restore a row the backup says is not there any more. The v6 → v7 migration is
  exactly a version bump: the field defaults to empty, and an archive written by
  the previous build still reads.
- **A removal is a key, not a row.** A row that is gone has no content to carry,
  and the row index never held one — it holds a fingerprint per row, and the
  fingerprint of a row that is not there is nothing. What travels is the statement
  "this key is not here any more". The kinds are the row index's kinds, so a
  removal is looked up under the key the row would have had, including the link
  tables, whose key is the pair of ids they join.
- **The merge applies the removals as well as the upserts.** A row the delta
  upserts replaces the base's row of that key; a row the delta says is gone is
  taken out; a row the delta says nothing about is kept. The removals are applied
  after the upserts of the same delta, so the two never overlap: a row that is gone
  cannot also have changed.
- **The merged payload is a state, and a state carries no removals.** The removal
  has been applied to it. That is also what keeps a chain honest: merging the next
  change on top of a merged payload must not carry the earlier removals along, or a
  row that came back would be taken out again.
- **The restore deletes the rows.** The restore applies the removals of every
  payload it applies — the base and each change — so a row the base holds and the
  change says is gone does not come back. This is the first deletion in the restore
  path, and it needed a single-row delete per table: a statement that cannot be
  aimed at one row would take the rest of the profile with it.
- **The removals of a payload go after its rows, and the children go before their
  parents.** Two orders, one rule. The removals go last because that is where the
  merge puts them: a row the payload carries is alive by the time the removals
  that name its children go out. The children go first among the removals
  because the link tables have no `user_id` of their own — a row of `task_tags`
  belongs to whoever owns the task it joins, and the statement that removes it
  reaches its row through that task. A removal of a task and a removal of one of
  its links can therefore only be taken out in one order, and the other order
  leaves the link behind pointing at a task that is not there. The tables have no
  foreign key to cascade, so nothing else comes to clean it up.
- **A removal is scoped to the profile being restored.** Every removal is
  user-scoped, and the profile is no exception: `ProfileDao` gained a
  `deleteForUser(id, userId)` next to its unscoped `deleteById(id)`, and the
  restore uses the scoped one. A profile whose `user_id` is NULL belongs to
  nobody, so nobody's restore takes it.
- **A task that is deleted in the app is archived, not erased.** Deleting a task
  sets `archived_at`; the row is still there, and the export still carries it.
  So the ordinary "delete a task" is a *changed* row — it travels in the change
  like any other edit, and the chain is not re-based over it. A tombstone is for
  the row that is *gone*: the row itself was erased from the table, which today
  happens when a profile is wiped. The distinction matters because the two
  travel differently, and the claim "a delete travels in the chain" is only
  literally true of the second.
- **An attachment that changed still costs the chain its base.** The attachment's
  bytes live in the snapshot and a delta carries no bytes, so a changed attachment
  still re-bases the chain. A *removed* attachment lands there too — the file is in
  the snapshot, and a delta cannot take a file back out of it.

## Rationale

**The earlier decision answered the wrong question.** It asked what a delta is and
concluded that a payload cannot express a deletion, and from that it concluded
that a deletion must cost a snapshot. But a delta is a payload *and* a payload may
carry removals: the two questions are separable, and separating them keeps the
property the earlier record valued — one application path, the same function for
the base and for every change — while removing the cost it did not value.

**A removal is not rare, and "rare" was the whole argument.** The cost of the old
behaviour is paid on the single most ordinary action in the app. A person who
deletes a task between two backups gets a full snapshot, and the chain that their
other device was going to download is thrown away with it. The bound on the chain
that made the old behaviour "bounded" does not bound this: the chain is re-based
when the *next* export happens, whatever the bound says.

**The destructive-failure argument does not apply to a keyed tombstone.** The
earlier record worried that "a partial chain or a wrong base produces a destructive
outcome instead of a visible one". The tombstone is not a guess: it names the exact
key to delete, it is scoped to the profile being restored, and it is applied by
the same path that writes the rows. The restore that applies a wrong base is
already wrong — it writes the base's rows over the account — and the tombstone
does not make it worse; it makes it *more* wrong in the direction the base already
points. The option the earlier record rejected, "the restore diffs the base against
the delta", is the guessing one, and it is not what this record decides.

**The chain keeps its meaning.** With a removal inside the chain, the chain is
again "the base plus the changes since", and the base's role is the same as it was
when the chain was introduced: the thing the changes apply to. Re-basing on every
deletion meant the chain was usually one entry long, which is not a chain.

**The version rule still holds.** The contract is unchanged: a build refuses a
payload newer than it knows, and reads an older one. The v6 client refuses a v7
archive rather than restoring a row the archive says is gone, and the v7 client
restores a v6 archive with everything it always restored.

## Consequences

- Deleting a task no longer re-bases the chain. An archived task travels as the
  changed row it is; an erased one travels as a tombstone. Either way the change
  that carries it travels with the chain, and the restore applies it.
- The restore path deletes rows for the first time. Every table it can delete from
  needs a single-row delete scoped to the profile — `TaskDao`, `NoteDao`,
  `ProjectDao`, `TagDao`, `TagGroupDao`, `ProjectInheritedTagGroupDao` and
  `AttachmentAnnotationDao` gained one; `ProfileDao` gained a scoped one beside
  the unscoped `deleteById` it already had; the tables that already had one were
  left alone.
- The removals are applied in an order, and the order is part of the contract: the
  children of a row go before the row, and the removals of a payload go after the
  rows that payload carries. A restore that took the parents first would leave
  link rows behind, pointing at rows that are not there.
- A change that only removes rows carries no rows of its own. It is still a
  change, and it is still written: "no rows" is not "no change".
- The removals are keys, so a restore cannot resurrect a row that was deleted
  between two exports and then restored from an older archive — the older archive
  has no tombstone for it, and the tombstone in the newer one wins.
- An attachment that changed still re-bases the chain. This record does not change
  the bytes problem; it only stops the *row* problem from re-basing the chain too.
- The payload's schema version moved to 7. Old archives read; new archives are
  refused by a v6 client.

## Links

- `docs/decisions/2026-10-27-a-backup-delta-is-a-mergeable-payload-of-rows.md` — the
  record this one amends; it decided that a removal costs the chain its base.
- `docs/decisions/2026-10-26-incremental-backups-are-a-chain-of-deltas.md` — the
  chain the removals now travel in.
- `docs/decisions/2026-10-25-the-archive-format-has-versions.md` — the version rule
  the schema version follows.
- `shared/src/commonMain/kotlin/com/singularity/todo/core/backup/BackupPayload.kt` —
  the optional field.
- `shared/src/commonMain/kotlin/com/singularity/todo/core/backup/BackupDelta.kt` —
  the merge and the planner.
- `shared/src/commonMain/kotlin/com/singularity/todo/core/backup/BackupImporter.kt` —
  where the removals are applied.
- `shared/src/commonMain/kotlin/com/singularity/todo/core/backup/BackupRowIndex.kt` —
  the kinds and the keys.
- Issue: #1184
