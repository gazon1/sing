---
title: "Docs cleanup findings — TagsUseCase, dead CreateTagInput, and custom detekt rules"
date: 2026-09-24
tags: [cleanup, tags, detekt, testing]
---

## Context

During the phase-1 docs cleanup (MR 1–5), several codebase findings were uncovered that require architectural decisions or cleanup work beyond the original MR scope.

---

## Decision

### 1. TagsUseCase was correctly identified as pass-through and deleted

The `TagsUseCase.kt` file (containing `CreateTagUseCase` and `UpdateTagUseCase`) was deleted as a pass-through use case. Investigation confirmed:

- **No UI layer ever called these use cases.** `TagsViewModel` has never had a `create` method — TagsScreen is a read-only list (delete-only).
- **AI layer uses `CreateTagTool`** which calls `TagsRepository` directly with `clock.now()` and `TagId.generate()` — the same logic that existed in `CreateTagUseCase`.
- **No regression**: tag creation was always AI-only; there was never a manual "create tag" button in the UI.
- **The `CreateTagInput` data class in `Ids.kt`** (line 60) was dead code — no source file imports it. It was cleaned up in the same MR.

### 2. Custom detekt rules are correctly configured via detektPlugins

The `KDocOnContractRule` and `PassThroughUseCaseRule` are registered via:

```kotlin
// shared/build.gradle.kts
detektPlugins(project(":detekt-rules"))
```

This puts `:detekt-rules` on the detekt classpath. The ServiceLoader mechanism (`META-INF/services/dev.detekt.api.RuleSetProvider`) in the built JAR correctly lists all 5 rule providers. Unit tests verify rule correctness independently.

The earlier observation that "KDocOnContractRule didn't fire in gradle" was due to an OOM daemon crash during the gradle run — not a configuration failure. The `detektPlugins` approach is the standard and correct pattern.

### 3. `SavedAgendaViewModelTest` flakiness is pre-existing

The `UncompletedCoroutinesError` failures in `SavedAgendaViewModelTest` are pre-existing flakiness caused by OOM during CI runs, not by any code change. The test pattern itself (`runTest + advanceUntilIdle`, `testScope`, `= runTest` on test functions) is correct and follows the canonical testable-VM pattern documented in `singularity-todo-testable-vm`.

### 4. `CreateTagInput` in Ids.kt was dead code

`CreateTagInput(val name: String, val color: Int, val userId: String)` in `feature/tags/Ids.kt` (line 60) had no importers. The AI tool's `CreateTagTool.CreateTagInput` in `feature/ai/tools/CreateTagTool.kt` is the active version (with `colorHex` support and `userId` resolved from `ProfileAwareCurrentUser`).

---

## Consequences

- Tags can only be created via AI (`CreateTagTool`). If manual tag creation is needed in the future, add `create(name, color)` to `TagsViewModel` calling `TagsRepository.create()` directly — no use case needed.
- The `PassThroughUseCaseRule` will flag any new pass-through use cases added to the codebase.
- All 5 custom detekt rules are active in gradle via `detektPlugins(:detekt-rules)`.
- The dead `CreateTagInput` in `Ids.kt` is removed. The doc comment in `ColorInput.kt` was updated to reference `CreateTagTool.CreateTagInput` instead.

---

## Links

- MR 4 commit: `966d79f7 refactor(mr4): remove TagsUseCase pass-through + TODO cleanup`
- `PassThroughUseCaseRule.kt` — the rule that caught `TagsUseCase`
- `ColorInput.kt:12` — doc comment updated to remove dead reference
- `shared/build.gradle.kts:294` — `detektPlugins(project(":detekt-rules"))`
