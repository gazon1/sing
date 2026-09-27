---
title: KDoc enforcement rules
date: 2026-09-26
status: accepted
---

# KDoc enforcement rules

## Context

After the canonical VM pattern was established (PR-0.0), the codebase had no automated enforcement that ViewModels and Repository interfaces carry class-level KDoc. Documentation hygiene requires machine-checkable rules.

## Decision

1. **Two custom detekt rules** added via `KDocEnforcementRules.kt` in `:detekt-rules`:

   - `ViewModelMustHaveKDoc` — flags `*ViewModel` classes without a class-level KDoc block
   - `RepositoryInterfaceMustHaveKDoc` — flags `*Repository` interfaces without a class-level KDoc block

2. **Registration:**
   - Rule set `kdoc-enforcement` registered in `config/detekt/detekt.yml`
   - Inner-class `KDocEnforcementRulesProvider` in `KDocEnforcementRules.kt`
   - ServiceLoader entry in `META-INF/services/dev.detekt.api.RuleSetProvider`

3. **KDoc definition:** `docComment?.text` is non-null and non-blank (checked via PSI `docComment.text`)

4. **Activation:** Both rules active in `warningsAsErrors: true` mode. Baseline regenerated — 0 findings after suppression.

## Rationale

- Naming convention (`*ViewModel`, `*Repository`) is enforced by existing `FunctionNaming`/`ClassNaming` rules, so a naming-based scan is reliable.
- PSI `docComment` is the standard Kotlin PSI API for KDoc presence.
- Using file-level scan (`visitKtFile`) rather than class hierarchy scan — simpler and sufficient.
- These rules are **additive** to the existing `UndocumentedPublicClass`/`UndocumentedPublicFunction` (which remain `active: false` in this project).

## Consequences

- PR-0.3 adds KDoc to the 4 currently flagged ViewModels (AppVersionGateViewModel, TagGroupsViewModel) and 2 Repositories (SavedAgendaViewsRepository, ChecklistRepository).
- After PR-0.3, all production ViewModels and Repositories will have KDoc. New additions without KDoc will fail CI.
- `ViewModelMustHaveKDoc` skips abstract and inner classes (documented via outer class).
