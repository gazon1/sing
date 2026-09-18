---
title: "Eliminate pass-through UseCases + machine enforcement via custom detekt rule"
date: 2026-09-18
tags: [usecase, detekt, architecture, lint]
status: accepted
---

## Context

**Habr article** (`Проблема UseCase-ов`, clint_eastwood, Sep 2024, https://habr.com/ru/articles/845604/) — argues UseCases are tools, not rules. Applying them dogmatically produces bloated code with layers of abstraction that serve no purpose. The article proposes a three-question rubric before creating a UseCase: (1) Is there business logic to separate? (2) Will it be reused? (3) Does it need independent testing? If all three are "no" → skip the UseCase. The article provides no tooling.

**Project audit (2026-09-18):** 9 source files, 16 declared use-case classes. Classified per the AGENTS.md rule ("only real logic — validation, clock.now(), build"):

| File | Classes | Verdict |
|---|---|---|
| `CreateTask.kt`, `UpdateTask.kt`, `CreateProject.kt`, `UpdateProject.kt`, `DeleteProject.kt` | 5 | **REAL** — validation, clock-stamping, cross-repo composition |
| `TaskMutations.kt` | 1 | **REAL** — atomicity guard via fail-fast `exists()` check before bulk mutations |
| `SearchUseCase.kt` | 1 | **REAL** — fan-out across 4 repositories |
| `TagsUseCase.kt` | 2 | **REAL** — `CreateTagUseCase` (require validation + TagId-gen); `UpdateTagUseCase` (clock-stamp, consistent with project pattern) |
| `LlmUseCase.kt` | 12 | **REAL** — typed facade over Koog `SimpleTool`; prevents Koog types from leaking into presentation layer |
| `ChecklistUseCase.kt` | 1 | **PARTIAL** — 3 real methods + **3 pass-through methods** |

Only `ChecklistUseCase.kt` violated the rule. Three pass-through methods:

| Method | Body | Called by |
|---|---|---|
| `watchChecklist(taskId)` | `repository.watchByTask(taskId)` | `ChecklistEditorViewModel`, `TaskDetailViewModel` |
| `deleteItem(id)` | `repository.delete(id)` | `ChecklistEditorViewModel`, `TaskDetailViewModel` |
| `createBatch(taskId, items)` | `repository.createBatch(taskId, items)` | **0 callers — dead code** |

The `createBatch` method is also dead code in the repository (`RoomChecklistRepository.createBatch` has real logic — clock-stamping + sortOrder — but is never called externally).

**Open follow-up already in decision log:** `docs/decisions/2026-09-05-ui-decomposition.md:105–109` noted that `DeleteTaskUseCase`, `ToggleTaskUseCase`, `TogglePinUseCase` were pass-throughs requiring consolidation. Those files were already removed in R10 (per `ARCHITECTURE.md:418`). The issue recurs when developers copy the anti-pattern — enforcement is needed.

## Idea

Four options for prevention:

1. **Custom detekt rule** — AST-walks `*UseCase` classes, flags single-expression delegation to a `*Repository` field. Runs locally via `just lint`. Zero CI infra needed today.
2. **Standalone Gradle task** — regex or kotlin-compiler embedding scan. Faster to write but less precise (false positives on multi-line), separate from `just lint`.
3. **Documented checklist only** — strengthen AGENTS.md prose. Lowest effort, but leaves the rule human-enforced; the same gap that let the violation slip through.
4. **All of the above** — detekt rule + AGENTS.md checklist + CI workflow. Most thorough but requires CI infra (`.github/workflows/`) which does not exist today.

## Decision

### Refactor: trim `ChecklistUseCase`

**Remove** `watchChecklist`, `deleteItem`, `createBatch` from `ChecklistUseCase.kt`. They are pure delegations with zero added logic.

**Keep** `addItem` (ID generation + defaults) and both `toggleItem` overloads (entity reconstruction / read-then-update with 404). These have real domain logic.

**Remove `Clock` parameter** from `ChecklistUseCase` — no remaining method uses `clock.now()`.

**Consumers updated:**
- `ChecklistEditorViewModel` — now takes `ChecklistRepository` alongside `ChecklistUseCase`; calls `repository.watchByTask()` and `repository.delete()` directly; `addItem`/`toggleItem` stay on the use case.
- `TaskDetailViewModel` (via `TaskDetailDeps`) — add `checklistRepository: ChecklistRepository` field; `watchByTask` and `delete` go to the repo; `addItem`/`toggleItem` go to the use case.
- `TasksDiModule.kt` — `factory { ChecklistUseCase(get()) }` (Clock removed); `ChecklistEditorViewModel` receives two constructor args; `TaskDetailDeps` receives the new repo field.
- `TaskLifecycleIntegrationTest.kt` — constructor updated; `watchChecklist` → `checklistRepo.watchByTask()`; `deleteItem` → `checklistRepo.delete()`.

### Prevention: `:detekt-rules` module with `PassThroughUseCaseRule`

**New Gradle module** `:detekt-rules` (`detekt-rules/build.gradle.kts`, `src/main/kotlin/.../PassThroughUseCaseProvider.kt`, `PassThroughUseCaseRule.kt`).

**Heuristic** (PSI-only, no `BindingContext`):

A method in a `*UseCase` class is flagged when ALL of:
- Class name ends with `UseCase` (excluding abstract `LlmUseCase<I,O>`)
- Function has an **expression body** (single-expression shortcut: `fun foo() = repo.bar()`)
- The call receiver is a property on the class whose declared type name ends in `Repository`
- The call receiver is **not** `clock` (clock-stamping is real domain logic)
- The call receiver is **not** `tool` (LLM use cases call Koog tools)

**Safe by construction** (rule does not fire on):
- `UpdateTagUseCase` — single `tag.copy(updatedAt = clock.now())`; body is `KtCallExpression` whose receiver `tag` is a `Tag`, not `*Repository`
- `CreateTaskUseCase` — `TaskDomain.buildTask(...)` + `repo.create(...)` inside a `KtBlockExpression` (not expression body)
- `SearchUseCase` — `combine(...)` call inside a `KtBlockExpression`
- All 11 LLM concrete use cases — receiver is `tool`
- All methods in test sources (detekt `excludes`)

**Registered via** `detektPlugins(project(":detekt-rules"))` in `:shared` and `:desktopApp`. No `detekt.yml` change needed — `buildUponDefaultConfig = true` auto-loads `RuleSetProvider`s.

**Suppression** when needed: `@Suppress("PassThroughUseCase")` on the function.

## Rationale

**Machines enforce what prose forgets.** The rule was documented in AGENTS.md (twice), ARCHITECTURE.md, and a decision log — yet a violation still slipped through. A detekt rule makes it self-enforcing.

**PSI-only heuristic is safe.** Expression body + single call + repository-named receiver is unambiguous. Legitimate use cases are either block-bodied or involve `clock`/`tool` receivers.

**The refactor is minimal.** Three pass-through methods removed, six files updated, zero changes to public contracts (only internal call-site routing). The `ChecklistUseCase` still earns its keep for mutations that require ID generation and entity construction.

## Consequences

- **`factory { ChecklistUseCase(get()) }`** in `TasksDiModule.kt` — Clock removed
- **`ChecklistEditorViewModel(checklistUseCase, checklistRepository, scope)`** — two deps
- **`TaskDetailDeps`** gains `checklistRepository: ChecklistRepository` field
- **`just lint`** now includes `PassThroughUseCase` checks for `:shared` and `:desktopApp`
- **False positives** can be suppressed per-function with `@Suppress("PassThroughUseCase")`
- **CI gate** (future): add `.github/workflows/ci.yml` with `just tcheck` as required status check

## Links

- AGENTS.md:29 (`*UseCase.kt — ТОЛЬКО реальная логика`)
- AGENTS.md:253 (❌ Что НЕ делать: pass-through use cases)
- ARCHITECTURE.md:126 (data-flow: UseCase — только реальная логика)
- ARCHITECTURE.md:418 (R10: удалены 16 pass-through use cases)
- `docs/decisions/2026-09-05-ui-decomposition.md:105–109` — open follow-up (resolved hereby)
- Habr article: https://habr.com/ru/articles/845604/
- `detekt-rules/src/main/kotlin/com/singularity/todo/detekt/PassThroughUseCaseRule.kt`
- `detekt-rules/src/main/kotlin/com/singularity/todo/detekt/PassThroughUseCaseProvider.kt`
