---
title: "Nav3: TasksNavigator replaces callback-passing in task screens"
date: 2026-09-14
tags: [nav3, navigation, koin, refactor]
---

## Context

The task feature screens (`TaskListScreen`, `TaskDetailViewScreen`, `TaskCreateScreen`) received navigation callbacks (`onBack`, `onNavigateToProject`, `onNavigateToTask`) as function parameters. This required every intermediate composable in the call chain to thread these callbacks through, even when they didn't use them. It also made screen-level previews impossible without providing fake callbacks.

Navigation 3 (Nav3) provides a `NavBackStack` + `NavDisplay` model where screens can navigate by mutating the back stack directly. The question was how to bridge "screen wants to go back" / "screen wants to open a project" with the `NavBackStack` — without returning to callback-passing.

## Decision

Each feature graph gets a **navigator object** (`TasksNavigator`) provided via a `CompositionLocal`. Screens read `LocalTasksNavigator.current` and call methods on it. The navigator mutates the `NavBackStack` directly and calls `onExitGraph` when the stack size would drop to ≤1 (i.e. the user is about to leave the feature graph).

The navigator is an `open class` so preview subclasses can override all navigation methods to be no-ops, enabling `@Preview` composables without Koin DI.

## Rationale

**Navigator vs direct `NavBackStack` access in screens:** Directly exposing `NavBackStack` to screens couples them to the Nav3 API. A navigator wraps the stack mutations (push, pop, close graph) into semantic actions (`openDetail`, `back`, `closeGraph`) and encapsulates the `onExitGraph` trigger logic (size ≤ 1). If the Nav3 API changes, only the navigator updates.

**`open class` for previews:** The alternative was to make `TasksNavigator` an interface with a default implementation. An interface requires all call sites to depend on the interface type. An `open class` lets preview code subclass and override only the methods it needs, while production code uses the concrete class. The `PreviewTasksNavigator` is a private inner class in `TasksPreviewHelpers.kt` — not part of the public API.

**`CompositionLocal` vs passing navigator as parameter:** The call chain for `TaskDetailViewContent` is `TaskDetailViewScreen → TaskDetailTopBar → ...`. Threading a navigator parameter through every composable is boilerplate. `CompositionLocal` lets intermediate composables that don't navigate still compile without the parameter. The rule (enforced by `LocalTasksNavigator` error message): screens must use the navigator, never access the `NavBackStack` directly.

**`size ≤ 1` triggers `onExitGraph`:** When the back stack has 0 or 1 entries, pressing Back would exit the feature graph entirely. The navigator detects this and calls `onExitGraph(appDestination)` so the parent graph can navigate to the appropriate sibling destination (e.g. Inbox).

**Platform `BackHandler`:** Android intercepts the system back gesture via `androidx.activity.compose.BackHandler`. JVM has no back gesture — `BackHandler.jvm.kt` is a no-op stub. The `TasksNavGraph` composable calls `BackHandler` on Android only.

**JetBrains `navigation3-ui-desktop`:** The terrakok/nav3 `jvmstubs` artifact is a lowest-common-denominator stub with no actual UI implementation. JetBrains publishes a proper KMP `navigation3-ui-desktop` that works on JVM. Using it eliminates the need for platform-specific stubs for the UI layer.

## Consequences

- All task feature screens (`TaskListScreen`, `TaskDetailViewScreen`, `TaskCreateScreen`) use `LocalTasksNavigator.current` for navigation — no callback parameters.
- `TasksNavigator` is the only class that mutates `NavBackStack<TasksRoute>`.
- `TasksRoute` is the sealed interface defining all routes within the tasks graph (Inbox, Today, ByProject, Detail, Create).
- `TasksNavGraph` is the `@Composable` nav host — it sets up `LocalTasksNavigator`, `LocalNavBackStack`, and the `BackHandler`.
- Screens that need `@Preview` use `TasksPreviewWrapper { ... }` which provides a `PreviewTasksNavigator` via `LocalTasksNavigator`.
- `TaskDetailIntent` no longer has `NavigateToProject` / `NavigateToTask` routing intents — those are now navigator methods.
- Android system back gesture is handled by `BackHandler` in `TasksNavGraph.android.kt`. JVM has no back handling.
