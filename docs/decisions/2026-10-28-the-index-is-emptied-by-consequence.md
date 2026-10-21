---
title: "The index is emptied by consequence, and the trigger is the proof"
date: 2026-10-28
status: accepted
tags: [database, testing, architecture]
---

# The index is emptied by consequence, and the trigger is the proof

## Context

The full-text search added four tables to the schema: `tasks_fts`, `notes_fts`,
`projects_fts` and `tags_fts`. Each is an FTS4 *external content* table — a
virtual table whose content lives in the ordinary table it mirrors
(`content=tasks`), holding only the token postings, never a second copy of the
text.

The profile reset reaches twenty-one tables and leaves nine alone. Those
numbers were written before the search existed, and the gate that holds the
document to the schema immediately noticed: four tables had no verdict, and a
table nobody decided about is exactly what that gate exists to catch.

The obvious answer — add the four index tables to the list of wiped tables —
is wrong twice over.

- Nothing writes to an index table. Room keeps it in step by installing
  triggers on the *content* table: `room_fts_content_sync_tasks_fts_BEFORE_DELETE
  BEFORE DELETE ON tasks BEGIN DELETE FROM tasks_fts WHERE docid=OLD.rowid; END`.
  A `DELETE FROM tasks_fts` aimed at the index would be a delete of a table
  Room maintains by itself.
- The index rows are not a separate copy of the profile's words. They *are*
  the profile's words, in the only form the search can find them in — so
  calling them "not profile content" and leaving them to survive would leave
  a deleted task findable by its title.

## Idea

An index is a **read model over the table it indexes**. Every row it returns is
a row of `tasks`, `notes`, `projects` or `tags`. So the reset does not need to
reach the index at all: reaching the content table reaches the index, because
the delete fires the trigger.

That is a fourth kind of table entry, and it needs a fourth kind of
evidence — a claim about what a delete does to a table the code never names.

## Decision

**The index tables are emptied by consequence.** The inventory gives them the
verdict "emptied by consequence" and names the table whose delete empties
them; `ProfileWipePlan.EMPTIED_BY_CONSEQUENCE` says the same thing in code.

**The link is read from the schema, not believed.** The gate derives it from
the Room annotations — `@Fts4(contentEntity = TaskEntity::class)` and the
`@Entity(tableName = …)` of that class — and fails when the table named in the
list is not the one the schema says the index mirrors, when the mirrored table
is not one the reset deletes, and when an index in the schema is in neither
list. A hand-written mapping of four rows would rot exactly like the list it
was supposed to replace.

**The behaviour is measured, not read.** The gate reads code; it cannot know
that the trigger fires. `ProfileResetReachesTheWholeProfileTest` counts the
rows of every index table against real SQLite — once before the reset (the
index must hold a row, or the count after it proves nothing) and once after
(it must hold none). The other profile's index rows must survive, because the
index is global and the reset is scoped to one profile.

**The reset reports the index tables among the tables it reached.** The debug
link says what the reset emptied, and the index is emptied.

## Rationale

The alternative — deleting from the index tables directly — is not merely
redundant, it is wrong: the index has no rows of its own to delete, and a
delete aimed at it would be a delete Room's triggers then contradict.

The other alternative — calling the indexes "not profile content" and leaving
them — is the one that hides the defect. The search would find a task the
reset deleted, and the finding would be a mystery rather than a gap in a
list.

Deriving the link from the annotations costs a parser and buys the property
that matters: the list cannot claim a mirror the schema does not have. The
four rows are in the list because the code says they are there, and the code
is read from the annotation that Room itself reads.

And the measurement is what makes the whole thing more than a belief. The
trigger is Room's, not ours; the code that relies on it is ours. A test that
counts the index rows after the reset is the only thing that says the two
agree.

## Consequences

- The inventory has a third verdict, and the gate has rules for it: an index
  nobody decided about, an index that mirrors another table, an index whose
  content survives the reset, and an index the list deletes directly are all
  findings.
- `ContentResetResult.wipedTables` now names twenty-five tables rather than
  twenty-one. The name still fits — the index tables are emptied — but a
  reader who wants the deletes alone should read `ProfileWipePlan.WIPED`.
- The gate reads two Kotlin maps now instead of one. The scoping matters: a
  regex over the whole plan file would read the indexes as tables the reset
  deletes, and the gate would then demand a `DELETE FROM` that must not exist.
- A future index — a new searchable kind — arrives with the same question, and
  the gate asks it: is this table emptied by consequence, and does the table
  it mirrors get deleted?

## Links

- `shared/src/commonMain/kotlin/com/singularity/todo/core/wipe/ProfileWipePlan.kt`
  — the two maps: the deletes and the consequences.
- `scripts/ci/check-profile-reset-inventory.py` — the gate that holds the
  document, the plan and the annotations to each other.
- `docs/architecture/wipe-inventory.md` — the verdicts and the reasons.
- `shared/src/jvmTest/kotlin/com/singularity/todo/core/wipe/ProfileResetReachesTheWholeProfileTest.kt`
  — the measurement.
- `docs/decisions/2026-10-28-reset-content-reaches-the-whole-profile.md` —
  the reset this extends.
