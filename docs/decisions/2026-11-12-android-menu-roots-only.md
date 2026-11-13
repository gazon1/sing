---
status: accepted
title: "The Android menu lists root projects; Desktop keeps the tree"
date: 2026-11-12
tags: ["ui", "navigation", "menu", "projects"]
---

## Context

Issue #2862 asks for an explicit decision on the Android project-menu model. The measured comparison
(`docs/research/2862-project-navigation-comparison.md`) found that the reference mobile app
lists only root projects and enters a project to reach its children, while our Android menu draws
the whole open hierarchy inline. On the imported profile that is **122 open projects with 20 roots,
four levels deep**; a one-line row measures 33dp, so the inline list is ~7.4 screens against ~1.2
for the roots. The same measurement found a second, unrelated cost of the inline rows: the expander's
48dp touch target reaches 15dp into the next row, and a tap 7px inside a child row opened its parent.

Issue #2740 already folded the section by default and put "All projects" above the tree. That
bounds the sheet's height but leaves the tree as the only way to a deep project from the menu. This
decision is not permission to drop the tree, and it must not change Desktop by accident.

## Idea

1. **Inline tree, status quo after #2740.** Every project reachable in one tap after scrolling; the
   sheet's height is bounded only by folding; a nested row can still be hit by its parent's expander.
2. **Roots-only on Android, tree on Desktop.** The menu becomes a launcher; the hierarchy is
   explored inside a project, where the project screen already shows sub-projects.
3. **Roots-only on both platforms.** One model everywhere, but the Desktop drawer is persistent,
   wide and pointer-driven, and the tree is not in the way of its destinations — dropping it would
   remove a working affordance for no measured reason.

## Decision

**Android's menu draws the root projects only. Desktop keeps the full tree.**

Tapping a root opens that project's detail through the existing `ProjectsOpener` rendezvous
(`ProjectsOpenRequest.Project`), the same call the tree's rows make today. The Android list has no
expander: a row with children and a row without behave identically — both open the project. Children
are reached from the project screen's existing "N sub-projects" entry.

"All projects" stays above the list, and the section stays folded by default. Up from a project is
the system back, which leaves the detail on the Projects list — the graph's start route — and from
there exits the graph. The shell title names the open project (#2734), so location is not inferred
from a highlighted row.

## Rationale

- **Measured need.** 20 rows instead of 122: ~1.2 screens instead of ~7.4. The menu's job is "where
  do I go", and a launcher that is seven screens long is not doing that job.
- **The hierarchy is not lost, it moves.** The project screen already renders "N sub-projects" and
  opens `ChildProjectsSheet` (`ProjectDetailHero.kt:191-196`), so drilling in is a path that exists,
  not one this decision has to invent.
- **Roots-only removes the touch-target defect on Android.** With no `IconButton` in the list there
  is no 48dp target to overflow into the next row, so the measured "child's top belongs to the
  parent" cannot happen here. Rows are still below the 48dp touch guidance; that is the separate
  row-height decision, still wanted for its own reasons.
- **Desktop is different on purpose.** Its drawer is persistent and wide, and the tree is not above
  its destinations. It keeps the expanders, the per-project folding and the one-tap reach.
- **#2740 survives.** The fold is still needed: 20 roots plus the destinations above them is taller
  than the sheet. Only the size of what the fold hides changes.
- **The route is already supported.** #2728/#2734/#2735 established that a project opened from the
  menu gets its own title and no wrong FAB (`AppDestination.ProjectsGraph` resolves to `null`), so
  entering a project from the menu is a supported, tested path today.

## Consequences

- The Android menu never draws a project below a root; a deep project is reached through its parent
  project's sub-projects, not from the menu.
- An Android menu row carries no expander; `menu_project_expander_*` exists only on Desktop. No
  Maestro flow taps a per-project expander today, so the flows keep working; a future Android flow
  must not look for one.
- The per-project fold state (`ProjectsTreeSettingsRepository`) stays: Desktop reads it, and the
  Project Picker will. Do not delete it because one surface stopped reading it.
- "All projects" remains above the list, folded or not; the section remains folded by default, with
  the transient fold of #2740 unchanged.
- Search stays in the Projects list. The menu still has none, and that is not revisited here.
- One tree model (`ProjectTreeBuilder`) and one tree composable remain; Android draws the depth-0
  subset rather than a second list type. No new destination, no new persistence.
- The switch between the two drawings is one nullable callback: `ProjectTreeSection` draws the depth-0
  subset when `onToggleExpanded` is `null`, and the tree when it is not. The Android sheet passes
  `null` and carries no expander column at all; the Desktop drawer passes its fold toggle. A list
  has no hierarchy to show, so a hierarchy that is not drawn is not there.
- If research later shows the back target surprises users — detail → Projects list, not detail →
  menu — the narrow change is to push the detail without the list; it is not a reason to reopen the
  roots-only decision.
- The row-height and emoji work from the comparison research is not part of this decision and is not
  unblocked by it.

## Links

- Issues: #2862, parent #2861, #2740 (fold + All projects), #2728 (nested route research),
  #2734 (nested titles), #2735 (nested FAB)
- Research: `docs/research/2862-project-navigation-comparison.md` (row heights, hit-testing,
  122/20 counts, 24dp picker indent)
- Decisions: `2026-11-10-the-menu-projects-section-is-folded-by-default.md`,
  `2026-09-08-projects-ux-rework.md`
- Code the decision names: `shell/MenuBottomSheet.kt`, `shell/ProjectsMenuSection.kt`,
  `feature/projects/.../components/ProjectTreeSection.kt`,
  `androidMain/.../AndroidShellNav3.kt`, `jvmMain/.../DesktopShellNav3.kt`,
  `feature/projects/.../nav/ProjectsOpener.kt`,
  `feature/projects/.../screen/ProjectDetailHero.kt`
- Scenario: `scripts/src/traceability/scenarios/menu/MENU-07.yaml`; flows
  `Maestro/flows/menu/01-projects-tree.yaml`, `03-open-project.yaml`, `05-tree-contents.yaml`,
  `06-tree-live.yaml`, `10-large-projects.yaml`
