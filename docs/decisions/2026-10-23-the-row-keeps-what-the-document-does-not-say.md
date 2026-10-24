---
title: "The row keeps what the document does not say"
date: 2026-10-23
status: accepted
tags: [tasks, sync, database, mapping]
---

## Context

A task document arrives from another device, and it is applied to the row that
is already here. The row is written whole — `@Upsert` replaces it — and the
fields the model cannot do without have defaults: `seriesId` defaults to no
series, `recurrence` to no recurrence, `estimateMinutes` to no estimate.

So a document that carries no `seriesId` key decoded into a task that says
"this task stands alone", and the row was written with it. The task left its
series on the device that pulled it. The same for a recurring task that lost
its recurrence, and for a task that lost its estimate.

The wire is the reason this matters. A document carries the fields the sending
device knew about: a device that predates the series sends no `seriesId` key,
and a device that predates the recurrence sends no `recurrence` key. Nothing
in the contract says a device sends the whole document; the allow-list says
what a device sends, and it grew.

The deletion is invisible. The row is written, the pull reports success, and
the task is simply not in its series any more — on one device, until the next
local edit of that task sends the row back without the series, and the series
loses it.

The rule for the parts that live *outside* the row — the tags and the blocking
edges — was already stated and already implemented: a document says only what
it carries, and what it does not carry is not applied. That change said so
about the parts, and named the row's own absences as the known limitation it
could not cover, and pointed here (#1026).

## Idea

A document is a statement about the work, and it states only what it carries.
Where it says nothing, the row that is already here goes on saying what it
says.

## Decision

1. **The path that applies a task document reads it against the local row.**
   The document wins wherever it carries a value; a field it does not carry is
   not reset, cleared or defaulted by its arrival. The read happens on the
   wire document, before the decode: after the decode the two cases are the
   same task, because the difference between "no series" and "no claim about
   the series" is gone.

   The row that is read is this profile's row. A document of another profile
   is not read against anything: two profiles of one account can hold a task
   with the same id, and the row of this profile must not fill the row of the
   other one.

2. **A claim is still a claim.** A document that carries `seriesId: null` says
   the task stands alone, and it takes the task out of its series. Only the
   *absence* of the key is an absence of a claim. The server already applies
   `unset` as a null value rather than as a missing key, so a device that
   takes a task out of its series still says so in a way the receiver can
   read.

3. **The parts that live outside the row are not the row's to claim.** The
   tags and the blocking edges are not columns of the row, and their claims
   are their own business. The merge adds no local values for them: a
   document that carries no `tags` key is still a document that carries no
   `tags` key afterwards, and the rule for those parts can hold beside this
   one.

   What happens to those parts is the sibling change's subject
   (`the-document-says-only-what-it-carries`): a part the document does not
   carry is not written at all. The merge only makes sure it is not made
   worse — the local row's reading of the tags, a task read through `get()`
   holds none at all, is not written as a claim.

4. **The series of the row is the series the row already had, and the rule
   of the series does not change.** The repair of the series writes the shared
   fields of the work to the other members of the series, and it runs on the
   series the row belongs to — the one the merge kept. A document that never
   spoke about the series still changes a task of the series, and the series
   still says one thing about the work: the rule does not depend on whether
   the sending device knew about the series.

5. **The wire names of the fields live with the model.** `TAGS_WIRE_NAME` and
   `BLOCKED_BY_WIRE_NAME` are the names the `@SerialName` annotations write,
   so a rename of a wire field cannot leave the merge behind, looking for the
   old name.

6. **The merge is on the path, not in the row writer.** The path that applies
   a document is where the document and the row meet: the pull handler and the
   revert of a lost race both go through it. The repository writes the row it
   is given, whole, because a row that is written whole is a row that says
   what the document said.

## Rationale

**Why the document is read against the row, rather than the row written field
by field.** Both shapes give the same result, and the merge is the one that
does not need a second write path: the fields the document does not carry are
the fields the row already holds, so the row that is written is the row the
document describes plus the fields it is silent about. Writing the row field
by field would mean a statement of the document's shape in the write layer —
which fields it spoke about — and that statement is exactly what the wire
document already is.

**Why the repair of the series is not conditional on what the document said
about it.** The series is one piece of work and its members say one thing
about it. A document that changes a task of the series changes the work, and
the other members follow — whether or not the sending device knew that the
task belonged to a series. Making the repair conditional on the document
carrying a `seriesId` key would leave the series saying two things about the
work whenever the change came from a device that predates the series: the
changed task would carry the new title and its members the old one. The
members of a series do not diverge by hand either — a local edit of one of
them repairs the series too — so there is no state in which a member holds a
newer title that the repair would destroy.

**Why a claim of null still wins.** The absence of a key says the sender does
not know about the field. A null says the sender knows the field and says it
is empty. Reading both as "empty" would make it impossible to take a task
out of its series at all, and the device that did it would see the task back
in the series on the next pull.

**Why the parts stay out of the merge.** The tags and the blocking edges live
in cross-reference tables, and the row does not hold them. A merge that took
the row's reading of them would speak about a part from a row that never
carried it — the local row of a task read through `get()` holds no tags at
all, and that emptiness is a reading artefact, not a claim.

## Consequences

- The path that applies a task document reads the local row before it writes.
  That is one extra read per applied document, and it is the price of knowing
  what the device already said.
- Nothing changes for a device that sends the whole document: every key is
  there, and the merge returns the document. The behaviour changes only where
  the document is silent — which is where the deletion was.
- The revert of a lost race goes through the same merge. A shadow recorded by
  a build that did not know the field carries no key for it, so it does not
  restore that field, and the local edit that lost the race stays locally
  instead of being dropped: the same rule, one level down. A shadow recorded
  by the current build carries every key of the model — a field that has a
  default is written out, null and all — so for that shadow the merge
  returns the shadow unchanged.
- The merge reads the local row before the repository's write transaction
  opens, so a local edit that lands between the read and the write is
  overwritten for the fields the document does not carry. The exposure is the
  same class as before — the document won the whole row anyway — and the
  window is the price of knowing what the device already said.
- The row of a task of another profile is written as the document says. This
  device does not own that row, so it does not read it and does not speak
  about the fields the document is silent about.
- The repository keeps its shape: `upsert` takes the document — the row and the
  claims about the parts — and the merge happens where the document and the
  row meet: in the sync layer, before the document reaches the repository.
- A document that omits a field the model cannot do without is read against
  the row that is already here, and it is applied as far as it goes: the
  missing field takes the row's own value, and the rest of the document is
  applied. Before this change such a document failed to decode, and the pull
  dropped it and left the row alone. Now a truncated document is a partial
  write, and the pull reports success.
- The rule covers every key of the row, the bookkeeping included. `hlc` and
  `serverVersion` are keys of the row like any other: a document that says
  nothing about them leaves the row's own, and a document that says
  something about them wins. What a given device sends is not the rule's
  business — the rule is about the absence of a claim, not about the field.
- The repair of the series works on the series the row kept. A document that
  came from a device that predates the series still brings the series to one
  thing about the work.
- The sibling change that owns the claims of the parts that live outside the
  row (`the-document-says-only-what-it-carries`) reads the absence of a key
  from the wire document before the decode — as this change does for the
  fields of the row. The two changes touch the same place in the code and the
  same question, and they can be read together: a part the document did not
  carry is not written, and a field of the row the document did not carry is
  not reset.

## Links

- Issue [#1026](https://github.com/gazon1/sing/issues/1026) — the row's own
  absences, and the limitation this decision closes
- ADR `2026-10-23-a-document-says-only-what-it-carries` — the parts that live
  outside the row, and the limitation this decision closes for the row
- ADR `2026-10-23-a-task-document-is-written-whole` — why the row is written
  whole
- ADR `2026-10-06-a-transient-failure-is-not-a-permanent-one` — why the pull
  path has no `Conflict` arm: nothing on that path is compared version by
  version, and this change does not make it so
- ADR `2026-10-04-sync-event-carries-post-merge-document.md` — the document a
  pull carries
- `shared/src/commonMain/kotlin/com/singularity/todo/core/sync/TaskDocumentMerge.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/core/sync/SyncDocumentWriter.kt`
  — `taskDocument`, where the document meets the row
- `shared/src/jvmTest/kotlin/com/singularity/todo/core/sync/TaskDocumentMergeTest.kt`
- `shared/src/jvmTest/kotlin/com/singularity/todo/core/sync/TaskDocumentApplyTest.kt`
- `shared/src/jvmTest/kotlin/com/singularity/todo/feature/tasks/TaskSeriesWritePathTest.kt`
  — the repair of the series, and the document that does not trigger it
