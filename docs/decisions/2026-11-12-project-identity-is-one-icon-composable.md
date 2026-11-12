---
status: accepted
title: "Project identity is one icon composable; surfaces choose size and state"
date: 2026-11-12
tags: ["ui", "projects", "identity"]
---

## Context

Issue #2863 asks for a shared presentation contract for a project, so the same project is
recognizable in the menu tree, the project list and the task Project Picker. The measured
comparison (`docs/research/2862-project-navigation-comparison.md`) found three visual languages
for one object: the menu drew an 18dp tinted glyph, the project card a 32dp colour circle with a
20dp glyph, and the picker ignored `Project.icon` entirely and drew a grey `Folder`. Long names
wrapped in the menu and the picker; the menu also passed the project name as the icon's content
description, so a screen reader announced the same project twice.

The parent issue (#2861) rules out both extremes: one universal row composable for every surface,
and a second identity model or emoji library.

## Idea

1. **One universal project row** used by the menu, the card list and the picker. Rejected: the
   surfaces legitimately differ in trailing controls, density and selected state, and forcing one
   row would make each surface carry the others' layout.
2. **A shared resolution contract, local layout.** Every surface draws the stored icon through the
   existing `ProjectIcon`; `resolveProjectIcon` remains the single resolver (registered Material key
   vs. free-form emoji vs. `Folder` fallback). Each surface keeps its own size, tint and selected
   treatment.
3. **A new identity type or model.** Rejected: `projects.icon` is already the identity, and the
   resolver already handles every form the importer stores.

## Decision

**A project is drawn through `ProjectIcon(icon = project.icon, …)` on every surface. The surface
owns `size`, `tint` and selection; the resolver, fallback and glyph rendering do not vary.**

- The icon is decorative when the row's title names the project: pass `contentDescription = null`,
  so the accessible name is the title, not the title twice.
- Compact rows keep the title on one line: `maxLines = 1`, `TextOverflow.Ellipsis`.
- An emoji is drawn as one grapheme: `maxLines = 1`, `softWrap = false`, clipped rather than
  wrapped, so a ZWJ sequence, flag, skin-tone modifier or variation selector is never split.
- Interactive project rows are `Role.Button` with one click target; a surface's own expander or
  trailing action stays a separate control.

## Rationale

- **It reuses what is already tested.** `ProjectIconRegistry`, `resolveProjectIcon` and
  `ProjectIcon` already implement the rule the issue asks for; the defect was that two surfaces did
  not call them, not that the rule was missing.
- **The picker was the real gap.** It drew a folder for every project regardless of stored icon, so
  the same project looked different there and nowhere else. Replacing that one icon call fixes the
  measurable difference without touching the picker's layout.
- **One line is a property of the row, not of the title.** The menu and the picker are compact
  scanned lists; the card has room to wrap. Keeping the policy on the two compact surfaces avoids a
  global truncation rule the card does not need.
- **No new dependency or abstraction.** The issue forbids an emoji library, a sprite sheet, a
  duplicate resolver and a global row-height abstraction; this decision adds none.

## Consequences

- The Project Picker now shows each project's stored emoji (or its Material icon), and only falls
  back to the folder when the project has no icon.
- A menu row announces its project name once; the icon is decorative.
- A long project name in the menu or the picker is ellipsized, not wrapped; project data is
  unchanged.
- The card keeps its 32dp colour circle and 20dp glyph — the contract fixes the glyph source, not
  the layout.
- Hierarchy, row height and search in the picker remain out of scope (#2864, #2865, #2866).
- Tests: `ProjectIconResolverTest` pins composed sequences and the fallback;
  `desktopApp … ProjectIdentityTest` pins fallback, composed emoji, one-line row and `Role.Button`.

## Links

- Issues: #2863, parent #2861, follow-ups #2864, #2865, #2866, #2867
- Research: `docs/research/2862-project-navigation-comparison.md`
- Code: `feature/projects/.../components/ProjectIcon.kt`, `ProjectIconResolver.kt`,
  `ProjectIconRegistry.kt`, `feature/projects/.../components/ProjectTreeSection.kt`,
  `feature/tasks/.../ProjectPickerSheet.kt`
- Decisions: `2026-11-12-android-menu-roots-only.md`,
  `2026-11-10-the-menu-projects-section-is-folded-by-default.md`
