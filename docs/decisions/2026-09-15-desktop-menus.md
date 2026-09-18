---
title: "Desktop context menu + window MenuBar via generic MenuNode sealed class"
date: 2026-09-15
tags: [desktop, ui, menu, navigation]
status: accepted
---

## Context

Desktop JVM-only chrome needed two features:
1. **Context menu** — right-click on a task list item with 28 items matching TickTick reference,
   dark theme, nested submenus, hover delay.
2. **Window MenuBar** — File / Edit / View / Help top-level menu with minimal items
   and Ctrl+N / Ctrl+, / Ctrl+Q shortcuts.

Both are desktop-only (JVM). They share a common menu tree representation but have
different renderers: `Popup`-based context menu vs `MenuBar`-based application menu.

## Idea

Introduce a single generic `MenuNode` sealed class in `commonMain` that models any menu tree.
A `MenuNodesBuilder` DSL (with `@DslMarker MenuDsl`) builds immutable `List<MenuNode>`.
Platform-specific renderers in `jvmMain`:
- `ContextMenuHost` — wraps a `Popup` with `MenuPanel` for the context menu
- `MenuBarHost` — wraps Material 2 `MenuBar` for the application menu (currently a stub)

Feature-specific code builds the concrete menu in `commonMain` (pure Kotlin, no Compose):
- `TaskMenuActions` data class — callbacks for wired actions
- `buildTaskContextMenu(taskUi, hasAiContext, actions)` — 28-item TickTick menu

Secondary click detection uses `Modifier.onSecondaryClick` via expect/actual:
- `commonMain/SecondaryClick.kt` — expect declaration
- `jvmMain/SecondaryClick.jvm.kt` — actual using `onPointerEvent(PointerEventType.Press)`
- `androidMain/SecondaryClick.android.kt` — no-op actual

## Decision

1. `MenuNode.kt` in `commonMain/core/ui/menu/` — sealed class with `Action`, `SubMenu`, `Divider`.
2. `MenuNodesBuilder.kt` — `@DslMarker MenuDsl`, `buildMenuNodes { ... }` entry point.
3. `SecondaryClick.kt` (expect/actual) — `Modifier.onSecondaryClick(onClick: (DpOffset) -> Unit)`.
4. `ContextMenu.kt` — generic `ContextMenuHost(openState, onDismiss, entries)` in `jvmMain`.
5. `MenuBar.kt` — generic `MenuBarHost(entries)` in `jvmMain` (stub until Material 2 lands).
6. `TaskMenuActions.kt` + `TaskMenuBuilder.kt` in `commonMain/feature/tasks/presentation/contextmenu/`.
7. State (open menu, showAbout) lives in the Composable layer, not the VM.

## Rationale

**Generic vs feature-specific**: A single `MenuNode` sealed class means renderers (`ContextMenuHost`,
`MenuBarHost`) are written once and reused. Feature code only builds the tree.

**DSL with `@DslMarker`**: Prevents accidental shadowing of builder methods by outer receivers.
Precedent: `BackupDsl`, `AgendaDslMarker`.

**pure Kotlin builder**: `buildMenuNodes { ... }` returns `List<MenuNode>` — immutable, testable,
serializable. The renderer is `@Composable` but the builder is not.

**expect/actual for secondary click**: Right-click detection requires platform-specific pointer APIs.
The expect/actual bridges this cleanly. Android gets a no-op; JVM gets real detection.

**State in Composable**: `showAbout` and `contextMenuOpenState` are transient UI state.
Per the `ui-event-vs-state` skill, they belong in the Composable layer, not the VM or NavGraph.

## Consequences

- `compose-material:material = 1.12.0` added to `libs.versions.toml` and `desktopApp/build.gradle.kts`
  for MenuBar dependency.
- `ContextMenuOpenState` data class in `jvmMain/core/ui/menu/` holds the screen `DpOffset`.
- `onSecondaryClick` is a no-op on Android; touch long-press is handled separately by the caller.
- `MenuBarHost` is a stub (Material 2 not available in current Compose version).
- 23 of 28 context menu items are wired to `actions.onDismiss()` — future iterations wire the
  remaining actions to use cases.
- `openGitHub()` uses `java.awt.Desktop.browse(URI(...))`; `exitProcess(0)` for quit.
- `DesktopShellNav3.kt` owns `showAbout` state and `menuEntries` — natural location since
  it already owns the window-level scaffold.
- Hover delay (300ms) on submenus via `LaunchedEffect(isHovered) { delay(300); onOpenSubMenu() }`.

## Links

- `shared/src/commonMain/.../core/ui/menu/MenuNode.kt`
- `shared/src/commonMain/.../core/ui/menu/MenuNodesBuilder.kt`
- `shared/src/commonMain/.../core/ui/menu/SecondaryClick.kt`
- `shared/src/jvmMain/.../core/ui/menu/SecondaryClick.jvm.kt`
- `shared/src/androidMain/.../core/ui/menu/SecondaryClick.android.kt`
- `shared/src/jvmMain/.../core/ui/menu/ContextMenu.kt`
- `shared/src/jvmMain/.../core/ui/menu/MenuBar.kt`
- `shared/src/commonMain/.../feature/tasks/presentation/contextmenu/TaskMenuActions.kt`
- `shared/src/commonMain/.../feature/tasks/presentation/contextmenu/TaskMenuBuilder.kt`
- `shared/src/jvmMain/.../shell/DesktopShellNav3.kt`
- `shared/src/commonMain/.../feature/agenda/presentation/screen/AgendaContent.kt`
- `shared/src/jvmMain/.../feature/agenda/presentation/nav/AgendaNavGraph.jvm.kt`
