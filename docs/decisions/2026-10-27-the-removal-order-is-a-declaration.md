---
title: "The removal order is a declaration"
date: 2026-10-27
status: accepted
tags: [backup, architecture]
---

# The removal order is a declaration

## Context

ADR `2026-10-27-a-delta-carries-the-rows-it-removed.md` decided that a payload
may carry the rows that were removed, and that the restore takes them out. It
also fixed the order they are taken out in: "the removals go after its rows, and
the children go before their parents".

That order was written down in the importer, as the order of the lines of
`applyRemovals` and the comments beside them. `applyRemovals` knew three things
at once: which rows exist, in which order they may be deleted, and which DAO
deletes them. Only the first is a fact about the domain. The second is a fact
about the tables — a task tag needs its task alive, a checklist item needs its
checklist alive, and nothing cascades, because the tables have no foreign key to
cascade — and it was expressed as a property of a method body.

Reading it took a decision. A reader who wanted to know what was removed before
what had to count the lines, and a reader who added a kind had to know that the
order of the lines was the order, because there was nothing else that said so.

## Idea

1. **The order stays in the method.** The lines are the order, as they are now.
2. **The order becomes a phase per kind.** Every kind declares the phase it is
   removed in, and the importer reads the phases.
3. **The order becomes a list of kinds per phase.** The phase holds the kinds it
   removes, and the importer walks the list.

## Decision

**A kind declares the phase it is removed in, next to the kind itself.**

- **`BackupEntityKind` carries a `removalPhase`.** The phase is a constructor
  argument, not a default: a kind added to the enum without a phase does not
  compile. That is the whole point — the failure moves from "the restore ran
  and the row was never deleted" to "the build did not finish".
- **`BackupRemovalPhase` is the order.** Three phases, in the order they are
  applied: `CHILDREN_FIRST`, `OWN_ROWS`, `PARENTS_LAST`. The children are the
  rows that need their parent alive — the link tables, and the rows that hang
  off one. The middle is the rows that hold no link of their own. The last is
  the rows the children hang off.
- **The kinds are declared in the order they are removed in.** The order within
  a phase is the order the kinds are written in, so the whole order of the
  removals is one list, in one place. The two orders are not two facts: the
  phase says which group a kind is in, and the list says where in the group it
  goes.
- **The importer reads the declaration.** `applyRemovals` walks the phases and
  the kinds of each phase; the per-kind delete stays where it was, in one
  `when` over the kind, which is exhaustive — a kind this build does not know
  how to delete does not compile either.
- **The phases are a decision about the tables, so they are recorded here.**
  The order is not a preference and not an implementation detail: it is the
  consequence of the tables having no foreign key to cascade.

## Rationale

**The order was never the importer's to decide.** It is a fact about the
schema: which rows hold a reference to which, and which statement reaches its
row through its parent. The importer only knows it because the code that deletes
the rows had to be written in that order. A fact about the schema belongs next
to the enumeration of the kinds, and the enumeration is what the schema is
described by.

**A kind without a phase has to fail loudly.** The failure this record exists
to prevent is a row that is silently never deleted: the restore reports success,
and the account keeps a task the person deleted on the other device — on the
one device that needed it. A compile error is the cheapest version of that
failure, and it is the only one that cannot be forgotten.

**The order within a phase matters too, and it stays in the same place.** An
annotation has to go before the attachment it belongs to, because the
annotations are not cascaded either. So the phase alone would not have been
enough: the declaration carries both the grouping and the order inside it, and
both live in one list.

**The phase does not replace the compiler's exhaustiveness.** The `when` that
deletes a row is exhaustive, so a kind without a branch does not compile
either. The declaration and the `when` answer two different questions — *when*
is this kind deleted, and *how* — and the second one was already answered well.

## Consequences

- A new kind is added in one place: the enum, with the phase it belongs to. The
  compiler asks for the phase and for the delete.
- The order of the removals is read from the kinds, not from the method. The
  importer's `applyRemovals` no longer carries the order, and its comments no
  longer have to explain it.
- The phases are three names to learn, and they say what the order is for: the
  children, the rows, the parents.
- The architecture test that says every kind has a removal path reads the same
  declaration, so the rule and the declaration cannot drift apart.
- The order is now visible in one file, which is what the ADR is for: a reader
  who wants to know what is removed before what reads
  `BackupEntityKind.kt`, and this record.

## Links

- `docs/decisions/2026-10-27-a-delta-carries-the-rows-it-removed.md` — the
  record that decided the removals travel in the payload, and that fixed the
  order this record makes a declaration.
- `shared/src/commonMain/kotlin/com/singularity/todo/core/backup/BackupEntityKind.kt`
  — the kinds and their phases.
- `shared/src/commonMain/kotlin/com/singularity/todo/core/backup/BackupImporter.kt`
  — where the removals are applied.
- Issue: #1224
