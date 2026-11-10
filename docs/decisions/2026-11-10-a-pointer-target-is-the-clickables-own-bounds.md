---
date: 2026-11-10
status: accepted
deciders: [maintainer]
---

# A pointer target is the clickable's own bounds, not the space reserved around it

## Context

Issue #1669 asks for a 48 dp pointer target on every control a finger has to
reach. The repository already names the number — `TouchTarget.MinSize` in
`core/ui/TouchTarget.kt` — and already says how to reserve the space:

    Modifier.touchTarget().size(20.dp)      // a 48 dp layout box, a 20 dp control

That helper, and Material's own `minimumInteractiveComponentSize()`, are
**reservations**. The content is measured and centred inside a larger box, and
the content's own node keeps its own bounds.

The three buttons of `SectionEditorCard` were Material's `IconButton` at

    IconButton(onClick = onMoveUp, modifier = Modifier.minimumInteractiveComponentSize().size(40.dp))

`IconButton` lays its own clickable out at the 40 dp it paints at — the size
sits **inside** the clickable — so the clickable's node was 40 dp whatever the
caller passed, and no modifier widened it.

That is what the first account of the gap said, and it was wrong about the
pointer. Compose expands the **touch bounds** of a `clickable` that is smaller
than `ViewConfiguration.minimumTouchTargetSize` to that minimum, centred
(`foundation/Clickable.kt`, `getExtendedTouchPadding`, copied from
`SuspendingPointerInputModifierNodeImpl.extendedTouchPadding`). The old
buttons — a 40 dp clickable centred in a 48 dp reservation — therefore answered
taps across the whole 48 dp. Measured, by sweeping a tap across the button and
recording where it stopped landing:

| shape | clickable's node | taps that land, in the node's own frame |
|---|---|---|
| old `IconButton` | 40 × 40 dp | −4 dp … +44 dp — 48 dp of target |
| new `SectionActionButton` | 48 × 48 dp | 0 dp … +48 dp — 48 dp of target |

The band was never dead, and the pointer area of the two shapes is the same
48 dp square. What the old shape did not have was **bounds**: the rect the
semantics tree reported for the button was 40 × 40 dp, and that rect is what an
accessibility service treats as the control's target.

## Idea

A pointer target has two faces, and they are measured separately.

- The **pointer area** — where a tap lands. The framework widens a small
  `clickable` to the platform's minimum touch target, so a 40 dp clickable
  centred in a 48 dp slot answers taps over 48 dp.
- The **bounds** — the rect the button reports. Those are the clickable's own
  node, and they stay at the size the control was laid out at.

The first face can be relied on only while the framework's expansion holds and
the platform's minimum touch target is 48 dp. The second face is the layout's
own, and it is what the contract asks for: a control that has to be 48 dp wide
is laid out at 48 dp.

## Decision

**A control that has to be reachable by a finger is laid out at 48 dp: the
clickable is the 48 dp box and the glyph is smaller, centred inside it.**

- The clickable's own node is the target. A reservation around a smaller
  clickable does not change it, and no modifier on `IconButton` can — the size
  it paints at sits inside its clickable.
- The pointer area is not the contract, and a test that only taps cannot tell a
  48 dp clickable from a 40 dp one: both answer a tap 22 dp from the centre.
  The contract is measured from the tree — the bounds of the node that owns the
  click — and a tap is used for what only a pointer can answer: that the band
  around the glyph reaches the button.
- `Modifier.touchTarget()` stays what it is — a reservation of layout space.

For `SectionEditorCard` this means the three buttons are laid out at
`TouchTarget.MinSize` with a 24 dp glyph centred inside them. The row's layout
does not change: it reserved 48 dp per button before, and it reserves 48 dp per
button now — only the clickable grows, from 40 dp to 48 dp.

## Rationale

- **The measurement has to come from the tree.** The gate reads the shape of a
  modifier chain; it cannot lay a modifier out, and Material's controls were
  out of its reach. The desktop test mounts the button and reads the bounds of
  the node that owns the click.
- **The control has to be a control.** Tapping is not enough to tell the two
  shapes apart — the framework widens both to the same 48 dp of pointer area —
  so the control for the measurement is the old shape itself, which measures
  40 dp and would fail the 48 dp assertion.
- **The row does not move.** The buttons reserved 48 dp before and reserve
  48 dp now, so the change is invisible in the layout and visible only to the
  bounds the button reports.
- **The pointer area was never the thing that was broken.** Saying otherwise
  would have been a wrong reason to make the right change, and it was written
  down: the comment on #1669 that this change answers claimed the old buttons
  gave the user a 40 dp target. That claim was wrong, and it is corrected on the
  issue.

## Consequences

- **The three buttons of `SectionEditorCard` report 48 × 48 dp bounds**, with a
  24 dp glyph centred inside them, and answer a tap anywhere in that square.
  They still do not answer while they are disabled.
- **The button is a local composable**, not a shared one: the change is scoped
  to the card that needed it. #2485 owns the shared shape and the other call
  sites, and the static gate that should judge the click-owning node.
- **The gate stays blind to Material controls** for now. The claim in
  `scripts/check-touch-targets.py` that they enforce their own minimum is
  wrong in the sense that matters here — their clickable is not 48 dp — and the
  gate's rule that a computed size is "a reviewer's job" is why
  `TouchTarget.MinSize` is not read by it. Both belong to #2485.
- **The same reasoning applies to the other small clickables in the repository**
  — `ColorSwatch` and the rest. Their pointer areas are already 48 dp; their
  reported bounds are not. #2485 owns the sweep.

## Links

- `shared/src/commonMain/kotlin/com/singularity/todo/core/ui/TouchTarget.kt` —
  the minimum, and what a reservation is
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/agenda/presentation/components/SectionEditorCard.kt` —
  the three buttons and the local `SectionActionButton`
- `desktopApp/src/jvmTest/kotlin/com/singularity/todo/feature/agenda/SectionEditorCardTouchTargetTest.kt` —
  the measurement and the tap
- `desktopApp/src/jvmTest/kotlin/com/singularity/todo/test/helpers/DesktopAssertions.kt` —
  `unclippedBoundsOfContentDescription`
- `desktopApp/src/jvmTest/kotlin/com/singularity/todo/test/helpers/DesktopInteractions.kt` —
  `clickContentDescriptionAtOffset`
- `scripts/check-touch-targets.py` — the gate that cannot see this
- `CODING_STANDARDS.md` — "Touch targets meet the minimum and the order is correct"
- `docs/decisions/2026-09-30-section-reorder-via-buttons.md` — the buttons themselves
