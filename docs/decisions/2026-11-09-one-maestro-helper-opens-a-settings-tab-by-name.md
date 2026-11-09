---
title: "One Maestro helper opens a settings tab by name"
date: 2026-11-09
status: accepted
tags: [testing, maestro, settings]
---

# One Maestro helper opens a settings tab by name

## Context

Every Maestro flow that navigated the settings screen spelled out the same
steps: open the menu, find "Settings", tap it, wait for the tab control, open
it, scroll to the row, tap the row. The spelling drifted. Three flows (backup,
calendar sync, and one negative flow) tapped a settings tab *without opening
the menu first*, so they could not reach the tab they named at all; the others
waited for a dropdown that no longer exists, or scrolled a popup that the
narrow layout had replaced with a bottom sheet.

Once the narrow layout became a scrollable sheet, "scroll the row into view"
stopped being an optional flourish and became part of opening a tab: the last
group's rows are below the fold on a phone, and a flow that assumes the row is
on screen waits for an element that is not there.

## Idea

A flow says which tab it wants, not how to get there.

The steps of reaching a settings tab are the app's business. They belong in
one place, and the place knows the layout.

## Decision

`Maestro/helpers/settings-tab.yaml` is the one way to open a settings tab. It
takes the tab's name in `SETTINGS_TAB` and does the whole trip:

1. wait for the trigger (`settings_tab_dropdown`),
2. tap it,
3. `scrollUntilVisible` the row with `direction: DOWN` and `timeout: 10000`,
4. tap the row.

Every flow that navigates a settings tab runs this helper and passes the tab's
own test tag (`settings_tab_*`) as `SETTINGS_TAB`. `Maestro/helpers/open-settings.yaml`
opens the settings screen and stops there — it is the "get to Settings" half,
not a second way to reach a tab.

The helper selects by `id:`, never by `text:`. A tab's tag is the tab's
identity; its label is wording that can change and be localised.

`Maestro/CONVENTIONS.md` documents both helpers, and
`scripts/tests/test_run_maestro.py` (`MaestroHelpersInventoryTest`) keeps every
helper used by a flow and named in the conventions — a helper nobody calls, or
one nobody documented, fails the test suite.

## Rationale

Eighteen rows in a sheet means the row a flow names is often below the fold.
Scrolling is not a detail of the flow; it is part of what "open this tab" means
on a phone. When it lived in each flow, it was in most of them and in none of
the three that were broken.

`scrollUntilVisible` scrolls the centre of the screen, which is where the
sheet's column is; the flow works the same way it works for the app-menu
sheet, and the whole point of one helper is that this is decided once and
proved once — on a device, not in a review.

Selecting by `text:` is the fragility the conventions already forbid: the
label "Account" is also the title of a card on the Account tab, and "Settings"
appears in more than one place in the app.

## Consequences

- A new settings tab needs no flow change: the helper finds it by tag.
- A flow that spells out the open-and-tap steps is now a bug — the steps live
  in one file, and a second copy is a second answer to "how do you open a tab".
- The helper assumes the trigger tag exists and the row carries the tab's own
  tag. Both are part of the settings screen's contract
  (`2026-11-09-the-settings-screen-is-one-list-of-groups.md`), and
  `scripts/ci/validate-maestro-selectors.py` knows the vocabulary of settings
  tab ids.
- The helper scrolls. A flow that must reach a tab without scrolling (because
  it is asserting on something else that is already on screen) cannot use it —
  and no flow needs to, since scrolling to a tab and scrolling away from it
  are the same gesture.

## Links

- `Maestro/helpers/settings-tab.yaml` — the helper.
- `Maestro/helpers/open-settings.yaml` — opening Settings, and stopping.
- `Maestro/CONVENTIONS.md` — the helpers and what they are for.
- `scripts/tests/test_run_maestro.py` — `MaestroHelpersInventoryTest`.
- `scripts/ci/validate-maestro-selectors.py` — the selector vocabulary.
- `Maestro/flows/settings/03-cycle-tabs.yaml` — all thirteen tabs, through the
  helper, on a device.
- Issue #2111 — "one Maestro helper opens a settings tab by name".
