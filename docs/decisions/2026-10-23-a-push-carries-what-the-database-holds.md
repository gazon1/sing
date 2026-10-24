---
title: "A push carries what the database holds"
date: 2026-10-23
status: accepted
tags: [tasks, sync, database]
---

## Context

A task that travels to the server is a claim: "this is what the work is". The
claim travels as a document, and the parts of the task that live outside the
row — the tags, the blocking edges — travel with it. The receiver applies the
claim: a document that says "the work has no tags" leaves the task with no
tags.

The rule that follows from that is one the receiver now honours
(`2026-10-23-a-document-says-only-what-it-carries.md`): **a part the document
does not carry is not written**. The absence of a claim is not a claim of
emptiness. The receiver reads the wire, and the wire is only as honest as the
device that wrote it.

The devices were not honest. A task read through `get()` carries no tags and
no blocking edges — they live in cross-ref tables, and the read is not
interested in them. The write path knew this for the *local* write: `update()`
does not write the tags, and the comment there says why — writing the
emptiness of a read would take the tags off the task. The *push* took the same
task and sent it, and the wire does not know the difference between "no tags"
and "I did not look".

So an edit — a title, a date, a completion — sent "the work has no tags" and
"the work waits for nothing" to the server. The server applied the claim. The
tags and the edges were gone from the server, and every other device lost them
on the next pull. The edit itself was applied; the work it belonged to was
quietly stripped, on every other device, by a device that had never been asked
whether the work had tags.

Three paths did it:

- `update()` — the ordinary edit, the path every editor takes;
- `updateAll()` — the batch write, which writes the tags and not the edges;
- `TaskDaoArchiveRepositoryImpl.archiveCompletedTasks()` — the bulk archive,
  which pushed a row it had read without its cross-refs.

The other repositories were checked: the notes, the tags, the tag groups and
the time entries carry their parts inside their rows, so a caller's copy of
one is the whole truth. The projects are the exception — a project's inherited
tag groups are a join table, and the project path has the same hole
(#1034). The rule bites where a part lives outside the row.

Nothing above the repository could see it. The outbox takes a `SyncableEntity`,
and the entity was the task as the caller held it. The claim left the device
inside an ordinary `enqueue` call, and the loss happened on another device,
later, in a pull.

## Decision

**A write pushes what the database holds, not what the caller happened to
hold.**

Every path that writes a task and enqueues it enqueues the task as the
database holds it: the row, the tags and the blocking edges, read back after
the write. The assembly lives in one place — `TaskDao.freshTask`, and
`TaskDao.freshTasks` for the paths that push many at once — next to the mapper
it ends in, so there is no second copy of "what a task is for the wire" to
drift.

The empty collection on the wire is a claim, and a claim has to be true. A
device that does not know whether the work has tags says nothing about the
tags; it does not say "the work has no tags".

The consequence reaches the screen. The tag edit on the detail screen went
through the ordinary write, which does not write the tags — the local write
has always said that the path that knows what the tags should be is the one
that writes them. The push carried the tags, so the edit reached the server
and came back on the next pull, and the local row never held them. The tag
edit now goes through `setTags`, the path that writes them: the screen shows
the change at once, and the push carries what the database holds.

## Consequences

- The push re-reads the row after the write. A write costs one more read, and
  buys a claim that is true. A batch of N members costs N reads, one per
  member: each read takes the row and the cross-refs of that member alone.
  The bulk archive is the exception and reads the cross-refs once for the
  whole set — a batch there is unbounded in size, and the per-member read
  would be one round trip per archived task. That one pass reads the
  cross-refs of the whole profile rather than of the set it assembles: it is
  one read either way, and the set of a bulk archive is not known before the
  rows are read.
- The parts of a task on the wire are always the database's. A caller that
  changes the tags changes them through `setTags`, which is the path that
  writes them — the same rule the local write already follows.
- A create pushes the task as the database holds it, and the database does not
  hold the edges the caller carried: the create writes the tags and the row,
  and not the dependencies. So the pushed task waits for nothing, and that is
  the truth about a task that has just been created — an edge the caller held
  and never wrote is not an edge the work has.
- The series' shared tags travel as the database holds them. A batch write
  carries the tags of the series and writes them first, and the push then says
  what it wrote — the emptiness of a read is not a claim, but the emptiness a
  batch *wrote* is one. A batch built from a task read without the tags
  therefore takes the tags off the task, and the push says so: the push is
  honest, and the mistake it reports is the caller's, in the same place the
  single-row write refuses to make it.
- The bulk archive pushes the tasks it archived with their tags and their
  edges, instead of pushing rows and calling it a task.
- The pushed task is the row, so the stamp it carries is the row's stamp.
  The stamp itself does not change: the write that re-stamps a row is the one
  that already re-stamped it (the update use case stamps the caller's copy
  before the write), and the push reads that stamp back from the row it
  wrote. What changes is that the push no longer carries the caller's copy of
  the task, which held the parts the read did not load.
- A test can now see the claim: the outbox's entity is the database's task,
  and a test that reads it says what the server was told.
- The tag edit on the screen now goes through `setTags`, and that path does two
  more things the old path did not. A tag the user removed is recorded as
  suppressed, so the AI does not propose it again; the suppression is a
  column of the row, so it travels with the push like any other part. And the
  path repairs the series: the tags are a shared field, so the other members
  follow the one whose tags were changed, and the repair aligns their shared
  fields with it. The old path wrote only the one row, and a series whose
  members disagreed stayed that way.
- A write that changes nothing claims nothing. `setTags` with the tags the row
  already carries writes nothing and pushes nothing: a claim about a set that
  did not move says nothing new, and the screen may ask, but the wire is not
  told.
- The bulk delete of a profile reads the cross-refs before it deletes them —
  that is the only way to say what the work had — and pushes the tasks it
  assembled from them. It is the one path that does not read the parts back,
  and the reason is written down where it happens.

## What this does not do

- The receiving side is not here. A document that arrives without a field
  still resets that field of the row, because the row is written whole — the
  absence of a claim in the row is the other half of the same rule, and it is
  tracked as #1026.
- The shadow does not remember what the device knew (#1032), and the
  allow-list on the wire is still a second list (#1033).
- The claim is still a claim: a device that means "this work has no tags" says
  so, and the other devices believe it. That is the protocol working, not the
  protocol failing.
