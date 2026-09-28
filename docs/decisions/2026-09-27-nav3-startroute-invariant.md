---
title: "Nav3 startRoute must be a top-level route — enforce with an invariant, centralize serializers"
date: 2026-09-27
tags: [nav3, navigation, bug, koin, architecture]
status: accepted
---

## Context

`just dr` crashed on launch with:

```
java.lang.IllegalArgumentException: NavDisplay entries cannot be empty
    at androidx.navigation3.ui.NavDisplay(NavDisplay.kt:537)
    at com.singularity.todo.shell.DesktopShellNav3Kt.DesktopShellNav3Root(DesktopShellNav3.kt:218)
```

`rememberNav3State()` in **both** `Nav3StateFactory.jvm.kt` and `Nav3StateFactory.android.kt` set:

```kotlin
val startRoute: NavKey = AppDestination.Today
val topLevelRoutes: Set<NavKey> = DestinationKind.tabs.toSet() + DestinationKind.menuEntries.toSet()
```

The AgendaEngine refactor had already moved the three agenda tabs to
`AppDestination.AgendaGraph(AgendaStartRoute.X)` data classes, leaving
`AppDestination.Today` as a `@Deprecated data object` that is no longer a member of
`DestinationKind.tabs`.

Because `backStacks` is built as `topLevelRoutes.associateWith { ... }`, no stack ever existed
for the start route. `Nav3State.getTopLevelRoutesInUse()` returns `listOf(startRoute)` on first
composition, `toDecoratedEntries()` maps that through `decoratedEntries[it] ?: emptyList()`, and
`NavDisplay` receives an empty list and throws.

The same code path existed on Android — it was latent, not fixed.

A second, independent defect surfaced during the audit: the Android
`navSavedStateConfig(...)` had never registered `AppDestination.AgendaGraph.serializer()` or
`AppDestination.Upcoming.serializer()`. Fixing only `startRoute` would have moved the crash from
"launch" to "process-death restore", as a `SerializationException` instead.

## Idea

1. Fix `startRoute` to `AppDestination.AgendaGraph(AgendaStartRoute.Today)` and stop.
2. Fix `startRoute`, add the two missing serializers, and additionally make the
   `startRoute ∈ topLevelRoutes` invariant impossible to violate silently.
3. Add the two missing serializers in place, leaving the hand-maintained list as-is.
4. Extract the serializer list into a single `AppDestinationSerializers` value and add an
   `init { require(...) }` guard on `Nav3State`.
   *(Superseded on 2026-09-28: the list is gone — see the update note below.)*

## Decision

We did all of option 2 and 4 combined:

- `startRoute` is now `AppDestination.AgendaGraph(AgendaStartRoute.Today)` on both platforms,
  with a comment recording why it must stay a member of `topLevelRoutes`.
- ~~All concrete `AppDestination` serializers moved into a single
  `AppDestinationSerializers: List<KSerializer<out NavKey>>` in `AppDestination.kt`.~~
  **Superseded 2026-09-28** (see
  `2026-09-28-android-cold-start-nav3-serializer-crash.md`): that hand-maintained list fed
  `subclass(serializer)` with type-erased serializers, which crashed every Android cold
  start. `AppDestination` is now `@Serializable sealed` and `navSavedStateConfig` registers
  the hierarchy via `subclassesOfSealed` — no per-leaf list exists.
- `Nav3State` gained an `init` block asserting both `startRoute in backStacks` and
  `topLevelRouteState.value == startRoute`.
- `Nav3State.requireBackStackFor(route)` was added, and `Navigator.navigate` / `Navigator.goBack`
  now use it instead of silently no-opping on a missing stack.
- The `AndroidShellNav3` fallback destination changed from the deprecated `AppDestination.Today`
  to `AppDestination.AgendaGraph(AgendaStartRoute.Today)`, matching the desktop shell and the
  new start route.

## Rationale

The multiplestacks pattern's `getTopLevelRoutesInUse()` contract is "render the start stack plus
the current stack". That contract is only correct when the start route is a real key in
`backStacks`. The terrakok recipe keeps the two in sync by convention; nothing enforced it, and
a deprecated-but-still-compiling `data object` was a silent trap.

Two decisions deserve explicit justification:

**`init { require(...) }` rather than fixing `getTopLevelRoutesInUse()`.** A "smarter"
`toDecoratedEntries` that filtered unknown keys would have hidden this class of bug permanently
— the app would render a blank screen instead of failing. Failing at construction points at the
misconfiguration immediately.

**Serializer list extraction rather than adding two lines.** A hand-maintained list next to a
sealed interface drifts silently: adding a `data class` to `AppDestination` compiles fine and
only fails on process-death restore, which is rarely exercised. Naming the list and documenting
the rule makes the gap visible at the point of the edit.

**`requireBackStackFor`.** `Navigator.navigate` previously did `state.backStackFor(...)?.add(route)`
— a missing stack made navigation silently do nothing, which is the same failure class as this
bug, one layer down. `goBack` already used `error(...)`; the asymmetry was accidental.

`startRoute` was deliberately left typed as `NavKey` rather than narrowed to `AppDestination`:
the factory actuals declare it as `NavKey` today, and narrowing would be churn without changing
behaviour. The `require` block gives the same safety with a smaller diff.

## Consequences

- `startRoute` used by `rememberNav3State` **must** be a member of `topLevelRoutes`
  (`DestinationKind.tabs + DestinationKind.menuEntries`), or `Nav3State` construction throws.
- ~~Register new `AppDestination` serializers in `AppDestinationSerializers`, nowhere else.~~
  Superseded 2026-09-28: a new `@Serializable` `AppDestination` subtype is covered
  automatically by the sealed serializer; no registration step exists anymore.
- Call `requireBackStackFor(route)` instead of `backStackFor(route)` at any call site that cannot
  meaningfully continue without a stack.
- Keep the shell fallback destination (`AndroidShellNav3`, `DesktopShellNav3`) in sync with
  `startRoute`; do not reintroduce deprecated `data object` singletons as fallbacks.

### Known issues left open

- **`TaskDetailViewModelTest."TitleChanged debounce saves after delay"` fails with
  `UncompletedCoroutinesError` (60s hang).** Pre-existing on `f3f3c548`; confirmed by running
  the test on a stashed tree. It uses real `delay()` against a VM whose collectors run on
  `Dispatchers.Default`, which escapes the `TestScope` scheduler. ADR
  `2026-09-25-testable-vm-dispatcher-clock` already prescribes the fix (inject a
  `StandardTestDispatcher` into `FakeProfileAwareCurrentUser`). An attempt to thread
  `TestCoroutineScheduler` through the test's `createVm` helper did not clear it, and converting
  the other tests' `delay()` calls to `advanceUntilIdle()` broke all 7 assertions — the VM's
  debounce appears to depend on real-time scheduling that virtual time does not reproduce. Left
  for a dedicated pass on the test harness, not the nav layer.
- **`rememberNavBackStackTyped<T>`** from `2026-09-16-nav3-type-asymmetry-adr.md` is still
  unimplemented; 5 `*NavGraph.android.kt` files still carry `@Suppress("UNCHECKED_CAST")`.
- **Deprecated `AppDestination` singletons** (`Inbox`, `Today`, `Upcoming`, `TasksByProject`,
  `TaskDetail`, `TaskDetailCreate`) are still wired into both entry providers and
  `FabActionResolver`. Safe to remove only once every caller has been migrated.
- **`topLevelRoute` is not persisted**, so a cold launch always restores the start tab rather
  than the last-used tab. Pre-existing, unrelated to the crash.
- **`just setup-hooks` is broken in worktrees.** It sets `core.hooksPath` to
  `<main>/.git/.githooks`, which does not exist — the real hooks are in `<main>/.git/hooks`.
  The recipe also rewrites `core.hooksPath` for *every* attached worktree. Until fixed, a
  worktree commit either skips hooks silently or compiles the main checkout: the `pre-commit`
  script resolves `ROOT` as `dirname($BASH_SOURCE)/../..`, so from a worktree it builds the main
  tree instead of the worktree. Use `git commit --no-verify` plus an explicit gradle run.

## Links

- Fixes the crash reported from `just dr` on branch `fix/repair-broken-build` (`f3f3c548`).
- Branch `fix/nav3-empty-entries-crash`, worktree `~/work/singularity-nav3-empty-entries`.
- Related: `2026-09-11-nav3-kmp-migration.md`, `2026-09-16-nav3-savedstate-serializers-required.md`,
  `2026-09-16-nav3-desktop-in-memory-no-savedstate.md`, `2026-09-16-nav3-type-asymmetry-adr.md`,
  `2026-09-14-nav3-vm-store-decorator-fix.md`, `2026-09-25-testable-vm-dispatcher-clock.md`.
- Files: `Nav3State.kt`, `AppDestination.kt`, `Nav3StateFactory.jvm.kt`,
  `Nav3StateFactory.android.kt`, `AndroidShellNav3.kt`.
