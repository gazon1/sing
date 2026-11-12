---
title: "The shell's button follows the route inside the agenda graph"
date: 2026-11-12
status: accepted
tags: ["navigation", "nav3", "shell", "fab", "android"]
---

# The shell's button follows the route inside the agenda graph

## Context

The agenda is a nested navigation graph with a stack of its own
(`AgendaStartRoute` keys). The shell that draws the floating button over the
agenda sits *above* that graph: it sees the graph as one destination
(`AppDestination.AgendaGraph`) and not the route inside it.

Both shells therefore resolved the button from the *start* route of the graph —
the tab the agenda was opened with:

```kotlin
val topMostRoute: NavKey = state.topMostRoute()
val fabCurrent: AppDestination = topMostRoute as? AppDestination ?: current
```

`FabActionResolver` already classifies every route: the saved-view screens (list,
results, editor, creator) carry **no** shell button, and the project and tag
drill-downs carry one that seeds the new task with the scope they were opened
from. That classification was unreachable from inside the agenda, because the
shell never saw those routes.

Two consequences on Android, where the shell's button is drawn over the content:

- The saved-views list draws its own "Create view" button, and the shell's "Add
  task" button was drawn on top of it, in the same corner. The covered button was
  not reachable at all: on a device the create button was absent from the
  accessibility tree, so the saved-view screens had no way to create a view. Every
  Maestro agenda flow that opens a saved view (02, 03, 04, 05, 06, 07, 08) stopped
  at that assertion.
- A drill-down (a project, a tag) got the button of the tab it was opened from,
  so the new task was seeded with the tab's scope rather than the drill-down's.

## Idea

- Let the graph tell the shell which route it is on.
- Keep the decision about *what a route carries* in one place: the resolver.
- Let a screen inside a nested graph hand its bar to the shell, the way the
  projects graph already does.

## Decision

**The agenda graph publishes the route it is on, and the shell resolves its
button from that route.**

- `AgendaRouteHolder` (in `feature/nav`) holds the route; the shell creates it,
  provides it through `LocalAgendaRouteHolder`, and reads it. The graph publishes
  the top of its own stack on every composition and clears it when it leaves.
- `shellFabTarget` (in `shell/FabActionResolver.kt`) turns the shell's three
  inputs — the top-level destination, the top of the outer stack and the
  published agenda route — into the destination the button is resolved from. Both
  shells call it, so the two platforms cannot drift.
- The resolver is unchanged: it already said what every route carries. The shell
  simply asks about the route the user is on now.

**The saved-view screens publish their bar to the shell.**

- Android has one bar and the shell owns it. A screen inside a nested graph that
  owns a back arrow cannot draw its own bar, because the shell's bar is already
  there — and the shell cannot work out how to leave the nested graph on its own.
  So the screen hands its title and its back arrow to the shell
  (`overrideShell = true` on `TopAppBar` / `BackTopAppBar`), the same contract the
  projects graph uses.
- The desktop shell has no bar of its own and the screen draws this one, so the
  parameter changes nothing there.

## Rationale

- One owner for the decision. `FabActionResolver` stays the only place that says
  what a route carries, and its `when` over the sealed route hierarchy keeps
  every route classified — a route added to the hierarchy still breaks the build
  until somebody says what it carries.
- The route is the truth, not a flag. A screen telling the shell "do not draw
  your button" would be a second classification of the same fact, and it would
  have to be repeated on every screen that owns a button. The route already says
  it.
- The graph is the only place that knows the route. The shell cannot see inside a
  nested graph, and the nested stack is the graph's own (`AgendaNavGraph` owns
  it), so the graph is the one that has to say where it is.
- The fix is symmetric across platforms. The desktop shell drew the same button
  in the same corner; its tests did not notice, because a Compose test asserts
  the semantics tree rather than what covers it.

## Consequences

- The saved-view screens keep their own create button, and the shell draws no
  button over them. The Maestro agenda flows can get past the saved-views list.
- A drill-down now seeds the new task with the scope it was opened from, which
  is what the resolver always said it should do (`FabActionResolverTest`).
- The shell's button appears one frame later when the agenda graph publishes its
  route: the button is resolved from a route the shell learns about in the same
  composition. On the saved-view screens the button disappears as the graph
  publishes that route.
- A screen mounted without a shell — a preview, a test that mounts one screen —
  has no holder, and the shell behaviour of before applies: the button of the tab
  the agenda opened with.
- The saved-view screens on Android now show their title and their back arrow in
  the shell's bar instead of drawing a second bar under it. The bar the shell
  draws is the one that was already there, so this is a change of what fills it,
  not a second bar.

## Links

- `shared/src/commonMain/kotlin/com/singularity/todo/feature/nav/AgendaRouteHolder.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/agenda/presentation/nav/AgendaNavGraph.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/core/ui/components/BackTopAppBar.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/agenda/presentation/screen/SavedAgendaScreen.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/agenda/presentation/screen/SavedAgendaListScreen.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/shell/FabActionResolver.kt`
- `shared/src/androidMain/kotlin/com/singularity/todo/AndroidShellNav3.kt`
- `shared/src/jvmMain/kotlin/com/singularity/todo/shell/DesktopShellNav3.kt`
- `shared/src/commonTest/kotlin/com/singularity/todo/shell/ShellFabTargetTest.kt`
- `docs/decisions/2026-09-16-android-shell-fab-fix.md`
