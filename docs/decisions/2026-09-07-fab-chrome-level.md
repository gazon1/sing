---
title: "FAB at chrome level — single source of truth in shells"
date: 2026-09-07
tags: [ui, navigation, architecture]
status: accepted
---

## Context

Каждый экран (`TasksScreen`, `ProjectsScreen`, `NotesScreen`) имел собственный `floatingActionButton` в `Scaffold`. Это приводило к дублированию логики: FAB弹窗 отображался на каждом экране, но не было единого места, где определялось, какое действие должен выполнять FAB. При push-роутах (редакторы) FAB оставался видимым, хотя не должен был.

## Decision

FAB определён **на уровне chrome** (`AndroidShell` / `DesktopShell`) как `fabActionFor(current, navigator): FabAction?`. Screens больше не имеют локального FAB — только `AppShell.ModalShell` / `AppShell.PermanentShell` показывают `ExtendedFloatingActionButton` если `fabAction != null`.

**Удалены FAB из:**
- `TasksScreen` — параметр `onNavigateToCreateTask`, FAB удалён
- `ProjectsScreen` — параметр `onNavigateToCreateProject`, FAB удалён
- `NotesScreen` — параметр `onNavigateToCreateNote`, FAB удалён
- `TagsScreen` — параметр `onNavigateToCreateTag`, FAB удалён (экран не подключён к навигации)

**Добавлен `FabAction` в `AppShell`:**
```kotlin
data class FabAction(val label: String, val onClick: () -> Unit)
```
Оба shell-а (`ModalShell`, `PermanentShell`) принимают `fabAction: FabAction?` и показывают `ExtendedFloatingActionButton`.

**Shell-логика:**
- Android: `fabActionFor(current, navigator)` в `AndroidShell`
- Desktop: `fabActionForDesktop(current, navigator)` в `DesktopShell`
- `AppDestination.Notes` → FAB "Add note" → `NoteEditor()`
- `AppDestination.Plans` → FAB "Add project" → `ProjectEditor()`
- `AppDestination.Inbox/Today` → FAB "Add task" → `TaskEditor()`
- Остальные — `null` (FAB скрыт)

## Rationale

- **Single source of truth**: какое действие показывать — определяется в одном месте (shell), а не дублируется в каждом screen.
- **Правильное скрытие при push-роутах**: когда открывается редактор (sub-route), chrome-level FAB скрыт, потому что `current` остаётся tab destination, а sub-route не меняет top-level.
- **Упрощение screens**: screen — только UI, навигационные действия вынесены наверх.

## Consequences

- `AppDestination` пополнился `Notes` (уже был), логика FAB его задействует.
- `AppShell` — minor change: добавлен `FabAction` parameter.
- `TagsScreen` больше не принимает callback — экран не подключён к навигации (menu destination `Tags` отсутствует в `AppDestination`).

## Links

- Commits: `fix(settings)`, `feat(settings)`, `feat(backup)`, `fix(fab)`
- Files: `AppShell.kt`, `AndroidShell.kt`, `DesktopShell.kt`, `TasksScreen.kt`, `ProjectsScreen.kt`, `NotesScreen.kt`, `TagsScreen.kt`
