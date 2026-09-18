---
title: "SelectorSerializer — Map-based registry + typeTag extension"
date: 2026-09-18
tags: [agenda, serialization, dsl]
status: accepted
---

## Context

`SelectorSerializer` had two near-identical `when` expressions — one in `serialize()` and one in the private `serializeToElement()` helper — covering all 14 Selector variants. Adding a new Selector variant required 5 changes spread across 3 functions (serialize, serializeToElement, deserialize). The serializer also lived inside `Selector.kt` (400+ lines) rather than in its own file. `@DslMarker` was applied to the top-level `selector()` function which has no effect (KT-81567).

## Decision

1. Extracted `SelectorSerializer` into its own file (`SelectorSerializer.kt`). `Selector.kt` now contains only the sealed interface and data classes.

2. Added `Selector.typeTag: String` extension property — a 14-case `when` expression that returns the variant's `@SerialName` value. No reflection, works on all KMP targets.

3. Replaced the dual `when`-expression pattern with a `Map<String, SelectorEntry>` registry. `SelectorEntry` pairs encode and decode logic for one variant.

4. Two-phase construction: `buildLeafEntries()` first (returns `Map`), then `buildCompositeEntries()` which references `lateinit var registrySnapshot` — a `var` that is assigned in `init {}` to `allEntries`. This avoids forward-reference errors (val not initialized when lambda captures) and closure-capture issues (lambdas referencing a map built with `buildMap { }` that resolves to the `MutableMap` receiver instead of the property).

5. Added `init { check(allEntries.size == 14) }` to catch missing entries at construction time.

6. Removed the `@DslMarker` annotation from `selector()` (KT-81567: `@DslMarker` on top-level functions has no effect).

## Rationale

The Map registry reduces shotgun surgery from 5 to 2 changes per new variant. The `typeTag` property makes the registry keys explicit rather than duplicated string literals. The two-phase construction with `lateinit var` is the cleanest workaround for Kotlin's val initialization order with respect to lambda captures — it was the most reliable pattern across all alternatives attempted.

## Consequences

- Adding a new Selector variant: add `@SerialName` annotation + one `put()` in the registry (2 changes).
- Composite selectors (`AllOf`, `AnyOf`, `Not`) encode their children via `registrySnapshot.getValue(child.typeTag).encode(child)` — works for any nesting depth.
- `SelectorSerializer` is now in its own file, improving build isolation.
- The `init` assertion catches missing entries at class load time with a clear message.

## Links

- `feature/agenda/domain/model/SelectorSerializer.kt` (new)
- `feature/agenda/domain/model/Selector.kt` (stripped of serializer, removed unused imports)
- `commonTest/.../SelectorSerializerTest.kt` (expanded: 5 → 19 tests, all 14 variants + nesting)
