---
title: "Sub-task 1-level hierarchy (like projects)"
date: 2026-09-08
tags: ["task-detail", "subtasks", "architecture"]
---

## Context

Sub-task hierarchy in `Task.parentTaskId` was never surfaced in the UI. The UX rework adds sub-task management. A design decision is needed: should sub-tasks support N-level nesting (TickTick/Todoist style) or 1-level (like the existing project hierarchy)?

## Decision

**1-level hierarchy for sub-tasks** (same invariant as projects).

- A task may have a `parentTaskId` pointing to any root task.
- A task that has a `parentTaskId` **may not** become a parent of another task.
- Domain validation: `assertNoNesting(parentId, taskId, allTasks)` — if `parentId` has its own `parentTaskId != null`, return `ValidationError`.
- No recursive cycle-detection needed — structurally impossible under 1-level rule.

## Rationale

- Matches the existing project hierarchy pattern — consistent mental model for users.
- Structurally impossible to create cycles — no cycle-detection code needed.
- Simpler UI: no deep indentation, no "nested task list" component needed.
- 1-level is sufficient for the primary use case (breaking a task into steps).
- N-level can be added later if needed — schema already supports it.

## Consequences

- **Always** validate `parentTaskId` in `CreateTaskUseCase` and `UpdateTaskUseCase` via `assertNoNesting`.
- **Never** allow a task with `parentTaskId != null` to become a parent — enforce in domain, not just UI.
- Sub-task count is denormalized via `TaskFilter.ByParent` query — no need for recursive count.
- Checklist items can be promoted to sub-tasks via "Convert to task" overflow action.

## Links

- `feature/tasks/TasksDomain.kt` (assertNoNesting)
- `feature/tasks/TasksUseCase.kt` (Create/Update validation)
- Skill: `singularity-todo-subtasks-ui`
- ADR: `2026-09-05-refactoring-summary` (1-level projects precedent)