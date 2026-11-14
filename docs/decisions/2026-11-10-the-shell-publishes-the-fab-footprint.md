---
title: "The shell publishes the FAB's footprint"
date: 2026-11-10
status: accepted
tags: [ui, shell, layout]
---

## Context

The FAB lives at chrome level (ADR `2026-09-07-fab-chrome-level.md`): the shell
decides whether there is a button, and draws it over the content.

Material3's `Scaffold` keeps no room for a floating action button. The content
is laid out behind it, and the button's offset from the bottom
(`fabHeight + FabSpacing + contentWindowInsets.bottom`) is known only to the
`Scaffold`. So the last row of a list ends *under* the button: on a phone the
agenda's last task row was covered, and the row could not be scrolled out from
under it (#2743).

Three things make "reserve a fixed 72 dp" the wrong answer:

- the button's height is not fixed — the desktop shell draws a medium button
  (56 dp), the Android shell an extended one;
- the button is not always there — `FabActionResolver` returns `null` for
  routes without an action, and a list that reserves room for an absent button
  keeps a band of blank space;
- the button's visibility is not a feature's business — it is the shell's
  (single source of truth).

## Idea

1. Each list reserves a hard-coded height for the button.
2. The shell passes the button's height down to the lists it draws.
3. The shell publishes the space the button takes, and a list that wants
   clearance adds that space to its own bottom content padding.
4. The button and the content share one layout that knows where the button
   sits, and the content is told how much room the button takes.

## Decision

We did (3).

`core/ui/components/FabBottomSpace.kt` holds the contract:

- `FabFootprint` — the shell measures the button (`onSizeChanged`) and reports
  its height;
- `LocalFabBottomSpace` — a `CompositionLocal` the shell provides with the
  space the button takes above the bottom edge of the content: the measured
  height plus Material3's `FabSpacing` (16 dp, private there), and `0` when no
  button is on screen.

Both shells publish it (`AndroidShellNav3`, `DesktopShellNav3`); `AgendaList`
adds it to its bottom content padding, and `TaggedSnackbarHost` keeps it clear
at the bottom of the snackbar (#2752 — a snackbar is content, so it is drawn
under the button, and the Undo action ended up under the button's hit target).
The host inside `NotificationHost`'s `Popup` takes it for the other reason: the
popup is drawn above the app's window, so its toast covers the button instead
of being covered by it.

## Rationale

(1) duplicates a constant that belongs to the chrome, in every list that needs
it, and it is wrong twice over: too small for the Android button, and pure
blank space on a route without a button.

(2) puts the shell's knowledge into the shell's own parameters, and the
lists that need the space are not the shell's children — the shell draws a
`NavDisplay`, and every screen below it would need the height threaded
through its own parameters.

(3) keeps the source of truth where the button is: the shell measures the
button it actually drew. Measuring beats assuming, because the assumption
drifts the moment the button changes shape. And a `CompositionLocal` matches
how the shell already hands a screen its title bar (`LocalScreenActions`).

(4) is the cleanest in isolation — one layout owns the button's position and
the content's clearance — but it is a bigger change than the fix needs: the
content is drawn by a `NavDisplay` under the shell, not next to the button, so
the layout would have to hand the clearance down through the navigation the
same way the `CompositionLocal` does. It buys a stronger guarantee at a much
higher price, and (3) already gets the guarantee from the same source.

The system navigation bar is not part of the reserved space: on a phone the
bottom panel already lifts the content above itself and consumes the bottom
insets, so adding them again would reserve the gap twice.

## Verification

What the reserved space has to match is Material3's own placement of the
button, and that is stated in its source: `Scaffold` sets

```
fabOffsetFromBottom = fabHeight + FabSpacing + contentWindowInsets.bottom
```

so the gap between the content's bottom edge and the top of the button is
`FabSpacing + fabHeight` — exactly what the shell publishes (the Android
shell's inner `Scaffold` takes horizontal insets only, and the navigation
suite consumes the bottom ones before it, so the bottom inset is not counted
twice).

Covered by the regression test
(`desktopApp/.../AgendaFabClearanceTest.kt`): the test renders Material3's
`Scaffold` with the button in the FAB slot, the way the desktop shell does,
so the button is placed by the framework and the test compares the last row
against the button's real bounds.

The snackbar's half of the contract is covered by
`desktopApp/.../AgendaSnackbarFabClearanceTest.kt`, and the popup's half — the
host inside `NotificationHost` — by
`desktopApp/.../NotificationPopupFabClearanceTest.kt`.

The latter publishes a large clearance and asserts that the toast leaves that
much clear below itself: a popup is its own semantics root, drawn above the
app's window, so a toast that ignored the clearance would cover the shell's
button rather than clear it.

The *other* ownership shape has its own test:
`desktopApp/.../SavedAgendaSnackbarFabClearanceTest.kt` renders one Material3
`Scaffold` holding both a screen-owned FAB and its native `SnackbarHost`, the way
`SavedAgendaListScreen` is built. It asserts the snackbar's action clears that
button, and that the placement is unchanged when an outer shell-like parent
publishes a non-zero `LocalFabBottomSpace` — the screen must not be consuming
someone else's clearance. It fails if the screen is switched to
`TaggedSnackbarHost` (the action would jump up by the shell's footprint) or if
the snackbar starts sitting over its own FAB.

`desktopApp/.../TagsUndoFabClearanceTest.kt` covers the third current screen:
the real `TagsScreen`, real `TagsViewModel` and a fake repository. It deletes a
tag and asserts the undo action clears the add button. Measured against the
button drawn inside the content, the action ended at y 626 while the button
started at y 568 — the same overlap the fix removes.

The popup's case was measured, at 360×640, on a shell-like `Scaffold` with the
button in the FAB slot at `FabPosition.End`. With the clearance the toast's
bottom edge is at y 564 and the button's top edge at y 568 — the toast clears
the button. Without it the toast's surface spans y 592..640 and x 8..352,
while the button occupies x 288..344 and y 568..624: the toast's action column
lands on the button, and because the popup's window is above the app's window,
the button is the thing that is covered.

The Android runtime was measured, by hand, on an AVD (`Medium_Phone`, API 36,
420 dpi) with the agenda holding 21 tasks, scrolled to its end. The bounds come
from the UI tree (`uiautomator dump`), not from a screenshot:

| Layout | Button | Content's bottom edge | Last row's bottom |
|---|---|---|---|
| phone, bottom navigation | top 1938, bottom 2085 | 2127 (the navigation bar's top) | 1917 |
| tablet, navigation rail | top 1492, bottom 1576 | 1504 | 1384 |

Both rows clear the button: 21 px (8 dp) on the phone, 108 px (72 dp — the
button's own height plus `FabSpacing`) on the tablet. And the button's bottom
sits exactly 42 px (16 dp = `FabSpacing`) above the content's bottom edge on the
phone, which is the formula above: the navigation suite has already shortened
the content by the navigation bar's height, so the bottom inset is not counted
twice.

Not covered: an automated Android test. The extended button and the navigation
suite's insets are not exercised by a test — there is no Compose UI harness in
the Android host tests — so the Android path rests on the formula above and on
this one measured run, not on a test that fails when the geometry drifts.

## Clearance ownership

`LocalFabBottomSpace` is the **shell's** button's clearance and nothing else. It
never means "whatever button happens to be visible in this screen", because a
screen that owns its own button would then lift its snackbar for a button that
is not there — or for two buttons at once.

Three cases, one owner each:

| Button | Snackbar | Who keeps the clearance |
|---|---|---|
| shell FAB (overlay, drawn by the shell) | screen's snackbar | `TaggedSnackbarHost` consumes `LocalFabBottomSpace` |
| screen's FAB in the screen's own Material3 `Scaffold` FAB slot | screen's snackbar | Material3 places the snackbar clear of the FAB slot. If the screen still uses `TaggedSnackbarHost` (it needs the countdown or the tagged action), the shell value must be `0` on that route — the button in the slot does the work, not the clearance |
| screen's FAB, overlaid independently of the `Scaffold` that places its snackbar | screen's snackbar | a dedicated, explicitly named screen-owned clearance and a host that consumes it. `LocalFabBottomSpace` is not widened or repurposed for it |

The middle row's "FAB slot" is the part that matters, and it is easy to get
wrong: a button drawn *inside* the content (a `Box` with
`Alignment.BottomEnd`) is invisible to Material3's placement, so the snackbar
lands on top of it and the button swallows the taps meant for the action. Both
of those were true of `TagsScreen` until #2814 moved its button into the slot.

A screen with a button but no snackbar host needs no clearance plumbing at all.

Never add the shell's clearance and a screen's own clearance together. Two
independent footprints do not sum into one meaningful number: the sum lifts the
snackbar for a button that is not there, and it is the double-clearance bug the
snackbar tests exist to catch.

Current screens:

- `SavedAgendaListScreen` owns a Material3 `Scaffold` FAB and a snackbar in the
  same `Scaffold`, so it keeps Material3's plain `SnackbarHost`. It does not add
  `LocalFabBottomSpace`, does not publish a footprint of its own, and does not
  migrate to `TaggedSnackbarHost` just for selector symmetry — a tagged action
  added here would need its own placement decision, not an inherited clearance.
- `TagsScreen` owns a FAB and a snackbar too, but keeps `TaggedSnackbarHost`
  because the undo needs the countdown bar and the tagged action. Its button
  therefore sits in the `Scaffold`'s FAB slot, which is what lets Material3 keep
  the two apart. It adds no clearance of its own and must see `0` from the
  shell — the tags screen is a settings tab, not a route with a shell button.
- `TextViewerScreen` puts `AttachmentAnnotationPanel` in its `Scaffold` FAB
  slot. The panel draws its own button but hosts no snackbar: its annotation
  error snackbar is collected by a `SnackbarHostState` that nothing renders, and
  this ADR does not change that. No clearance plumbing, no new host.
- The shell FAB is absent on the saved-view routes today, but that is not what
  makes the above correct — the ownership rule is. It must keep holding if
  `FabActionResolver` ever returns a button there.

## Consequences

- A new contract between the shell and the features: `LocalFabBottomSpace` in
  `core/ui`. A list that floats under the **shell's** FAB consumes it, and so
  does a snackbar host that has to clear that button; a screen-owned button does
  not (see Clearance ownership above). Anything that does not is unaffected
  (the value is `0`).
- Both snackbar hosts live under this contract and both use the value: the
  agenda's own host, and the one inside `NotificationHost`'s `Popup`. The popup
  is its own semantics root, drawn above the app's window, so its toast covers
  the shell's button instead of being covered by it — it takes the clearance for
  the same reason the agenda's host does. "Both hosts" means both hosts that
  clear the shell's button, not every snackbar host in the app.
- The clearance follows the button, not the host: the shell publishes `0` on a
  route without a button, so a toast on such a route stays on the bottom edge.
  A host that asked for the clearance of a button that is not there would leave
  a band of blank space.
- `FabSpacing` (16 dp) is duplicated here because Material3 keeps its own
  private. A change there is invisible to us — the value is small and the test
  asserts the outcome (the row is above the button), not the constant.
- The agenda lists reserve the space. The other lists that float under the FAB
  (projects, tags) are a separate task (#2213).
- The footprint is `0` until the button has been laid out, so a list that is
  drawn before the button has a size keeps no space — the button is not on
  screen yet either.

## Links

- Issue: https://github.com/gazon1/sing/issues/2743
- Issue: https://github.com/gazon1/sing/issues/2752
- Issue: https://github.com/gazon1/sing/issues/2814
- `shared/src/commonMain/kotlin/com/singularity/todo/core/ui/components/FabBottomSpace.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/core/ui/components/TaggedSnackbarHost.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/agenda/presentation/screen/AgendaContent.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/agenda/presentation/screen/SavedAgendaListScreen.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/tags/TagsScreen.kt`
- `shared/src/androidMain/kotlin/com/singularity/todo/AndroidShellNav3.kt`
- `shared/src/jvmMain/kotlin/com/singularity/todo/shell/DesktopShellNav3.kt`
- `desktopApp/src/jvmTest/kotlin/com/singularity/todo/feature/agenda/AgendaFabClearanceTest.kt`
- `desktopApp/src/jvmTest/kotlin/com/singularity/todo/feature/agenda/AgendaSnackbarFabClearanceTest.kt`
- `desktopApp/src/jvmTest/kotlin/com/singularity/todo/feature/agenda/SavedAgendaSnackbarFabClearanceTest.kt`
- `desktopApp/src/jvmTest/kotlin/com/singularity/todo/feature/tags/TagsUndoFabClearanceTest.kt`
- `desktopApp/src/jvmTest/kotlin/com/singularity/todo/core/ui/components/NotificationPopupFabClearanceTest.kt`
- ADR: `docs/decisions/2026-09-07-fab-chrome-level.md`
