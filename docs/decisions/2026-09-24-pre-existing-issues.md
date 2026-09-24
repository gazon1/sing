---
status: accepted
date: 2026-09-24
tags: [techdebt, testing, di, epic1]
epic: refactor/techdebt-epic1
---

# Pre-existing Issues Found During Tech Debt Audit

## Context

В ходе работы над Epic 1 рефакторинга (`refactor/techdebt-epic1` worktree) были обнаружены проблемы, которые **не были внесены** изменениями PR 1.1–1.4, а **существовали до начала рефакторинга** (подтверждено сравнением с чистым HEAD `952db1c`).

Также обнаружена проблема в main checkout, не связанная с worktree.

## Pre-existing: DiGraphTest failures

**Файлы:** `shared/src/jvmTest/kotlin/com/singularity/todo/core/di/DiGraphTest.kt`

**Проблема:** 2 теста падают с `KoinInstantiationError` при старте Koin DI graph:

```
~ ChecklistRepository — could not instantiate:
  java.lang.NoSuchMethodError: ...
~ AttachmentSaver — could not instantiate:
  java.lang.IllegalArgumentException: Parameter count mismatch
```

**Доказательство:** Те же тесты падают на чистом `952db1c` (HEAD main на момент начала работы), без каких-либо изменений из worktree.

**Влияние:** ~2 failing tests из ~964 total. Не является critical blocker для merge PR 1.1–1.4.

**Действие:** Записано как pre-existing. Решение отложено — требует отдельного investigation (вероятно, рассинхронизация zwischen Room schema и DAO конструкторов, или несовместимость `ChecklistRepositoryImpl` с Koin).

---

## Pre-existing: SavedAgendaViewModelTest failures

**Файлы:** `shared/src/jvmTest/kotlin/com/singularity/todo/feature/agenda/presentation/viewmodel/SavedAgendaViewModelTest.kt`

**Проблема:** 3 теста падают с `UncompletedCoroutinesError`:

```
~ test[RestoreEmptyDraft] — Test failed: Uncompleted coroutines in test
~ test[RestoreNonEmptyDraft] — Test failed: Uncompleted coroutines in test
~ test[DiscardAndRestore] — Test failed: Uncompleted coroutines in test
```

**Корень причина:** Fire-and-forget `scope.launch { emitEditingState() }` в `onIntent` — `advanceUntilIdle()` завершается до того как корутина доэмитит state.

**Доказательство:** Падают на чистом HEAD. Не были исправлены в PR 1.1 (требует нетривиального подхода — либо `repeatOnLifecycle` миграция, либо `runCurrent()` после каждого intent).

**Влияние:** 3 failing tests. Deferred до Epic 2 PR 2.3 (VM scope & lifecycle hardening), где запланирована `repeatOnLifecycle` миграция.

**Действие:** Записано как pre-existing deferred issue. Ref: Epic 2, PR 2.3.

---

## Main checkout: uncompilable state

**Локация:** `/home/max/AndroidStudioProjects/singularity_cllone_kmp` (main checkout, НЕ worktree)

**Проблема:** `DependencyValidatorImpl.kt` (untracked файл) импортирует `core.error.Result`, который не существует. Также за-stashed изменения в `Daos.kt`, `Mappers.kt`, `TasksDiModule.kt`.

```
shared/src/commonMain/.../core/error/AppError.kt:29: error:
  Unresolved reference 'Result'
```

**Причина:** Возможно, внесённые изменения в рамках параллельной работы в main checkout, которые не были закончены.

**Действие:** Требуется отдельное внимание — либо до-completing the untracked file, либо discard stash. Не связано с tech debt worktree.

---

## Status

| Issue | Pre-existing? | Blocker? | Action |
|---|---|---|---|
| DiGraphTest × 2 | ✅ Yes | No | Deferred to feature PR |
| SavedAgendaViewModelTest × 3 | ✅ Yes | No | Deferred to Epic 2 PR 2.3 |
| Main checkout uncompilable | N/A | Yes | Resolve separately |

---

## Links

- Epic 1 PRs: `refactor/techdebt-epic1` (commits b61b753 → 7ff1ae7)
- Epic 2 PR 2.3: VM scope & lifecycle hardening
- `docs/decisions/2026-09-24-pr1-tech-debt-audit-resolution.md` — Resolved issues from same audit
