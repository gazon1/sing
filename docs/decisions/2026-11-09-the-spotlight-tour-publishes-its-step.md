---
title: "The spotlight tour publishes its step, and the series card counts its dates"
date: 2026-11-09
status: accepted
tags: ["ui", "compose", "resources", "tests"]
---

# The spotlight tour publishes its step, and the series card counts its dates

## Context

Two desktop UI test classes were disabled in the merge that moved `:shared-ui`
back into `:shared` (`eb2644247`, "Merge :shared-ui back into :shared and fix
build"). The reason in the annotation was "UI does not render in headless test
environment": four tests stopped running.

A disabled test is a silent green — JUnit counts it in `tests=` exactly like a
passing one — and `scripts/check-test-runs.py` measures skipped tests against a
ceiling of 0. The merge therefore left `origin/main` with a red CI: the
`desktopApp:test` floor gate reported "4 skipped tests, ceiling 0 (+4)".

The ceiling is not a number to raise. The baseline file says it plainly:
"Do not raise it to make this gate pass — re-enable the test." So the question
was what the four tests were hiding, and it turned out to be two product
defects.

**The plural resources were never substituted.** Compose Resources formats a
resource argument with `Regex("""%(\d+)\$[ds]""")` — only a *positional*
placeholder is replaced. Every plural in the corpus used a bare `%d`:

```xml
<item quantity="other">Series · %d dates</item>
```

so the series card rendered the literal text `Series · %d dates`, on every
platform. The test that noticed this had been disabled before anyone read it.

**The tour published its state one registry change too late.**
`rememberSpotlightTour` keeps the machine's answer in `SpotlightTourStateMachine`,
a plain class whose `index` was an ordinary field. The composable republished
the state through a `snapshotFlow` that read `machine.index` — and a `snapshotFlow`
only re-emits when the *snapshot state* it read changes. A plain field is not
snapshot state, so the flow re-emitted only when an anchor's bounds changed.
The flow that starts the tour and the flow that publishes the state woke on the
same registry change; the publisher ran first, read `index == null`, and the
machine started a moment later — with nothing left to wake the publisher. The
overlay appeared only if the registry changed *again* afterwards.

In the app that "again" was a later layout pass, so the tour could appear by
accident. In the isolated harness there is no second layout pass, so the
overlay never appeared at all: the tree stayed empty.

## Idea

- Fix the two product defects the disabled tests were pointing at, and re-enable
  the tests. The gate stays as it is.
- Use the positional placeholder the library actually formats, rather than
  working around the library in Kotlin code.
- Make the machine's answer observable, so the state the composable publishes
  cannot lag the state the machine decided on.

## Decision

- The plurals name their argument positionally: `%1$d date`, `%1$d dates`,
  and the same in `values-ru`. Compose Resources substitutes them; the card
  shows "Series · 2 dates" again.
- `SpotlightTourStateMachine.index` and `.ready` are `mutableStateOf` rather
  than plain fields. The machine keeps every decision it had — it is still a
  class with no composable in it, and its own unit tests still exercise it
  without a composition — but its answer is now snapshot state, so the
  `snapshotFlow` that publishes it re-emits when the answer changes.
- `SpotlightTourTest` and `TaskSeriesCardTest` lose `@Disabled`. The four tests
  run, and the ceiling stays 0.

## Rationale

**The gate is not the thing that is wrong.** The skipped ceiling exists to
catch coverage that quietly disappeared, and four `@Disabled` tests are exactly
that. Raising the ceiling to 4 would have made the gate green and kept the
defects — a literal `%d` in the interface and a tour that appears only by
accident.

**A positional placeholder is the library's contract, not a workaround.**
Compose Resources has one formatting rule, and it is documented in its own
source. The corpus had one plural consumer and no positional placeholder
anywhere, which is why nothing had ever rendered a count.

**Observability is the whole bug.** The state machine's job is to answer "which
step is showing"; the composable's job is to publish that answer. A plain
field between them is a channel with no back-pressure: the answer changed and
nobody was told. Making the answer snapshot state is the smallest change that
closes the channel — the alternative, publishing the state from the flow that
starts the tour, makes the publication order depend on which collector the
dispatcher resumes first.

## Consequences

- The series card shows how many dates the series has, in both locales.
- The tour appears as soon as its target is measured, instead of waiting for a
  later layout pass to re-publish the state. A user who has already seen the
  tour still does not see it: the "already seen" branch leaves `index` null.
- The four tests run in CI. The `desktopApp:test` floor gate measures 0 skipped
  again, with the ceiling still at 0.
- The state machine is now Compose-aware. It is still a plain class — the
  decisions stay in it and its unit tests still run without a composition — but
  a caller that mutates it off the main thread writes to the global snapshot.

## Links

- `shared/src/commonMain/kotlin/com/singularity/todo/core/ui/onboarding/SpotlightTourStateMachine.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/core/ui/onboarding/SpotlightTourController.kt`
- `shared/src/commonMain/composeResources/values/strings.xml`, `values-ru/strings.xml`
- `desktopApp/src/jvmTest/kotlin/com/singularity/todo/core/ui/onboarding/SpotlightTourTest.kt`
- `desktopApp/src/jvmTest/kotlin/com/singularity/todo/feature/tasks/presentation/components/detail/TaskSeriesCardTest.kt`
- `scripts/check-test-runs.py`, `config/docs/test-runs-baseline.txt`
