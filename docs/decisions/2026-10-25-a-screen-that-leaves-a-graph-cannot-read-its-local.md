---
title: "A screen that leaves a nested graph cannot read that graph's CompositionLocal"
date: 2026-10-25
status: accepted
tags: [navigation, auth, product]
---

# A screen that leaves a nested graph cannot read that graph's CompositionLocal

## Context

Two screens are app-level destinations that the settings graph *opens and then leaves*:
`AttachAccountScreen` and `ChangePasswordScreen`. The settings graph reaches them
through `SettingsNavigator.openAttachAccount()` / `openChangePassword()`, and both of
those end in `onExitGraph(AppDestination.X)` — the nested graph is exited, and the
destination is opened in the outer graph.

Both screens then read the settings graph's own navigator:

```kotlin
val settingsNavigator = LocalSettingsNavigator.current
```

`LocalSettingsNavigator` is provided **inside** `SettingsNavGraph`:

```kotlin
CompositionLocalProvider(LocalSettingsNavigator provides navigator) { … }
```

and its default is an error, not a value:

```kotlin
val LocalSettingsNavigator = compositionLocalOf<SettingsNavigator> {
    error("SettingsNavigator not provided — wrap with SettingsNavGraph")
}
```

So opening either screen crashed the app:

```
java.lang.IllegalStateException: SettingsNavigator not provided — wrap with SettingsNavGraph
    at com.singularity.todo.feature.auth.presentation.AttachAccountScreenKt.AttachAccountScreen(AttachAccountScreen.kt:510)
```

**The crash was masked by the E2E stub.** The "Attach account" button only renders for
`Session.Anonymous`, and the stub's session was `Session.SignedIn`, so no flow could
reach the screen; the crash was never triggered. It became visible on 2026-10-25, when
the auth flows started seeding an anonymous session (`todo-debug://seed?session=anonymous`)
and finally opened the screen. The same crash was waiting behind the "Change password"
button, which *every signed-in user* can reach from Settings → Account — no flow
covered it, so nothing had ever opened it.

## Idea

A screen that lives outside a nested graph must not read that graph's CompositionLocal.
The graph's local answers questions about navigating *inside* the graph, and a screen
that has left the graph has no business asking.

## Decision

**The two screens use the app-level navigator** (`LocalAppNavigator`, provided at the
shell and therefore always available) for their way back, and no longer read
`LocalSettingsNavigator`.

The way back is the same as it was: the app's back stack still holds the settings graph
underneath, because `openAttachAccount` / `openChangePassword` resolve to
`OpenAction.ExitAndOpen`, which *pushes* the destination onto the stack and leaves the
origin under it ("the origin staying underneath (back returns to it)"). So
`navigator.goBack()` pops the screen and lands on the settings graph, which is what
`settingsNavigator.back()` meant.

## Rationale

The alternative — giving `LocalSettingsNavigator` a default — would turn a crash into a
silent no-op: a screen that means to go back would do nothing, and the failure would
move from the first tap to the second. A CompositionLocal whose default throws is
telling the truth about its lifetime; the bug was the reader, not the local.

The app-level navigator is the right scope for a screen that is *not* inside the
settings graph: it is the one handle that is provided everywhere, including in the
signed-out auth state.

## Consequences

- "Attach account" and "Change password" no longer crash the app. Both are reachable by
  a user today, and both were crashing.
- `LocalSettingsNavigator` now has exactly one reader: `AccountSettingsScreen`, which
  is a tab *inside* the settings graph and is at home there.
- The attach flows (`07-account-attach`, `10-attach-account-validation-error`) can run
  in the E2E build, which is what exposed the crash in the first place.

## Links

- `shared/src/commonMain/kotlin/com/singularity/todo/feature/settings/presentation/nav/LocalSettingsNavigator.kt` —
  the local and its throwing default.
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/settings/presentation/nav/SettingsNavGraph.kt` —
  the only place that provides it.
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/nav/Nav3State.kt` —
  `ExitAndOpen` and `goBack`, the mechanics the two screens now rely on.
- `docs/decisions/2026-10-14-stubauthrepository-jvmmain-boundary.md` — the stub's
  `Session.SignedIn` default, and the smoke flow that was replaced because the attach
  button was unreachable.
