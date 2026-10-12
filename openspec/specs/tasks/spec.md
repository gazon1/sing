# tasks Specification

## Purpose
Manage the task lifecycle: create, edit, complete, delete, archive, and track
time against individual tasks. The agenda organises tasks by date bucket; the
editor exposes attributes (priority, due date, start date, project, tags,
recurrence, pin) as rows.

## Scenario Matrix

<!-- MACHINE-READ. `python -m traceability scenarios --check` сверяет этот
     блок с `infra/kiwi/scenarios/**`: объявление без YAML, расхождение по
     title/priority/status/targets и незаполненный TODO — ошибки.
     Поведение (steps/expected) здесь объявлять нельзя — оно принадлежит
     YAML-сценарию. -->

```yaml
scenarios:
- id: TASK-01
  title: Create a task from the FAB and see it in the Inbox
  area: feature.tasks
  priority: P1
  status: confirmed
  targets:
  - android
- id: TASK-02
  title: Tapping the checkbox toggles the task's completion state
  area: feature.tasks
  priority: P1
  status: confirmed
  targets:
  - android
- id: TASK-03
  title: Tapping a task row opens its detail screen with the correct title
  area: feature.tasks
  priority: P1
  status: confirmed
  targets:
  - android
- id: TASK-04
  title: Delete a task from its detail screen overflow menu
  area: feature.tasks
  priority: P1
  status: confirmed
  targets:
  - android
- id: TASK-05
  title: Deleting a task shows an Undo snackbar and Undo restores it
  area: feature.tasks
  priority: P1
  status: confirmed
  targets:
  - android
- id: TASK-06
  title: Long-press opens context menu; archive removes the task
  area: feature.tasks
  priority: P1
  status: confirmed
  targets:
  - android
- id: TASK-07
  title: Set task priority from the editor attribute row
  area: feature.tasks
  priority: P1
  status: confirmed
  targets:
  - android
- id: TASK-08
  title: Set task due date from the editor attribute row
  area: feature.tasks
  priority: P1
  status: confirmed
  targets:
  - android
- id: TASK-09
  title: Cancelling the date picker leaves the task undated
  area: feature.tasks
  priority: P1
  status: confirmed
  targets:
  - android
- id: TASK-10
  title: Empty title is rejected; original title remains intact
  area: feature.tasks
  priority: P1
  status: confirmed
  targets:
  - android
- id: TASK-11
  title: Cyrillic task title is created, saved, and displayed correctly
  area: feature.tasks
  priority: P2
  status: confirmed
  targets:
  - android
- id: TASK-12
  title: Create a task with all attribute fields filled
  area: feature.tasks
  priority: P1
  status: confirmed
  targets:
  - android
- id: TASK-13
  title: Tasks appear in the correct agenda date-bucket sections
  area: feature.tasks
  priority: P1
  status: confirmed
  targets:
  - android
- id: TASK-14
  title: FAB from Today tab pre-fills the due date to Today
  area: feature.tasks
  priority: P1
  status: confirmed
  targets:
  - android
- id: TASK-15
  title: FAB from Inbox tab does NOT pre-fill a due date
  area: feature.tasks
  priority: P1
  status: confirmed
  targets:
  - android
- id: TASK-16
  title: Creating from a tab returns to that tab after save
  area: feature.tasks
  priority: P1
  status: confirmed
  targets:
  - android
- id: TASK-17
  title: Start and stop a time-tracking session on a task
  area: feature.tasks
  priority: P2
  status: confirmed
  targets:
  - android
- id: TASK-CHECK-01
  title: Add a checklist item to a task
  area: feature.tasks
  priority: P1
  status: confirmed
  targets:
  - android
  group: checklist
- id: TASK-REC-01
  title: Create a daily recurring task
  area: feature.tasks
  priority: P1
  status: confirmed
  targets:
  - android
  - desktop
  group: recurrence
- id: TASK-SUB-01
  title: Subtask lifecycle — add, toggle, delete
  area: feature.tasks
  priority: P1
  status: confirmed
  targets:
  - android
  group: subtasks
- id: TASK-TIME-01
  title: Start a timer on a task from its detail view
  area: feature.tasks
  priority: P1
  status: confirmed
  targets:
  - android
  - desktop
  group: timetracking
```
