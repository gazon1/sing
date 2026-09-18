---
title: "Desktop menus: MenuBar AWT, right-click fix, agenda wiring"
date: 2026-09-16
tags: [desktop, jvm, menu, bugfix]
status: accepted
---

## Context

Three bugs surfaced after the initial desktop menus implementation (2026-09-15):

1. **MenuBar invisible** — `MenuBarHost` in `MenuBar.kt` was an empty stub with no body; the layout in `DesktopShellNav3` placed it in its own `Column` sibling to `ModalNavigationDrawer`, not in the window chrome.
2. **Right-click broken** — `AgendaContent.kt` wrapped `SwipeableTaskRow` in `Box(modifier = rightClickModifier)`, but `SwipeableTaskRow` internally wraps content in `SwipeToDismissBox` which uses `pointerInput` and consumes pointer events, preventing right-click from reaching the outer modifier.
3. **Agenda context menu** — `desktopContextMenuHost` slot had all 5 `TaskMenuActions` as TODO stubs; only "complete" was wired in `AgendaViewModel`.

Additionally, two quality issues: `subMenu` required awkward `children = buildMenuNodes { }` syntax, and submenus appeared at a fixed screen offset rather than next to the hovered row.

## Decision

### MenuBar: AWT via `LocalAwtWindow`

Replaced the stub `MenuBarHost` with `AwtMenuBarInstaller` — a `@Composable` function that uses `LocalAwtWindow.current` (set by `ComposeWindow` during composition) to obtain the `java.awt.Frame` and sets `window.menuBar = menuBar` in a `LaunchedEffect`. Menu bar entries are converted to `java.awt.MenuBar` / `Menu` / `MenuItem` via `buildAwtMenuBar()`, a pure Kotlin function in `jvmMain`.

`compose-ui-desktop` was added as a `jvmMain` dependency in `shared/build.gradle.kts` to access `LocalAwtWindow`.

`MenuBarHost` was retained as a `@Deprecated` wrapper for source compatibility with existing test callers.

### Right-click: `secondaryClickModifier` parameter

`SwipeableTaskRow` accepts a `secondaryClickModifier: Modifier = Modifier` parameter. The modifier is applied to the inner content `Box` inside `SwipeToDismissBox.content`. In `AgendaContent`, the outer `Box` wrapper was removed and `rightClickModifier` is passed directly as `secondaryClickModifier = rightClickModifier`.

### SubMenu positioning: `onGloballyPositioned` + `boundsInWindow`

`SubMenuRow` tracks its own `rowBounds` via `onGloballyPositioned { rowBounds = it.boundsInWindow() }`. When the submenu `Popup` is shown, it uses `IntOffset(rowBounds.right - 1, rowBounds.top)` instead of the fixed `224dp` offset.

### Agenda wiring: 3 new intents + 1 event

Added `TaskPinClicked`, `TaskDeleteClicked`, `TaskExpandClicked` to `AgendaIntent`. Added `ExpandTask` to `AgendaUiEvent`. The `desktopContextMenuHost` slot signature gained a 4th parameter: `onIntent: (AgendaIntent) → Unit`. `AgendaViewModel.onIntent` now handles all 6 intents. Pin, delete, expand, complete are wired; AI actions remain TODO (require `AgendaDeps` extension).

### subMenu trailing lambda

Added overload:
```kotlin
fun subMenu(id: String, label: String, icon: ImageVector? = null, enabled: Boolean = true, block: MenuNodesBuilder.() -> Unit)
```
The `children = buildMenuNodes { }` awkward form is now rarely needed.

## Rationale

**Why `LocalAwtWindow` over direct window reference?** `singleWindowApplication` wraps content in a `ComposeWindow` managed by Compose. `LocalAwtWindow` is the sanctioned CompositionLocal for this — it is set during `ComposeWindow.setContent()` and is the only way to obtain the AWT window from within composition.

**Why reflection for `MenuBar.add` / `Menu.add`?** Kotlin 2.x overload resolution on Java 25 picks the wrong overload (`Menu.add(Menu)` instead of `Menu.add(MenuItem)`) for covariant return types. Reflection or explicit typed variables with cast are the available workarounds; reflection was chosen to keep the call sites clean.

**UPDATE (2026-09-16):** The original reflection-based approach failed at runtime: `java.awt.MenuBar.add(java.awt.MenuItem)` does not actually exist — `MenuBar.add` accepts **only** `java.awt.Menu`. Top-level `MenuNode.Action` items are now wrapped into a single-item `java.awt.Menu` (using the action's label as the menu title) so they can be added to the MenuBar. Submenus still work as expected. Lesson: always inspect the actual AWT API with `javap` before relying on Kotlin's overload resolution.

**Why `@Suppress("OPT_IN_USAGE_ERROR")` for `onPointerEvent`?** `ExperimentalPointerInputApi` annotation class lives in AndroidX `compose-ui` which is not part of the multiplatform metadata JAR. The annotation is unavailable at compile time even though the API works at runtime. The suppression with a documented comment is the pragmatic choice.

## Consequences

- Menu bar appears in OS-native window chrome on all three desktop platforms.
- Right-click context menu works again on task rows in the agenda.
- Agenda context menu: Pin, Delete, Expand, Complete are functional.
- `AgendaDeps` extension for AI actions is the next step for AI menu items.
- `compose-ui-desktop` is now a required `jvmMain` dependency for `shared`.

## Links

- Commit: `fix(desktop): MenuBar AWT, right-click via secondaryClickModifier, agenda intent wiring`
- Files: `AwtMenuBarInstaller.kt` (new), `MenuBar.kt` (rewritten), `AgendaContent.kt` (right-click fix), `AgendaIntent.kt` (3 new intents), `AgendaUiEvent.kt` (ExpandTask), `AgendaViewModel.kt` (new branches), `AgendaNavGraph.jvm.kt` (wiring), `AgendaScreen.kt` (slot update), `ContextMenu.kt` (submenu positioning), `MenuNodesBuilder.kt` (trailing lambda)
- Tests: `MenuBarTest.kt` (updated for `AwtMenuBarInstaller`), `MenuNodesBuilderTest.kt` (+1 trailing lambda test)
- Related: `2026-09-15-desktop-menus.md`
