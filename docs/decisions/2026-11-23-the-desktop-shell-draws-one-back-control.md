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
  composed bar, in the order the bars were composed; the last entry wins. The
  screen that came up last is the one on top, and its answer is the one that
  counts.
- The holder publishes the *identity* of the owning bar, not a flag. The shell
  draws its control only while the owner is null, and a bar draws its own
  leading control only while it is the owner. Both read the same fact, so the
  two controls cannot be on screen at the same time — not even in the frame in
  which the screen on top is still publishing.
- The shared `TopAppBar` wrapper publishes whether it draws a leading control
  of its own. It is the single funnel every screen bar goes through, so one
  publication covers the whole app. A screen that draws its arrow outside that
  funnel — the external agent screen draws its arrow beside its title — calls
  `PublishScreenBackControl()` itself.
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
- The control's state is published in a `SideEffect`, so the ownership changes one
  composition after the screen on top publishes. The screen's own arrow therefore
  appears one frame after the shell's arrow has gone: the arrow moves from the
  shell's chrome into the screen's bar. The two are never on screen together —
  the holder publishes an owner, and both controls read it — but the arrow is
  briefly drawn in the shell's place.
  shortcut: the ownership is published in a `SideEffect`, as `ScreenActionsHolder`
  publishes its row; a screen's arrow could appear in the same frame by
  publishing during composition instead, at the price of writing state during
  composition.
- The correctness of the ownership rests on the order in which bars are composed
  matching the order of the screens: the screen that came up last has to be the
  last one composed. That is the same convention `ScreenActionsHolder` already
  relies on for the action row.
- The handoff holds in both directions, and both are proved frame by frame
  (`DesktopSingleBackControlTest`): entering a screen that owns the control, and
  leaving one. Leaving is the half where a control could go missing — the screen
  is gone, and the shell's control is back only after the release reached it —
  so the test counts the controls in every frame of the way back as well. There
  is exactly one in every frame of both handoffs.
- The holder counts "a leading control", not "a back arrow": a screen in
  selection mode publishes its "Exit selection" icon, and the shell's control
  stands down while such a bar is on top. That is what the Android shell does
  with the same slot: in selection mode its leading icon is "Exit selection"
  instead of the back arrow (`ShellNavigationIcon` in `AndroidShellNav3.kt`).
  The leading slot of a bar holds one control, and the screen that is on top
  decides what it is — the same answer the Desktop shell gives.
  shortcut: the holder counts any leading control, so a screen whose leading
  control is not a back arrow takes the shell's control down with it; upgrade to
  a publication per control kind when a screen shows a leading control that is
  not a back affordance and the shell still has to stay reachable.

## Links

- `shared/src/commonMain/kotlin/com/singularity/todo/core/ui/components/ScreenBackControlHolder.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/core/ui/components/TopAppBar.kt`
- `shared/src/jvmMain/kotlin/com/singularity/todo/shell/DesktopShellNav3.kt`
- `shared/src/jvmMain/kotlin/com/singularity/todo/feature/ai/external/AcpExternalAgentScreen.kt`
- `shared/src/commonTest/kotlin/com/singularity/todo/core/ui/components/ScreenBackControlHolderTest.kt`
- `shared/src/jvmTest/kotlin/com/singularity/todo/shell/DesktopBackTest.kt`
- `desktopApp/src/jvmTest/kotlin/com/singularity/todo/feature/flows/desktop/DesktopBackClosesWindowTest.kt`
- `desktopApp/src/jvmTest/kotlin/com/singularity/todo/feature/flows/desktop/DesktopSingleBackControlTest.kt`
- `docs/decisions/2026-11-12-the-shells-button-follows-the-route-inside-the-agenda-graph.md`
- `docs/decisions/2026-11-10-a-published-shell-title-is-read-where-it-is-drawn.md`
