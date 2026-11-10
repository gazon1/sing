---
title: "The menu's Projects section is folded by default, and the way to the full list stays above the tree"
date: 2026-11-10
status: accepted
tags: [ui, navigation, menu, projects]
---

## Context

The app menu carries the projects as a tree. A profile with a hundred projects drew a
hundred rows into the sheet, and the sheet became thirty screens tall. Everything the
menu is for — the destinations, the way into the full project list — sat above a list
the user had to scroll past to reach the end of, and the sheet's own height was the
problem: a menu that is thirty screens tall is a menu nobody reads to the end.

The issue (`gazon1/sing#2740`) asked for two things: the primary destinations must stay
reachable before the project tree, and the project section must be collapsible with a
clear route to the existing All Projects list.

Two ways to reach the same end were on the table.

## Idea

Fold the section by default, and move the way into the full list above the tree.

The folding is the user's, and it is the user's for the moment: it is not an
arrangement of the profile that has to be kept between sessions the way the
per-project folding is. The section therefore lives in the transient state of
`ProjectsTreeViewModel` — the fold belongs to the decision of the moment, and it does
not outlive the ViewModel. That is the rule of ADR
`2026-10-20-a-transient-state-carries-what-the-user-chose`: the choice the user made
sits in the state, and a later tree arriving does not throw it away.

## Decision

**The section is folded by default, and the fold is a field of the tree's UI state.**

`ProjectsTreeUiState` carries `sectionExpanded`, and every variant declares it as a
constructor parameter without a default — the same shape as the continuity fields of
ADR `2026-10-20`. `ProjectsTreeIntent.ToggleSection` flips it. A tree that arrives
later — a project created elsewhere — is written with `updateState`, so it carries the
fold the user chose into the new tree instead of folding the section back.

**The toggle is a button in the section's heading, and its tag names the action it
offers.** While the section is folded the button carries `menu_projects_expand`; while
it is unfolded it carries `menu_projects_collapse`. A test that has to know which state
the section is in should not have to infer it from the tree's length, and a flow that
unfolds a section which is already unfolded taps a button that is not on the screen.

**The way into the full list is above the tree.** "All projects" is a
`NavigationDrawerItem` of the section's heading, drawn before the tree. The tree is a
way into the projects, not the only way, and the route to the list is reachable whether
the tree is folded or not. Both surfaces draw the one `ProjectsMenuSection`, so the
Android sheet and the Desktop drawer offer the same route.

**The fold is transient; the per-project folding is kept.** The per-project folding
stays in `ProjectsTreeSettingsRepository`, per profile: which projects a person has
folded away is their arrangement. The section's fold is not an arrangement — it is the
answer to "do I want to see the tree now" — so it is held in the state and nowhere
else.

**No search in the menu.** The issue allows a project search only if it reuses the
existing project-list search. The list with its search is one tap away, above the tree,
so a second search implementation in the menu would be a second search for the same
projects.

## Rationale

The menu is opened for a destination. A menu that opens onto a hundred project rows
makes the user scroll to find the thing they came for, and makes the sheet's height
depend on the size of the profile rather than on the size of the menu. Folding the
tree makes the sheet's height a property of the menu again.

Moving "All projects" above the tree is what keeps the fold from becoming a dead end:
the list, with its search and its sorting, stays one tap away, and the user does not
have to unfold the tree to reach it.

The fold is not persisted because a persisted fold is a claim about the profile that
the profile cannot make. A profile's project set changes; the tree's length changes
with it; a fold remembered from a profile with three projects is a surprise in a
profile with three hundred. The per-project folding is different — it names the
projects themselves, so it survives the profile's growth.

## Consequences

- The tree is not drawn until the user unfolds it: the menu's own height no longer
  depends on the number of projects.
- The menu keeps one tree and two drawings: the fold is the same on the Android sheet
  and in the Desktop drawer, and the parity between them stays a fact rather than a
  claim.
- The flows that address the tree unfold the section first; the unfold is safe to
  repeat, so a flow that opens the menu twice is not broken by a section that stayed
  unfolded.
- The fold does not survive the ViewModel: opening the menu in a new session shows
  the section folded, which is the default the issue asks for.

## Links

- Issue `gazon1/sing#2740`
- ADR `2026-10-20-a-transient-state-carries-what-the-user-chose`
- ADR `2026-09-08-projects-ux-rework`
- Scenario `MENU-07` — `scripts/src/traceability/scenarios/menu/MENU-07.yaml`
