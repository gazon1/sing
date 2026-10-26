# Specification Quality Checklist: Task States and Inline Task Items

**Purpose**: Validate specification completeness and quality before proceeding to planning
**Created**: 2026-10-26
**Feature**: [spec.md](../spec.md)

## Content Quality

- [x] No implementation details (languages, frameworks, APIs)
- [x] Focused on user value and business needs
- [x] Written for non-technical stakeholders
- [x] All mandatory sections completed

## Requirement Completeness

- [x] No [NEEDS CLARIFICATION] markers remain
- [x] Requirements are testable and unambiguous
- [x] Success criteria are measurable
- [x] Success criteria are technology-agnostic (no implementation details)
- [x] All acceptance scenarios are defined
- [x] Edge cases are identified
- [x] Scope is clearly bounded
- [x] Dependencies and assumptions identified

## Feature Readiness

- [x] All functional requirements have clear acceptance criteria
- [x] User scenarios cover primary flows
- [x] Feature meets measurable outcomes defined in Success Criteria
- [x] No implementation details leak into specification

## Notes

**Validation pass 1 — 2026-10-26.** All items pass.

Three scope decisions were settled with the user before drafting, and their answers are recorded in the spec's Clarifications section rather than left as markers:

1. Task states — a stored workflow state plus derived markings that appear together.
2. The task create/detail checkbox — only the subtask and checklist rows gain checkboxes; the task's own completion control is unchanged.
3. Subtask modelling — subtasks stay full tasks, rendered inline with real checkboxes.

Two points of judgment were made without asking, both recorded in Assumptions and Out of Scope:

- The stored workflow state deliberately has no "waiting" value. Waiting on someone else is already derived from real prerequisites, so a stored flag for it would give one fact two sources of truth. "Waiting" is therefore out of scope rather than undefined.
- The attached screenshot is treated as the reference for the layout of a task's parts — a flat section with a heading, a finished-of-total count, and rows carrying real checkboxes — not as a mandate to reproduce another application's chrome or to restructure the whole task screen.

Terminology used throughout the spec:

- *workflow state* — the stored state: Open, Next up, In progress.
- *marking* — a derived condition shown on a task: blocked, follow-up, overdue, no date, pinned, repeating, completed.
- *follow-up* — the task holds a relation that follows up on another task; not a blocker.
- *subtask* — a task belonging to another task, openable as its own task.
- *checklist item* — a titled item belonging to a task, not itself a task.

Items marked incomplete require spec updates before `/speckit-clarify` or `/speckit-plan`.
