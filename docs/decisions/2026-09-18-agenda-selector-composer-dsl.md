---
title: "Agenda — selector composer DSL + universal section() overload"
date: 2026-09-18
tags: [agenda, dsl, selector]
---

## Context

The `AgendaDefinition` DSL had two problems: (1) `SectionScope.selector` used `lateinit var` which threw an opaque `UninitializedPropertyAccessException` when a section had no selector, and (2) composing selectors programmatically (AND/OR/NOT combinations) required imperative `Selector.AllOf(listOf(...))` constructor calls rather than a declarative block DSL. Both made preset authoring error-prone and the error messages unhelpful.

## Decision

1. Added `SelectorBuilder.kt` with a `@DslMarker`-annotated `SelectorScope` and public `selector { }` function. Combinators (`allOf`, `anyOf`, `not`) are methods that append to an internal `MutableList<Selector>`; `build()` unwraps single-child cases and errors on empty blocks.

2. Changed `SectionScope.selector` from `lateinit var` to `var selector: Selector? = null` and added a universal `section()` overload with an explicit `selector` parameter. The overload calls `checkNotNull(effectiveSelector)` with a clear message when neither the parameter nor the block-assigned selector is present.

3. Removed the private `fun agenda()` and `private class AgendaScope` from `AgendaPresets.kt`. All 7 presets were rewritten to use the public `section(name, selector, order, discard)` overload.

4. Removed the dead `Selector.within()` anti-pattern from `AgendaDefinition.kt` (lines 110–112, 0 usages).

## Rationale

`lateinit var` on a public property invites misuse; `var? = null` + `checkNotNull` gives a clear error at the right call site. The `selector { }` DSL mirrors the established `agenda { section { } }` pattern already used in presets. Removing the private DSL from `AgendaPresets` eliminates the parallel mechanism and forces all callers through the same contract, making it easier to evolve.

## Consequences

- `section("X") { }` without a selector now throws `IllegalStateException("Section 'X' has no selector — pass as parameter or assign inside block")` instead of `UninitializedPropertyAccessException`.
- All 7 presets now use the canonical public DSL path.
- Selector composition uses `selector { allOf(...); not(...) }` style instead of `Selector.AllOf(listOf(...))`.
- `SelectorBuilderTest` and `AgendaScopeSectionTest` added in `commonTest`.

## Links

- `feature/agenda/domain/selector/SelectorBuilder.kt` (new)
- `feature/agenda/domain/model/AgendaDefinition.kt` (changed)
- `feature/agenda/domain/logic/AgendaPresets.kt` (changed)
- `commonTest/.../SelectorBuilderTest.kt` (new)
- `commonTest/.../AgendaScopeSectionTest.kt` (new)
