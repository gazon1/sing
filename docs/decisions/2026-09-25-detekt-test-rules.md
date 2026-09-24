---
title: Detekt Rules for Tests — NoRealDelay, NoViewModelScope
status: accepted
date: 2026-09-25
authors: ZCode Agent
deciders: Singularity Developer
tags: [detekt, testing, lint, epic2]
epic: refactor/test-suite-acceleration
---

# Detekt Rules for Tests

## Context

Two custom detekt rules were added in `detekt-rules/` module (loaded via `META-INF/services/dev.detekt.api.RuleSetProvider` ServiceLoader) to enforce test standards at lint time.

Both rules are **warning severity only** on day 1. Promotion to error requires baseline stabilization (separate PR).

## Rule 1: `NoRealDelayInTestRule`

**RuleSet:** `no-real-delay-in-test`  
**File:** `detekt-rules/src/main/kotlin/com/singularity/todo/detekt/NoRealDelayInTestRule.kt`

Bans `kotlinx.coroutines.delay(N)` where `N > 1` in test sources.

```kotlin
// VIOLATED (N=100 > 1)
delay(100)

// ALLOWED
delay(0)
delay(1)
// detekt:allow-real-time   ← suppresses the warning for that line
```

The rule walks `KtCallExpression` AST nodes. Exempt:
- `delay(0)` and `delay(1)` (too short to matter)
- Lines with `// detekt:allow-real-time` comment
- Non-test source sets (detekt path filters handle this)

## Rule 2: `NoViewModelScopeInProductionRule`

**RuleSet:** `no-viewmodel-scope`  
**File:** `detekt-rules/src/main/kotlin/com/singularity/todo/detekt/NoViewModelScopeInProductionRule.kt`

Bans `viewModelScope.launch`, `viewModelScope.async`, `viewModelScope.cancel` in production code.

```kotlin
// VIOLATED
viewModelScope.launch { ... }

// REQUIRED
scope.launch { ... }   // scope is a constructor parameter
```

**Rationale:** `viewModelScope` is untestable without `Dispatchers.setMain`. The canonical pattern injects a `CoroutineScope` as a constructor parameter.

Allowed methods (not flagged): `toString`, `hashCode`, `equals`, `coroutineContext`.

## Implementation Notes

Rules are registered via `META-INF/services/dev.detekt.api.RuleSetProvider`:

```
com.singularity.todo.detekt.NoRunBlockingProvider
com.singularity.todo.detekt.NoViewModelScopeInProductionProvider
com.singularity.todo.detekt.NoRealDelayInTestRuleProvider
```

The `detekt-rules` module uses `kotlin.jvmToolchain(17)` and `detekt-api 2.0.0-alpha.3` (same version as the project's detekt plugin).

## Consequences

- `NoRealDelayInTestRule` fires on all 44 pre-existing `delay(N>1)` occurrences
- `NoViewModelScopeInProductionRule` fires on 7 pre-existing `viewModelScope.launch` occurrences
- Both rules are in **warning mode** — they do not fail the build
- Promotion to error: after baseline is reduced in a follow-up PR

## Links

- Skill: `singularity-todo-quality-tools`
- Skill: `singularity-todo-detekt-workflow`
- ADR: `2026-09-25-test-standards-comprehensive`
