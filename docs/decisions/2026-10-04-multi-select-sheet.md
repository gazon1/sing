---
title: "A multi-select sheet is a different component from a single-select one"
date: 2026-10-04
status: accepted
tags: [compose, ui, testing, architecture]
---

# A multi-select sheet is a different component from a single-select one

## Context

The agenda editor could add only seven section types, all parameterless
(date buckets, statuses). The product gap recorded in the test plan was that a
saved view's selectors take no parameters — a `Selector.Tags` needs tag ids, and
an empty id set matches no task, so the editor had no way to offer one.

Building the configurator: pick a section *type*, and for the types that need
values, pick the values.

The obvious implementation reuses `ListPickerSheet` for both steps, with the
second step getting a confirm button in the sheet's existing `footer` slot. That
compiles, reads correctly, and is wrong.

## Idea

`ListPickerSheet`'s row handler is:

```kotlin
onSelect = {
    onItemSelected(item.key)
    onDismiss()      // ← every pick ends the interaction
}
```

That is not an implementation detail, it is the component's contract: it is a
single-select picker, and picking the row you wanted *is* the end of the
interaction. The profile picker, the menu sheets, the "Add section" template
list all rely on it.

## Decision

A separate `MultiSelectSheet` component, for sets rather than one.

- The sheet stays open until the user confirms; toggling a row never dismisses.
- The confirm button is disabled while the selection is empty, rather than
  silently doing nothing.
- Selection state is **passed in**, not held by the sheet. The sheet is a
  renderer; the caller owns the selection because the caller is the one that has
  to decide what an empty or invalid selection means.

`ListPickerSheet` is untouched. Single-select and multi-select are different
components, and the fact that they render similarly is exactly what makes the
reuse tempting.

## Rationale

The failure was invisible in the source and only showed up as a test that could
not find a button which was provably in the composition tree. Reading the
component settled it in one step — and the comment above the `footer` slot
("protection against the footer being pushed out") suggests the slot was built
for a case nobody had used yet.

The generalisable lesson, which is why this is an ADR rather than a code comment:
**a UI component's contract includes what happens after the interaction, not just
what it renders.** The rows looked reusable; the dismissal was the part that
wasn't.

## Consequences

- `MultiSelectSheet` is in `core/ui/components/sheet/`, next to its
  single-select sibling, with a KDoc that says why it exists rather than
  implying the other one is deprecated.
- Callers that need a set must not reach for `ListPickerSheet` and then work
  around the dismissal. The second step of the agenda configurator keeps its own
  picker, and the three desktop flow tests cover the two-step interaction.
- Both components apply `exposeTestTagsAsResourceId()` to their rows, so both
  are reachable from Maestro — a sheet that cannot be automated is how the
  configurator's own bugs stayed hidden.

## Links

- `shared/src/commonMain/kotlin/com/singularity/todo/core/ui/components/sheet/MultiSelectSheet.kt`
- `SavedAgendaScreen.kt` — `SelectorParameterSheet`
- `SavedAgendaSelectorConfiguratorFlowTest.kt`
- `docs/decisions/2026-10-04-testtag-visibility-helper.md`
