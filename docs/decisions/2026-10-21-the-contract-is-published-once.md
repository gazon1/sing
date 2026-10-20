---
title: "The contract is published once: the skill's tables are the registry"
date: 2026-10-21
status: accepted
tags: [architecture, text-fields, tests, docs]
---

# The contract is published once: the skill's tables are the registry

## Context

The text-field contract is published in the skill
`.agents/skills/singularity-todo-text-field-states/SKILL.md`, in two tables:

- the **inventory** — one row per file that owns a text field, with the shapes that
  file's fields have;
- the **rewrite table** — one row per bridged field site, with the upstream, the kind of
  source the site is handed, the counter and the file that advances it.

Both arch tests that hold the contract in place also carry their **own copy** of the
registry:

- `TextFieldShapeConventionTest` holds a 35-row `registry: Map<String, Classification>`,
  and separately parses the skill's inventory table, and then compares the two;
- `TextFieldRewriteContractTest` holds a 15-row `registry: List<Row>`, and separately
  parses the skill's rewrite table, and then compares the two.

So the contract is written **twice**: once where a developer reads it, and once inside
the test that checks it. The two copies are compared, which catches a divergence — but
only the copy in the test is *checked* against the code, and only the copy in the skill is
*read*. The comparison between them is a third thing to keep honest.

The duplication has a cost that the comparison does not pay for. The two copies are
written at different times, in different files, with different column sets: the test's
`Row` carries an `owner` — the file that builds the source — while the skill's rewrite
table has no such column, only the upstream described in prose. A reader of the skill
therefore cannot see *which file* builds the source, and a reader of the test cannot see
the prose that explains why the upstream may write the value at all. The owner's column
is checkable, and it is exactly the column the table is missing.

## Idea

Let the **published table be the registry**. The test reads the table it checks, and
holds no second copy of it.

That is only possible if the table carries everything the test needs. The gap is the
owner, so the owner becomes a column.

## Decision

**The skill's tables are the registry, and the tests read them.**

- **`TextFieldShapeConventionTest` holds no registry.** Its classification *is* the
  skill's inventory table, read by its header. The test checks the table against the code:
  a file that owns a field and is missing from the table fails, a row whose shape does
  not match the helper the file imports fails, and a row whose file no longer owns a
  field fails as a stale row.
- **`TextFieldRewriteContractTest` holds no registry either.** Its rows are the skill's
  rewrite table, read by its header. A new check comes with it: a row that names a file
  which no longer owns a bridged field fails as a stale row — the old code could only
  complain about a *missing* row, never about an extra one.
- **The rewrite table gains an `Owner` column** — the file that builds the source. It is
  the answer to "who may rewrite this field?" at the level of files, and the test has to
  check it. The one row whose owner is the field site itself is the GenUI renderer, whose
  owner is a server the renderer does not own: the row says so by naming the site as its
  own owner, and the test reads the exception off the row rather than out of a flag.
- **The tables are found by their header.** A Markdown reader that picks "the first table
  with a `.kt` in the first cell" reads rows of other tables; a reader that looks for
  `| File | Shape | Field |` reads the one table it means. A table whose header changed is
  a table the test no longer finds — and it says so, instead of quietly reading nothing.

## Rationale

- **A registry that is written twice is a registry that can disagree with itself.** The
  comparison between the copies is a check, but it is a check of *prose against prose*:
  the two copies can be consistent and both wrong about the code. Reading the table
  directly removes the layer where that can happen.
- **The copy nobody reads is the copy that rots.** The skill's table is what a developer
  reads before adding a field. The test's copy is what the test checks. When they drift,
  the one that rots is the one nobody looks at — and a stale row in the test's copy is a
  contract for a file that has not been bridged for a year.
- **The missing column was the tell.** The test needed the owner and the table could not
  give it, so the test kept its own copy. The duplication was not a design choice; it was
  a symptom of a table that was one column short. Adding the column is the fix that
  makes the second copy unnecessary.
- **Reading by header is honest about failure.** A parser that reads "any row that looks
  like it" cannot fail loudly: it just finds fewer rows. A parser that looks for a header
  fails with "there is no table with this header", which is the truth when the table
  changed shape.

## Consequences

- The skill's rewrite table has one more column, and its 15 rows carry the file that
  builds the source. That file was named in the test before; now a developer reading the
  skill can see it too.
- The tests no longer contain a copy of the contract, so a change to the contract is a
  change to the skill — and the skill's own table is the artefact a reviewer reads.
- Two checks are new: a stale row in the rewrite table (a row naming a file that no
  longer owns a bridged field) fails, and a stale row in the inventory (a file that no
  longer owns a field) fails. Before, only a *missing* row failed.
- A row in the rewrite table whose `Owner` is empty is a row with no owner, and the
  "the owner declares a source of that kind" check fails on it. The column is not
  optional.
- The tables are Markdown, so a row is prose with a shape: the readers take the rows they
  understand and skip the ones they do not. A cell that stops being a path is a row the
  check cannot use, and the check says which row it was.

## Links

- `2026-10-21-the-field-shape-is-derived-not-read` — the shape is derived from the
  imports; this decision decides *where* the classification is written down.
- `2026-10-20-the-rewrite-authority-is-a-type` — the decision that moved the answer into
  the type of the source.
- `.agents/skills/singularity-todo-text-field-states/SKILL.md` — the inventory and the
  rewrite table, which are now the only copies of the contract.
- `shared/src/jvmTest/kotlin/com/singularity/todo/arch/SkillTables.kt` — the reader that
  finds a table by its header.
