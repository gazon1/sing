---
title: "A desktop sheet has no half-expanded state"
date: 2026-11-12
status: accepted
tags: [ui, desktop, shell]
---

## Context

A bottom sheet on the desktop is a **window of its own**: Material3 renders
`ModalBottomSheet` as a `Dialog` on skiko, and the dialog has the size of the
window it opens over. The sheet's body is measured against that window and the
sheet is anchored to the window's bottom edge.

That is what makes the half-expanded state a defect on the desktop and not on
Android. Android's bottom sheet is a window of its own as well, but it is
anchored to the *screen* and its height is a fraction of the screen, so the
half-expanded sheet hangs inside the screen. The desktop sheet is as tall as the
window, so "half expanded" means half of it is *below* the window.

Measured in a 360x768 desktop window, before the fix:

```
sheet body   bounds=[0,432][360,1152]   ← 384 px below the window's bottom edge
Pinned switch bounds=[0,1069][360,1125] ← off-screen, unreachable
```

The sheet's own window reported the same 360x768 as the parent window, so the
overflowing half was not merely drawn outside — it was outside the window
altogether.

The Android behaviour was already settled by
`2026-10-22-a-sheet-opens-to-what-it-holds.md`: a sheet opens to what it holds,
and the half-expanded state is skipped where it does not fit. That decision was
written from the phone, where the half state is a useful peeker.

## Idea

1. Bound the sheet's height so that even the half-expanded anchor stays inside
   the window.
2. Skip the half-expanded state on the desktop.
3. Pin the action row to the bottom of the sheet, outside the scroll.

## Decision

We did (2). `rememberAppSheetState` is the state factory every sheet in the app
asks for, and on the desktop it removes `SheetValue.PartiallyExpanded` from the
enabled values:

```kotlin
enabledValues =
    if (isDesktopPlatform) enabledValues - SheetValue.PartiallyExpanded
    else enabledValues
```

Android keeps exactly the values it asked for — the half-expanded sheet is
still there, and the Maestro flows and the device measurements that were made on
the phone still describe what the phone does.

Measured in the same 360x768 window, after the fix:

```
sheet body   bounds=[0,48][360,768]     ← inside the window
Pinned switch bounds=[40,685][320,741]  ← on screen, no scroll needed
Apply button bounds=[273,728][336,768]  ← inside the window, after a scroll
```

## Rationale

(1) fights the framework: the anchor is computed from the sheet's measured
height and the window's height, and the sheet is *supposed* to be as tall as its
content. Capping it means a second layout rule on top of Material's.

(3) is a different product: the actions would leave the scroll and become a
fixed footer, which is not what the phone does, and the phone is the platform
this app is built for. The desktop sheet should look like the phone's.

(2) is the same rule the phone already follows, applied where the half state
does not fit: on the desktop the half state is not a peeker, it is the sheet
hanging out of the window.

## Consequences

- Every desktop sheet opens expanded and stays inside its window. The action
  row is still the last item of the scrollable body, exactly as on Android.
- The rule lives in one place. A sheet that asks for
  `enabledValues = persistentSetOf(Hidden, Expanded)` — the menu, the settings panel, the
  attachments sheet — is unaffected, and a sheet that asks for the default gets
  the desktop's rule applied to it.
- `isDesktopPlatform` is a new platform fact next to `isAndroidPlatform`; the
  repository has two apps, so exactly one of the two is true.
- The Android sheet is untouched. `SheetValue.PartiallyExpanded` is still in
  its enabled values, and the phone's half-expanded sheet is still the peeker
  the phone's flows expect.
- The desktop test asserts the sheet's body is inside its window and that a
  switch from the lower half of the body is on screen without a scroll — the two
  facts that were false before.

## Links

- `shared/src/commonMain/kotlin/com/singularity/todo/core/ui/components/sheet/BottomSheetHost.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/core/ui/Platform.kt`
- `desktopApp/src/jvmTest/kotlin/com/singularity/todo/feature/search/SearchScreenSheetSmokeTest.kt`
- `docs/decisions/2026-10-22-a-sheet-opens-to-what-it-holds.md`
