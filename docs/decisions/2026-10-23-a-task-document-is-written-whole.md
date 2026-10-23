---
title: "A task document is written whole, by one writer"
date: 2026-10-23
status: accepted
tags: [tasks, sync, database, mapping]
---

## Context

A task is not one row. It is three places and one derived column:

- the row in `tasks` — the title, the dates, the state, and the `outgoing_links`
  column, which is *derived* from the description rather than carried by the
  domain model;
- the tag cross-refs in `task_tags`;
- the blocking edges in `task_dependencies`.

`@Upsert` replaces the whole row. So a writer that applies only the row does
not leave the rest of the document alone — it resets the parts it does not
write. The pull path was exactly such a writer: it wrote the row and nothing
else. The row it wrote carried no `outgoing_links`, so every pull wiped the
links of that task; the tags and the dependencies were never applied at all.

The same shape appeared twice more in the same branch. `updateAll` wrote the
rows and never the tag cross-refs, so merging two series left the joined rows
with the old tags in the database. And the tags of an ordinary edit were taken
from the task the edit was given — a task read through `get()`, which does not
load the tags, because the tags live in the cross-ref table. Every other member
of the series then looked divergent on the tags, was rewritten, and had its tag
rows deleted.

The read-side completeness gate is green through all of this.
`EntityMapperCompletenessTest` checks that *someone* touches every column of
`TaskEntity` — and the reader touches all of them. The writer is a private
mapper inside a repository, and the gate does not reach it.

## Idea

One document, one writer. The path that applies a document applies all of it,
in one unit of work. The parts that apply *part* of a document are a different
thing, and they say which part they are.

## Decision

1. **The path that applies a document applies all of it.** The pull path writes
   the row that arrived and then the parts of the document that are not the row
   — the tags, the links the description implies, the blocking dependencies —
   in one unit of work, so a document lands whole or does not land at all.

2. **The pull path calls it, and nothing else.** A row of another profile is
   stored as it is, keeping the links it carried: this device does not rewrite
   the rows of a profile it is not on, and an `@Upsert` would reset the links of
   such a row to nothing.

3. **The links are derived from the description.** A document that carries a
   description carries the links that description implies. The row's mapper
   cannot derive them — the domain task has no field for them — so the writer
   that has the description writes them, and the mapper takes them as a
   parameter rather than inventing an empty list. The exception is a row of
   another profile: its links are kept, not derived, because deriving them
   would be a write to another account's row.

4. **The row keeps the timestamp of the document.** A pull that stamped the row
   with the local clock would say "this work was changed now" for a document
   that says otherwise, and the next push would carry that time to the other
   devices. The links of the row are written with the document's own stamp.

5. **Only the `BLOCKS` edges come from the document.** They are the ones the
   wire carries (`dependsOn`). The other verbs — a task that follows up
   another, one that duplicates another — are not in the document at all, and
   replacing them with the document's edges would delete what the document
   never spoke about.

6. **An edge that would close a cycle is refused on its own**, and the rest of
   the document still applies. The local graph stays acyclic — the code that
   walks it assumes that — while a document that says something the local graph
   cannot hold does not stop the row from landing.

7. **The paths that apply part of a document say so.** `update()` does not
   write the tags: the task it is given was read through `get()`, which does
   not load them, and the caller that sets the tags is `setTags`. Writing them
   in `update()` would take the emptiness of the read for the truth about the
   work.

## Rationale

**Why one path applies the whole document.** A document that arrives
half-applied is worse than one that did not arrive: the row says the work has
no links and no dependencies while the rest of the database still says it has
them, and nothing in the system knows which of the two is true. One path makes
"the document was applied" a single fact rather than four.

**Why the links are derived rather than carried.** The links are a function of
the description: they are the wikilinks in it. Carrying them in the domain
model would give one fact two homes, and the two would drift — which is the
same class of defect as the tags, one level up.

**Why the cycle is refused edge by edge.** A document is a statement about the
work, and refusing the whole statement because one edge cannot be taken would
lose the other edges with it. Refusing the one edge keeps the local invariant
(true: the graph has no cycles) and lands the rest of the document (true: the
row says what the remote device said it says).

**Why `update()` is not a document writer.** An edit is a statement about one
field of one row, and a statement about one field must not be read as a
statement about the others. The empty tag list of a task read through `get()`
is not a claim that the task has no tags; it is the absence of a claim.

## Consequences

- The pull path now writes the dependencies, and a dependency that the remote
  document dropped is dropped locally too. The local graph is the remote graph,
  which is what the sync model says it should be.
- The four `sync` columns of the task row (`sync_status`, `sync_error`,
  `last_synced_at`, `device_id`) are vestigial: nothing writes them, and the
  sync state of a task lives in the shadow table. They are declared as such in
  `TaskRowWriterTest`, which checks that every column of the row has a writer
  and that a column without one is said out loud.
- The links of a task of another profile are kept as they were until that
  profile's own device rewrites them. The alternative — writing them here —
  would be a write to another account's rows. A row that is new on this device
  has no links to keep, and this device does not derive them either.

## Links

- ADR `2026-09-27-write-layer-soundness.md` — the write layer and its soundness
- ADR `2026-10-22-the-series-says-one-thing-about-the-work.md` — the repair that
  runs on the pull path
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/tasks/data/TaskRepositoryImpl.kt`
  — `upsert` and `writeTaskParts`
- `shared/src/jvmTest/kotlin/com/singularity/todo/feature/tasks/TaskDocumentWriteTest.kt`
  — the behaviour
- `shared/src/jvmTest/kotlin/com/singularity/todo/arch/TaskRowWriterTest.kt` — the
  columns and their writers
