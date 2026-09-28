---
title: "MR-4 retro — a lint guard that passes its test and misses the real file"
date: 2026-09-28
tags: [retro, tech-debt, detekt, coroutines, mvi]
status: accepted
epic: refactor/tech-debt-roadmap-v3
---

Retro after MR-4 of the tech-debt roadmap (`mr-4-combine-soundness`).

## Inventory

| Metric | Value |
|---|---|
| Production files changed | 2 (`SearchViewModel`, `CalendarSyncViewModel`), +25/−17 |
| `@Suppress("UNCHECKED_CAST")` in `SearchViewModel` | 1 → 0 |
| `:shared:jvmTest` | 1140 tests, 0 failed |
| detekt (shared) | 0 findings |

## What the MR was scoped to do, and what it did

The plan asked to **delete** `SearchViewModel`'s four-flow combine as redundant and push
`updateState` into the intent handlers. That would have broken the screen. The
`updateState` calls in `onQueryChange` and `onApplyFilter` write only `isSearching` and
`results`; the combine is the only thing that mirrors `_queryString`, `_parsedQuery`,
`_activeFilter` and `_activeSavedSearchId` into `state`. Removing it would have spread one
invariant across 18 write sites in 5 methods.

It was not redundant; it was **unnecessarily indirect**. `combineStates` gives a typed
4-parameter transform, so the `listOf` packing and the index casts go away — the cast was
only ever an assertion about a tuple the previous line had built.

`CalendarSyncViewModel` was worse than the plan assumed. Its five-flow combine called
`updateState` inside the transform and then `.collect {}` on nothing:

```kotlin
combine(enabled, calendarId, status, lastAt, appPkg) { … ->
    updateState { it.copy(…) }        // ← side effect in the projection
}.collect {}                           // ← empty collector
```

That is precisely what `NoCombineSideEffect` exists to prevent, live in production.
Both files now return the reducer from the transform and let `collect` apply it.

## The rule change, and the false negative that hid it

The plan's third item was to add `updateState` / `setState` to
`NoCombineSideEffectRule.SIDE_EFFECT_CALLS`, on the reasoning that a side effect in a
projection can be any call that writes state, not just the four names listed.

**When this ADR was first written, the extension was rejected** on the grounds that the
extended rule did not fire on `CalendarSyncViewModel` — `seed` in the same transform was
reported and `updateState` was not, despite both names being present in the compiled
class and the jar. That conclusion was wrong. The rule worked the whole time; see
`2026-09-28-detekt-daemon-and-crashing-rule.md` for the full investigation. The extension
is now shipped, with a positive control.

The failure mode that produced the false negative is worth recording, because it is the
same one this project has hit repeatedly: **a build task that throws leaves its previous
output in place, and stale output reads as a result.** See the infrastructure finding
below.

## A second infrastructure finding: a stale report cost this MR several hours

`NoOpUpdateStateRule.isIdentityTransform` calls
`arg.collectDescendantsOfType<KtLambdaExpression>()` and **throws** on some inputs —
reproducibly, on `updateState(reduce)` where the argument is a name reference, and on an
unrelated probe file. When a custom rule throws, `:shared:detekt` fails with
`Execution failed for task ':shared:detekt' … led to an exception` and **writes no
report**, leaving the previous run's `detekt.md` on disk.

That is how a `seed` finding was attributed to a file that no longer contained `seed`,
and why several intermediate measurements here were wrong. The detekt report is only
trustworthy when the run reached the report-writing step — check the task outcome, not
just the file's contents.

**Fixed** in `2026-09-28-detekt-daemon-and-crashing-rule.md`: the descendant walk is now
an explicit tail-recursive search, and the Gradle-daemon classpath caching that made the
rule look broken is documented.

## Rules

- **A rule that passes its tests has not been shown to fire.** Run it against the real
  file, and treat "the test passes" as the start of the investigation rather than the end.
- **When a build task throws, its previous output is not evidence.** Verify the report was
  regenerated before reading it.
- **Prefer removing the shape over banning it.** `combineStates` made the cast
  unnecessary; a rule banning the cast would have been a weaker fix at a higher cost.

## Open

- `ArchiveViewModel` has `updateState` inside a `.catch { }` on a pure transform. That is
  legitimate error handling, not the projection anti-pattern — noted so the next reader
  does not "fix" it.

## Links

- `2026-09-27-feature-slot-pattern` — the rule this MR tried to extend, and the
  `combineStates` helper it introduced
- `2026-09-27-framework-drift-resolution` — the "verify a rule exists before relying on
  it" rule, restated here from the other direction
- `2026-09-28-detekt-daemon-and-crashing-rule` — the investigation behind the retraction
- `2026-09-26-konsist-architecture-tests` — the `NoFactoryViewModelRule` correction
- `2026-09-28-roadmap-status` — the consolidated done/remaining list
