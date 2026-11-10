---
title: The compact attribute strip is not adopted over the reordered rows
date: 2026-11-10
tags: [ui, tasks, prototype]
deciders: [Singularity Developer]
status: accepted
---

# The compact attribute strip is not adopted over the reordered rows

## Context

Issue #2736 asked for a comparison, not a shipment. The full-row layout already places the due
date, the priority, the project and the tags in the first viewport on a 360 dp phone
(#1754, #1942, #2010 — all closed and verified on the real detail screen by
`TaskDetailFoldFlowTest`). The open question was whether a compact, wrapping chip strip
would improve the first-screen hierarchy without hurting readability.

The comparison mounts the real `TaskEditorContent` twice with identical data at
**360 dp** and **600 dp** (800 dp tall, desktop test density 1, so pixels are dp), in four
states: all four attributes unset, all four populated, long project/tag names, and six
tags. Both variants use the same model, the same sheet-opening callbacks and the same test
tags; the only difference is the presentation.

## Idea

- **Keep the full rows.** Two bare rows (priority, due date) and two filled cards
  (project, tags).
- **Replace them with one wrapping strip.** One chip per attribute, all sharing the
  `surfaceVariant` / `primaryContainer` language of `DetailMetaChip`.

## Decision

Keep the reordered full rows. The chip strip stays a prototype on this branch, behind
`TaskEditorContent(compactAttributes = false)` by default; it does not replace the rows.

## Rationale

The measurements below come from `TaskAttributeStripPrototypeTest`, which writes the
captures to `desktopApp/build/diagnostics/strip-prototype/`.

**The strip wins where the screen is wide.** At 600 dp the four chips fit on one line and
the whole attribute block is one glance long. The rows are still four stacked rows there.

**The strip loses readability on a phone.** At 360 dp a long project name and a long tag
name cannot be shown whole: each chip is capped at 240 dp and truncates with an ellipsis.
The rows wrap instead, so the same data reads in full. With two long tags the first one
consumed the whole chip before the fix; the label now leads with the count
(`2 tags · …`) so a second tag is never invisible, but the names themselves still truncate.
Telling two long, similar names apart means opening the picker — a round trip the rows do
not require.

**Interaction, semantics and touch targets pass.** Each chip is one tap into the same
sheet the row opens; the chip is a `FilterChip`, so it carries a text label and a selected
state rather than colour alone; the visual surface is 32 dp but `Modifier.touchTarget()`
pins the touch height to the app's 48 dp floor (`assertTouchTargetHeight`). These are not
the reason for the decision — readability is.

**The strip drops the rows' one-tap clear.** Each full row has an X that clears its value
in one tap; the chips open the picker instead, so clearing a due date, priority or project
becomes two taps. The pickers do expose the empty value, so nothing is lost, but the
comparison did not measure this and the variants are not behaviour-identical. If the strip
is ever revisited, it needs either the clear affordance back or an explicit note that
clearing moves into the picker.

**The compactness gain does not change the fold claim.** #1754 is already satisfied by the
reordered rows: the due date and the priority are visible without scrolling. The strip
makes the attribute block shorter, but it does not move anything new above the fold that a
person needs first.

**Four filled chips read as a heavier block.** In the populated state the strip is four
saturated `primaryContainer` pills; the rows mix bare rows with two cards and read lighter
despite being taller.

## Consequences

- No production screen changes: the editor keeps its `compactAttributes = false` path
  and main carries this decision only.
- The prototype is **not** in the main tree. It lives on branch
  `prototype/2736-compact-attribute-strip` together with its comparison test, so the
  evidence stays reproducible without leaving dead UI code behind. It touches
  `TaskAttributeChipStrip`, `TaskEditorModel.tagNames` (derived display data, not new
  state — the detail screen fills it from the tags it already loads) and
  `DetailMetaChip(maxLines)`.
- Screenshots are not committed: the repository ignores `*.png`. Check out the prototype
  branch and run
  `./gw :desktopApp:test --tests '*TaskAttributeStripPrototypeTest' -Ptest.tags=slow`;
  the captures land in `desktopApp/build/diagnostics/strip-prototype/` (16 files: four
  states × two variants × two widths).
- A future attempt would need a long-name treatment that keeps the distinguishing part
  visible (middle ellipsis, or a two-line chip), or a responsive rule that uses the strip
  only at ≥600 dp. Neither was built: the request was a comparison, and a phone-first app
  would pay the truncation cost on the screen that matters most.

## Links

- Issue #2736 — the experiment this answers
- #1754, #1942, #2010 — the section-order work the experiment depends on
- Branch `prototype/2736-compact-attribute-strip` — the strip, the comparison test and the
  48 dp / one-tap / visibility checks
- `TaskDetailFoldFlowTest.kt` — the existing 360×800 proof that the rows are on the first screen
