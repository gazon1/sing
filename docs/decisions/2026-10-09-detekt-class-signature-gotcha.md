---
title: "detekt `ClassSignature` Rule Gotcha for Data Classes"
date: 2026-10-09
status: accepted
---

# ADR: detekt `ClassSignature` Rule Gotcha for Data Classes

When adding `SelectorTemplate.ByDateRange` as a `data class` with multiple parameters with default values, the initial implementation split parameters across lines:

```kotlin
// VIOLATES ClassSignature rule — triggers 3 errors
data class ByDateRange(
    val from: kotlinx.datetime.LocalDate? = null,
    val to: kotlinx.datetime.LocalDate? = null,
) : SelectorTemplate {
```

The `ClassSignature` rule (custom, in `detekt-rules-module.yml`) requires:
- No whitespace between opening parenthesis and first parameter name
- Single whitespace before parameter
- No whitespace between last parameter and closing parenthesis

## Decision

All data classes with parameters should keep parameters on the SAME LINE as the opening parenthesis:

```kotlin
// CORRECT
data class ByDateRange(val from: kotlinx.datetime.LocalDate? = null, val to: kotlinx.datetime.LocalDate? = null) :
    SelectorTemplate {

data class ByTags(val matchAll: Boolean = false) : SelectorTemplate {
```

If parameters exceed the line length limit, wrap them into a secondary constructor or use `@Suppress` — do NOT split parameter list across lines inside the primary constructor declaration.

## Why This Matters

The `ClassSignature` rule enforces the style used by the rest of the codebase. Splitting parameters triggers 3 errors per class, making PRs noisy and blocking CI.

## Affected Patterns

This affects any `data class` with:
- 2+ parameters, AND
- At least one parameter with a default value, AND
- Parameters that don't fit on one line

Workaround: use a secondary constructor for the defaults, or refactor to a simpler data class and use a factory function.
