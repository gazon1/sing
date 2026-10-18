---
title: "LocalDataDisposition is an enum, not a sealed class — today"
date: 2026-10-18
tags: [auth, domain-model]
status: superseded
superseded-by: 2026-10-19-local-data-disposition-is-sealed-class
---

## Context

`LocalDataDisposition` describes the choice a user makes when attaching a local,
account-less session to a real account: keep the local data or discard it.

```kotlin
enum class LocalDataDisposition {
    Keep,   // move the rows to the account
    Discard // erase them
}
```

The domain also has several other sealed interfaces that model choices with a fixed
set of outcomes — `ClaimStep`, `SyncAction`, `OutboxItem.Status` — all of which are
sealed interfaces, not enums.

## Idea

A sealed class would be more consistent with the rest of the domain, and would make
it possible to attach additional data to each variant later (e.g. a `Discard`
variant could carry the erase counts so the UI can confirm what was deleted).

An enum is sufficient today because `Keep` and `Discard` carry no payload, and
changing `enum class` to `sealed class` is a backward-compatible expansion.

## Decision

Keep `LocalDataDisposition` as an `enum class`. Migrate to a sealed class when
either variant needs to carry data, or when a third outcome is added.

## Rationale

**Why enum today is correct:**

1. Both variants are pure signals with no payload. An enum field is `Int`-cheap
   compared to a sealed class object, and the difference matters in a
   frequently-serialised domain object.

2. The set is provably closed. There are exactly two choices, and both are
   represented. A third outcome — "move and rename" or "move and archive" — does
   not exist in the current product and would require a product decision before
   the model could express it. An enum enforces closure without ceremony.

3. Migration cost is zero. Kotlin allows promoted `enum class` → `sealed class`
   without breaking binary or source compatibility. Callers that `when` over the
   enum will continue to compile with an `else` branch added by the compiler if
   the sealed class later gains a new subclass.

**Why sealed class later will be correct:**

The moment `Discard` needs to carry `EraseCounts` (so the confirmation screen
can show "42 tasks will be removed" instead of a generic warning), the sealed
class pays for itself:

```kotlin
// The moment this becomes true, enum is the wrong shape:
sealed class LocalDataDisposition {
    data class Keep(val transferCounts: ReassignCounts) : LocalDataDisposition()
    data class Discard(val eraseCounts: EraseCounts)   : LocalDataDisposition()
}
```

The `when` exhaustive-check guarantee applies to both shapes. The sealed class
is strictly more expressive. The enum is strictly simpler for the current facts.

## Consequences

- Existing `when (disposition) { LocalDataDisposition.Keep -> … }` calls continue
  to compile. If a third subclass is later added, the compiler forces the caller
  to handle it.
- If a future product requirement adds a third disposition (e.g. "Move to a
  different existing account"), the migration is: rename the file, replace
  `enum class` with `sealed class`, wrap each former enum entry in `data object`,
  update the `when` clauses. No call-site changes required beyond adding the new
  branch.
- This ADR is superseded by 2026-10-19-local-data-disposition-is-sealed-class.
