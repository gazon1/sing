---
title: "A logbook note is created once, already attached; every later save is an update"
status: accepted
date: 2026-10-25
tags: [notes, tasks, write-pipeline, room, bugfix]
---

# Context

A note written from a task's logbook lost its attachment on the first save and
fell out of the logbook. The user opened a task, tapped "+", typed a title,
saved — and the note was gone from the logbook, while the note itself survived
as an ordinary note.

The substrate was in place: the 2026-10-01 ADR added a `notes.task_id` column
with a foreign key, and `NotesRepository.createForTask` wrote it. What the ADR
promised was **create on save**. What the implementation did was **create on
open** — the editor wrote the note when the screen appeared, so that by the time
the user typed anything, the row already existed.

That turned the editor's own autosave into the bug's mechanism. The autosave
wrote the note with `createWithContent`, which ends in
`noteDao.upsert(NoteEntity(...))`. Room's `@Upsert` is a **whole-row write**: it
replaces every column of the row it finds. The entity the editor built carried
no `task_id`, so the `task_id` the create had written was overwritten with
`NULL`. The attachment was gone — and because the FK is `ON DELETE SET NULL`,
the loss was silent and permanent.

So the editor applied the *create* primitive to a row it had itself created.
The house model already says that is a mistake: `create` is a whole row and
`edit` is a read-modify-write (`2026-09-27-write-layer-soundness.md`). The
editor violated it, and the violation was invisible because the second write
looked like a first write.

Three decisions had to be made, and each has a plausible alternative:

1. **Where the attachment is guaranteed.** The row could carry a fixed list of
   structural columns, or the editor could create the note exactly once and
   write it attached from the start.

2. **What an untouched draft means.** The editor could create the note when the
   screen opens, or when the user first saves.

3. **Whether a note that merely *links* to a task belongs in the logbook.**

## Idea

The invariant is one sentence: **the editor creates the note exactly once,
already attached; every later save is a targeted UPDATE that touches the
content columns only.**

Everything else follows from it. The create happens once, so the create carries
the attachment. Every later save is an update, and an update touches only the
content columns, so it cannot touch the attachment. The two properties that
were broken separately — "the note is attached" and "the note stays attached" —
are the same property stated once.

The untouched draft is the second half of the story. If the note is created on
the first save, then a user who opens the editor and leaves has written nothing
— and nothing is left behind. That is not a special case bolted on; it is what
"create on save" means. The editor already knew this: `Editing.isNew` and the
`createNote marks isNew and first save calls createWithContent` test said so,
while the implementation did the opposite.

## Decision

1. **`createForTask` is the only way a logbook note comes into being, and the
   editor calls it once.** The editor generates the note's id before the note
   exists and hands it to `createForTask`, so the draft, the navigation and the
   written row all speak about the same note. The attachment is written by that
   call and by no other.

2. **The editor's writes go through one writer.** `AutosaveContext.writeNote`
   serves both the autosave and the explicit save. It picks the primitive from
   the draft: `isNew` → create (attached), otherwise → update. There is no
   second path that could pick differently.

3. **An untouched draft is not written.** The autosave returns early while the
   draft is new and not dirty. The consequence is deliberate and reaches beyond
   the logbook: a user who opens the note editor and leaves no longer leaves an
   empty note behind, for ordinary notes too.

4. **The logbook holds structurally attached notes only.** A note whose body
   links to a task (`task://<id>` in `outgoing_links`) is *about* the task and
   stays in the task's "Linked" card. The logbook is the notes that belong to
   the task, and it says so by showing exactly those.

## Rationale

**Why not merge on write?** The alternative to "create once" is a write that
preserves the existing structural columns: read the row, merge the content
fields, write the whole row back. It is the standard read-modify-write, and it
is what the DAO-level `ON CONFLICT DO UPDATE` would have given. It was
rejected for two reasons. First, it makes every content save a read *plus* a
write, and it makes the write's correctness depend on a list of columns someone
remembered to exclude — a list that a future column would silently join.
Second, it hides the actual mistake: the editor was writing a row that already
existed with a primitive meant for rows that do not. Fixing the primitive
choice removes the class of bug; merging papers over one instance of it.

**Why not keep the create-on-open behaviour and fix the second write?** That is
the minimal patch — make the autosave an update instead of a create. It leaves
the abandoned-note problem in place: opening the editor and leaving still
leaves an empty row in the logbook, which is the other half of the reported
symptom ("the logbook does not work"). The user's decision was explicit:
a note is created when it is saved, not when it is opened.

**Why is the logbook not a union of attached and linked notes?** Because the two
are different relationships and the logbook is a place, not a search. A note
that mentions a task in its body is not *of* that task, and a logbook that
mixed the two would be confidently wrong — worse than empty. The structural
column makes the distinction cheap to answer, and `notes.task_id` is indexed.

**Why did the ADR of 2026-10-01 not prevent this?** It promised create-on-save
and the implementation created on open. The ADR was not wrong about the
substrate; it was not specific enough about *when* the create happens, and
nothing in the code made the promise checkable. The invariant is now stated as
a single sentence, it is pinned by a test at the repository level
(`updateContent` does not drop the attachment) and at the editor level (the
editor creates once, then updates), and the editor's KDoc says what it does
rather than what it used to do.

## Consequences

- The editor creates the note on the first save, attached to the task. The
  attachment survives every later edit, because no later edit writes the
  attachment column.
- An abandoned note editor leaves nothing behind — including for ordinary
  notes, which is a behaviour change beyond the logbook.
- The editor's autosave now reports its failure to the user instead of logging
  it, and the explicit save waits for the write instead of reporting success
  before it happened.
- `createWithContent` stays a whole-row write. That is deliberate: it is the
  primitive for a row that does not exist yet, and the editor uses it once.
- A note that lost its attachment in an earlier build is an ordinary note from
  now on. Nothing recognises it as a former logbook note, so old attachments
  are not restored.
- The 2026-10-01 ADR's mention of `task://` links in `outgoing_links` is not
  what shipped: the attachment is the structural `task_id` column alone, and a
  logbook note carries no outgoing link to its task.

## Links

- `specs/015-fix-task-logbook/spec.md` — the specification this implements.
- `docs/decisions/2026-09-27-write-layer-soundness.md` — `create` is a whole
  row, `edit` is a read-modify-write.
- `docs/decisions/2026-10-01-notes-task-logbook-substrate.md` — the substrate
  this fixes.
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/notes/presentation/viewmodel/NoteEditor.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/notes/data/NotesRepositoryImpl.kt`
