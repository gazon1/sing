---
title: "A restore can keep the rows the account already has"
date: 2026-11-04
status: accepted
tags: ["backup", "restore", "import"]
---

# A restore can keep the rows the account already has

## Idea

A restore wrote the archive over the account: every row the archive carried was
upserted, and a change behind it could take a row out. So restoring an older
archive over an account that had been edited since replaced those edits with the
archive's older versions, and the only warning was the confirmation dialog's
text. The feature briefly carried an `overwriteExisting` option that said
otherwise; it was removed because nothing read it — a caller that set it to
`false` still got an overwrite.

The question the option raised is still a real one: can a person merge a backup
into an account without losing what the account holds now?

## Decision

`ImportOptions.keepExistingRows`, default `false`, and it is read by
`BackupImporter`:

- **`false` — the archive wins, exactly as before.** Every row the archive
  carries is written over the account, and a removal is applied. This is what
  every existing caller gets, and no restore changes behaviour.
- **`true` — the account's rows are kept.** Only the rows the account does not
  have are written. `RestoreResult.keptEntityCounts` reports what was left
  alone, by kind, so a result can say "the account already had all of this"
  instead of looking like a restore that did nothing.

The existing rows are read once, at the start of the restore, before anything is
written: "already there" means the account as the restore found it, not the
archive's own earlier entry. The base and the changes behind it therefore stay
one story in their own order, and a row that appears in both is counted as kept
once.

The reads are the exporter's own `listAllForUser` statements, so "what the
account holds" and "what an export would carry" cannot drift apart.

## The edge cases, answered

- **A change says a row was removed.** When keeping, removals are not applied.
  An additive restore may not delete what the account holds, and the row the
  change says is gone is one of the rows the person asked to keep. This is a
  consequence of the choice, not a bug: a person who wants the archive's
  deletions wants the archive.
- **An attachment row the account already has, with different bytes under the
  same id.** The row *and its file* are left alone, and it is counted as kept.
  The bytes are part of the row: overwriting the file while keeping the row's
  metadata would leave a row describing a file it does not match. The kept
  attachment appears in neither `restoredAttachmentCount` nor
  `missingAttachmentIds`.
- **A restore of an archive into an account that already holds most of it.**
  That is "nothing changed", and it is what the person asked for: the result
  carries zero written rows (the archive's counts less the kept counts) and a
  full kept count. The screen says so rather than implying a failure.
- **The rows that hang off a kept row** — a tag link, a dependency edge, a
  checklist item, a reminder, a time entry — are rows of their own. They are
  written when the account does not have them, so a kept task can gain the
  archive's tags while its own title stays as it was. The alternative (a kept
  parent suppresses its children) is a stronger promise, "leave my task wholly
  alone", and it is not the one chosen here: the unit is the row.

## The restore screen

The screen offers the archive-wins path only, and its confirmation says exactly
that: "Restore will overwrite current data." That wording matches the behaviour
the screen invokes — the default — and this record is where the choice to keep
the merge off the screen is written down. A screen control would be the next
step if the merge is wanted by a person rather than by a caller.

## Consequences

- `ImportOptions` grows the flag, and `RestoreResult` grows
  `keptEntityCounts` (defaulted, so nothing that constructs either breaks).
- `BackupRepository.import` takes the choice as a defaulted parameter; the cloud
  restore stays archive-wins.
- The importer reads the account's rows in full to build the key sets. A restore
  is rare and already holds the archive in memory; an id-only statement per
  table is the change to make if this ever shows.

## Tests

`BackupKeepExistingTest` (jvm): the default still lets the archive win; with the
flag, an existing row keeps the account's version while a missing row is
written, and the kept/written counts say so; a change that removes a row does not
remove it when keeping; a second restore of the same archive reports every row
kept and changes nothing.
