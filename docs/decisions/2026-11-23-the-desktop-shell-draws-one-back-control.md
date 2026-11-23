---
title: "The Desktop shell draws one back control, and stands down while a screen draws one"
date: 2026-11-23
status: accepted
tags: ["navigation", "nav3", "shell", "desktop", "back"]
---

# The Desktop shell draws one back control, and stands down while a screen draws one

## Context

The Desktop shell drew its back arrow only while `navigator.canGoBack()` reported
something to go back to, and it called `navigator.goBack()` itself:

```kotlin
if (canGoBack) {
    IconButton(onClick = { navigator.goBack() }, modifier = Modifier.testTag(TestTags.SHELL_BACK_BUTTON)) { ... }
}
```

Two defects came out of that one condition:

- The root close was unreachable. The shell's arrow was *absent* exactly where
  there was nothing to pop — the ultimate root of the start tab — so the
  `desktopBack` policy (pop, or ask the host to close the window) could never
  run from the shell's own control. The half of the pairing the Android shell
  bar makes between `goBack()`'s result and `activity.finish()` had no button
  on Desktop.
- The arrow stacked. The shell sits above the content, so its arrow was drawn
  on top of a screen that draws a back arrow of its own — the task editor, the
  project detail, the saved-view screens. Two arrows for one meaning, and the
  shell's one stood *over* the screen's own control.

The policy itself was already written and already tested
(`desktopBack(navigator, onCloseRequest)` in `DesktopShellNav3.kt`, proved by
`DesktopBackTest`). What was missing was a control that reaches it.

## Idea

- One control in the shell, always drawn, whose meaning follows the navigation
  state.
- The screen on top wins when it draws a control of its own: one back control,
  drawn by whoever is on top.

## Decision

**The shell draws its back control unconditionally, and routes the press through
`desktopBack`.**

- The control is always in the shell's chrome, next to the drawer button. Its
  meaning follows the navigation state: with somewhere to go back to it pops — a
  nested screen, or the tab that was active before — and at the ultimate root of
  the start tab it takes the window's own close path.
- The decision lives in `desktopBack`, so this control, the `NavDisplay` back and
  every screen's own arrow cannot drift apart, and one press cannot both pop
  and close.

**The screen on top owns the gesture, and the shell's control stands down.**

- `ScreenBackControlHolder` (in `core/ui/components`) holds one entry per
  composed bar, in publication order; the last entry wins. The screen that
  published last is the one on top, and its answer is the one that counts.
- The shared `TopAppBar` wrapper publishes whether it draws a leading control
  of its own. It is the single funnel every screen bar goes through, so one
  publication covers the whole app.
- The Desktop shell provides the holder and reads it. While the screen on top
  owns a control, the shell draws none: the screen's arrow already is the back
  affordance, and a second arrow stacked on top of it would be two controls for
  one meaning.

Android needs none of this: its shell owns the only bar there, and a screen that
owns a back arrow publishes that arrow to the shell itself
(`ScreenActionsHolder`, see
`2026-11-12-the-shells-button-follows-the-route-inside-the-agenda-graph.md`).

## Rationale

- The control has to be on screen where it means something. Drawing it only
  while there is something to pop made the close half of the policy
  unreachable: the root — the one place a close is possible — had no button.
- One owner for the gesture. The rule "the screen on top owns the control" is
  a single fact about the topmost screen, published by the one composable every
  bar goes through. A rule per screen ("does this screen have an arrow?") would
  be the same fact restated in twenty places, and a new screen would have to
  remember to restate it.
- The holder is the same shape as `AgendaRouteHolder` and
  `ScreenActionsHolder`: the shell sits above the content and cannot see inside
  it, so the content publishes what the shell needs to know.
- The policy stays in one place. The shell's control does not decide what back
  means; it calls `desktopBack`, the function the `NavDisplay` back also calls.

## Consequences

- At the root of the start tab the shell's control is the back control, and one
  press asks the host to close the window — exactly once.
- On a screen that draws its own back arrow the shell's control is not drawn.
  The screen's own arrow is the back affordance there, and the shell's
  *graph-level* back (the shell's arrow popping the whole top-level stack) is
  no longer reachable from such a screen. Two desktop flows were adapted to
  press the screen's own arrow instead of the shell's.
- The control's state is published in a `SideEffect`, so the shell's chrome
  reacts one composition after the screen on top publishes. A screen that
  appears with an arrow leaves a brief moment where both controls are drawn.
- The holder counts "a leading control", not "a back arrow": a screen in
  selection mode publishes its "Exit selection" icon, and the shell's control
  stands down while such a bar is on top.
  shortcut: the holder counts any leading control, so a screen whose leading
  control is not a back arrow takes the shell's control down with it; upgrade to
  a publication per control kind when a screen shows a leading control that is
  not a back affordance and the shell still has to stay reachable.

## Links

- `shared/src/commonMain/kotlin/com/singularity/todo/core/ui/components/ScreenBackControlHolder.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/core/ui/components/TopAppBar.kt`
- `shared/src/jvmMain/kotlin/com/singularity/todo/shell/DesktopShellNav3.kt`
- `shared/src/jvmTest/kotlin/com/singularity/todo/shell/DesktopBackTest.kt`
- `desktopApp/src/jvmTest/kotlin/com/singularity/todo/feature/flows/desktop/DesktopBackClosesWindowTest.kt`
- `docs/decisions/2026-11-12-the-shells-button-follows-the-route-inside-the-agenda-graph.md`
- `docs/decisions/2026-11-10-a-published-shell-title-is-read-where-it-is-drawn.md`
