# Feature Specification: Task Logbook Attachment

**Feature Branch**: `015-fix-task-logbook`
**Created**: 2026-10-25
**Status**: Draft

**Input**: User description: "Почини logbook. Сейчас он как будто вообще не работает. Напиши/обнови на это тесты в Maestro, прогони и на подключенном телефоне."

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Write a note from the task logbook (Priority: P1)

A user opens a task, finds the Logbook section, taps "+", writes a note and leaves.
Coming back to the task, the note is there, with the title they wrote.

**Why this priority**: The logbook is the only place a user can attach a note to a task.
Today the attachment silently disappears on the first save, so the feature is not usable
at all — every other scenario is downstream of it.

**Independent Test**: Create one note from the logbook, leave, reopen the task.

**Acceptance Scenarios**:

1. **Given** a task with an empty logbook, **When** the user taps "+", writes a title
   and saves, **Then** the logbook shows that note under the task's title.
2. **Given** a task with an empty logbook, **When** the user taps "+" and leaves without
   writing anything, **Then** the logbook is still empty — an abandoned note leaves
   nothing behind.
3. **Given** a note attached to a task, **When** the user edits it again later, **Then**
   the note is still attached and the logbook still shows it.

### User Story 2 - Trust the logbook's contents (Priority: P2)

A user reads the logbook of one task and expects to see exactly the notes that belong to
that task.

**Why this priority**: A logbook that shows notes from another task, or hides notes the
user attached, is worse than no logbook — it is confidently wrong.

**Independent Test**: Attach a note to one task, look at another task's logbook.

**Acceptance Scenarios**:

1. **Given** a note attached to task A, **When** the user opens task B's logbook, **Then**
   task B's logbook does not show that note.
2. **Given** a note whose body merely links to a task, **When** the user opens that task's
   logbook, **Then** the note is not in the logbook — it stays in the task's "Linked" card.

### Edge Cases

- The task was deleted elsewhere: the note write fails visibly; no half-written row is left.
- The app is killed while the editor is open: nothing was written, so nothing is left behind.
- A note with an empty title: it is attached and visible, titled by its content.

## Scenario Matrix

```yaml
scenarios:
  - id: TASK-LOG-01
    title: A note written from the task logbook stays attached to the task
    area: feature.tasks
    priority: P1
    status: confirmed
    targets: [android]
    group: logbook
  - id: TASK-LOG-02
    title: Abandoning the logbook's note editor leaves no note behind
    area: feature.tasks
    priority: P1
    status: confirmed
    targets: [android]
    group: logbook
```

## Requirements

### Functional Requirements

- FR-001: A note written from a task's logbook is attached to that task and stays attached
  across every later edit of that note.
- FR-002: Opening the logbook's "+" action creates nothing until the user saves.
- FR-003: The first save of a logbook note creates the note already attached to the task.
- FR-004: The logbook of a task shows exactly the notes attached to that task and nothing else.
- FR-005: A note that only links to a task (a `task://` link in its body) does not appear in
  that task's logbook.
- FR-006: The attachment survives the app being closed and reopened.
- FR-007: A failed write of a logbook note is reported to the user and leaves no partially
  written note behind.

### Success Criteria

- SC-001: A note written from the logbook is visible in that task's logbook every time the
  user returns to the task, in 100% of runs.
- SC-002: Opening the logbook's "+" and leaving without writing produces zero notes in 100%
  of runs.
- SC-003: No note from another task ever appears in a task's logbook.
- SC-004: An end-to-end flow on a physical Android device creates a note from the logbook
  and observes it in the logbook, passing on every run.

### Assumptions

- The logbook keeps its current shape (a section on the task detail listing notes and time
  entries) and gains no new user-facing controls.
- The task detail screen's Logbook section is Android-only in this build; the desktop build
  renders that slot empty and is out of scope.

## Out of Scope

- Showing notes that merely link to a task in the logbook — they stay in "Linked".
- Any change to the logbook's visual design, ordering or columns.
- Restoring attachments that older builds already lost: a note whose `task_id` was cleared is
  an ordinary note from now on.
- Bringing the logbook to the desktop build.

## Risks

- A note that lost its attachment in an earlier build cannot be recognised as a former
  logbook note, so the fix does not restore old attachments.
