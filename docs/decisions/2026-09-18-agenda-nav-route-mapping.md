---
title: "MR7: AgendaNavContent shared route mapping"
date: 2026-09-18
tags: [navigation, refactor]
---

## Context

Two platform-specific files (`AgendaNavGraph.android.kt` and `AgendaNavGraph.jvm.kt`) each had two identical copies of an 8-branch `when` expression that mapped `AgendaStartRoute` variants to screen composables. This duplication made adding new routes error-prone — both copies had to be kept in sync manually.

Additionally, `SavedAgendaScreen` appeared in both files but exists only in `commonMain` (not platform-specific), creating an inconsistency in how the route-to-screen mapping was structured.

## Decision

Created `AgendaRouteMapping.kt` in `commonMain` with a single `AgendaNavContent(route, desktopContextMenuHost)` composable that contains the canonical 8-branch `when` expression for all `AgendaStartRoute` variants.

Both platform nav graphs now delegate to it inside their `entry { }` blocks:
- `entry<AgendaStartRoute.X> { AgendaNavContent(route = it, desktopContextMenuHost = ...) }`
- `entry<AgendaStartRoute.X> { r -> AgendaNavContent(route = r, ...) }` (for data class routes)

`SavedAgendaScreen` and `SavedAgendaListScreen` remain in commonMain — `AgendaNavContent` references them directly since they are platform-agnostic.

## Rationale

- One canonical place for the route→screen mapping. Adding a new route variant now requires changing only `AgendaRouteMapping.kt`.
- `AgendaNavContent` is `@Composable` so it can call `@Composable` screen functions directly.
- `desktopContextMenuHost` is an optional parameter with a no-op default — the JVM override passes the real desktop context menu, Android ignores it.
- The `entry { }` lambda receives the route as an implicit `it` (for object routes) or explicit parameter `r` (for data class routes). `AgendaNavContent` receives it as the `route` parameter.

## Consequences

- All `AgendaStartRoute` variants are now handled in one place.
- When adding a new `AgendaStartRoute` variant, add it to `AgendaStartRoute.kt`, then add a branch to `AgendaNavContent.when`.
- `AgendaNavGraph.android.kt` and `AgendaNavGraph.jvm.kt` still have platform-specific setup (SavedState, in-memory backstack, desktop context menu) — those remain appropriately separated.

## Links

- Commit: MR7 — AgendaNavContent shared route mapping
- New file: `feature/agenda/presentation/nav/AgendaRouteMapping.kt`
- Modified files: `feature/agenda/presentation/nav/AgendaNavGraph.android.kt`, `feature/agenda/presentation/nav/AgendaNavGraph.jvm.kt`
