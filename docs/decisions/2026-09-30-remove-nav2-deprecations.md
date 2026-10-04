---
title: "MR-3 Nav2 Deprecation Removal"
date: 2026-09-30
status: accepted
tags: [mr, navigation, deprecation]
---

## Context

`AppDestination` had several deprecated entries (`Inbox`, `Today`, `Upcoming`, `TasksByProject`, `TaskDetail`, `TaskDetailCreate`) and `TasksStartRoute` had deprecated variants (`Inbox`, `Today`, `Upcoming`, `ByProject`). Investigation confirmed these are **dead code** — the shell never navigates to them at runtime. `DestinationKind.tabs` lists only `AgendaGraph(Inbox/Today/Upcoming)` instances; `Nav3StateFactory` starts with `AgendaGraph`; `AndroidShellNav3` and `DesktopShellNav3` iterate only `DestinationKind.tabs` and `DestinationKind.menuEntries`.

## Decision

**Phase 1 (MR-3)**: Remove dead-code `@Suppress("DEPRECATION")` and dead-code when-branches from `FabActionResolver` — the minimal safe change that doesn't require full navigation refactoring.

- Remove `@Suppress("DEPRECATION")` from `FabActionResolver.kt`
- Remove the `AppDestination.Inbox`/`Today` when-branches from `fabActionForNav3` (dead code)
- Update KDoc to reflect the modern routing
- Remove dead-code tests from `FabActionResolverTest.kt`
- Remove unnecessary `@Suppress("DEPRECATION")` from tests of non-deprecated destinations

## Consequences

- `FabActionResolver` no longer carries a suppression for code that was never reachable
- Tests compile without suppressions for non-deprecated destinations

## Deferred to MR-N (future)

Full removal of deprecated `AppDestination` singletons and `TasksStartRoute` variants:
- Delete `Inbox`, `Today`, `Upcoming`, `TasksByProject`, `TaskDetail`, `TaskDetailCreate` from `AppDestination`
- Delete `TasksStartRoute.Inbox`, `Today`, `Upcoming`, `ByProject`
- Update `AppDestination.icon` to remove deprecated branches
- Remove `entry<AppDestination.Inbox>` / `entry<AppDestination.Today>` / `entry<AppDestination.Upcoming>` from `AndroidNavEntries` and `JvmNavEntries`
- Update NavKeyRegistrationTest and NavSavedStateConfigTest to use AgendaGraph equivalents
- Update `toTasksRoute` to remove deprecated fallbacks

## Resolution (accepted)

Resolved 2026-10-05: the work landed.

Verified: no `androidx.navigation2` reference remains in `shared/src`, `androidApp/src`
or `desktopApp/src`, and no `nav2` entry remains in any version catalog. The only
occurrence of the removed destinations is a KDoc line in `shell/FabActionResolver.kt:20`
that documents the removal — the `@Suppress("DEPRECATION")` annotation and the dead
`Inbox`/`Today` when-branches are gone, which is what this ADR asked for.
