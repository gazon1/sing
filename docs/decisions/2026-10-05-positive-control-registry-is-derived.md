---
title: The positive-control registry is derived from registration, not written by hand
date: 2026-10-05
status: accepted
tags: [gates, testing, testing-integrity, infra]
---

## Context

`scripts/check-gate-wiring.py` already carries Part B, which proves that a
registered gate can fail: it mutates a committed file and requires a non-zero
exit. That is the right shape, and it was applied to eight gates.

What it did not have was a check on the registry itself. `SCRIPT_GATES` was a
hand-written list, and measured on 2026-10-05 it held **8 of the 18** gate
scripts that a gate actually invokes. The **ten** without an entry were
indistinguishable, in that list, from the eight with one. Three of them decide
whether a test run counts as evidence — `check-test-runs.py`,
`check-coverage.py`, `check-flaky-tests.py`.

This is pattern A one level up. The ADR
`2026-10-05-gate-audit-text-shape-vs-fact` named it: a check that matches
nothing is indistinguishable from a check that is working. A hand-written list
of what has been verified has the same property — an entry that was never added
is invisible, and the list reads as complete.

The follow-up was already written down, in
`openspec/changes/detekt-rule-has-positive-control` task 8, which asked for
exactly this. That task also named its input as "the audit table in ADR
`2026-10-05-gate-audit-text-shape-vs-fact`, thirteen gates". **The table does
not exist.** The ADR has never contained a table row, in either of its two
revisions. The reference pointed at work that had not been done, and the first
thing anyone taking that task would do is go looking for it.

## Idea

Two moves, and the second is what the first one turned out to need.

1. Derive the registry. A gate script named by `check.sh`, a workflow, or a
   `just` recipe must appear in the registry or in an exemption list carrying a
   reason. Adding a gate to a recipe is then what makes it show up.
2. Decide what a control *is*, per gate, rather than assuming every gate can be
   broken by mutating a file.

## Decision

**Part F** derives the registry from `registered_gate_scripts()` and fails on a
gate with no control. The derivation covers `check.sh`, `.github/workflows/*.yml`
and `.just/**/*.just`; the last of those was not obvious, and omitting it would
have left four gates invisible — including `check-kiwi-gaps.py` and
`check-coverage-measurement.py`, which are reachable only through a recipe.

**Two kinds of control, both real.**

- `SABOTAGE` — the gate reads a committed file, so mutating it and re-running is
  a complete control. Fourteen entries.
- `FIXTURE` — the gate reads something not in the repository: a directory of
  JUnit XML, a captured Gradle log, a Kover report. Mutating a file proves
  nothing, because no file is the input. The control synthesises the input and
  requires a non-zero exit. Three entries: `flaky-tests`, `coverage`,
  `coverage-measurement`.

The fixture gates declare `needs_clean_run = False`, because none of them has
an invocation that means "the clean repository" — `check-flaky-tests.py`
requires `--current`, `check-coverage-measurement.py` requires a `log`. The
clean-run guard is not weakened for them; it is *inapplicable*, and the
`FixtureGate` docstring says so. A guard silently skipped for an unstated reason
is the same defect one level up.

Two gates are exempt, each with a reason: `check-gate-wiring.py` (a registry
that had to sabotage itself would be circular) and `check-gate-honesty.py`
(which *is* a positive control — it plants a real detekt violation and asserts
the report names it).

**The audit table is not written, and that is the deliberate answer to task 8.**
It would be a snapshot of a list the gate now maintains and checks on its own.
A hand-maintained table and a derived registry are the same object, and one of
them is verifiable. The task was corrected to name the ADR as the *method* and
to record that its table never existed.

## Rationale

**Why derive rather than extend the hand-written list.** The list was not
incomplete by accident; a list of what has been verified is completed by adding
to it, which is a decision made once and never revisited. Deriving it from
registration makes coverage a property of the repository rather than of whoever
last edited a Python literal. Part A already derives Gradle check tasks from
build files for the same reason, and the `detekt-rule-has-positive-control`
change already requires deriving the rule list from the rule providers.

**Why two kinds and not one weaker kind.** A single control kind would have
meant either excluding the run-evidencing gates (leaving the three that matter
most uncontrolled) or weakening every control to a unit test. The fixture form
is not a lesser control: `check-flaky-tests` rejecting a test that passed
yesterday and fails today is precisely the property it exists to catch.

**Why the negative controls are in the test file and not the ADR.** Two
regressions were found by breaking the code and watching for red, and both are
recorded as tests rather than as prose, because prose does not run:

- A `\./?` in the pattern requires a literal dot, so it matched only the
  `./scripts/*.sh` invocations — **3 of 18** gates. The check reported a short
  list and called it complete. `test_the_python3_invocation_without_a_dot_slash_is_registered`.
- No word boundary meant `Maestro/scripts/check-tags.sh` matched as
  `scripts/check-tags.sh`, a path that does not exist, and Part F then demanded
  a control for a gate it had invented. Asserted as "every registered path is a
  real file", not as "`check-tags.sh` is absent" — the absence held even with
  the boundary removed, because the invented path does not exist.
  `test_a_path_prefix_is_not_truncated`.

**A third instance of the same mistake, this time in a test.** The first
`test_check_rule_intent.py` re-implemented the comparison it was testing instead
of calling the script's function, so it passed on the broken code — the copy in
the test was correct and the copy in the script was not. The partition was then
extracted into `partition_reporting()` so the test calls it. This is the
`flow_has_tag` lesson from ADR `2026-10-05-gate-audit-text-shape-vs-fact`,
re-learned: *a test of a predicate must call the predicate.*

**A fourth instance, and the one that nearly got away.** The `test-runs` control
replaced the literal `shared:jvmTest 1788 0` in the baseline. Hours later the
floor moved to 1825 — legitimately, because a new test class was added — and
`str.replace` began matching nothing. The gate was handed an unmodified file
and reported the entry as a working control. It was caught only because the
clean-tree guard fired for an unrelated reason (a stale filtered `--tests` run
had left one class of XML on disk), and the resulting message pointed at the
gate rather than at the control.

A control that quietly stops sabotaging is worse than no control, because it is
reported as a passing control. The sabotage now rewrites the floor by regex and
**asserts that it changed something**, and two tests pin it:
`test_a_sabotage_that_cannot_apply_fails_loudly` moves the floor to a value the
original literal could not have matched, and
`test_a_sabotage_runs_against_the_repository_as_it_is` runs every content
sabotage against today's file and requires a diff. The first was verified red
against the literal-value version.

**Two defects found while measuring, and fixed here.** Both were in the shape
this ADR is about — a number that reads as a measurement and cannot vary:

- `config/docs/test-runs-baseline.txt` still described the four-column format
  after the class column was removed in `a7c4c4b0`. A tool's own header
  disagreeing with its data is a wrong answer waiting for a reader.
- `check-rule-intent.py` computed `declared_and_reporting` by comparing a raw
  baseline rule name against the *normalised* set from `declared_rules()`, so
  the count was structurally `0` and printed as "declared + reporting: 0"
  beside 102 declared and 33 reporting. The verdict underneath was computed
  correctly, so the gate returned 0 — the line above it was decoration. Now 33,
  and `test_check_rule_intent.py` pins both the count and the partition.

## Consequences

- `check-gate-wiring.py` gains Part F, `--registry`, `FIXTURE_GATES`,
  `SABOTAGE_ONLY_GATES`, `GATE_EXEMPTIONS`, and a `target_is_dir` field for the
  one control that mutates an mtime rather than content.
- `ScriptGate` mutating a directory restores the mtime, not the text. The
  content comparison would have reported a failed restore for a correct
  mutation — `check-openspec-stale` reads staleness from `stat().st_mtime`.
- The `test-task-inputs` control removes the *covering* `inputs.dir`, not the
  root property. The rule is one-directional, so removing the property leaves
  nothing to check and the gate returns 0 — the first attempt at this control
  did exactly that, and the comment in the registry says so.
- Part B now runs 17 controls (14 sabotage, 3 fixture) across 16 gate scripts —
  `check-doc-dead-refs.py` carries two, one per mode. All were measured green
  individually before being committed to the registry.
- `scripts/tests/test_check_gate_wiring.py` gains 13 tests, two of which are
  negative controls over the derivation itself, plus two added later when a
  control turned out to have stopped sabotaging silently.

## Links

- `scripts/check-gate-wiring.py` — Part F, `FIXTURE_GATES`, `GATE_EXEMPTIONS`
- `scripts/tests/test_check_gate_wiring.py` — `PartFTest`
- `scripts/tests/test_check_rule_intent.py` — the count that was always zero
- `openspec/changes/detekt-rule-has-positive-control/tasks.md` — task 8, corrected
- `docs/decisions/2026-10-05-gate-audit-text-shape-vs-fact.md` — pattern A, and
  the `flow_has_tag` deferral that was reasoned wrongly
- `openspec/specs/test-execution-integrity/spec.md` — the normative spec
