# Detail Screen — Definition of Done Checklist

> Read this checklist before starting any new detail screen (Task, Project, Note, Tag).
> Not a skill — a markdown checklist. Update as patterns evolve.

---

## 1. Model fields surfaced

- [ ] All entity fields are shown on the detail screen
- [ ] Fields that exist in the schema but are not shown are listed in a "known gaps" comment
- [ ] `kind` (Task vs Note) shown if entity has it
- [ ] `someday` / `isPinned` shown if entity has it
- [ ] `createdAt` / `updatedAt` shown as subtle relative text ("Created Sep 8 · Updated 2m ago")
- [ ] `parentId` shown with navigation affordance (chevron →)
- [ ] `archivedAt` shown only in Trash/Archive context

---

## 2. Inline edits

- [ ] Title is inline-editable via `BasicTextField` (no border, no label)
- [ ] Description is inline-editable via `BasicTextField`
- [ ] Inline edits are **debounced** (300ms)
- [ ] Inline edits are **silent** — no `Saved` event emitted from debounced collector
- [ ] `_lastEditedAt` is updated on every silent save
- [ ] "Saved X ago" indicator shown using `formatSavedRelative(now, lastEditedAt)`
- [ ] No `|| true` or other tautological conditions in inline-edit conditionals

---

## 3. Saved events (when to emit)

Emit `Saved` (one-shot) only for:
- [ ] Completion toggle
- [ ] Explicit confirmations (dialog confirm, sheet confirm)
- [ ] AI actions
- [ ] Bulk operations

**Never** emit `Saved` from:
- [ ] Debounced inline editors
- [ ] Chip-setter methods (date, priority, project, tag pickers)

---

## 4. Bottom action bar

- [ ] 4 icon buttons: Remind, Attach, Pin, Delete (or equivalent)
- [ ] Badge counts on icons when count > 0
- [ ] Pin icon tinted `primary` when active, `onSurfaceVariant` when inactive
- [ ] Delete icon always `error` tint
- [ ] `SnackbarHost` integrated for undo feedback

---

## 5. Picker sheets

- [ ] All pickers use `ModalBottomSheet` (not `AlertDialog`)
- [ ] Pickers use `TaskEditorSheetHost` or a shared `PickerSheetHost` for chrome consistency
- [ ] **Inline create** supported: "Create new X" row at bottom of picker sheet
- [ ] **Clear action** present on DatePicker and TimePicker
- [ ] **Pre-select current value** when opening a picker (don't reset to default)
- [ ] No dead-end: every picker leads to a result or cancel

---

## 6. Confirmation dialogs

- [ ] Destructive actions (delete) use `ModalBottomSheet` confirm (not `AlertDialog`)
- [ ] Confirmation shows a preview of the entity being acted on
- [ ] Archive does **not** require confirmation (undo is sufficient safety net)
- [ ] Undo snackbar shown for 4 seconds after delete

---

## 7. Navigation from detail screen

- [ ] Cross-feature navigation uses `IconButton(Icons.AutoMirrored.Filled.ChevronRight)` adjacent to chip — **not** `combinedClickable` on the chip
- [ ] Parent reference chip: tap chevron → navigate to parent; tap × → detach
- [ ] Child navigation: tap child row → navigate to child detail

---

## 8. Accessibility

- [ ] Every `IconButton` has an explicit `contentDescription`
- [ ] `IconButtonWithBadge` requires `contentDescription` as a parameter (not hard-coded)
- [ ] `Checkbox` in rows has dynamic `contentDescription` ("Mark complete" / "Mark incomplete")
- [ ] `Modifier.semantics { role = Role.Button }` on interactive elements that lack it

---

## 9. Compose structure

- [ ] Screen file ≤ 500 lines (split into `sections/` if larger)
- [ ] Callbacks packed into `@JvmInline value class` when ≥ 4 callbacks
- [ ] 4 sections maximum: Hero, MetaChips, Body, BottomBar
- [ ] `ActiveSheet` sealed interface for all sheet/dialog routing (no `remember { mutableStateOf<Sheet?>(null) }` per sheet)
- [ ] `CollectEvents` used (not raw `LaunchedEffect` for SharedFlow)

---

## 10. Preview samples

- [ ] `PreviewSamples.kt` has builder for the entity with all field combinations
- [ ] Each section file has at least 2 preview functions (default, edge case)
- [ ] Preview uses `PreviewThemed` wrapper
- [ ] `Clock.System.now()` NOT used in preview code — use fixed timestamps

---

## 11. Tests

- [ ] VM unit tests for all setter methods (silent save, error path, explicit action emits Saved)
- [ ] TOCTOU race test: rapid edits + concurrent remote change → no data loss
- [ ] Formatter unit tests for all `format*` functions
- [ ] Repository tests for `restore()`, `archive()` (if added)

---

## 12. Skill applicability

Reference these skills when building:
- `singularity-todo-document-style-detail` — 4-section anatomy
- `singularity-todo-task-detail-ux` — task-specific worked example
- `singularity-todo-inline-edit-saved-feedback` — debounce + silent save
- `singularity-todo-ui-event-vs-state` — Saved event semantics
- `singularity-todo-shared-ui-components` — value-class callbacks, decomposition
- `singularity-todo-subtasks-ui` — if entity has `parentTaskId`
