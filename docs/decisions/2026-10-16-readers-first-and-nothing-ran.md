---
status: accepted
date: 2026-10-16
---

# A reader of build output reads it first — and "nothing ran" is its own verdict

Three defects in one family, found on 2026-10-16 while validating PR #684, fixed
together because they share a root: **a measurement reported on state it does not
own, or on a fact it did not establish.**

## Context

The family already had two members when this was written. A Gradle step's exit
code could not be reported at all (ADR `2026-10-16-ci-gradle-step-cannot-fail`), and
two Gradle invocations in a job could select different test populations
(ADR `2026-10-16-one-test-population-per-ci-job`). Both were loud: CI went red or a
gate exited non-zero. What follows are three that were not loud enough.

**`:koverMarkdownTable` has produced nothing on every run since the configuration
cache landed.** The task read `project.rootProject.layout` inside its `@TaskAction`,
which the configuration cache forbids. `ci.yml` runs it as `|| true`, so the
failure was invisible and the coverage table has been missing from every job summary
since. `just coverage-markdown`, the documented local recipe, was broken the same
way. Verified in run `38942680186`, a green run containing
`Invocation of 'Task.project' by task ':koverMarkdownTable' at execution time is
unsupported with the configuration cache`.

**The flake rate was computed from a directory a later Gradle step could rewrite.**
`Flake analysis vs previous run` reads `shared/build/test-results/jvmTest` and ran
after `Coverage floors`, `Coverage table` and `Kover verify (detekt-rules)` — any of
which can pull the shared test task and rewrite that directory. The step is
`continue-on-error`, so even a visible failure would have been one summary line. A
flake rate computed from a re-measured set compares this run against a previous
run's set and calls the difference flaky, which is the one thing a flake measurement
must not do.

**`check-test-runs.py` reported "a suite stopped running" for a suite that never
started.** Run locally with no results on disk, it printed
`Test runs below the recorded floor — a suite stopped running`, then listed all 326
declared `@Tag("fast")` classes as having produced no JUnit report. Every sentence
was true; none of them was the fact. It reads as a catastrophic loss of coverage
when the truth is that nobody ran the tests — which is the ordinary state of a
worktree between runs, and the state an agent inspecting the tree is always in.

## Idea

Each of the three has a different shape, and conflating them is what made the
third expensive to read.

## Decision

**`:koverMarkdownTable` declares its input.** The report path becomes an
`@InputFiles` bound at configuration time. Declared over the *directory* rather
than the file: `@InputFile` would fail Gradle's own validation with "file does not
exist" whenever the XML is absent, replacing a message that names the fix
(`:koverReport` first) with one that does not.

**Readers move above the writers, and the ordering becomes a gate.**
`Flake analysis` moves directly after `Normalise desktop results`, joining every
other reader. `scripts/check-build-output-readers.py` then enforces the rule: in a
job, a step whose command reads `build/test-results`, `build/reports` or
`build/traceability` must precede every test-capable Gradle invocation except the
one that produced them. The first invocation is exempt by construction — it is what
creates the directory.

**"Nothing ran" gets its own exit code.** `EXIT_NO_RUN = 3`, distinct from the `1`
that means a floor was breached, plus a headline that says what it is and what to do
instead. The by-results check skips source sets already reported as empty, so one
absent run produces one line rather than 326.

## Rationale

The exemption for the producing invocation is the part worth stating, because the
first version of the gate had it backwards and reported the *test run itself* as a
violation for five different readers. It was not a subtle bug: it was the shape of
the rule, written as "reader then writer" when the property is "reader then *any
later* writer". A rule that cannot tell the producer from the re-run is not a rule
about ordering.

The reader detection is a text heuristic rather than a declaration, and that is a
trade, not an oversight. A declaration is more precise and drifts: a declaration
nobody is forced to update is a claim, and the experience with declared-but-unread
Gradle roots (ADR `2026-10-05-test-task-external-inputs`) is that the missing
declaration is the defect that bites. Text recognition reads the command that will
actually run and cannot be forgotten. What it cannot see is a read through a
variable — stated in the gate's own docstring rather than left as a surprise.

The exit code is the smallest possible fix and the only one that survives someone
reading only the exit code. Both outcomes are failures; a caller that must not
investigate a phantom regression has to be able to tell them apart without parsing
prose. The unit tests assert the *counts* the gate reports, not just the code — an
empty finding list is what a broken parser looks like, and it reads as a pass.

## Consequences

- The coverage table appears in job summaries again, or the step is removed. It is
  no longer possible for a step to be permanently broken and permanently
  advisory at the same time without a gate noticing, because the failure is now a
  build-configuration error rather than something `|| true` can absorb silently.
- `check-gate-wiring`'s "gates are alive" pass will read `check-test-runs.py`'s
  clean-tree exit as 3 rather than 1 in a worktree without results. That is the
  intended consequence: the harness already treats any non-zero as "already
  failing", and the new code makes the *reason* legible instead of hiding it in the
  exit code.
- Any workflow that adds a Gradle step after a reader now fails a gate in about a
  second, in the `static` job, instead of producing a wrong number 30 minutes later.

## Links

- Runs: <https://github.com/gazon1/sing/actions/runs/38942680186> (green, contains
  the `koverMarkdownTable` failure), <https://github.com/gazon1/sing/actions/runs/38933517902>
  (the traceability failure this family starts from)
- [2026-10-16-a-control-must-reproduce-the-real-defect.md](2026-10-16-a-control-must-reproduce-the-real-defect.md)
- [2026-10-16-ci-gradle-step-cannot-fail.md](2026-10-16-ci-gradle-step-cannot-fail.md)
- [2026-10-16-one-test-population-per-ci-job.md](2026-10-16-one-test-population-per-ci-job.md)
- [2026-10-05-test-task-external-inputs.md](2026-10-05-test-task-external-inputs.md)
- [2026-10-04-measurement-integrity.md](2026-10-04-measurement-integrity.md)
- `openspec/specs/test-execution-integrity/spec.md` REQ-18