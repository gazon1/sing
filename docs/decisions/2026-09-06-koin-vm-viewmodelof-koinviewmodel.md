---
title: "ViewModel DI: viewModelOf + koinViewModel() instead of factory + koinInject()"
date: 2026-09-06
tags: [koin, di, vm]
status: accepted
---

## Context

Аудит DI (2026-09-06) выявил системные расхождения с best practices Koin 4.2.2:

- 15/17 ViewModel регистрировались через `factory {}` (создаёт новый инстанс при каждом `get()`), 2 через `viewModel {}` (корректно для VM с lifecycle scoping)
- 0 использований `viewModelOf(::VM)` — Koin 4.x constructor-reference overload, auto-resolve параметров
- Все 22 Composable инжектили VM через `koinInject()` — нет привязки к `ViewModelStoreOwner`, lifecycle не управляется Koin
- Skill `feature-scaffold` рекомендовал `koinViewModel()` в Composable, но реальный код использовал `koinInject()`
- Decision entries про `viewModelOf`/`koinViewModel` отсутствовали

## Idea

Варианты:
1. **Оставить `factory {}` + `koinInject()`** — текущая схема работает, но создаёт новый VM при каждом навигационном переходе; VM не привязан к lifecycle
2. **`viewModelOf(::VM)` + `koinViewModel()`** — современный Koin 4.x паттерн; VM scoped к `ViewModelStoreOwner`; constructor-reference убирает boilerplate
3. **Koin Annotations `@Single class VM`** — compile-time verification, но KSP processor не настроен; миграция значительна

## Decision

Использовать `viewModelOf(::VM)` для регистрации ViewModel в Koin-модулях (вместо `factory {}`) и `koinViewModel()` в Composable (вместо `koinInject()`).

**Исключение:** `TaskEditorViewModel` с runtime-параметром `initialDueDate` — оставить `viewModel { (p) -> VM(p, ...) }` + `koinViewModel { parametersOf(p) }`.

**Snapshot:**
- 14 VM → `viewModelOf(::VM)` (ChatViewModel, ArchiveViewModel, StatisticsViewModel, TagsViewModel, NotesViewModel, ProjectEditorViewModel, ProjectDetailViewModel, ChecklistEditorViewModel, SearchViewModel, SettingsViewModel, AttachmentsViewModel, AuthViewModel, BackupViewModel, TaskDetailViewModel)
- 2 VM → `viewModel { Vm(get(), ...) }` с явными deps (TasksViewModel, ProjectsViewModel) — `viewModelOf` с nullable dep + `getOrNull()` не работает корректно на JVM
- 1 VM → `viewModel { (initialDueDate) -> TaskEditorViewModel(...) }` (runtime-параметр)
- 22 Composable → `koinViewModel()` для VM-injection
- Репозитории, use-case'ы, порты — **не трогать** (`single {}` остаётся)
- `PomodoroTimer`, `AuthRepository`, `ProjectsRepository`, `TagsRepository` в Composable — остаются `koinInject()` (это не VM)

## Rationale

`factory {}` для ViewModel создаёт новый инстанс при каждом `get()` — это memory leak и нарушение lifecycle scoping. `viewModel {}` привязывает VM к `LocalViewModelStoreOwner` Compose, обеспечивая：正确ный lifecycle, state preservation при навигации.

`viewModelOf(::VM)` — concise constructor-reference, auto-resolves deps. Однако `viewModelOf` не работает корректно когда VM имеет nullable dep с default `null` и использует `getOrNull()` — на JVM это приводит к `ClassCastException`. Для таких VM используем `viewModel { Vm(get(), get(), ...) }` с явными typed `get<T>()`.

`koinInject()` для не-VM зависимостей остаётся корректным — эти объекты stateless или singleton.

## Consequences

- **`viewModelOf(::VM)` для VM без nullable dep** — предпочтительный паттерн
- **`viewModel { Vm(get(), get(), ...) }`** — для VM с nullable dep + getOrNull() (TasksViewModel, ProjectsViewModel)
- **`koinViewModel()` для VM в Composable** — `koinInject()` для VM антипаттерн
- **`koinInject()` для репозиториев/сервисов остаётся** — не VM
- **`TaskEditorViewModel` special case** — `viewModel { (initialDueDate) -> ... }` + `koinViewModel { parametersOf(initialDueDate) }`
- **@Preview и widget-тесты не затрагиваются** — все preview используют `*Content` helpers (stateless)
- **`singularity-todo-vm-koin-scoping` skill** — создан как single source of truth

## Links

- Commit: `feat(di): migrate ViewModel registration to viewModelOf + koinViewModel()`
- Modules.kt: все VM-регистрации
- 22 Composable-файла: заменён `koinInject()` → `koinViewModel()` для VM
- `singularity-todo-vm-koin-scoping` skill
- `singularity-todo-koin-di` skill (обновлён)
- `singularity-todo-feature-scaffold` skill (обновлён)
