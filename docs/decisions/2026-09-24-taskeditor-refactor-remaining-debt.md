---
title: "TaskEditor + ProjectDetail refactor remaining debt"
date: 2026-09-24
tags: [refactor, taskeditor, projectdetail, sheets]
status: accepted
reviewedBy: Singularity Developer
---

## Context

The TaskEditor (Sections D-E) and ProjectDetail (Section F) refactors extracted sheet hosts, grouped callbacks, and split inline sheets into separate files. Three gaps were identified during the refactor review.

## Decision

**Resolved immediately (low effort):**

1. **`showSheet` in `TaskEditorCallbacks` removed.** It was dead code — `TaskEditorContent` owns `activeSheet` internally and passes its own `onShowSheet` to `TaskEditorBody`. All callers passed `showSheet = {}` (no-op). Removed from `TaskEditorCallbacks`, `TaskCreateScreen`, and `TaskDetailViewScreen`.

2. **ChildProjectsSheet navigation wired.** Added `NavigateToChild(ProjectId)` routing intent to `ProjectDetailIntent.Routing`. `ProjectDetailActions.onNavigateToChild` dispatches it. The `when` dispatcher in `ProjectDetailContent` calls `nav.openDetail(intent.projectId)`. Tapping a child chip in `ChildProjectsSheet` now navigates to that child project.

**Deferred (requires separate domain ADR):**

3. **Project-level reminder wiring is a no-op.** `CurrentProjectContent.onSetReminder` is constructed as `{ /* TODO */ }`. `ProjectDetailSheetsHost.PickReminder` calls it, but there is no `Domain.SetReminder` intent in `ProjectDetailIntent` and no mutation in `ProjectDetailViewModel`. Requires a project-reminder domain ADR first.

**Deferred (bounded effort, out of scope for MR-3):**

4. **`ProfilePickerSheet` extraction** from `SavedAgendaListScreen` — straightforward use of existing `ListPickerSheet`.
5. **`BacklinksSheet` extraction** from `NotePreviewScreen` — straightforward extraction.

## Consequences

- Routing intents can originate from sheets (not just from the screen). Pattern: `NavigateToChild` routing intent → `ProjectDetailActions.onNavigateToChild` → `nav.openDetail()`.
- `showSheet` should never be added back to `*Callbacks` data classes when the content composable owns the sheet state internally.
- `CurrentProjectContent` is the correct pattern for bundling 16+ nullable callbacks for sheet hosts — keep as-is until >20 fields.

## Links

- `singularity-todo-sheet-extraction` — skill covering the sheet extraction pattern
- `ProjectDetailIntent` — intent sealed hierarchy with routing/domain separation
- `ProjectDetailSheetsHost` — sheet routing implementation
- `ChildProjectsSheet` — child navigation UI
- `ReminderPickerSheet` — project reminder picker (unwired)
