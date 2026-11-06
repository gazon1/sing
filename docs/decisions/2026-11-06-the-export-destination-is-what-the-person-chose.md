---
title: "The export destination is what the person chose"
date: 2026-11-06
status: accepted
tags: [backup, export, storage]
supersedes: 2026-10-24-every-directory-in-the-graph-is-qualified
---

# The export destination is what the person chose

## Context

`2026-10-24-every-directory-in-the-graph-is-qualified` fixed the backup directory's
name and, in the same breath, fixed what an export may be aimed at. Two rules came out
of that entry:

1. **Every directory in the graph carries a qualifier, and every consumer names the one
   it wants.** A bare `String` in a constructor is resolved by type, and a type is not
   specific enough to mean "a directory".
2. **The backup repository owns the backup directory.** The caller passes a *file name*;
   the repository joins it to the directory it owns. A path is rejected rather than
   followed, so no caller can aim the archive at the filesystem root.

The first rule still holds. The second one did not survive contact with the feature
that followed it: a person must be able to save an archive where they want it, and the
system save dialog answers with a `content://` URI — a document, not a path inside a
directory the app owns. The repository that owns a directory cannot produce that URI.

So the export grew a second kind of destination, and the code changed to accept one:
a destination carrying a scheme, a `content:` prefix, or a path separator is used as it
is, while a bare file name is still joined to the backup directory. The change landed
as a feature commit, without a decision entry — so the 2026-10-24 entry went on
reading as current, and it was no longer true. It said a path is rejected rather than
followed, while the code accepts an absolute path verbatim.

## Idea

1. **Keep the repository's ownership and treat the person's choice as an exception.**
   The caller says what it means; the repository decides what a bare name means. The
   shape of the value tells the two apart, so nothing has to be passed alongside it.
2. **Resolve the destination in one place.** The repository reads
   the value, decides which kind it is, and either joins it or uses it as it is. A
   rule that says "a path is refused" cannot be kept next to a feature that lets a
   person pick a path.
3. **Write a new entry and supersede the old one.** The old entry explains why the
   directories are qualified; that half is still in force. Superseding it wholesale
   would lose a live rule from the digest, so the new entry carries it forward.

## Decision

**A destination is one of two kinds, and the shape of the value says which.**

- **A bare file name** — `backup.zip`, no scheme and no path separator. The
  repository owns the backup directory, joins the two, and creates the directory if it
  is not there. A blank name is refused.
- **A destination the person chose** — an absolute path, or a `content://` URI from
  the system save dialog. It is used **verbatim**: nothing is joined to it, and the
  archive lands exactly there.

The kinds are told apart by the value: `://` anywhere, a `content:` prefix, or a path
separator (`/` or `\`) means the second kind; none of them means the first.

**What a person-chosen destination does not get is the chain.** The change that
follows the snapshot — the delta, the bookkeeping of what the last export's rows
looked like — is written only when the destination resolves **inside the backup
directory**. An archive saved elsewhere is a snapshot and nothing more.

**Nor is it one of the local backups.** The list of local backups *is* the backup
directory: it is a scan of that directory, rescanned every five seconds. An archive
written somewhere else does not appear in it, is not pushed from it, and is not
deleted by it. It is a file the person has, like the export they made of it.

**The rule about the directories themselves is unchanged.** Every directory in the
graph still carries a qualifier and every consumer still names the one it wants; that
half of the 2026-10-24 entry is restated here because the entry it lived in is
superseded.

## Rationale

The shape of the value carries the choice, so the caller does not have to say it twice.
A separate flag would be a second thing to keep in step with the first — and the first
thing that can be wrong is the string itself, because it comes from a system dialog.

Joining a person-chosen destination would have been wrong in the only way that
matters: the archive would not be where they put it. Rejecting it would have meant
the feature does not exist. Following the path the way the old rule feared — treating
it as a path under the app's control — is what the old rule was protecting against,
and the protection is now unnecessary, because the person is the one naming the
destination.

The chain and the local-backup list stay tied to the backup directory on purpose. The
delta is bookkeeping about a chain of snapshots, and a chain that spans two unrelated
directories is a chain nobody can restore. The local-backup list is the app's view of
"the backups this device holds"; a file the person saved elsewhere is theirs, and
putting it in that list would make the app offer to push, and to delete, something it
does not own.

A destination that is a `content:` URI is a **document**, not a file: there is no
sibling to write next to it and no rename to make, so the archive is written straight
into it. Any other destination is written under a temporary sibling and moved into
place once the archive is whole, so a failed export leaves no half-written archive
behind.

## Consequences

- A destination carrying `://`, a `content:` prefix, or a path separator is a
  person-chosen destination and is used **verbatim**; a bare file name is joined to the
  backup directory the repository owns.
- A blank destination is **refused** — the repository's own resolution rejects it. A
  value carrying a separator never reaches that resolution: it is a person-chosen
  destination.
- The repository still owns the backup directory and still creates it for a bare file
  name; a person-chosen destination does not go through it.
- The change that follows the snapshot is written **only** for a destination inside the
  backup directory.
- An archive saved outside the backup directory is **not** one of the local backups
  the repository lists, and is neither pushed nor deleted from that list.
- Every directory in the graph still carries a qualifier, and every consumer still
  names the one it wants (carried over from `2026-10-24-every-directory-in-the-graph-is-qualified`).

## Links

- `shared/src/commonMain/kotlin/com/singularity/todo/core/backup/BackupRepository.kt` —
  `isUserChosenDestination`, `resolveBackupDestination`, `export`, `scanBackups`.
- `shared/src/commonMain/kotlin/com/singularity/todo/core/backup/BackupExporter.kt` —
  `isAppResolvedDestination`, `temporarySiblingOf`.
- `shared/src/commonMain/kotlin/com/singularity/todo/core/backup/BackupOptions.kt` —
  `ExportOptions.destPath`.
- `shared/src/commonMain/kotlin/com/singularity/todo/core/backup/BackupCodec.kt` —
  the platform codecs that write to the destination.
- Supersedes: `docs/decisions/2026-10-24-every-directory-in-the-graph-is-qualified.md`.
- Supersede process: `docs/decisions/2026-09-26-adr-supersede-process.md`.
