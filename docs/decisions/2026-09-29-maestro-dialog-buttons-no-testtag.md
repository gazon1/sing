---
title: "AlertDialog buttons use visible text instead of testTag"
date: 2026-09-29
status: accepted
tags: [maestro, testing, ui]
---

## Context

Maestro flows for delete, confirm, discard, and cancel actions currently use
`tapOn text: "Удалить"` / `text: "Отменить"` / `text: "Сохранить"` instead
of stable `id:` selectors. This works but is fragile:

- Every Maestro flow that touches a dialog button uses `text:` keyed on
  Russian UI copy.
- If the copy changes (哪怕小小的 "Delete" → "Remove"), every affected flow
  breaks silently.
- The same fragility applies across all 11 languages the app ships.

The same problem exists for `ModalBottomSheet` action rows (icon/color pickers,
filter sheets, list pickers) — selectable only by visible label.

## Idea

Three approaches were considered:

1. **Do nothing.** Keep using `text:` for dialog/sheet buttons. Accept the
   maintenance cost. Accept the localization brittleness.
2. **Add testTag to every AlertDialog button.** Standardize on `dialog_confirm`,
   `dialog_cancel`, `dialog_dismiss` in `TestTags.kt` and apply in
   `ConfirmActionDialog`, `ResultDialog`, and all callers. Also add
   `sheet_item_<label>` for bottom-sheet rows.
3. **Maestro `text:` with fallback.** Accept `text:` for now, document the
   risk, add a pre-commit check that flags hard-coded Russian strings in flows.

## Decision

We do approach (2), incrementally. Each Phase 2 PR that touches a screen
with dialog buttons adds the missing testTag as part of the same PR. The
gap is documented in `Maestro/TAGS.md` under "Missing testTags" so agents
know to fix it when they encounter it.

## Rationale

Approach (1) accumulates brittle strings that will silently break on copy
changes. Approach (3) catches only Russian strings, not the copy itself, and
pre-commit checks for hardcoded UI text in YAML are noisy. Approach (2)
provides stable selectors at the source — the right fix at the right layer.

Doing it incrementally (per-PR, not a big-bang PR) avoids the Phase 0.2
mistake: bulk testTag additions without a flow that exercises them are
impossible to verify and become ballast.

## Consequences

- All `AlertDialog`-based buttons in `core/ui/components/` must eventually
  carry `dialog_confirm`, `dialog_cancel`, `dialog_dismiss` testTags.
- All `ModalBottomSheet` item rows should carry `sheet_item_<slug>`.
- When writing a new Maestro flow that hits a dialog/sheet without a testTag,
  the author adds the testTag in the same commit — not as a separate issue.
- `Maestro/TAGS.md` is the authoritative list of missing testTags; it is
  updated when a gap is discovered and fixed.

## Links

- `Maestro/TAGS.md` — "Missing testTags" section
- `shared/src/commonMain/kotlin/com/singularity/todo/core/ui/TestTags.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/core/ui/components/ConfirmActionDialog.kt`
- Related: `2026-09-29-maestro-archive-seed-strategy.md` (archive session coupling)
