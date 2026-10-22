---
title: "A sheet opens to what it holds"
date: 2026-10-22
status: accepted
tags: [ui, shell, android]
---

## Context

The app's menu is a `ModalBottomSheet`. The sheet holds eight entries in three
sections, and its content is about 1286px tall on a 720x1600 phone.

Material3 lets a bottom sheet settle in a partially expanded state — half the
screen — and the sheet settled there. The content did not fit in the half, and
Compose does not clip a sheet's content to the sheet: the content ran past
the sheet's bottom edge and past the bottom of the screen. The system
navigation bar covers the last 96px, so the sheet's lower entries were drawn
*under* it.

Measured on the device before the fix:

```
menu_sheet        bounds=[0,0][720,1450]
menu_settings     bounds=[32,980][688,1092]
menu_quick_search bounds=[32,1298][688,1410]
menu_statistics   bounds=[32,1496][688,1600]     ← under the navigation bar
```

A tap on `menu_statistics` did not reach the app. It reached the system HOME
button: the logcat shows the launcher coming to the front
(`change the database and preApp is : com.singularity.todo, nextApp is :
com.android.launcher3`), and the flow that followed — waiting for
`statistics_tab_tasks` — failed on a screen that was no longer the app's.

The entry was not merely unreachable: the tap *did* something, and what it did
was leave the app.

## Idea

1. Make the menu shorter so it fits in the half-height.
2. Let the sheet scroll and leave the lower entries below the fold.
3. Open the sheet to its content: skip the partially expanded state, and let
   the content scroll if it still does not fit.

## Decision

We did (3). `MenuBottomSheet` creates its sheet state with
`enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)`, and the menu
column scrolls.

## Rationale

(1) would cut the menu down to a list of icons, and the entries are named
destinations — the names are the point.

(2) is what the sheet did by accident, and it is the worst of the three: the
entry that a user wants is drawn where the system bar is, so the tap goes to
the system. A screen that looks complete and cannot be used is worse than one
that says it scrolls.

(3) makes the sheet's height follow its content, which is what a menu wants:
the user opened it to see what it holds. The scroll is the safety net for a
screen shorter than the menu.

## Consequences

- The menu opens full-height. On a 720x1600 phone every entry is on the
  screen: `menu_statistics` sits at y=846..958, well above the navigation bar
  at y=1504.
- The menu column scrolls, so a shorter screen still reaches every entry.
- The sheet state is created with `enabledValues` rather than the deprecated
  `skipPartiallyExpanded` flag — the Material3 version in this project takes
  the set of values the sheet may settle in, and the old flag is not part of
  its API.
- `TaskEditorSheetHost` and `BottomSheetHost` still open partially. Their
  content is short enough to fit, so the half-height is a peeker rather than a
  clip. If one of them ever holds more than a half-screen of content, this
  decision applies to it too.

## Links

- `shared/src/commonMain/kotlin/com/singularity/todo/shell/MenuBottomSheet.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/core/ui/components/sheet/BottomSheetHost.kt`
- `Maestro/flows/smoke/03-health-blocked-drilldown.yaml`
