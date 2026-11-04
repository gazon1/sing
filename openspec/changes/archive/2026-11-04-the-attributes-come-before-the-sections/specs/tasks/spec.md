# Tasks — Observable Behavior

**capability:** `tasks` | **status:** accepted

---

## ADDED Requirements

### Requirement: The task's attributes come before the sections that add to it

The system SHALL draw the editor's attribute rows — the priority, the estimate, the
due date, the start date, the project, the tags, the recurrence, the pin, the
dependencies card and the workflow state — under the description and under the
content that asks the person for something (the first-run offer and the pending AI
proposals, which keep their own place above the rows), and SHALL draw the sections
that add to the task — the checklist, the subtasks, the attachments, the logbook —
after the rows.

The dependencies card joins the group after the pin row instead of leading the
column, and the workflow-state row is drawn after the rows that precede it in the
group, so a task with dependencies draws its priority and its due date in the same
place as a task without them.

The rows are the fields a person fills in, and the sections grow with use: an empty
checklist is still a card on the screen. A task opened on a narrow phone SHALL show
its priority and its due date without a scroll.

#### Scenario: The attributes are above the sections
- **WHEN** the user opens a task
- **THEN** the due date and the priority are shown above the checklist and the
  attachments

#### Scenario: The attributes are on the screen of a narrow phone
- **WHEN** the user opens a task on a phone-sized screen
- **THEN** the priority and the due date are visible without scrolling
