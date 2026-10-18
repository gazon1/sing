---
title: "LocalDataDisposition is a sealed class, not an enum"
date: 2026-10-19
tags: [auth, domain-model]
status: accepted
supersedes: 2026-10-18-local-data-disposition-is-enum
---

## Context

`LocalDataDisposition` describes the choice a user makes when attaching a local,
account-less session to a real account: keep the local data or discard it.

The 2026-10-18 decision deferred the sealed class migration on the grounds that
"an enum is sufficient today". That proved to be the right call for the moment,
but the design it anticipated has now arrived:

- The attach screen must show **exact per-table counts** before the user chooses,
  so a generic warning is replaced by "42 tasks, 7 notes will be removed".
- `OwnerCountsCalculator` computes both `ReassignCounts` and `EraseCounts` in a
  single pass — the screen needs both before the user has chosen anything.
- `ClaimStep.Done` now carries the disposition so the UI can show a result toast
  ("12 tasks moved to your account") without a separate lookup.

## Decision

`LocalDataDisposition` is a `sealed class` with two `data class` carriers:

```kotlin
sealed class LocalDataDisposition {
    data class Keep(
        val transferCounts: ReassignCounts,
        val summary: LocalDataSummary,
    ) : LocalDataDisposition()

    data class Discard(val eraseCounts: EraseCounts) : LocalDataDisposition()
}
```

## Rationale

**Why sealed class now is correct:**

1. Each variant carries exactly the data the UI needs at each point in the flow.
   `Keep.transferCounts` feeds the preview; `Discard.eraseCounts` feeds the inline
   confirmation panel. Neither can be shown without a payload on the variant.

2. `ClaimStep.Done(disposition)` carries the outcome to the ViewModel without a
   second database lookup. A `data object`-variant enum could not carry this.

3. The two-variant sealed class is zero-cost at the call site for the common
   case (the screen reads the payload it needs), while keeping the exhaustive
   `when` guarantee the original ADR valued.

**Why the original enum was correct then:**

The counts needed for the confirmation screen required `OwnerCountsCalculator`, which
in turn required adding `count*` methods to `OwnerEraseDao` and wiring the calculator
into `LocalAccountClaimer`. That work was only justified once the UI requirement
became concrete. Pre-empting it would have added complexity for a case that did not
yet exist.

## Consequences

- `LocalDataDisposition.Keep` and `LocalDataDisposition.Discard` no longer exist as
  enum entries. All call sites use the `data class` constructors.
- `LoginFormState.Saver` serialises just the class name (`"Keep"` / `"Discard"`);
  counts are reloaded from `summarise()` when the screen opens.
- The `sealed class` guarantees exhaustive `when` coverage. Adding a third variant
  (e.g. `"Move to a different account"`) requires updating every `when` over
  `LocalDataDisposition`, which is the intended enforcement.
- `OwnerCountsCalculator` replaces two separate DAO calls with one pass, and its
  `CountsPair` return type makes the pairing of the two counts explicit at the
  type level.
