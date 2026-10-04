---
title: "Every AppError grouped under its subtype's name, and a ratchet over the runCatching debt main already paid"
date: 2026-10-05
status: accepted
tags: [observability, error-handling, detekt, architecture]
---

## Context

The AppTracer integration gave the app somewhere to send failures. One consequence of that was
written down in `2026-10-04-observability-followups.md` and left unfixed:

- **`AppError` code: 0 of 31 call sites passed one.** Every subtype carries a defaulted code
  (`error.validation`, `error.not_found`, …), so `AppError.Validation("Title cannot be blank")`
  compiles and looks correct.

The second item in that list — silent `runCatching` in the data layer — was **paid on `main`
while this change was in flight**, by `db132beb fix(coroutines): migrate 199 runCatching sites to
runCatchingCancellable`. It is recorded here because this work still depends on it, and because
the number that survives is a different one than either of the estimates that were floating
around:

- "~139", from `2026-10-04-observability-followups.md` — measured differently, not reproducible.
- 197 plain `runCatching` sites in commonMain, measured here on 2026-10-05 with comments and
  KDoc stripped and `runCatchingCancellable`/`runCatchingResult` excluded.
- **22 today**, after `db132beb` rebased underneath this work. 56 files now route through
  `runCatchingResult` or `runCatchingCancellable`.

A stale number in a decision record is not a small thing: it is the figure a future reader
plans against, and it was the reason to believe there was a data-layer problem to solve here.

## Decision

**Give every construction a domain code, and enforce it with a rule.** All 29 call sites now
pass `code = "<module>.<what_failed>"` — `auth.email.blank`, `task.title.blank`,
`project.delete.has_tasks`, `sync.push_failed`. The new `AppErrorCode` detekt rule keeps it
that way, and is excluded on test sources: an `AppError` built in a test never reaches a
dashboard, so a code there is a value with no consumer, and demanding one would train the eye
to type a code without reading it — which is exactly when a wrong one ships.

**Activate `NoRunCatchingInSuspend` anyway, as a ratchet.** `main` converted the sites but left
the rule at `active: false`, so the migration has no gate: a 23rd plain `runCatching` in a
`suspend` function would be a silent regression of the exact bug `db132beb` just spent a commit
removing. The rule has a correctness rationale, not a style one — `kotlin.runCatching` catches
`Throwable`, `CancellationException` included, so inside a `suspend` function it converts a
cancellation into an ordinary failure and lets the coroutine run past the boundary meant to stop
it.

**It needs no baseline entries.** Verified, not assumed: with the rule active the current tree
reports **zero** violations, so `config/detekt/baseline-shared.xml` is untouched by this change.
That is a stronger position than a baseline of 188 — a baseline is a record of debt, and there
is none left to record.

Worth recording how that was established, because the obvious method gives the wrong answer:
`./gw :shared:detektBaseline` does **not** load the custom rule set, so regenerating the
baseline silently drops every custom-rule entry — 357 down to 338 on this branch, and detekt
then reports a clean tree because the rules were not running. The ratchet was proven live by
planting a `runCatching` inside a `suspend fun` in `commonMain` and reading the report, not by
regenerating anything.

## A real defect found while doing this

`TagGroupUseCases.CreateTagGroupUseCase` validated with:

```kotlin
require(input.name.isNotBlank()) { AppError.Validation("Name cannot be blank") }
```

`require`'s second argument is `lazyMessage: () -> Any`. The `AppError` was constructed,
stringified into an `IllegalArgumentException` message, and **discarded** — subtype, code and
cause all gone. `runCatchingResult` then classified the `IllegalArgumentException` as `Unknown`,
so a blank name and a transparent colour landed in the same untyped bucket as every other
unexpected throw. The two validations now `throw` the `AppError` directly.

This is the second defect in this repository found by *looking for* one rather than by a gate
firing. It survived because it compiled, because the message stringified to something
plausible, and because no test asserted on the resulting type.

## Rationale

- **A code is grouping identity; a message is prose.** The message is not transmitted
  off-device, is not stable across a copy-edit, and for a group named after it is exactly what
  a translator would break. Fourteen unrelated validation failures sharing `error.validation`
  is not a group, it is a bucket.
- **A default for a required value is a silent omission.** The default made 29 sites look
  correct. The same reasoning as the `NoOpCrashReportingPort` default, arriving from the other
  direction: a default is fine when the value has no consumer, and wrong when it does.
- **A migration without a gate is a suggestion.** `db132beb` was real work; leaving the rule
  off means the next plain `runCatching` in a suspend function is one reviewer's memory away
  from undoing it.

## Consequences

- The crash dashboard gains real separation: `error.validation` splits into 14 distinct
  groups, and `task.not_found` is distinguishable from `project.not_found`.
- `AppErrorCode` fires on new sites and is proven to fire — verified by planting a
  codeless construction in `commonMain` and reading the report, not by the tests passing.
  The rule's first draft matched only the *fully-qualified* receiver and therefore matched
  nothing, because every real call site writes `AppError.Validation(…)` behind an import; a
  positive test for the simple-name spelling now pins that.
- `NoRunCatchingInSuspend` is live with an empty baseline. A new violation fails the build;
  proven by planting one. The pre-existing debt it was written for is gone.
- The data-layer `runCatching` item on `2026-10-04-observability-followups.md` is **closed by
  `db132beb`**, not by this change. Anyone re-reading that record should see this one.

## Links

- `core/error/AppError.kt` — the defaulted codes
- `detekt-rules/.../AppErrorCodeRule.kt` — the rule and its rationale
- `detekt-rules/.../NoRunCatchingInSuspend.kt` — the ratchet, now active
- `db132beb` — the commit that migrated 199 `runCatching` sites
- `feature/tags/domain/usecase/TagGroupUseCases.kt` — the discarded-AppError fix
- `2026-10-04-observability-followups.md` — items 4 and 6, one closed elsewhere, one here
