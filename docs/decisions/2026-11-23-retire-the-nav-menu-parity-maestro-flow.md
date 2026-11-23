---
title: "The nav/02-menu-parity Maestro flow is retired — the menu model owns the parity"
date: 2026-11-23
status: accepted
tags: [maestro, traceability, nav, menu]
issue: 3119
---

## Context

`Maestro/flows/nav/02-menu-parity.yaml` was the audit's top-ranked
replace-candidate (#3105): 35 s of device time for three visibility
assertions, and the guarantee behind them — "the menu offers the same
destinations on both platforms" — is a fact about `AppMenu` and
`DestinationKind`, two pieces of common code.

Issue #3119 asks for the flow to be retired, with four conditions: every
assertion of the flow must be matched to a named owner (not inferred from
the class name of a candidate), the headless owner must demonstrably fail
when parity regresses, only the duplicated behaviour may be removed, and the
catalogue, the scenario link and the ownership documentation must move in
the same change.

## Idea

1. **Delete the flow and trust the class name.** The flow's scenario id is
   `NAV-02`, and `MenuParityFlowTest` carries `NAV-02` in its
   `@DisplayName`. That is a link, not a proof of equivalence: the class
   could assert something entirely different.
2. **Read the two assertion sets against each other, entry by entry**, and
   keep the flow if any assertion of it has no owner on any tier.
3. **Delete the flow, and narrow the scenario's claim to the tier that
   still has a carrier** — the android cell becomes unclaimed, so the hole
   count does not move.

## Decision

**Option 2, and the flow goes — in full.**

The three assertions of the flow, and where each one lives now:

| Flow assertion | Owner after the retirement |
|---|---|
| `nav_tab_calendar` is visible (the Calendar is a bar tab, not a menu entry) | `Maestro/flows/nav/01-no-calendar-tab.yaml` (scenario NAV-01) — the tab bar's own flow, which asserts the Calendar tab's absence from the bar and the other tabs' presence; `Maestro/flows/nav/bottom-nav-tabs.yaml` covers the bar's tabs; the *model* half — Calendar is a tab and Pomodoro is a menu entry — is asserted by `MenuParityFlowTest` itself and by `shared/src/commonTest/.../shell/AppMenuTest.kt` |
| `menu_all_projects` is visible (the way into the full project list) | `Maestro/flows/menu/04-all-projects.yaml` (scenario MENU-06) and, device-side, `androidApp/src/androidTest/.../ui/MenuSheetBoundsTest.kt`, whose `requiredMenuTags()` is derived from `AppMenu` and adds `MENU_ALL_PROJECTS` explicitly |
| `menu_project_node_roadmap` is visible (a seeded project is listed in the menu) | `Maestro/flows/menu/01-projects-roots.yaml` (scenario MENU-08) — the flow that lists the projects' roots on the device |

No assertion of the flow was Android-only. The two that touch the sheet
(`menu_all_projects`, `menu_project_node_roadmap`) are sheet-reachability
properties, and reachability *as a resource id inside the sheet's own
window* is what `MenuSheetBoundsTest` already asserts for the whole entry
set — with a list derived from the model, so it cannot drift the way a
hand-written flow list can. The project row itself is covered on the device
by MENU-08's own flow, which lists the roots.

The scenario has a second half — the project tree — and it is owned by the
same class. Both surfaces draw the same section from the same state, and the
one switch between the two drawings is the expander: the sheet passes `null`
and draws the roots, the drawer passes a toggle and draws the tree (ADR
`2026-11-12-android-menu-roots-only.md`). The roots the two surfaces offer
are therefore the roots of one state, and `MenuParityFlowTest` asserts that
the two drawings offer the same root projects in the same order.

### The owner was shown to fail when parity regresses

The claim "the headless owner catches what the flow caught" was checked, not
assumed. One entry was added to `AppMenu.account` — a destination the
declared menu destinations do not contain — and the desktop test was run:

```
MenuParityFlowTest > NAV-02 Android and Desktop expose the same menu entries
and the same project tree FAILED
  org.opentest4j.AssertionFailedError: the menu offers something the declared
  menu destinations do not, or misses one ==> expected:
  <[Statistics, Notes, AiChat, Proposals, Search, Archive, Pomodoro,
  ProfileSwitcher, Settings, AiUsage]> but was:
  <[Settings, ProfileSwitcher, AttachAccount, Search, Statistics, Notes,
  AiChat, Proposals, Archive, Pomodoro, AiUsage]>
```

The probe was reverted in the same change; the tree carries no trace of it.
The Maestro flow would have kept passing, because the entry it spot-checks
(`nav_tab_calendar`, `menu_all_projects`, one project row) was untouched.

The scenario keeps both targets. `NAV-02`'s android cell is now an honest
hole, and the hole floor in `config/docs/traceability-ratchet.json` was
raised 71 → 72 with the reason next to the number — and lowered back to 71
when main closed the hole `PROFILE-01` was carrying in the same window (see
Consequences).

## Rationale

- **The model is the guarantee.** Both surfaces draw `AppMenu`; the parity
  assertion `DestinationKind.menuEntries.toSet() == AppMenu.allDestinations`
  fails on the commit that introduces a divergence, while the flow would
  keep passing as long as the three entries it spot-checks remained. The
  flow was strictly weaker than its owner.
- **The flow was the only android carrier of `NAV-02`, and that is the
  point.** A carrier that asserts the same thing from the other side is
  what the inventory called redundancy. Removing it opens one hole in the
  matrix, which the matrix is built to show.
- **Narrowing the claim to dodge the hole is the wrong trade.** Option 3
  would keep the number at 71 by deleting the promise "Android and Desktop
  expose the same menu" — the promise is still true, and it is still the
  scenario's title. The repository's precedent is to attach a carrier or
  split the scenario, never to narrow a claim; a narrower claim would have
  made the matrix green and the guarantee unwritten.
- **Attaching a weaker android carrier would contradict the issue.** An
  inert `@DisplayName` on the JUnit4 `MenuSheetBoundsTest` (plus a new
  `junit-jupiter-api` androidTest dependency) would fill the cell with a
  nightly-only marker that asserts nothing about parity. That is a hole
  painted over, not a guarantee.
- **The stale prose is fixed in the same place.** The scenario's
  `expected` said both surfaces offer the same entries "including
  Calendar". Calendar is a tab — `AppDestination.Calendar !in
  AppMenu.allDestinations` — so the sentence was wrong before this change.
  It now says what the model says.

## Consequences

- The Maestro suite loses one `full`-suite flow (35 s of measured flow
  time) and one android carrier of `NAV-02`.
- The scenario matrix shows `NAV-02` as `○` on android and `●` on desktop;
  the hole is recorded in the ratchet floor with the reason. The floor was
  raised 71 → 72 when the flow was retired, and lowered back to 71 when
  main's `PROFILE-01` change dropped that scenario's android claim in the
  same window: the hole `PROFILE-01` was carrying closed as this one opened,
  so the total is back at 71 and this retirement spends no hole budget. The
  composition moved — `NAV-02`'s android cell is the hole now — the budget
  did not.
- The menu's parity is now enforced in one place, in common code, on the
  host, in seconds — and the Android sheet's own reachability stays on the
  device where it belongs, in `MenuSheetBoundsTest`.
- The qualification artifacts (`config/docs/maestro-flow-qualification-initial.yaml`
  and `...-baseline.yaml`) are untouched: the frozen set is the historical
  record of the flows that existed when the rule was introduced, and the
  gate stays green.

## Review round 1

The independent review (ChatGPT, correctness role) returned one MAJOR
finding, and it was right:

> The surviving parity test does not verify the project-tree parity still
> claimed by NAV-02. Its model assertion compares menu destinations, and its
> UI assertion checks destination entries in the Desktop drawer. Neither
> assertion compares root projects or their ordering between Android and
> Desktop.

The claim was in the scenario's `expected` all along — "the same root
projects in the same order" — but no test asserted it: the desktop carrier
covered the entries and nothing else, so the matrix said `●` for a cell
whose guarantee was half-covered. The flow that was retired did not close
the gap either: it asserted that one seeded root was visible, never the
order and never the cross-platform equality.

The fix is in the owner rather than in the scenario: `MenuParityFlowTest`
now renders the two drawings of the shared section over one state — the
sheet's (`onToggleExpanded = null`, the roots) and the drawer's (the tree) —
and asserts that they offer the same root projects in the same order, and
that the sheet holds no child. The claim in `NAV-02.yaml` is unchanged; what
changed is that it now has a carrier that says it.

## Links

- Issue: https://github.com/gazon1/sing/issues/3119
- Parent inventory: https://github.com/gazon1/sing/issues/3105
- Owner: `desktopApp/src/jvmTest/kotlin/com/singularity/todo/feature/flows/menu/MenuParityFlowTest.kt`
- Android residue: `androidApp/src/androidTest/kotlin/com/singularity/todo/ui/MenuSheetBoundsTest.kt`
- Scenario: `scripts/src/traceability/scenarios/nav/NAV-02.yaml`
- Inventory: `docs/testing/maestro-guarantees-inventory.md`
- Related: `docs/decisions/2026-11-12-android-menu-roots-only.md`
