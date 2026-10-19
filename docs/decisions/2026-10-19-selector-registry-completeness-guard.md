---
created: 2026-10-19
title: "A completeness check must compare two sources, not a list against itself"
tags: [serialization, agenda, testing, architecture, quality-tools]
status: accepted
---

# ADR: a completeness check must compare two sources, not a list against itself

## Context

`Selector` is a sealed hierarchy of 17 variants, and `SelectorSerializer` is a
hand-written registry that encodes and decodes each one. The registry had its own
completeness check:

```kotlin
private val expectedTypeTags: Set<String> by lazy { setOf( /* one instance per variant */ ) }

init {
    val missing = expectedTypeTags - registry.allEntries.keys
    check(registry.allEntries.size == expectedTypeTags.size && missing.isEmpty()) {
        "SelectorSerializer registry is incomplete. Missing: $missing"
    }
}
```

The check compares the registry against `expectedTypeTags` — **a second hand-written
list of the same variants**.

`Selector.TimeOfDay` was added to the hierarchy and to `typeTag`, and to neither
list. The check still passed: 16 registry entries, 16 expected tags, nothing
missing. Both lists were wrong in the same way, and the check could not see it.

The failure it produced was total and silent. Serializing any agenda section that
used a work-day block threw:

```
java.util.NoSuchElementException: Key TimeOfDay is missing in the map.
```

`getValue("TimeOfDay")` on a map that never had the key. Saving the current agenda
as a saved view therefore never wrote anything, never navigated, and — because the
exception escaped a coroutine whose only handler was the crash reporting port —
never said why. The user saw a spinner that never stopped.

The same drift was visible elsewhere: `SelectorSerializerTest.typeTag returns
@SerialName value for all 14 variants` asserted 14 of 17.

## Decision

1. **A completeness check must compare two independent sources.** Where the source
   of truth is a declaration (`Selector.kt`) and the thing that must follow it is a
   registry (`SelectorSerializer.kt`), the check compares those two files — it does
   not compare the registry with a third list.

   `SelectorSerializerCoverageTest` (arch test) reads `Selector.kt`, collects the
   `@SerialName` of every variant, reads the registry's keys, and asserts both
   directions: nothing declared without an entry, nothing registered that does not
   exist. A stale entry is as much a defect as a missing one — it hides a renamed
   or removed variant.

2. **The scan is line-based, not a single regex.** The declarations are regular —
   `@SerialName("X")` on one line, the declaration on the next — and a regex that
   tries to span the two has to survive KDoc, annotations and default values in
   between. The first version of the scan matched **one variant out of seventeen**
   and would have passed vacuously. The scan therefore asserts that it found a
   plausible number of variants before asserting anything about them.

3. **A hand-written list in a test is a coverage claim, not a proof.** The
   round-trip test in `SelectorSerializerTest` now enumerates all 17 variants. It
   is a second line of defence, not the guard itself: the guard is the one that
   reads the hierarchy.

4. **The runtime failure mode must be loud.** A missing registry entry throws
   `NoSuchElementException` from `getValue` — an exception whose message names a
   map key, not a domain concept. Where a registry is the thing being read, the
   error should name the variant. (`getValue` was left as-is here; the ADR records
   the class of defect rather than a rewrite of the registry's lookups.)

## Consequences

- **Positive:** adding a `Selector` variant without registering it now fails an
  arch test, at build time, naming the variant.
- **Positive:** the same defect class in other hand-written registries is worth
  auditing — a registry compared only against a sibling list has the same blind
  spot.
- **Negative:** the arch test reads source files, so it is coupled to the shape of
  the declarations. A change in how variants are written (a different annotation
  layout) breaks the scan — loudly, since the scan asserts it found a plausible
  number of variants.
- **Alternative explored — a runtime check over a hand-written list of instances:**
  this is the check that already failed, and it fails in the same way.

## Links

- `shared/src/commonMain/kotlin/com/singularity/todo/feature/agenda/domain/model/SelectorSerializer.kt` — the registry
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/agenda/domain/model/Selector.kt` — the hierarchy
- `shared/src/jvmTest/kotlin/com/singularity/todo/arch/SelectorSerializerCoverageTest.kt` — the guard
- `shared/src/commonTest/kotlin/com/singularity/todo/feature/agenda/domain/SelectorSerializerTest.kt` — round-trip
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/agenda/domain/selector/package-info.kt` — the checklist for adding a variant
- `docs/decisions/2026-10-19-catchto-is-fire-and-forget.md` — why the failure stayed invisible
