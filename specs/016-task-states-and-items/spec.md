# Feature Specification: Task States and Inline Task Items

**Feature Branch**: `016-task-states-and-items`

**Created**: 2026-10-26

**Status**: Draft

**Input**: User description: "1. Сделать в интерфейсе понятно и различимо какие задачи blocked, какие followed next, какие inprogress и т.д.; сделать переход между состояниями задач явным и понятным. Также добавить в agenda возможность создавать вьюхи с этими статусами. 2. Нет способа добавить новый тег, которого ещё нет. 3. Добавить чекбокс в task details/create screen; разделить подзадачи и чеклисты по сущностям и в UI сделать как на прикреплённом скриншоте."

## Clarifications

### Session 2026-10-26

- Q: What set of task states should the feature define? → A: **A stored workflow state plus derived markings.** The task carries a stored workflow state the user moves through explicitly, and the conditions the app already derives — blocked, follow-up, overdue and so on — are shown as markings that can appear together on one task.
- Q: What should the checkbox on the task create/detail screen be? → A: **Only the subtask and checklist rows.** The task's own completion control stays exactly as it is today; the create screen keeps hiding it, because there is no task to complete before it exists.
- Q: How should subtasks be modelled after separating them from checklists? → A: **Subtasks stay full tasks, shown inline.** A subtask keeps its own screen, its own dates and its own tags; what changes is that it is rendered inline on the parent's screen with a real checkbox.

---

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Read a task's state from the list (Priority: P1)

A user scans their task list and needs to know, without opening anything, which tasks are waiting on something, which is the one they chose to do next, which they have already started, and which are overdue. Today a task row carries at most one small mark, chosen by a fixed priority, so a task that is both blocked and overdue shows one of the two facts and the other is lost.

**Why this priority**: Reading the state of the list is the thing the user asked for first, and it is the precondition for every other part of the feature — a state the user cannot see is a state the user cannot act on.

**Independent Test**: Can be validated by preparing tasks that are blocked, in progress, next up, overdue, and a follow-up, placing them in a list, and confirming each carries exactly the markings that apply to it.

**Acceptance Scenarios**:

1. **Given** a task whose prerequisite is not finished, **When** the task is shown in a list, **Then** it is visibly marked as blocked.
2. **Given** a task marked as in progress, **When** the task is shown in a list, **Then** it is visibly marked as in progress.
3. **Given** a task that is both blocked and overdue, **When** the task is shown in a list, **Then** the blocked marking and the overdue marking are visible at the same time.
4. **Given** a task that follows up on another task, **When** it is shown in a list, **Then** it is visibly marked as a follow-up and is not marked as blocked.
5. **Given** a task with no state and no special condition, **When** it is shown in a list, **Then** it carries no marking.
6. **Given** a row too narrow to show every marking, **When** it is shown, **Then** the number of markings not shown is stated rather than one of them silently dropped.

---

### User Story 2 - Move a task between states deliberately (Priority: P1)

A user picks up a task and wants to say so — mark it as the one to do next, then mark it as in progress — and wants that to be one obvious action rather than something they infer from a date or a checkbox. They also want to see, before committing, what the task's state is now and which states they may move it to.

**Why this priority**: The stored state only earns its keep if the user can move a task through it deliberately. Without an explicit transition, the state is another label nobody sets.

**Independent Test**: Can be validated by moving one task through every state it may enter, from the task's own screen, and confirming each change is kept across an app restart.

**Acceptance Scenarios**:

1. **Given** a task in the default state, **When** the user opens its state control, **Then** the current state and the states they may move it to are both shown.
2. **Given** the state control, **When** the user chooses a state, **Then** the task shows that state immediately and the choice is kept.
3. **Given** a task whose state was changed, **When** the application is restarted, **Then** the task still shows that state.
4. **Given** a completed task, **When** the user opens its state control, **Then** the state is shown but cannot be changed.
5. **Given** a completed task that is later reopened, **When** it is shown, **Then** the workflow state it held before completion is still there.

---

### User Story 3 - Build an agenda view from task states (Priority: P1)

A user wants a screen that shows only the tasks they are working on, or only the ones that are blocked, and wants to save that screen by name alongside the views they already keep.

**Why this priority**: The user asked for this in the same breath as making the states visible; it is what turns a marking into a working tool. A state nobody can gather tasks by is decoration.

**Independent Test**: Can be validated by creating a view filtered to one state, saving it, changing a task's state, and confirming the saved view's contents follow the change.

**Acceptance Scenarios**:

1. **Given** the view editor, **When** the user adds a section, **Then** sections filtered by task state and by the blocked and follow-up conditions are among the choices offered.
2. **Given** the user chooses the in-progress section, **When** they confirm it, **Then** a section is added that shows only tasks in progress.
3. **Given** a view filtered to a state, **When** the user reopens the saved view later, **Then** it still applies that state filter.
4. **Given** a view filtered to a state, **When** a task changes state, **Then** the view's contents reflect the change without the view being re-created or re-saved.
5. **Given** a view filtered to in progress, **When** the user looks at it, **Then** completed tasks are not included.
6. **Given** a state-filtered section, **When** it is shown, **Then** it states how many tasks match.

---

### User Story 4 - Add a tag that does not exist yet (Priority: P2)

A user is tagging a task and realises the tag they want — "invoice", "waiting on client" — does not exist yet. Today the only way to create a tag is to leave the task, go to the tags screen, create it there, and come back.

**Why this priority**: The gap is small but it interrupts a routine moment, and it is the only one of the three asks with no path to the goal at all today.

**Independent Test**: Can be validated by opening the tag picker on a task, creating a new tag there, and confirming the tag exists and is applied to that task.

**Acceptance Scenarios**:

1. **Given** the place where tags are chosen for a task is open, **When** the user looks at it, **Then** there is a way to create a new tag from within it.
2. **Given** the user creates a tag there, **When** it is created, **Then** it is applied to the task they were working on.
3. **Given** a tag with that name already exists, **When** the user tries to create it, **Then** the existing tag is offered instead of a second tag with the same name being created.
4. **Given** an empty or invalid name, **When** the user tries to create the tag, **Then** the reason is stated and no tag is created.
5. **Given** the place where tags are chosen, **When** the user looks at it, **Then** the existing tags are still offered alongside the create action.
6. **Given** a tag was just created there, **When** the list of tags is shown, **Then** the new tag is in it without the user having to reopen the list.

---

### User Story 5 - Work through subtasks and checklist items in place (Priority: P2)

A user working on a task wants to tick off its parts without leaving the screen and without opening a separate panel. Today the subtask list is text with a tick character in front of it, and the checklist lives behind a modal panel.

**Why this priority**: This is the shape the user attached as reference, and it is what makes the two kinds of item usable while working rather than afterwards.

**Independent Test**: Can be validated by ticking one subtask and one checklist item on the task screen and confirming both counts update and neither affects the other.

**Acceptance Scenarios**:

1. **Given** a task with subtasks, **When** the task screen is shown, **Then** the subtasks are listed on it, each with a checkbox.
2. **Given** a subtask that is not finished, **When** the user ticks its checkbox, **Then** the subtask is marked as finished and the subtask count updates.
3. **Given** a task with checklist items, **When** the task screen is shown, **Then** the checklist items are listed on it, each with a checkbox.
4. **Given** a checklist item, **When** the user ticks its checkbox, **Then** the item is marked as finished and the checklist count updates.
5. **Given** both lists, **When** the task screen is shown, **Then** each list states how many of its items are finished out of how many there are.
6. **Given** the task screen, **When** the user wants to add a part, **Then** they can add a subtask and a checklist item from the screen itself, without a separate panel opening.
7. **Given** a finished item, **When** it is shown, **Then** it is visibly marked as finished and its title stays readable.

---

### User Story 6 - Keep subtasks and checklist items apart (Priority: P3)

A user needs to tell which of a task's parts are steps in the work and which are a checklist of things to verify. The two behave differently — a subtask is a task with its own screen, a checklist item is not — and the interface should not blur them into one list.

**Why this priority**: The separation only matters once both kinds are on the screen at once, which is what User Story 5 delivers.

**Independent Test**: Can be validated by giving one task both kinds of item and confirming the two lists stay separate and that ticking in one leaves the other untouched.

**Acceptance Scenarios**:

1. **Given** a task with both subtasks and checklist items, **When** the screen is shown, **Then** the two are shown as two separate lists, each with its own heading and count.
2. **Given** a checklist item, **When** the user looks at it, **Then** it is not presented as something that opens as a task of its own.
3. **Given** a subtask, **When** the user looks at it, **Then** it can still be opened as its own task.
4. **Given** a subtask that is ticked, **When** the checklist is looked at, **Then** the checklist's items and count are unchanged.
5. **Given** a checklist item that is ticked, **When** the task is looked at, **Then** the task is not marked as finished.

---

### Edge Cases

- A task is both in progress and blocked → both markings show; the stored state is not overridden by the derived condition.
- A task's prerequisite is deleted → the task stops being marked blocked.
- A prerequisite is finished on another device → the blocked marking clears once that reaches this device.
- Two tasks follow up on each other → neither is marked blocked; the follow-up marking still shows.
- A task has more subtasks than fit on the screen → the count states the true total and the list can be scrolled; nothing is truncated without saying so.
- A new tag's name differs from an existing one only in letter case → treated as the same tag.
- A new tag's name is only whitespace → rejected with the reason stated.
- A state-filtered agenda view matches nothing → the section says it is empty rather than disappearing.
- A saved view that filters by state is opened after the application is restarted → the state filter still applies.
- The last checklist item is deleted → the count reads zero of zero rather than a heading with nothing to say.
- There are no tags at all and the user opens the place where tags are chosen → the create action is still reachable.

---

## Requirements *(mandatory)*

### Functional Requirements

**Stored workflow state**

- **FR-001**: System MUST store a workflow state on every task, with exactly these values: *Open* (the default, meaning the user has claimed nothing about it), *Next up*, and *In progress*.
- **FR-002**: System MUST keep the workflow state independent of the task's completion flag: completing a task MUST NOT change its stored workflow state, and reopening a task MUST restore the workflow state it held.
- **FR-003**: Users MUST be able to change a task's workflow state from the task's own screen.
- **FR-004**: System MUST show, in one place on the task screen, the task's current workflow state and the states the user may move it to.
- **FR-005**: System MUST offer exactly one way to move a task between workflow states, so the same change is made the same way from every screen that offers it.
- **FR-006**: System MUST show a task's workflow state wherever tasks are listed.
- **FR-007**: A task that existed before this feature, or one created without choosing a state, MUST be shown as *Open*.
- **FR-008**: System MUST treat workflow state and completion as separate facts when tasks are asked for by state: a state-filtered list MUST exclude completed tasks.

**Derived markings**

- **FR-009**: System MUST mark a task as blocked when at least one prerequisite that blocks it is unfinished, and MUST stop marking it blocked once that prerequisite is finished.
- **FR-010**: System MUST distinguish a relation that blocks a task from a relation that only relates or orders work: a task that follows up on another task MUST NOT be marked blocked.
- **FR-011**: System MUST mark a task as a follow-up when it holds such a relation to another task.
- **FR-012**: System MUST show every marking that applies to a task at the same time; one marking MUST NOT replace another.
- **FR-013**: Where a row cannot show every marking, System MUST state how many markings it did not show.
- **FR-014**: System MUST show the following markings where they apply: blocked, follow-up, in progress, next up, overdue, no date, pinned, repeating, completed.

**Agenda views by state**

- **FR-020**: System MUST offer, when the user adds a section to an agenda view, sections filtered by task state and by the blocked and follow-up conditions.
- **FR-021**: A state-filtered agenda section MUST show only tasks whose workflow state matches the chosen state.
- **FR-022**: A state-filtered agenda section MUST exclude completed tasks.
- **FR-023**: A state-filtered agenda section MUST state how many tasks match.
- **FR-024**: A saved agenda view built from a state MUST keep applying that state filter when it is reopened, including after the application is restarted.
- **FR-025**: A state-filtered agenda view MUST reflect a task's state change without the user re-creating or re-saving the view.
- **FR-026**: System MUST offer built-in views for the tasks in progress and for the blocked tasks.

**Tags**

- **FR-030**: System MUST allow the user to create a new tag from any place where tags are chosen for a task.
- **FR-031**: A tag created while choosing tags for a task MUST be applied to that task.
- **FR-032**: System MUST NOT create a second tag whose name matches an existing tag; it MUST offer the existing tag instead.
- **FR-033**: System MUST state the reason when a new tag's name is rejected, and MUST NOT create the tag.
- **FR-034**: The place where tags are chosen MUST continue to offer the existing tags alongside the create action.
- **FR-035**: A newly created tag MUST appear in the list of tags to choose from without the user having to reopen that list.

**Inline subtasks and checklist items**

- **FR-040**: The task screen MUST show the task's subtasks inline, each with a checkbox that reflects whether the subtask is finished.
- **FR-041**: The task screen MUST show the task's checklist items inline, each with a checkbox that reflects whether the item is finished.
- **FR-042**: Toggling a subtask's checkbox MUST mark that subtask finished or unfinished and MUST update the subtask count.
- **FR-043**: Toggling a checklist item's checkbox MUST mark that item finished or unfinished and MUST update the checklist count.
- **FR-044**: The task screen MUST state, for the subtask list and for the checklist list separately, how many items are finished out of how many there are.
- **FR-045**: The user MUST be able to add a subtask and a checklist item from the task screen without opening a separate panel.
- **FR-046**: System MUST keep subtasks and checklist items as distinct kinds of item: ticking a subtask MUST NOT change any checklist item, and ticking a checklist item MUST NOT change the task's completion or any subtask.
- **FR-047**: A checklist item MUST NOT be presented as something that opens as a task of its own.
- **FR-048**: A subtask MUST remain openable as its own task.
- **FR-049**: A finished subtask or checklist item MUST be visibly marked as finished while its title stays readable.
- **FR-050**: The subtask list and the checklist list MUST be shown as two separate lists, each with its own heading and count, when a task has both.

### Key Entities

- **Task workflow state**: The stored state of a task's work — *Open*, *Next up*, or *In progress*. Independent of whether the task is finished.
- **Task marking**: A condition shown on a task that is derived from the task and the other tasks — blocked, follow-up, overdue, no date, pinned, repeating, completed. A task may carry several at once.
- **Task relation**: A typed link from one task to another — one that blocks the other, or one that only relates the two, such as a follow-up.
- **Agenda view**: A named, saved set of sections that filter tasks by date, state, condition, tag, project, priority, or text.
- **Agenda section**: One filter within an agenda view, with a heading and a count of what it matched.
- **Subtask**: A task that belongs to another task and can be opened as a task of its own.
- **Checklist item**: A titled item belonging to a task that can be ticked and is not itself a task.
- **Tag**: A named, coloured label that can be applied to a task and can be created where it is applied.

---

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Every task that is blocked, is a follow-up, is overdue, or holds a stored state shows that marking in every list that shows tasks, with no marking hidden by another.
- **SC-002**: 100% of tasks that are both blocked and overdue show both markings in the same row.
- **SC-003**: A user changes a task's workflow state in at most two actions from the task's own screen, without leaving that screen.
- **SC-004**: A user builds and saves an agenda view filtered to a task state in under 60 seconds, without leaving the view editor.
- **SC-005**: A user creates a new tag from the place where they choose tags in under 15 seconds, and the new tag is applied to the current task with no further step.
- **SC-006**: A user adds a subtask and a checklist item from the task screen without any separate panel opening.
- **SC-007**: The subtask list and the checklist list each state how many items are finished out of how many, and the stated numbers match the items shown.
- **SC-008**: Ticking a checklist item never changes the task's completion state or any subtask, and ticking a subtask never changes any checklist item.
- **SC-009**: A user who opens the task screen can name the task's workflow state, whether it is blocked, and how many subtasks and checklist items are finished, without scrolling past any of them.
- **SC-010**: Tasks that existed before this feature open with a workflow state of *Open* and are otherwise unchanged.

---

## Assumptions

- "Followed next" is read as a follow-up relation — the task follows up on another task. The app already stores typed relations between tasks, so this marking is derived rather than stored, and a follow-up is deliberately not a blocker.
- The stored workflow state has three values (*Open*, *Next up*, *In progress*) and deliberately has no "waiting" value: waiting on someone else is already derived from the task's real prerequisites, and a second stored flag for it would give the same fact two sources of truth.
- The completion flag stays as it is, including the create screen's present behaviour of offering no completion control before the task exists. The only checkboxes added are on subtask and checklist rows.
- The attached screenshot is the reference for the layout of a task's parts — a flat section with a heading, a count of finished items, and rows carrying real checkboxes — and not a mandate to reproduce another application's chrome or to restructure the rest of the task screen.
- Subtasks keep everything they have today: their own screen, their own dates, their own tags. Separating subtasks from checklist items is a matter of how the two are presented and how they behave, not of taking capability away from subtasks.
- The single mark that a task row carries today is replaced by a set of markings that can appear together; the existing priority rule that picks one mark does not survive this change.
- Tags belong to a user, as they do today, and a tag created inline belongs to the user signed in on that device.
- Agenda views keep the behaviour they have today: a view is saved by name, can be renamed and deleted, and can be copied to another profile.

---

## Out of Scope

- Choosing the priority or colour of a marking, or letting the user define their own markings.
- A manual "waiting" or "on hold" flag, for the reason given in Assumptions.
- Declaring a task blocked without a prerequisite — blocked-ness stays derived from real prerequisites.
- Nesting subtasks deeper than the current single level.
- Sharing a checklist between tasks, or checklists that outlive their task.
- Changing how tags are coloured, grouped, or inherited by projects.

---

## Governance

This feature changes the task entity, the way tasks are stored and the contract by which tasks travel between devices. The project constitution requires both a specification and an architectural decision record for a change of that shape, and the decision record must be produced before the implementation starts.

Two decisions in particular are the kind the decision log exists for, because more than one answer was reasonable and the choice is not recoverable from the code:

- Whether a task carries a stored workflow state, and which values it may hold.
- Whether "waiting" is a stored state or a derived condition — decided here as derived, because waiting on someone else is already derived from real prerequisites.

The scenario matrix for this feature is not declared in this document. A declaration without a corresponding scenario file is a blocking error, and a scenario file without a carrier opens a hole in the coverage matrix, which the coverage ratchet treats as a regression. Both belong to the planning phase, where the scenarios are written and the carriers are planned with them.

The planning phase has since written the ten scenarios: they are in [quickstart.md](quickstart.md), one per requirement cluster, each driving the production path rather than a test double. The matrix itself — the scenario files, their carriers and the declarations here — is produced by the implementation, and each scenario's file lands **in the same commit as its carrier**, so the hole count never moves. That ordering is a requirement, not a convenience: a scenario with a claim and no carrier is a hole, and a *growing* hole count fails `check-traceability-ratchet.py`. The tasks that create them are in [tasks.md](tasks.md).
