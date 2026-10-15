---
title: SettingsNavigator — composition-local over DI
adr: 2026-10-14-settings-navigator-di-vs-composition-local
status: accepted
---

## Context

`SettingsNavigator` is a navigation API for screens inside the settings nested graph.
It is currently provided via `CompositionLocalProvider(LocalSettingsNavigator provides ...)`
in `SettingsNavGraph` rather than via Koin DI.

The alternative is a Koin-injected singleton: `koinInject<SettingsNavigator>()` available
everywhere, with DI wiring in a module.

## Decision

Keep `SettingsNavigator` as composition-local via `LocalSettingsNavigator`. Do not move
it to Koin DI.

## Rationale

**The navigator is scoped to the graph, not the app.** A `SettingsNavigator` instance
is tied to the `onExitGraph` callback that the *parent* nav host provides when it
creates the settings graph. That callback is specific to one nav host instance and one
back-stack entry. Injecting it as a singleton would produce an instance that either
 outlives the graph or holds a stale reference after the graph is popped.

**A navigator that escapes its graph is a bug.** If `LocalSettingsNavigator.current` is
accessed outside `SettingsNavGraph`, it throws `error("SettingsNavigator not provided")`.
This is a loud, testable failure. A stale injected singleton would silently navigate to
the wrong destination.

**The only consumer of `SettingsNavigator` is the settings graph and its children.**
There are two current call sites — `AccountSettingsScreen` and `AttachAccountScreen` —
both of which are descendants of `SettingsNavGraph`. No other feature needs to navigate
*within* the settings graph from outside it; the only cross-graph hop is via
`AppDestination` on `onExitGraph`, which is the outer nav host's concern.

**Consistency with the codebase pattern.** `LocalAppNavigator` uses the same pattern
for the same reason: the app-level navigator is scoped to the app nav host. Moving
`SettingsNavigator` to DI while `AppNavigator` stays composition-local would be
asymmetric for no reason.

## Consequences

- `SettingsNavigator` is only accessible inside `SettingsNavGraph`. Attempts to access
  it outside throw immediately.
- The `AttachAccountScreen` (which is not itself inside the settings graph but is
  reachable from it via `openAttachAccount()`) does not need `LocalSettingsNavigator` —
  it uses `LocalAppNavigator` to navigate to `AgendaGraph` on success.
- If a future screen outside the settings graph needs to open a settings sub-screen,
  the navigation target is an `AppDestination` on the outer `onExitGraph` callback,
  not a direct call into `SettingsNavigator`.

## Counter-argument considered: DI for testability

An injected navigator would be easier to mock inCompose UI tests, replacing the
implementation without the graph setup. However, Compose UI tests for settings screens
can wrap the screen under test in a `CompositionLocalProvider` with a test navigator,
exactly as `AccountSettingsScreen` already does for its stateless preview variant.
The test surface does not need DI to be testable.

## Links

- `LocalSettingsNavigator` — `feature/settings/presentation/nav/LocalSettingsNavigator.kt`
- `SettingsNavigator` — `feature/settings/presentation/nav/SettingsNavigator.kt`
- `SettingsNavGraph` — `feature/settings/presentation/nav/SettingsNavGraph.kt`
- `LocalAppNavigator` — the same pattern applied at app scope
