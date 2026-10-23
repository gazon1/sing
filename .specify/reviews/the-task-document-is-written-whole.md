---
change: the-task-document-is-written-whole
verdict: fail
date: 2026-10-23
pr: 997
reviewed: 23a6b1070a60e834b10f4f9dd8c2c6a89bef4497
documents: sha256:2e6c98c58d3e843f9032a4fbc40f61758a7b73c833385d8ed886132a38fed7f1
---

## What was reviewed

The whole change: `openspec/changes/the-task-document-is-written-whole/` — the
proposal, the design, the tasks and the `tasks` spec — against the code it
describes and against
`docs/decisions/2026-10-23-a-task-document-is-written-whole.md`.

The two fields above were added on 2026-10-23, when the rule "a verdict names
what it reviewed" arrived. `reviewed:` is the head of the pull request as it
landed — the commit the reviews were made against. It is a pointer for a reader
who has the history: this pull request landed by merge, so the commit is in
the history of `main`. `documents:` is the digest of the contract as the
verdict speaks for it — the change's proposal and its spec delta. The contract
has not moved since the reviews, so the digest is the one the gate recomputes.

The reviewers were separate agents given the diff, the ADR, the OpenSpec change
and the two new test files, and nothing else: no history of writing the code,
no pressure to find nothing wrong. They were asked to judge the change against
the contracts it states.

Two reviews, split by what each could see well: one on the write path (the
pull path, the mapper, the dependency edges, the spec against the code), one on
the new gate and the new test (whether they can fail on the behaviour they
claim, and whether they read the sources correctly).

Both were told not to run Gradle: a build was running in the same worktree, and
a second one corrupts the shared build directory. Neither ran one.

## The reviews: FAIL

Four blockers, two of them in the behaviour and two in the checks.

1. **Blocker — the pull path stamped the row with the local clock.** The row
   was written with the timestamp of the document, and the write that put the
   links back into it stamped `updated_at` with `clock.now()`. So after a pull
   the row said *modified now* while the document said *modified at the
   remote's time*, and the next push of that task shipped the local clock to
   the other devices. The helper already took the stamp as a parameter; the
   pull path did not pass it.

2. **Blocker — the spec had no exception for a row of another profile.** The
   spec said the system *shall* write every part of a document, in one unit of
   work, with no carve-out; the code writes only the row for a row of another
   profile, and the ADR says so. The spec and the code said different things,
   and the project rule is that the difference is fixed in the same commit.

3. **Blocker — the tags of the pull path were asserted nowhere.** The file
   claimed the pull path applies the whole document — the row, the tags, the
   links, the dependencies — and the only tag test in it exercised `update()`.
   Deleting `writeTags(task, uid)` from the pull path left every test in the
   file green while the headline behaviour of the change stopped happening.

4. **Blocker — the test that claims to cover the foreign branch covers
   something else.** `another profile's row keeps the links it carried` read
   the links of *this* profile's row while the write under test was the other
   profile's row, and the other profile's row was created by that very write —
   so it never carried links to keep. Reverting the production code to
   `taskDao.upsert(task.toEntity())` — dropping the preservation of the
   previous links — failed no test in the file.

5. **Should-fix — the gate merged the embedded columns into the row's set.**
   The named arguments of the embedded `SyncColumns(...)` call were added to
   the row's written columns, so a column of the row that happened to share a
   name with a column of the embedded class would have counted as written.

6. **Should-fix — any `name =` inside the argument text counted as a writer.**
   A named argument of a nested call, or an assignment inside a lambda, would
   have produced a phantom writer for a column of that name.

7. **Should-fix — the gate's parser could break silently.** A default value
   with a comparison, or an annotation with a nested call, would unbalance the
   depth and shorten the column list — and a gate that reads half a list sees
   half the columns and passes.

8. **Should-fix — the declaration keys had two shapes**, a bare name for a
   column of the row and a qualified name for a column of the embedded class.

9. **Should-fix — a column declared as unwritten was taken on trust.** The four
   vestigial sync columns were declared as having no writer, and nothing
   checked that claim; the check that a named writer exists never ran, because
   every declaration said "nobody".

10. **Should-fix — the gate claimed more than it checked.** It reads one
    mapper, while the backup importer and `setTags` also write task rows.

11. **Should-fix — the mapper's default argument was the hazard the ADR
    exists to kill.** `toEntity(outgoingLinks = EMPTY_LINKS)` meant a call site
    that forgot the argument compiled and reset the links column.

12. **Should-fix — the spec named fields where it should have named paths**:
    `updateAll` does write the tags, and the spec did not say so.

13. **Nit — the links were asserted with a substring match** on the JSON
    column, where the exact list was available.

## What was done about it

The verdict stays **fail**, because the reviews found something: that is what
this file is for. The findings were fixed in the same branch:

- The pull path writes the links with the timestamp of the document, not with
  the local clock (`saveOutgoingLinks(task.id, task.description,
  task.updatedAt.toEpochMillis())`).
- The spec gained a requirement for a row of another profile — *stored as it
  is, keeping the links it carried, with no tags, links or dependencies
  written* — and two scenarios for it. The spec also says what happens to a
  pair that already has an edge of another kind, and it names the paths that
  write the tags (`update`, `updateAll`, `setTags`, the pull) instead of
  speaking about fields.
- `TaskDocumentWriteTest` gained the two tests the file was missing: the pull
  path writes the tags the document carries, and the pull path replaces the
  tags with the ones the document carries. The foreign-branch test now seeds
  the other profile's row *with* links, pulls it, and asserts that *that* row
  still carries them; the isolation check became a test of its own. The
  helpers take the owner, so a row of one profile cannot be read as a row of
  another by accident.
- The gate reads the named arguments at the top level of the constructor call
  only, and keeps the row's columns and the embedded class's columns apart.
- The gate fails loudly when the parameter list it reads does not balance,
  instead of reading half of it.
- The declarations use one shape for both kinds of column.
- The gate now checks the claim it used to take on trust: a column declared as
  having no writer is not written by any targeted `UPDATE tasks SET …` in the
  production sources. The claim is checked against the nine updates that
  exist, and the check would fail the day one of them touches those columns.
- The gate's claim is narrowed to the writer the repository uses for a task it
  is given, and the doc says so.
- The mapper derives the links from the description by default — the links of
  the task it is given — and the two writers that write the row of *another*
  task pass the links that belong to that row: the repair of a series and the
  row of a profile this device is not on.
- The links are asserted as the exact list, parsed out of the column.

Two findings were not fixed, and the reasons are in the open:

- **A refused cycle is refused silently** — no log, no marker. The repository
  has no logging facility of its own, and adding one is a change of its own.
- **The four vestigial sync columns stay unwritten.** Removing them is a
  schema migration and a separate decision; until then the gate says out loud
  that nobody writes them.

## Links

- ADR `docs/decisions/2026-10-23-a-task-document-is-written-whole.md`
- `shared/src/jvmTest/kotlin/com/singularity/todo/feature/tasks/TaskDocumentWriteTest.kt`
- `shared/src/jvmTest/kotlin/com/singularity/todo/arch/TaskRowWriterTest.kt`
