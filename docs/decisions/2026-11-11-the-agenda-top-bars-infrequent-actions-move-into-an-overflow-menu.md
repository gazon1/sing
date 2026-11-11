---
title: "The agenda top bar's infrequent actions move into an overflow menu"
date: 2026-11-11
status: accepted
tags: ["ui", "compose", "accessibility", "onboarding"]
---

# The agenda top bar's infrequent actions move into an overflow menu

## Context

The agenda's top bar carried three icon buttons, each with an English content
description and nothing else: "task on several dates", "saved views", "save current
agenda". Three unlabelled glyphs in one row told the user nothing about any of
them, and the tour had to point at two of them to say what they do.

The issue that asked for the change (#2746) counted four icons. There are three:
the fourth is the shell's own search button, which the Android shell draws next to
the screen's published actions and which is not the agenda's to move.

Two of the three buttons were anchored by the onboarding tour
(`SpotlightAnchorId.SavedViews`, `SpotlightAnchorId.SaveCurrent`), and a tour step
is skipped rather than waited on when its anchor never registers
(`SpotlightTourStateMachine`). A `DropdownMenu` row lives in the popup's own
window, so it cannot be the tour's target: the tour measures its targets in the
window it draws in.

## Idea

- Keep one action visible and label the rest.
- Put the labelled actions in the overflow menu the app already uses elsewhere
  (`ProjectDetailTopBar`, `TaskDetailTopBar`) rather than inventing a component.
- Point the tour at a target that is still on screen.

## Decision

**Saved views stays a button on the bar. The other two become rows of an overflow
menu, and the tour's second step points at the overflow button instead of at the
row it used to point at.**

- The bar draws a `MoreVert` icon button (`AGENDA_OVERFLOW_BUTTON`) that opens a
  `DropdownMenu` with two labelled rows: "Task on several dates" and "Save current
  agenda". The rows keep their old tags (`AGENDA_MULTI_DATE_BUTTON`,
  `AGENDA_SAVE_CURRENT_BUTTON`), so the flows that addressed them still address
  the same action.
- The labels come from the resource bundle (`agenda_multi_date`,
  `agenda_save_current`), in both locales. A menu row without a label is the
  defect the menu was meant to fix.
- The overflow trigger wears its own description (`agenda_more_actions`,
  "More actions"), not `Res.string.menu` ("Menu"). The desktop shell's drawer
  button already reads "Menu", and two buttons with the same description make
  every "the Menu button" selector ambiguous — the desktop flows that open the
  drawer failed on exactly that.
- The menu owns itself inside the published actions block. On Android the shell
  owns the only bar, so a menu drawn outside that block would be anchored to a
  composable the shell never renders — and would never open
  (see `TaskDetailTopBar`'s KDoc).
- The menu is a popup window of its own, so it carries
  `Modifier.mapTestTagsAsResourceIds()`: the bar's own `testTagsAsResourceId`
  does not reach into it, and without the mapping a Maestro `id:` selector finds
  nothing.
- `SpotlightAnchorId.SaveCurrent` becomes `SpotlightAnchorId.AgendaOverflow`, and
  the second tour step's copy points at the ⋮ button at the end of the top bar.
  The copy names what the user can *see*: the trigger's own description
  ("More actions") is a label the bar does not draw, and the tour used to name a
  "More menu" that the app never had.
  `SpotlightContent.VERSION` goes to 2, so the reworded step is shown again.

## Rationale

- **One visible action, and it is the one the tour already pointed at.** Saved
  views is the action people reach for; the issue calls it the "list" action, and
  it is the one whose anchor already worked. Nothing in the repository says which
  of the three is used most, so the choice was made on what the code already
  treats as the agenda's own entry point.
- **The tour needed a target that is on screen.** A step whose anchor never
  registers is skipped silently — the user would never see it and nobody would
  notice. Re-anchoring the step to the overflow button keeps both steps visible
  and teaches the honest thing: the action is one tap further than it was.
- **The menu rows keep their tags.** A flow that says "the save-current action" by
  id keeps saying it; only the way there changed. Changing the id would have
  meant renaming it in every flow and in every test for no gain.
- **The labels are resources, not literals.** The issue asked for localized
  visible labels and accessibility descriptions, and the three old content
  descriptions were hardcoded English. All three now come from the bundle, in
  both locales there are.

## Consequences

- A tag that belongs to a menu row is only in the tree while the menu is open.
  Tests that assert such a tag is *displayed* have to open the menu first — this
  is the cost of the move, and it is paid in the tests rather than in the app.
- The tour re-runs once for everyone (`VERSION` 2). The copy changed, so the
  version is what says so.
- **The tour's copy is English-only, and stays that way.** Both steps are
  literals in `SpotlightContent`, and this change adds a sentence to one of them:
  a Russian user reads the tour in English. That is the tour's pre-existing gap,
  not one this change opened — but the version bump widens its reach, because
  the reworded step now reaches users who had already finished the tour.
  Localizing the tour means making `SpotlightStep.body` a resource, which is a
  change of its own and did not belong here.
- The overflow button is the agenda bar's anchor for the tour now, and the
  toolbar row it opens is the only place the two infrequent actions are named.

## Links

- #2746 — the issue this implements.
- `2026-10-21-the-popups-open-their-own-windows-too` — a popup's content is in
  the scene, in a root of its own.
- `2026-10-04-undo-snackbar-and-popup-testtags` — a popup owns its own
  semantics root, and its rows need their own ids.
- `2026-10-07-spotlight-onboarding-measures-its-targets` — how the tour measures
  and skips its targets.
