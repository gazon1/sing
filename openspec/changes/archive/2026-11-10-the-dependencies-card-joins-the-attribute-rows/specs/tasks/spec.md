# Tasks — Observable Behavior

**capability:** `tasks` | **status:** accepted

---

## ADDED Requirements

### Requirement: The dependencies card is drawn with the attribute rows

The task editor SHALL draw the dependencies card after the due-date row, with the other
attribute rows, not before them. The card SHALL keep its behaviour: it is drawn only
when there is something to pick from, it names the relations the task has, and it opens
the dependency picker.

The priority and the due date SHALL be drawn in the same place whether or not the task
has dependencies.

#### Scenario: Dependencies do not move the priority or the due date

- **GIVEN** a task with dependencies and a task without them
- **WHEN** either task is opened
- **THEN** the priority and the due date are drawn in the same place

#### Scenario: The card still opens the picker

- **GIVEN** a task with another task to depend on
- **WHEN** the user taps the dependencies card
- **THEN** the dependency picker opens
