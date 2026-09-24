---
Context: TaskDetailScreen.kt was a form-style read-only screen with every field in its own Row + explicit "Edit"/"Save" buttons. FieldMode state was declared in TaskDetailUi but never mutated by the VM — EditableTextRow was permanently stuck in View mode. Date/time/priority entered as free-form text. Empty sections (Reminders, Attachments) occupied vertical space with "(none)" text. No completion checkbox in the hero position. Emoji used for Pin/Archive status. The screen had no visual hierarchy.
Decision: Migrate to document-style UX (TickTick/Todoist reference): hero block (checkbox + inline-edit title/description), meta-chips row (date/time, priority, project as FilterChips), tags row, checklist with progress bar, bottom action bar with badge counts. Inline-edit via debounced MutableStateFlow in VM (300ms) — not Composable state. Unified ActiveSheet sealed interface routes all bottom sheets/dialogs from a single state source. Three-level due-date visual state (Overdue/Today/Future) via pure formatter.
Rationale: form-style is a known UX anti-pattern for task-detail screens — every professional app (TickTick, Todoist, Things 3) uses document-style. The form-style pattern also requires explicit Edit/Save buttons per field (5+ on screen) creating visual noise. Removing FieldMode and replacing with direct VM method calls eliminates dead UI state. ActiveSheet prevents the "state as? Loaded" cast race condition. Three-level due-date states match TickTick's visual language.
Consequences: EditableTextRow and TaskDetailField stub remain until full screen rewrite (see below). ProjectPickerSheet and TagPickerSheet remain AlertDialog (not ModalBottomSheet) — follow-up migration needed separately. ChecklistItemRow (feature/checklist/) was already designed as a replacement for the inline rows in this screen — now wired up. Debounce(300) requires FlowPreview opt-in. deleteTask uses TaskRepository.softDelete (already existed at TaskRepository.kt:32).
Links: skill:singularity-todo-task-detail-ux, skill:singularity-todo-ui-event-vs-state, skill:singularity-todo-pure-formatters, skill:singularity-todo-adb-workflow, docs/decisions/DIGEST.md
Tags: ux, task-detail, compose
title: "Task Detail — Document-Style Migration"
status: accepted
---

# Task Detail — Document-Style Migration

## Context

`TaskDetailScreen.kt` (325 lines) was a form-style read-only screen:

```
Title     [Edit]  ← every field is its own row
Description [Edit]  ← with an explicit button
Due date   [Edit]  ← free-form text input
Priority   [Edit]  ← plain text "High"
Project     [Edit]  ← opens picker
Tags (none)
Reminders (0) (none)
Attachments (0) (none)
📌 Pinned   🗑 Archived  ← emoji
```

`FieldMode` was declared on `TaskDetailUi` (6 fields: `titleField`, `descriptionField`, etc.) but **the VM never mutated them**. `EditableTextRow` rendered the View branch permanently. The `Edit` button set a local `draft` variable but the VM's state never changed — a textbook dead state pattern.

## Decision

Migrate to **document-style** UX:

```
┌──────────────────────────────────────┐
│  ←                                  │
├──────────────────────────────────────┤
│  ☐  Complete project proposal        │
│      Add description...              │
├──────────────────────────────────────┤
│  [📅 Today, 9:00 AM] [🚩 High]     │
│  [📁 Work]                           │
├──────────────────────────────────────┤
│  [urgent ×] [client ×] [+ Add tag]  │
├──────────────────────────────────────┤
│  Checklist ────── 2/3 ▓▓▓░░          │
│  ☑ Research phase            [🗑]     │
│  ☐ Final review          [🗑]       │
│  [Add item______________] [+]       │
├──────────────────────────────────────┤
│  [🔔 1] [📎 0] [📌] [🗑]            │
└──────────────────────────────────────┘
```

**Key architectural decisions:**

1. **Inline-edit via debounced `MutableStateFlow` in VM** — not Composable state. `titleDraft = MutableStateFlow<String?>(null)` + `debounce(300)` + `scope.launch { ... .collect { updateTask(...) } }`. Draft is NOT in `TaskDetailUi` — no recompositions on every keystroke. Per `singularity-todo-ui-event-vs-state` skill.

2. **`ActiveSheet` sealed interface** — single `mutableStateOf<ActiveSheet?>` drives all bottom sheets and dialogs. VM emits `TaskDetailUiEvent.OpenDatePicker` etc., Composable maps them via `toActiveSheet()` to `ActiveSheet.Date` etc., then renders the appropriate picker in one `when` block. Replaces `var showProjectPicker by remember { mutableStateOf(false) }` pattern with its stale-cast `state as? TaskDetailUiState.Loaded` risk.

3. **Three-level due-date visual state** — pure formatter `formatDueChip(date, time, today) → DueChipModel(text, DueVisualState)` at `core/ui/components/Formatters.kt`. `Overdue` → `errorContainer`, `Today` → `secondaryContainer`, `Future` → `surfaceVariant`. TickTick-style.

4. **`ChecklistItemRow`** from `feature/checklist/components/ChecklistItemRow.kt:41` — its KDoc explicitly states it replaces the inline checkbox rows from `TaskDetailScreen`.

5. **`BottomActionBar`** replaces `(none)` text blocks with badge counts on icon buttons.

## Known Follow-ups

- `ProjectPickerSheet` / `TagPickerSheet` remain `AlertDialog` (not `ModalBottomSheet`) — migration is a separate PR that touches multiple callers.
- `ReminderPickerSheetContent` is a stub in this PR — real reminder creation via `ReminderScheduler` needed.
- `AttachmentSheet` is stubbed (`TODO`) — full attachment integration follow-up.

## What Was Deleted

- `TaskDetailField` enum and all 6 `*Field` properties on `TaskDetailUi` — dead state, removed.
- `EditableTextRow` composable — replaced by `TaskHeroSection` + `InlineEditableText`.
- All `📌` / `🗑` emoji text blocks — replaced by bottom action bar icons.
- `(none)` text placeholders for empty Reminders/Attachments — replaced by badge counts.

## Files Changed

- `TaskDetailScreen.kt` — rewritten (~325 → ~600 lines)
- `TaskDetailViewModel.kt` — expanded with new methods, removed dead FieldMode state
- `TaskDetailUiEvent.kt` — extended with 8 new sheet/dialog event variants
- `ActiveSheet.kt` — new sealed interface + `toActiveSheet()` mapper
- `TaskDetailField.kt` — new stub file (deprecated, for backward compat during transition)
- `core/ui/components/Formatters.kt` — added `formatDueChip`, `DueVisualState`, `DueChipModel`
- `core/ui/components/FormattersTest.kt` — new test file
