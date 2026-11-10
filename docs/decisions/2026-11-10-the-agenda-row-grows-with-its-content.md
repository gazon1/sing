---
title: "The agenda row grows with its content: density, metadata order, and priority as a shape"
date: 2026-11-10
status: accepted
tags: ["tasks", "ui", "accessibility"]
---

# The agenda row grows with its content: density, metadata order, and priority as a shape

## Context

The 2026-11-09 comparison of the agenda screen against Todoist, Things and
Apple Reminders found three things in the task row (#2738):

- a one-line row spent 80 dp of screen height — the checkbox's 48 dp touch
  target plus 16 dp of padding above and 16 dp below — while the reference rows
  spend about 60;
- the due date was pushed out of the row by the repeat mark and the project
  badge standing next to it — the field the row is read for was the field that
  lost;
- the word beside the priority marker ("Medium") was painted in the priority's
  own colour. Medium is amber, and amber on a light surface measures about
  1.5:1 — the word was unreadable.

The comparison reported the priority as drawn twice and the marker as a clickable
24 dp star. In this tree the agenda row already draws the priority once, as a
non-clickable 16 dp `PriorityIndicator` star, and the amber word belongs to
`PriorityChip` — the archive, project-detail and search card, not the agenda row.
The two surfaces are treated as one row here because the issue does: the row's
density, its metadata order and its priority signalling are one component.

The row is the busiest surface in the app: it is drawn once per task, in a
list that scrolls for hundreds of rows, on a 360 dp phone. Its density is not a
matter of taste alone — it decides how many tasks fit above the fold, and
therefore how much of the day a person can see at once.

The measurements that started this are the ones in `TaskRowDensityTest`: a
one-line row's height, the width the due date keeps, and the colour the
priority's word is painted in.

## Idea

- **Density is padding, not a height.** The row must not have a fixed height
  and must not clip: a title that needs three lines gets three lines, and the
  row grows. What the row may not do is spend 20 dp of padding on a one-line
  title.
- **The metadata row has an order, and the order is the hierarchy.** The due
  date is the fact the row is read for, the marks are context, the project is
  the least urgent of the three. A `Row` measures its children in order, so
  the order *is* the width policy: the date first means the date gets its width
  before anything else can take it.
- **Priority is a shape, never a text colour.** The colour coding stays — the
  hue is how the priority is recognised at a glance — but the word next to the
  marker is text, and text has to be readable in both themes.

## Decision

`TaskRowFlat` drops its vertical padding from `Md + Xs` (16 dp) to `Md`
(12 dp): a one-line row is now 48 dp of checkbox plus 24 dp of padding = 72 dp.
The row keeps no height of its own — the padding is a minimum, and the row
grows with its content. The `taskItem` test tag moved onto the row's own
modifier so a measured height is the row's height and not the row plus the
divider below it.

`TaskRowContent` lets the title take three lines (it was two), still with an
ellipsis: a long title wraps instead of pushing the metadata row off the screen.

`TaskMetaRow` draws the due date first, then the marks, then the series
marker, then the separator, and the project last. The project is the only
child that gives up width (`Modifier.weight(1f, fill = false)`), so it is the
only field that ellipsises. `TaskMarkingsRow.ConveyedElsewhere` became
`internal` so the meta row can tell whether a mark is drawn at all — a row
whose only mark is conveyed elsewhere must not hang a separator on empty space.

`PriorityChip` keeps the priority hue as a 13 dp dot and paints the word in
`MaterialTheme.colorScheme.onSurfaceVariant`. The agenda row itself was not
changed here: it already draws the priority once, as a non-clickable
`PriorityIndicator` star, and that star stays non-clickable — priority is
edited in the task editor, and the issue asks for no new click target.

## Rationale

**The order is the width policy.** Compose measures a `Row`'s children in
order, and a weighted child only gets what is left. With the date first, the
date is measured against the full width and keeps its intrinsic width; the
project, weighted last, is the one that is measured against what is left and
ellipsised. The alternative — the project first — measures the project against
the full width and pushes the date out, which is exactly the defect the
comparison found.

**A measured bound beats a re-derived one.** The row's height is asserted
against a laid-out tree, not recomputed from the tokens: a test that re-derived
72 dp from the padding and the touch target would prove that the arithmetic
still balances, and would keep passing if a third of padding appeared
somewhere else. `TaskRowDensityTest` measures the tree, so any other reason
for the row to grow fails the test.

**The unmerged tree is where the measurement lives.** The row is clickable, and
a clickable merges its descendants' semantics — in the merged tree the row's
node carries the title's text and the whole row's bounds. The title's own box
is only visible in the unmerged tree, so that is where the test reads it.

**Contrast is measured on the pixels, not on the source.** A test that read
`MaterialTheme.colorScheme.onSurfaceVariant` and compared it with the amber
would prove that the theme has a readable colour, not that the chip uses it.
`PriorityChipContrastTest` screenshots the chip and compares the label's own
pixels against the surface behind them, so a label that drifts back to the
priority hue fails on the rendered result.

## Consequences

- A one-line agenda row is 72 dp tall instead of 80 dp, and a three-line title
  is fully readable instead of being cut at two.
- The due date is never truncated: it is measured first and the project gives
  up the width. A long project name ellipsises, as before.
- The priority chip (archive, project detail, search) is a coloured dot with a
  readable word. The agenda row itself is unchanged in this respect: it already
  drew the priority once, as a non-clickable star, and it still does.
- **A residual gap, stated plainly.** The Medium marker itself is amber on a
  light surface, which measures about 1.6:1 — below the 3:1 that WCAG asks of
  a non-text signal. Fixing it would mean changing the priority palette, and
  `PriorityColorTest` pins Medium to `0xFFF2C94C` app-wide; the issue forbids
  a global priority-colour change. So the marker's hue stays, and the issue's
  own acceptance — one visible signal, no low-contrast amber *text*, no new
  click target — is met. A future change to the palette would have to change
  the pinned colour and the pin together.
- The priority checkbox fill is untouched: the accent only shows on a
  completed task's circle, and the row hides the star for completed tasks, so
  the two signals never coexist.

## Links

- `shared/src/commonMain/kotlin/com/singularity/todo/feature/tasks/presentation/components/list/TaskRowFlat.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/tasks/presentation/components/list/TaskRowContent.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/tasks/presentation/components/list/TaskMetaRow.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/tasks/presentation/components/list/TaskMarkingsRow.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/tasks/presentation/components/PriorityChip.kt`
- `desktopApp/src/jvmTest/kotlin/com/singularity/todo/feature/tasks/presentation/components/list/TaskRowDensityTest.kt`
- `desktopApp/src/jvmTest/kotlin/com/singularity/todo/feature/tasks/presentation/components/PriorityChipContrastTest.kt`
- Issue #2738
