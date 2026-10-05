---
title: A source set detekt is not told about is a place a defect can hide
date: 2026-10-05
status: accepted
tags: [lint, detekt, gates, android, testing-integrity]
---

## Context

`androidApp/build.gradle.kts` names the directories detekt scans in a literal
list. `src/debug` was not on it, so `DebugSeedActivity.kt` had never been
linted, and the module's only unscanned source set was the one holding its
debug tooling.

The reason it stayed that way is not that the omission was expensive. It is
that **nothing reports it.** Every other gate in this repository reports a
finding; this one reported an absence, and no reader of a green detekt report
can distinguish "there is nothing wrong" from "nothing was looked at".

The same list once named `src/androidAndroidTest/kotlin`, a directory that does
not exist — detekt ignored the entry without complaint, so the list looked
complete while covering a path that was never scanned.

`openspec/changes/androidapp-debug-lint-policy` had already made the decision
and left it unimplemented: **lint it, carrying the intentional findings in a
baseline with a reason each.** Exempting the source set was the cheaper
alternative and was rejected, because "not linted" and "linted with everything
suppressed" are the same hiding place, the second with a nicer badge.

## Decision

`src/debug/kotlin` is scanned. The findings it surfaced were split by
measurement rather than by assumption, and the measurement disagreed with the
plan:

| | Plan recorded | Measured |
|---|---|---|
| total findings | 16 | **15** |
| formatting, auto-correctable | 12 | **9** |
| intentional, baselined | 4 | **6** |

The 9 were fixed with `--auto-correct`. The 6 live in
`ManuallySuppressedIssues`, not `CurrentIssues`, with a reason each written
about what the tool *is* rather than about debug code in general — because a
justification written as "this is debug code" is the blanket exemption wearing
a suppression's syntax, and it would be copy-pasted into production where it is
not true. `ManuallySuppressedIssues` is hand-written, so a seventh finding has
to be a decision; a `CurrentIssues` entry is regenerated from a run and quietly
absorbs whatever the next one finds.

Two of the 6 are single baseline entries covering four `Clock.System` call
sites, because detekt keys the signature on the expression rather than the
line. The baseline was verified in both directions: it passes, and removing one
line brings the four findings straight back.

## The part that mattered more than the linting

`DetektSourceSetsAreAllScannedTest` was written first, and it **did not work**.
It reads each module's `detekt.source` list, so the obvious negative control is
to delete an entry and see whether the test notices. It did not — `:shared:jvmTest`
was `UP-TO-DATE` and reported a verdict about a file it had not re-read.

That is the same defect as `MaestroFlowTagsTest` (a derived path nothing
declared as an input) and as the measured `TestTagCoverageTest` staleness in the
comment right above it in `shared/build.gradle.kts`. A gate that cannot be
invalidated by the change it watches is **worse than no gate**, because its
silence is indistinguishable from a pass.

The four module build files are now `inputs.files` on the task. With that
declared, the control fires and names the source set. The test also fails when
`detekt.source` names a directory that does not exist, which is how
`src/androidAndroidTest/kotlin` survived in the list.

It reads the source sets off the filesystem rather than from a hard-coded list,
because a hard-coded list would be a second list to forget to update — the same
mistake in a different file. And it asserts that it found at least three
modules with a list, so a drift in its own regex cannot turn it into a test
that passes by checking nothing.

## Consequences

- `src/debug` code is now held to the production rule set. The next debug file
  inherits that, which is the point: the decision generalises to every future
  file in the source set rather than to this one.
- Four findings are accepted debt with written reasons and a test proving the
  baseline is load-bearing. A new defect in `DebugSeedActivity.kt` is now a
  build failure rather than an accumulation.
- `NoDirectClockSystem` has seen this file for the first time. Three
  `Clock.System` calls are baselined as intentional, and
  `ui-reads-the-system-clock-directly-so-a-fixed-date-cannot-reach-it` (#91)
  keeps the production-side instance of the same escape hatch.
- A module that adds a source set and forgets `detekt.source` fails
  `:shared:jvmTest`. The failure mode it replaces was silence.
- The seed model deferred in #171 has somewhere to live that a linter reads. It
  was going to be built in this source set, and that was the blocker.

## Links

- `config/detekt/baseline-androidApp.xml` — the four entries, with reasons
- `shared/src/jvmTest/kotlin/com/singularity/todo/arch/DetektSourceSetsAreAllScannedTest.kt`
- `openspec/changes/androidapp-debug-lint-policy` — the decision and its task list
- `docs/decisions/2026-09-25-test-suite-tag-defaults` — the neighbouring case of
  a rule that is right for production and wrong for a test
