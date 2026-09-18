---
title: "TaskDetail critical fixes: TOCTOU race, Saved-spam, dead condition"
date: 2026-09-08
tags: [task-detail, critical-fix, ux]
status: accepted
---

## Context

TaskDetailViewModel.kt contains three critical bugs discovered during the v2 UX rework audit (2026-09-08):

1. **TOCTOU race** (potential data loss) — lines 106, 118: debounced inline-edit collector re-fetches the task via `.first()` after a 300ms debounce. If the task was edited by another device or MCP agent during that window, the local change overwrites the remote one.
2. **Saved-spam regression** — every setter method (setTitle, setDueDate, setPriority, etc.) emits `TaskDetailUiEvent.Saved("...")` on success. Typing a title fires 5+ "Saved" pulses. The skill `inline-edit-saved-feedback` forbids this pattern.
3. **Dead condition** — `TaskDetailScreen.kt:508` has `if (ui.tags.isNotEmpty() || true)` — always true, never hides the tags row when empty.

## Decision

1. **TOCTOU fix:** Replace `taskRepo.watchTask(taskId).first()` in the debounced collectors with a `combine` of the existing `taskFlow` that is already part of the combined state. The VM already holds the latest task via the state flow — re-fetching via `.first()` is both redundant and racy.
2. **Saved-spam fix:** Remove all `onSuccess { _events.emit(Saved(...)) }` from setter methods in TaskDetailViewModel. Only completion toggle, AI actions, and bulk operations emit `Saved`. Silent saves update `_lastEditedAt` only.
3. **Dead condition fix:** Change `if (ui.tags.isNotEmpty() || true)` to `if (ui.tags.isNotEmpty())`.

## Rationale

- TOCTOU fix uses the existing `taskFlow` which is already combined in the VM's state — no new dependency introduced.
- Silent saves + continuous `lastEditedAt` is the correct UX pattern per the `inline-edit-saved-feedback` skill.
- The dead condition was a copy-paste remnant that never had any effect.

## Consequences

- **Always** `combine` the debounced draft flow with the entity's source `StateFlow` — **never** use `.first()` re-fetch inside a debounced collector.
- **Always** silent saves for inline edits — `Saved` event is reserved for explicit user actions only.
- **Never** leave `|| true` or other tautological conditions in UI conditionals.

## Links

- `feature/tasks/TaskDetailViewModel.kt` (fix location)
- `feature/tasks/TaskDetailScreen.kt:508` (dead condition)
- Skill: `singularity-todo-inline-edit-saved-feedback`
- Skill: `singularity-todo-document-style-detail`
