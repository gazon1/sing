---
title: "DI module split: one monolith → 7 feature modules"
date: 2026-09-06
tags: [di, koin, architecture]
---

## Context

`Modules.kt` разросся до 427 строк, 6 логических групп в одном файле. Добавление новой фичи требовало навигации по длинному файлу; ревью и мёрж-конфликты затрагивали весь DI сразу.

## Idea

Разбить `Modules.kt` на feature-based модули, каждый из которых отвечает за одну область:

- `TasksDiModule` — Tasks + Archive + Checklist + Pomodoro + Search + Reminders
- `ProjectsDiModule` — Projects repositories + use cases + VMs
- `NotesDiModule` — Notes repositories + use cases + VMs
- `TagsDiModule` — Tags repositories + use cases + VMs
- `CoreDiModule` — Auth, sync, attachments, backup, settings, IDs
- `AiToolsDiModule` — AI tools, GenUI, AI use cases, AI VMs
- `Modules.kt` — `coreLoggingModule()`, `domainModule()` (includes all), `expect fun aiToolsModule()`

## Decision

Реализовано exactly as described above. `domainModule()` использует `includes()` для подключения всех feature-модулей.

**Ключевые архитектурные решения:**

1. `aiToolsCoreModule()` — internal функция в `AiToolsDiModule.kt`; platform-модули (`AiToolsModule.jvm.kt`, `AiToolsModule.android.kt`) вызывают `includes(aiToolsCoreModule())` и добавляют платформенные bindings.

2. Feature-модули **не экспортируют** AI-специфичные bindings наружу (TasksDiModule не знает про AI tools). TasksViewModel с nullable AI deps зарегистрирован в `AiToolsDiModule`, а не в `TasksDiModule`.

3. `TasksViewModel` зарегистрирован в `aiToolsCoreModule()` с явными nullable dep via `getOrNull()` (ClassCastException workaround, documented in `2026-09-06-koin-vm-viewmodelof-koinviewmodel.md`).

4. `Single<BackupRepository>` в `CoreDiModule` — `FakeBackupRepository` (для graph verification). Реальная имплементация подменяется в `desktopApp` DI.

## Rationale

- Feature-модули автономны: добавление Tasks-зависимости = один файл.
- Реducer в merge-конфликтах: изменения AI не трогают TasksDiModule.
- Тестируемость: каждый модуль можно проверить отдельно через `DiGraphTest`.

## Consequences

- **Breaking:** `coreDomainModule()` удалён; заменён на `domainModule()` (includes everything). Test files обновлены.
- **New file count:** 8 новых файлов (7 модулей + decision).
- **Existing tests:** `DiGraphTest`, `JvmAiDiGraphTest`, `AppSmokeTest` обновлены и проходят.
