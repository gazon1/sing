---
title: "A document says what it carries, and what it does not say it does not take"
date: 2026-10-23
status: accepted
tags: [tasks, sync, database, mapping]
---

## Context

The change that made the pull path apply the whole document also made it
apply parts that were never in the document.

A task document is decoded as a `Task`
(`core/sync/SyncDocumentWriter.kt`), and the fields the model cannot do
without have defaults: `tags` defaults to an empty list, `blockedBy` to an
empty set. A document that carries no `tags` key therefore decodes into a task
that says "no tags" — and the pull applies what the document says, so the
tags of the task are taken off.

That was harmless before the change: the pull wrote the row and nothing else,
so the tags of a task were never touched by a pull. The change made the pull
apply the tags, and with it the reading of "no key" as "no tags" became a
deletion. The tags live in a cross-reference table, so an empty list is not a
statement about them — it is a removal.

The wire is the reason the reading matters. A document carries the fields the
sending device knew about: a device that predates the tags sends no `tags`
key, and a device that predates the dependencies sends no `dependsOn` key.
Nothing in the contract says a device sends the whole document; the allow-list
says what a device sends, and it grew.

Two smaller things came with the change and were left unsaid:

- A pair of tasks may carry several relations. The cross-reference key is
  `(task_id, depends_on_task_id, verb)`, the pull adds the blocking edge
  beside the relation the pair already had, and only the spec said that this
  is allowed. The model says nothing about it.
- A refused edge — one that would close a cycle — was refused silently. The
  device that sent it keeps asserting a graph this device cannot hold, and
  every later pull refuses the same edge again. Nothing said so anywhere.

## Idea

A document says what it carries. What it does not carry, it does not say, and
what it does not say is not applied.

## Decision

1. **A document carries claims, and a claim can be absent.** The parts of a
   document that live outside the row — the tags, the blocking dependencies —
   are nullable in the document: `null` means the document says nothing about
   that part, and an empty collection means the document says there are none.
   The two are different claims, and only the second one is a deletion.

2. **The pull applies what the document says and leaves the rest alone.** The
   row is written whole — that is what a document is — and the parts the
   document speaks about are written with it, in one unit of work. A part the
   document does not speak about is not written at all.

3. **The absence is read before the decode.** The wire key is visible only in
   the raw document; after the decode the two cases are the same task. The key
   names live in the model, next to the annotations that write them
   (`TAGS_WIRE_NAME`, `BLOCKED_BY_WIRE_NAME`), so a rename of a wire field
   cannot leave the check behind.

4. **A pair may carry several relations.** The verbs are different claims
   about the same pair — a task can wait for another one and follow it up —
   and the document speaks about the blocking one only. The other relation
   stays where it is.

5. **A refused edge is said out loud.** The refusal is logged: a device that
   refuses an edge silently disagrees with the device that sent it, and the
   disagreement is invisible until somebody looks for it.

6. **A batch write repairs the series, like an ordinary write.** The single
   write repairs the series around it; the batch write did not. Both callers
   of the batch write the whole series, so the asymmetry was invisible — and
   a partial batch would have left the series saying two things about the work.

## Rationale

**Why the absence is a claim of its own.** The document is the sender's
statement about the work. A statement that omits a part is not a statement
that the part is empty — it is a statement about a sender that does not know
about the part. Applying it as an emptiness would let an old device delete
the work of a new one, and the deletion would be invisible: the row is
written, the pull reports success, and the tags are simply gone.

**Why the row is still written whole.** The row is the document. A document
that carries a title carries a title; a document that carries no tags says
nothing about them. This change is about the parts that live *outside* the
row — the tags and the dependencies — because those are the parts a device can
add to the model later without changing the row.

The row's own absences are still absences: a document that carries no series id
decodes into a task with no series, and the row is written with it. The row is
written whole, and a field the model cannot do without has a default that
reads as an emptiness. That is a known limitation, stated here so that nobody
reads this decision as a promise about the row: the series id and the
recurrence rule are in the same position as the tags were (#1026).

**Why the refusal is logged.** The refusal is a disagreement between two
devices about the shape of the work, and it repeats on every pull. A log
line is the cheapest thing that makes it visible. The repository had no
logger of its own; it has one now, injected like the ones the other features
use.

**Why the batch write repairs.** A series is one piece of work, and the rows
of a series are copies of it. A write that changes one of the copies changes
the work, and the other copies follow. The batch write is a write that
changes several of them at once; it is the same write.

## Consequences

- The pull path takes a [TaskDocument] instead of a [Task]. A caller that
  holds a whole task says everything it carries (`TaskDocument.of(task)`); a
  caller that holds a wire document says only what the wire carried. Today the
  production caller is the pull handler, and `TaskDocument.of` is the entry
  point for the writers that hold a whole task — the fakes and the tests.
- The tags and the dependencies of a task read through `get()` are still
  empty — `get()` does not load them — and a caller that writes such a task
  as a document has to say what it means. `TaskDocument.of(task)` on a task
  that was read without its parts claims "no tags" and "no dependencies", and
  the KDoc says so.
- A device that sends no `tags` key no longer takes the tags off the tasks it
  pulls. A device that sends `tags: []` still does — that is a claim, and it
  is what a device that means "this work has no tags" says.
- The repair of the series follows the same rule: a document with no claim
  about the tags does not take the tags off the other members of the series
  either. The series may keep a disagreement about the tags that it had
  before the document arrived — the document said nothing, and inventing a
  claim would be worse than the disagreement.
- The row's own absences are not covered: a document that carries no series id
  or no recurrence rule still writes the row without them (#1026). This
  decision is about the parts that live outside the row.
- The revert of a lost race writes the shadow's confirmed document. If that
  shadow was recorded before the device knew a part, the revert no longer
  restores that part — so the local edit that lost the race stays locally
  instead of being dropped. Narrow, and it is the same rule: the part that was
  never claimed is not written.
- The refusal of a cycle-closing edge leaves a line in the log. The edge is
  still refused, and the rest of the document still lands.
- The batch write repairs the series. Today every caller writes the whole
  series, so the repair finds nothing to do; a partial batch is the case the
  repair exists for, and a partial batch is now repaired instead of leaving
  the series saying two things about the work.
- The batch write writes the tags it was given. A batch built from a task read
  through `get()` carries no tags at all, and writing that emptiness takes the
  tags off the rows it writes — the hazard the single-row write refuses. The
  callers that write a series read it through `getSeries`, which loads them.

## Links

- ADR `2026-10-23-a-task-document-is-written-whole.md` — the change that made
  the pull apply the whole document, and the reading this one corrects
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/tasks/domain/model/TaskDocument.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/core/sync/SyncDocumentWriter.kt`
  — `taskDocument`, where the absence is read
- `shared/src/jvmTest/kotlin/com/singularity/todo/core/sync/TaskDocumentPartsTest.kt`
  — the wire level
- `shared/src/jvmTest/kotlin/com/singularity/todo/feature/tasks/TaskDocumentWriteTest.kt`
  — the behaviour
