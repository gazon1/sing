---
title: "Detekt custom rules — activate unregistered rule sets and clean up orphan rules"
date: 2026-09-26
status: accepted
tags: [detekt, quality, kotlin]
---

## Context

During a documentation audit (PR-0), the `detekt-rules/` module was found to have several custom rules that were **silently inactive**:

1. **`NoRunBlockingProvider`** — registered in ServiceLoader, but **no rule-set config** in `detekt.yml`
2. **`NoViewModelScopeInProductionProvider`** — same issue
3. **`NoStateInRuleProvider`** — defined in `NoStateInRule.kt` but **not registered in ServiceLoader** at all
4. **`NoStaticProfileAwareCurrentUserProvider`** — separate `.kt` file, not inner class; also unregistered
5. **`PassThroughUseCaseProvider`** — same as above

Additionally, two orphan rule files existed without any provider class:
- `NoCombineSideEffectRule.kt`
- `NoGlobalScopeLaunchRule.kt`

This meant that 5+ custom rules were compiled and loaded but **produced zero findings** despite being correct.

## Decision

1. **Register all existing providers in `detekt.yml`** under their canonical rule-set IDs:
   - `no-runblocking` → `NoRunBlocking`
   - `no-viewmodel-scope` → `NoViewModelScopeInProduction`
   - `no-state-in` → `NoStateIn`
   - `no-static-profile-aware-current-user` → `NoStaticProfileAwareCurrentUser`
   - `pass-through-use-case` → `PassThroughUseCase`

2. **Add `NoStateInRuleProvider` to `META-INF/services/dev.detekt.api.RuleSetProvider`** — it was defined but missing from ServiceLoader.

3. **Delete orphan rules** `NoCombineSideEffectRule.kt` and `NoGlobalScopeLaunchRule.kt` — no provider, no registration, no test coverage.

4. **Migrate `PassThroughUseCaseProvider` and `NoStaticProfileAwareCurrentUserProvider` to inner-class pattern** (rule + provider in same file) per `singularity-todo-detekt-rules-authoring` skill convention.

## Rationale

Custom rules in this project use the **inner-class pattern**: the `RuleSetProvider` is an inner class inside the same `.kt` file as the rule. This keeps rule and registration in sync. The two separate-provider files were legacy that predated the convention.

The orphan rules had no tests, no registration, and no maintenance — deleting them reduces noise.

## Consequences

- All 7 custom rule sets now produce findings when violations exist
- `NoRunBlocking`, `NoViewModelScopeInProduction`, `NoStateIn`, `NoStaticProfileAwareCurrentUser`, `PassThroughUseCase` are active in `shared` and `desktopApp` modules
- `NoCombineSideEffectRule` and `NoGlobalScopeLaunchRule` are removed — if needed later, they must be rewritten with tests and proper registration

## Links

- `config/detekt/detekt.yml` — rule-set registration
- `META-INF/services/dev.detekt.api.RuleSetProvider` — ServiceLoader entries
- `detekt-rules/src/main/kotlin/com/singularity/todo/detekt/` — rule source files
- `singularity-todo-detekt-rules-authoring` skill — how to author new rules
