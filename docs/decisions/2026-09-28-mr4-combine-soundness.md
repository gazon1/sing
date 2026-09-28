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

## The rule change that is NOT in this MR

The plan's third item was to add `updateState` / `setState` to
`NoCombineSideEffectRule.SIDE_EFFECT_CALLS`, on the reasoning that a side effect in a
projection can be any call that writes state, not just the four names currently listed.

**It is not included, because the extended rule does not fire on the real file.**

What was measured, in order:

1. The rule's logic is correct. A unit test with `combine(a, b) { … updateState { … } }`
   is flagged, and one with two offenders reports both.
2. It still does not fire on `CalendarSyncViewModel`, whose transform is byte-for-byte
   the shape the unit test uses — including the 5-argument multi-line form, the
   `.collect {}` suffix and the enclosing `vmScope.launch { }`.
3. In the same file and the same transform, `seed(...)` **is** flagged and
   `updateState(...)` is not, with both names present in the compiled
   `NoCombineSideEffectRule.class` and in `detekt-rules.jar`.
4. `search` and `strings` on the class and the jar both show `updateState`. A clean
   rebuild of `:detekt-rules` did not change the outcome.

The discrepancy is unexplained. A passing unit test is not evidence that a rule fires
on real code — the same lesson as `NoFactoryViewModelRule`, which `2026-09-26-konsist-
architecture-tests` had to correct after it was credited with catching a violation it
never saw, and `2026-09-28-task-detail-slot-refactor`'s own note that the skill's rule
table listed a rule that "had never been written".

**Shipping the extension anyway would have added the eighth guard in this project that
looks active and is not.** The two transforms are fixed structurally instead, which does
not depend on a lint rule firing.

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

**Worth fixing:** bound or replace the descendant walk in `NoOpUpdateStateRule` with an
explicit shallow search, and make a rule exception fail with a message that says the
report was not written.

## Rules

- **A rule that passes its tests has not been shown to fire.** Run it against the real
  file, and treat "the test passes" as the start of the investigation rather than the end.
- **When a build task throws, its previous output is not evidence.** Verify the report was
  regenerated before reading it.
- **Prefer removing the shape over banning it.** `combineStates` made the cast
  unnecessary; a rule banning the cast would have been a weaker fix at a higher cost.

## Open

- `NoCombineSideEffectRule` does not detect `updateState` in a combine transform on real
  code, though it does in a unit test. Unexplained; needs its own investigation.
- `NoOpUpdateStateRule` throws on some inputs and takes `:shared:detekt` down with it.
- `ArchiveViewModel` has `updateState` inside a `.catch { }` on a pure transform. That is
  legitimate error handling, not the projection anti-pattern — noted so the next reader
  does not "fix" it.

## Links

- `2026-09-27-feature-slot-pattern` — the rule this MR tried to extend, and the
  `combineStates` helper it introduced
- `2026-09-27-framework-drift-resolution` — the "verify a rule exists before relying on
  it" rule, restated here from the other direction
- `2026-09-26-konsist-architecture-tests` — the `NoFactoryViewModelRule` correction
- `2026-09-28-roadmap-status` — the consolidated done/remaining list
