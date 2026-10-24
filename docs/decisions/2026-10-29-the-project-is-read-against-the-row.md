---
title: "The project is read against the row, and its inheritance is a claim"
date: 2026-10-29
status: accepted
tags: [sync, database, mapping]
---

## Context

The row keeps what the document does not say. The decision that generalised that
rule to every synchronised type left one type out: the project.

The reason was the inheritance. `ProjectEntity` has no column for
`inheritedTagGroupIds` — the inherited tag groups live in a cross-reference table
— and the project is written through `replaceInheritedTagGroups`, a write path
of its own. A merge that spoke about the inheritance would have had to change
that write, so the rule was not applied to the project, and the decision said so
outright.

The project therefore kept decoding the document on its own and writing it whole,
and it had both defects the other types had:

- a document that carried no `description` wrote the model's default over the
  row;
- a document that carried no `name` did not decode at all, and the pull dropped
  it and left the row alone;

and one more, which is the reason the type was left out:

- the write always replaced the table of inherited groups with what the document
  carried. A document that carried no key carried an empty set — the field has a
  default — so the project's inherited tag groups went away on this device. The
  row was written and the pull reported success.

That last one is not a defect of the merge. It is a defect of a write that cannot
tell "the project inherits nothing" from "the document says nothing about the
inheritance", and the merge cannot fix it: the merge reads a document and a row,
and the inheritance is in neither.

## Idea

The project is a type like the others, and the inheritance is a claim like the
others. The document says which groups the project inherits, or it says nothing
about them — and a write that knows the difference can leave the groups alone.

## Decision

1. **The project is read against the row that is already here.** The writer has
   one path for every type that is synced, and the path is per type only in what
   it reads and in how it decodes. A project document that carries no `name` is
   a partial write, not a dropped event.

2. **The inheritance is a claim, and the claim travels with the write.** The
   write takes a document: the project, and the claim the document makes about
   the inherited groups. The claim is `null` when the document says nothing
   about them, and a set when it claims them. The port says this in its own
   name — `ProjectDocument` — because a document is what arrives, and a project
   with a set of groups inside it cannot say whether the set is a claim or a
   default.

3. **The table is replaced only when there is a claim.** A document that claims
   an empty set takes the groups away, and a document that claims a set replaces
   it. A document that says nothing leaves the groups the project has.

4. **The row makes no claim about the inheritance.** The key is filtered out of
   the row that is read, exactly as the tags and the blocking edges are filtered
   out of the task's row. A project read through `get()` cannot see the table —
   `toProject()` returns an empty set for a project that inherits six groups —
   and that emptiness is an artefact of the reading, not a statement about the
   project. The merge must not turn the artefact into a claim.

5. **The profile boundary is the boundary of every other type.** A document of
   another profile is written as it says, and this profile's row does not fill
   it.

## Rationale

**Why the claim travels with the write.** The writer knows the document and the
repository knows the table, and neither of them knows the other. The claim is
the one thing that has to cross from the first to the second, so it crosses
explicitly: as a value that is either there or not there. Passing the project
alone cannot carry it, because a project with an empty set of inherited groups
is the same object whether the document claimed the emptiness or the field
simply had its default.

**Why there is no convenience constructor.** `TaskDocument.of(task)` exists, and
it carries a warning: the task must be one that was read whole. The project has
no such constructor, because the trap is worse. A project read through `get()`
does not see its inheritance, so a constructor that took the groups out of the
project would claim "the project inherits nothing" for every project that was
read that way — a deletion, in the shape of a convenience. The claim is stated
by the caller that read the document.

**Why the returned project carries the truth.** The write reports what it did,
and a project that reports no inherited groups while the table holds six would
be a lie the caller cannot see. The repository reads the table back when there
was no claim, so the project it returns is the project that is there.

**Why the row is filtered and not merged.** The rule of the merge is "the
document wins where it speaks, and the row speaks where the document does not".
The row cannot speak about the inheritance — it cannot see the table — so
letting it speak would give the empty set of a reading the voice of a claim.
The same reasoning filtered the tags out of the task's row.

## Consequences

- The path that applies a document reads the local project row before it writes
  it, and reads the table of inherited groups back when the document makes no
  claim about them. That is one read per applied project document that says
  nothing about its inheritance, and it is the price of knowing what the device
  already had.
- `ProjectsRepository.upsert` takes a document instead of a project. The port
  grows a type, and the two implementations of the port grow the reading of it.
- The project is no longer the type the rule does not cover. Every synchronised
  type is read against the row that is already here, and the parts that live
  outside a row are their own claims.
- Nothing changes for a device that sends the whole document: every key is
  there, the merge returns the document, and the claim is the set the document
  carries.

## Links

- ADR `2026-10-29-the-row-keeps-what-the-document-does-not-say-for-every-type` —
  the rule, and the type it left out
- ADR `2026-10-23-the-row-keeps-what-the-document-does-not-say` — the rule, and
  the task it was written for
- ADR `2026-10-23-a-document-says-only-what-it-carries` — the parts that live
  outside the row
- Issue [#1026](https://github.com/gazon1/sing/issues/1026)
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/projects/domain/model/ProjectDocument.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/core/sync/SyncDocumentWriter.kt`
  — `projectDocument`, where the project meets the row
- `shared/src/jvmTest/kotlin/com/singularity/todo/core/sync/ProjectDocumentWriteTest.kt`
