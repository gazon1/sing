---
title: "Desktop: replace PermanentNavigationDrawer with explicit Row+Sidebar rail"
date: 2026-09-06
tags: [desktop, compose, ui, navigation]
status: accepted
---

## Context

On desktop (`./gradlew :desktopApp:run`), the menu covered the entire screen and was unresponsive. Investigation showed:

- Desktop used `AppShell(Permanent)` → `PermanentNavigationDrawer { AppDrawerContent } { Scaffold { AppNavHost } }`.
- `AppDrawerContent` had no explicit width — `PermanentNavigationDrawer` sized itself based on the Scaffold's intrinsic measurements, which collapsed to 0 width on the AWT event thread because the inner `Scaffold` had no chrome (no top bar, no bottom bar, only `padding = PaddingValues(0)`).
- The stale `NavGroup` doc comment in `DesktopShell.kt` described a feature deleted by the `AppDestination` refactor — the drawer was already a flat 9-entry list.

The result: the drawer visually claimed the full canvas and clicks landed on a non-reactive surface.

## Idea

Three options were considered:

1. **Fix `PermanentNavigationDrawer` width** by adding `Modifier.width(280.dp)` to the drawer's content. Keeps Material3's drawer semantics but works around the AWT layout issue.
2. **Replace `PermanentNavigationDrawer` with a hand-rolled `Row { Sidebar; VerticalDivider; Box }`** — explicit width, no reliance on Material3 drawer sizing on AWT, matches VSCode/JetBrains IDE UX precisely.
3. **Add a `NavigationRail`** — but `NavigationRail` is capped at 80 dp wide and designed for phone-first layouts; a VSCode-style pinned-left sidebar needs 200–280 dp.

## Decision

Replace `PermanentShell` in `AppShell.kt` with a hand-rolled `Row` layout:

```kotlin
Row(modifier = Modifier.fillMaxSize()) {
    DesktopSidebar(
        current, onSelect,
        modifier = Modifier
            .width(240.dp)
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .testTag(TestTags.DESKTOP_SIDEBAR),
    )
    VerticalDivider()
    Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
        content(Modifier)
    }
}
```

`DesktopSidebar` is a private composable in `feature/nav/AppShell.kt` (not in `core/ui/components/` — per `singularity-todo-shared-ui-components` skill, widgets go there only when used by ≥2 features; desktop sidebar is desktop-only).

`NavDestination` gains an `icon: ImageVector` field so the sidebar can render icons alongside labels. Icons use `Icons.Filled.*` / `Icons.AutoMirrored.Filled.*` from the existing `compose-material-icons-extended 1.7.3` on the classpath.

`ModalShell` is **kept** in `AppShell.kt` (not deleted) — it is latent for future use and deleting it would be a breaking change with no benefit.

## Consequences

- Desktop chrome is a 240 dp left rail, VSCode/JetBrains-style. Width is explicit, not derived from drawer measurements.
- `ModalShell` + `DrawerStyle.Modal` remain in `AppShell.kt`. They are not wired to any platform but are preserved for future modal drawer needs.
- Every `NavDestination` entry has an `icon` field. When adding a new entry, pick an icon from `androidx.compose.material.icons.Filled` or `Icons.AutoMirrored.Filled`.
- `singularity-todo-shared-ui-components` skill governs decomposition: desktop-only chrome stays in `feature/nav/`, shared widgets go to `core/ui/components/`.

## Links

- `feature/nav/AppShell.kt` — `PermanentShell` + `DesktopSidebar`
- `feature/nav/NavDestination.kt` — `icon: ImageVector` field
- `core/ui/TestTags.kt` — `DESKTOP_SIDEBAR` test tag
- `shell/DesktopShell.kt` — doc comment fix (removed stale NavGroup reference)
- Follows: `2026-09-05-android-bottom-nav.md` (AppShell pattern for desktop chrome)
