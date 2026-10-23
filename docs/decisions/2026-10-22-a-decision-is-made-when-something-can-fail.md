---
title: "A decision is made when something can fail"
date: 2026-10-22
status: accepted
tags: [tasks, series, gates, testing]
---

# A decision is made when something can fail

## Context

This session shipped one feature — one work, several dates — and then spent most of
its time on what that feature revealed about the way the project verifies itself.
Three of the session's failures had one shape.

The Room upgrade from v45 to v46 was written as an *automatic* migration. Room
rewrites a table when it cannot prove that the schema matches, and rewriting
`tasks` unlinks every note that points at a task: the foreign key is
`ON DELETE SET NULL`, so the notes survive and lose their subject. The migration
passed every local test — it was tested against a schema with no notes in it — and
broke the data of everyone who had a note about a task.

The gate that keeps the Maestro tag catalogue in step with `TestTags.kt` had a
positive control in the registry and no twin that ran it. Part J of the wiring
gate asks "does every registered gate have a control?" and answers "no", so the
CI job that proves the gates can fail refused to start — and the correct verdict
was given for the wrong reason: the control existed, the control was never
executed, and nothing said so.

The catalogue gate itself was added in the same session because the JVM test that
owns the same comparison lives in a forty-minute suite. For the rest of a
session, a renamed tag is invisible; the failure lands on a device, in a flow,
hours later.

All three are the same defect at three levels: a promise was stated, and nothing
in the tree could fail because the promise was wrong.

## Idea

A decision recorded in prose is a comment. A decision recorded in code is a
behaviour. A decision is made when something in the tree can fail because the
decision was implemented wrongly — and when that something runs on every change,
not on the day somebody remembers to look.

## Decision

**Every rule this session states is stated twice: once as the behaviour, once as a
check that can fail. The check lands in the same change as the behaviour.**

| The rule | The behaviour | The check that can fail |
|---|---|---|
| A series is one work on several rows | `series_id` on the task row | `Migration45To46Test` |
| The upgrade adds a column, it does not rewrite the table | `Migration45To46` | `Migration45To46Test` (a note keeps its task) |
| The series says one thing about the work | `TaskSeriesInvariants` | `TaskSeriesInvariantsTest` |
| A series can be split and joined | `DetachTaskFromSeries`, `MergeTaskSeries` | `DetachTaskFromSeriesUseCaseTest`, `MergeTaskSeriesUseCaseTest` |
| The series shows itself where it appears | `SeriesMarker` | `AgendaSeriesCountTest`, `CalendarTaskMapperTest` |
| The statistics count a series as one work | `groupIntoWorks` | `StatisticsWorksTest` |
| The test cycle has a map | `scripts/test-impact-map.py` | `scripts/tests/test_test_impact_map.py` |
| A gate that no control runs is not a gate | `GATE_CONTROLS` + the `SCRIPT_GATES` twin | Part B of `check-gate-wiring.py` |

**And the check that fails must fail on the defect, not on a proxy.** The
migration check asserts that a note keeps pointing at its task, because "the
upgrade added a column" is a claim about the implementation and "the note is
still attached" is a claim about the data. The catalogue control renames one
tag value, because a check that compares the file with itself passes on any
file.

**The twin and the registry are one declaration.** A control exists once, in
`_registry.py`; the `SCRIPT_GATES` entry names it. The twin exists only so that
Part J can ask the question "does every registered gate have a control that
somebody runs?" — and the answer is only true when both halves name the same
thing. That is why the twin and the registry are compared field by field rather
than trusted to stay in step.

## Rationale

The cost of a rule without a check is paid late, and paid by whoever happens to
be holding the device when it breaks. The session paid it three times, and each
payment came with a false green: the migration that passed locally, the control
that existed and never ran, the catalogue that drifted for the length of a
session.

The cost of the opposite is paid immediately and visibly: every change now
carries the check that states its rule, and the CI job that proves the gates can
fail is part of the definition of done rather than an occasional audit. That is a
real cost — a session that produces rules also produces the machinery that proves
them — and it is the smaller one, because it is paid by the person who can still
fix the thing.

The single sentence that carries the decision is *"a decision is made when
something can fail"*. It is also the sentence the wiring gate is built on, and
the sentence the test map is built on: the map exists so that the checks which
can fail are the ones that run.

## Consequences

- A change that states a rule states its check in the same change. A check that
  arrives later is a check that arrives after the defect has shipped.
- The session's last item — the test map — is not a convenience feature. It is
  this decision applied to the loop itself: the map answers "which checks that
  can fail does this change put at risk", and it says why it thinks so.
- A control that no twin runs certifies nothing, so the twin is not optional
  bookkeeping. It is the part of the declaration that makes the declaration
  executable, and Part J compares the two halves because the copy that rotted is
  always the one nobody runs.
- The registry of manual migrations
  (`scripts/check-room-schema-integrity.py`) exists for the same reason: a
  manual migration is a rule the tool cannot infer, so it is written down and
  checked.

## Links

- [One work, several dates](2026-10-22-task-series-materialized-copies.md) — the
  feature the session shipped, and the migration that had to become manual.
- [The series says one thing about the work](2026-10-22-the-series-says-one-thing-about-the-work.md)
  — the invariants, and where they are allowed to act.
- [A series can be split and joined](2026-10-22-a-series-can-be-split-and-joined.md)
  — the two operations that make a series something the user can correct.
- [A series shows itself where it appears](2026-10-22-a-series-shows-itself-where-it-appears.md)
  — the marker, the undo, and the statistics that count a series as one work.
- [The test cycle has a map](2026-10-22-the-test-cycle-has-a-map.md) — the same
  decision, applied to the loop that runs the checks.
