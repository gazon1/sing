---
title: "Read but never written" is a question about a set, not about syntax
date: 2026-10-07
status: accepted
slug: reading-a-state-property-is-not-writing-one
---

# "Read but never written" is a question about a set, not about syntax

Item 4 of `2026-10-05-google-sync-known-gaps-and-field-drift.md` records a class of
defect this repository cannot currently detect:

> A property that is declared, read by the screen, and never assigned. `isSupported`
> and `importWindow` both shipped that way on `CalendarSyncUiState`. The field had a
> KDoc explaining why it mattered, and a test asserting on it. Both were dead.

Two attempts to build the check, and they failed for the same reason.

## Attempt 1: regex over Kotlin source

A pattern matching "declared here, referenced elsewhere, never assigned". It returned
**63 candidates whose first entry was a false positive** — `ProfileSwitcherUiState.isLoading`
*is* assigned, on the line after its declaration, and the pattern missed it because the
write is:

```kotlin
_state.update { it.copy(isLoading = true) }
```

Nothing there looks like an assignment to a regex. A gate that cries wolf gets switched
off, and a switched-off gate protects nothing, so this was recorded rather than shipped.

## Attempt 2: a detekt rule, in `:detekt-rules`

The natural home: there are already 19 custom rules, and `PassThroughUseCase` is the
precedent for a domain invariant expressed as a lint failure.

It cannot work, and the reason is structural. **A detekt rule is handed one file at a
time.** The question "is `isLoading` ever assigned?" is then unanswerable from inside
`SomeUiState.kt` — the assignment is in `SomeViewModel.kt`. The rule can only see reads
in its own file, so it would report most healthy state classes as broken. The rule was
written, and it reported the same healthy `isLoading` the regex did.

> **A per-file analyser cannot answer a cross-file question.** The signal is spread
> across files by construction; that is the whole nature of a ViewModel updating a
> state object declared elsewhere.

## What actually works, and what is built

**Whole-module resolution**, which is what KSP provides: a processor sees every file in
the module at once, so a write anywhere counts. That is the right tool and this ADR
recommends it.

Built now: `tools/unwritten-properties` — the decision logic, with **no dependency on
KSP or on the Kotlin compiler**, over two plain data types (`Declaration`, `Reference`).
It is covered by 13 tests, each one a shape that produced a false positive in attempts 1
and 2 — the `copy(x = …)` write, the write in another file, the name shared by two
owners, the never-read property, the Room DAO.

Not built: the KSP adapter. KSP 2.3.11's `symbol-processing-api` artifact ships
**declarations only** — `KSClassDeclaration`, `KSPropertyDeclaration`, `KSValueArgument`
— and not the expression API (`KSExpression`, `KSCallExpression`,
`KSPropertyAccessExpression`) that a reference walk needs. That API is a separate
artefact this repository does not depend on. Writing the adapter against an API that
does not resolve locally, and cannot be compile-checked, would be committing code that
does not build.

## Why the split is the design, not a workaround

The false positives live in the *rules*, not in the symbol plumbing. Keeping
`findNeverWritten(declarations, references)` pure means every rule is testable in
milliseconds with no compiler in the loop — which is the only way to test the thing that
actually goes wrong.

## Two decisions deliberately deferred

1. **Not wired into `:shared`.** Running it would fail the build on every real finding in
   the tree, and those findings need a human to look at each one. Turning that into a
   blocking gate is a decision to make *after* the population has been reviewed once,
   not before.
2. **No baseline yet.** There is nothing to baseline until the adapter can run, and a
   baseline of untriaged candidates is a file nobody maintains.

## The generalisable rule

> Before building a checker, check what *the tool* can see. A defect defined across
> files needs a tool that reads across files. Two attempts here failed not because the
> logic was hard but because the instrument had a narrower field of view than the
> question — and the first symptom of that is a false positive on healthy code.

That is the same lesson as the two non-graders, one level up: the cost of a gate that
cannot see is that it reports what it cannot see, and the first finding is always wrong.

## Links

- `tools/unwritten-properties/` — the analysis and its 13 tests
- `2026-10-05-google-sync-known-gaps-and-field-drift.md` — item 4, the open gap
- `2026-10-07-three-files-that-only-one-gate-reads.md` — the other tool-shaped blind spot
- `scripts/check-dead-settings.py` — the closest working gate: it answers a
  cross-boundary question by following a *chain* of named files rather than by scanning
