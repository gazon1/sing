---
title: "The settings screen is one list of groups, and the two layouts are two readers of it"
date: 2026-11-09
status: accepted
tags: [architecture, settings, compose]
---

# The settings screen is one list of groups, and the two layouts are two readers of it

## Context

`SettingsScreen.kt` had two layouts for the same thirteen settings tabs, and
they were not the same screen.

On a wide window the screen showed five labelled sections with icons
("Appearance", "Productivity", "AI & Account", …), each holding the tabs under
it. On a phone it showed a dropdown of thirteen undifferentiated rows —
"Interface", "Agenda", "Notifications", "AI Provider", … — in an order that
matched neither the rail's grouping nor the tab order, and the collapsed button
named only the current tab, so "Account" gave no hint that it lived under
"AI & Account". The phone layout was not a narrower version of the wide one; it
was a second information architecture, and it was the one most people used.

Then the phone layout outgrew the dropdown. The menu listed five headings and
thirteen tabs — eighteen rows — inside a popup shorter than the screen, so the
rows of the last group sat below the fold.

## Idea

The groups are the content. The layout is a reader of the content.

One list of groups, in one order, is the settings screen. The wide rail and the
phone sheet are two ways of drawing that list, and neither of them is allowed
to know the list's contents — they only render it.

## Decision

`SettingsNavigation.kt` holds `SETTINGS_GROUPS`: five `SettingsGroup`s, each
with its title and its tabs, in the order the wide rail shows them.
`SettingsMenu.kt` flattens that list into `SETTINGS_MENU_ROWS` — a heading row,
then a row per tab — and `SettingsScreen.kt` renders the *same* row list in
both layouts: `SettingsNavRail` for a wide window, `SettingsTabSheet` for a
narrow one.

The two consequences that follow from "one list, two readers" are load-bearing:

- **The phone screen groups its rows.** The sheet draws the headings and the
  tabs under them, in the rail's order, so the phone screen is the rail's
  information architecture at a smaller size. `SettingsTab.group` and
  `SettingsTab.groupedLabel` ("AI & Account › Account") answer "where does this
  tab live" without a second list to keep in step; the lookup is a map
  (`SETTINGS_GROUP_BY_TAB`), so the answer costs a hash, not a scan.
- **The phone screen scrolls.** Eighteen rows do not fit a phone, so the sheet
  is a scrollable column and the rows below the fold are reached by scrolling.
  The wide rail does not need it and does not have it.

The sheet is a `ModalBottomSheet` that opens expanded. The trigger keeps its
`settings_tab_dropdown` test tag and names the section and the tab it is on.

## Rationale

The old phone layout failed the one test that matters for a screen that exists
on two form factors: "open Settings on a phone and on a wide window — the two
must offer the same groups in the same order". It offered neither the same
groups nor the same order. Any fix that keeps two lists will drift again the
first time a tab is added, because adding a tab means remembering to add it in
two places, in two orders, under two headings.

A bottom sheet is the house pattern for a picker of this size, and a sheet that
lists eighteen rows without grouping is the same information problem the
dropdown had, in a taller box.

## Consequences

- Adding a tab is one entry in `SETTINGS_GROUPS`. The rail, the sheet, the
  trigger's label and the tab's group all follow from it.
- The two layouts cannot drift: they iterate the same `SETTINGS_MENU_ROWS`.
- The sheet scrolls and the rail does not, so a flow that reaches a tab on a
  phone has to scroll — see
  `2026-11-09-one-maestro-helper-opens-a-settings-tab-by-name.md`.
- The tab's group is an O(1) lookup. The first version scanned the group list
  for every row it drew, which is a list walk per row per frame of a scrolling
  sheet.

## Links

- `shared/src/commonMain/kotlin/com/singularity/todo/feature/settings/SettingsNavigation.kt`
  — `SETTINGS_GROUPS`, `SETTINGS_GROUP_BY_TAB`, `SettingsTab.group`,
  `SettingsTab.groupedLabel`.
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/settings/SettingsMenu.kt`
  — `settingsMenuRows`, `SETTINGS_MENU_ROWS`.
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/settings/SettingsScreen.kt`
  — `SettingsNavRail` and `SettingsTabSheet`, the two readers.
- `shared/src/jvmTest/kotlin/com/singularity/todo/feature/settings/SettingsNavigationTest.kt`
  — the group list and the tab's group.
- `shared/src/jvmTest/kotlin/com/singularity/todo/feature/settings/SettingsMenuTest.kt`
  — the flattened row list.
- Issue #1712 — the two information architectures, and the account area that
  named nobody.
- Issue #2111 — the narrow menu's rows below the fold.
