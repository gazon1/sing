---
title: An empty task section is hidden — the `+ Ещё` sheet is the way in
date: 2026-11-13
status: accepted
slug: task-detail-empty-section-visibility
---

# An empty task section is hidden — the `+ Ещё` sheet is the way in

## Context

Issue #2014 asked for one answer to "does the task detail draw a child section that
has nothing in it?" and settled on **draw it, and let it say so**: the empty
checklist card, the empty subtasks card and the empty logbook card stay on the
screen, and each says in a line of its own that it is empty.

That answer was reversed in code without a record. The checklist section and the
subtasks section now carry an inline add-row and were kept on the screen for a
different reason — the add-row is the only way to add the first item — while the
attachments, the related tasks, the time tracking and the logbook kept being drawn
empty. A task with only a title opened onto a stack of cards that said nothing:
the research in #2871 measured six empty sections, about 55% of a two-screen scroll
(`UXF-04`).

The measured target (`docs/research/task-detail-ux/implementation/target-ux.md` §2–§3)
says the opposite of the code: **a group is drawn when it has content**, and a
group's entry point stays reachable in at most one extra tap. The wireframe draws
the way back as one row — `+ Ещё` — above the footer.

Three other issues touch the same ground and must not be duplicated:

- **#2032** asks for the three empty children cards to collapse into one compact
  row. The `+ Ещё` row is that row, generalised to every optional property.
- **#2532** asks for one owner of the "a section's title carries a count only when
  there is something to count" rule, so the four sections cannot half-apply it.
- **#2872** is the tracking parent of the whole Task Detail rework.

## Idea

Three ways to give the screen one rule.

1. **Keep drawing the empty sections.** What the code does now. The screen spends
   its first screen on cards that say nothing, and the density problem stays.
2. **Hide every empty section and drop the ways in.** Compact, and the checklist,
   the subtasks, the attachments and the logbook become unreachable — which is the
   failure #2014 was written to prevent.
3. **Hide the empty sections and add one row that lists what is hidden.** The
   screen is compact, and every optional property keeps a way in: the checklist and
   the subtasks keep a one-line header of their own, and everything else is listed
   in `+ Ещё`.

Option 3 is what this ADR takes.

## Decision

### The rule

1. **A section with nothing in it is not drawn.** The attachments, the logbook, the
   related tasks and the time tracking are not on the screen of a task that has
   none of them. The workflow-state row is not drawn when nothing was claimed.
2. **The checklist and the subtasks keep a one-line header.** They are the two ways
   to write into the task without opening a sheet, so their header is always on the
   screen — closed, and opened by a tap on the header. They are the two exceptions
   to rule 1, and they are exceptions because their way in *is* the header.
3. **Everything else that is hidden is listed in `+ Ещё`.** The row is the last row
   of the body. It opens a sheet that lists exactly the optional properties that are
   not on the screen at that moment — derived, not a static list, so it shrinks as
   the task fills. Picking one adds the property to the body and opens it.
   A property that was added this way stays on the screen even while it is still
   empty: the user asked for it.
4. **A section with content is drawn collapsed.** The header row is the single
   expand/collapse target and carries the section title and its count. Expanding
   shows the content; collapsing hides it and changes no data. Nothing is expanded
   automatically on open — a section that opens itself pushes the rows below it
   off the screen.
5. **One owner for the section title.** `sectionTitle` produces `Title` for an empty
   section and `Title (2/5)` for a non-empty one. No section reports a count over
   nothing, and no section writes that rule of its own. This is the title half of
   #2532; the empty-line half has no customer left once the empty sections are
   hidden.

### Reachability

Every optional property is reachable within two deliberate actions:

| Property | Way in | Actions |
|---|---|---|
| Checklist | its header | 1 |
| Subtasks | its header | 1 |
| Attachments | `+ Ещё` → Attachments | 2 |
| Logbook | `+ Ещё` → Logbook | 2 |
| Related tasks | `+ Ещё` → Related tasks | 2 |
| Time tracking | `+ Ещё` → Time | 2 |
| Workflow state | `+ Ещё` → State | 2 |

### What the sections do when they are expanded

The five collapsible sections — the checklist, the subtasks, the attachments, the
time tracking and the logbook — share one header: the icon, the title with its
count, and the expand affordance. The header row is the only target that toggles
the section; tapping it changes that one section and no other.

Collapsing does not lose an edit: the add-row's text field state belongs to the
section, not to the expanded content, so a half-typed checklist item survives a
collapse and an expand.

### The `+ Ещё` sheet

The sheet lists the properties that are currently hidden. Picking one:

- reveals the property (it stays on the screen from then on), and
- opens the section, so that what was just added is the thing under the user's
  finger.

It opens nothing else. A sheet that appears on top of the sheet the user just
chose is a sheet the user cannot tell apart from the first, and every property
has its own way in once it is on the screen — the attachments row opens the
attachments sheet, the logbook's header carries its add-note button, the
related-tasks section carries its add row, the time section carries its start
chip.

There is no pin: the rule "shown when it has a value" already decides visibility,
and a second mechanism saying the same thing would be a second source of truth.

## Rationale

**The screen is a screen, not an inventory.** A title-only task spent most of its
scroll on cards that said nothing. What the user reads first — the title, the due
date, the priority, the project — is what the screen must keep.

**Hiding without a way in is the failure mode, not the fix.** #2014's warning still
holds: a section that hides itself takes the way in with it. `+ Ещё` is that way
in, and it is one row instead of six empty cards.

**A collapsed section is not a hidden one.** The distinction matters: the checklist
of a task with twenty items is *present* and says `Checklist (4/20)`, and the user
opens it with one tap. The attachments of a task with none are *absent*, and the
user brings them back with two taps.

**One rule, one owner.** The title rule was written out four times (#2532) and a
count over nothing was one edit away in each of them. One function removes the
class of bug.

## Consequences

- The detail screen of a title-only task draws: the hero, the attribute rows, the
  checklist header, the subtasks header and the `+ Ещё` row.
- `TaskDetailEmptyChildSectionsTest` asserted the old rule and is rewritten. The
  Maestro flows `TASK-CHECK-01`, `TASK-SUB-01`, `TASK-LOG-01` and `TASK-LOG-02`
  open the section they need before they use it, because the add-row is behind the
  header now.
- **#2032 is answered by this ADR**: the one compact row it asked for is the
  `+ Ещё` row, and it covers every optional property rather than the three child
  sections. It should be closed as superseded rather than implemented separately.
- **#2532 is partly answered**: the title has one owner. The empty-state copy has
  no customer left — the empty sections are hidden — so its second half closes with
  it.
- The `+ Ещё` row is drawn by the shared editor, so the create screen gets it too
  when it has hidden properties to offer. Create and Edit parity in full is #2872's
  WS8 and is not decided here.
- The strings of the new sheet are literals. WS10 (`#2872`) moves every
  user-visible literal in `feature/tasks` to `Res.string.*`; the sheet is written
  with the names the sections themselves use, so that it can be translated in one
  pass with everything else.

## Links

- `shared/src/commonMain/kotlin/com/singularity/todo/core/ui/components/SectionHeader.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/tasks/presentation/components/detail/ExtraSectionCard.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/tasks/presentation/components/detail/TaskOptionalProperty.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/tasks/presentation/components/detail/TaskEditorSheetsHost.kt`
- `docs/research/task-detail-ux/implementation/target-ux.md`
- `docs/research/task-detail-ux/implementation/wireframes/task-detail.md`
- Supersedes the empty-section rule of #2014; answers #2032 and #2532; part of #2872
