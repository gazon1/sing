# Feature Specification: Agenda Saved Views

**Feature Branch**: `003-agenda-saved-views`

**Created**: 2026-10-12

**Status**: Draft

**Input**: User description: "у меня 8 e2e-потоков Maestro в зоне agenda, ни один не связан со сценарием — матрица показывает дыры вместо существующих проверок"

## Clarifications

### Session 2026-10-12

- Q: Inbox как «список без даты»? → A: **Нет.** Как зафиксировано в `Maestro/flows/agenda/01-smart-lists.yaml`: Inbox — полная agenda (Today, Yesterday, …, No Date). Датированная задача остаётся в Inbox и просто переезжает в секцию своей даты, поэтому утверждение делается на заголовке секции, а не на исчезновении строки.
- Q: Desktop? → A: **Заявляется там, где есть носитель.** Часть потоков имеет desktop-аналог (`AgendaReachabilityFlowTest`, `SavedViewsFlowTest`, `SavedAgendaEditFlowTest`), но это отдельный Kotlin-тест со своим `@DisplayName`, а не тот же поток. Заявка desktop ставится вместе с этим тегом, а не по факту существования аналога.
- Q: Что считать сценарием — поток или поведение? → A: **Поведение.** Один поток может закрывать несколько утверждений; `06-full-lifecycle` проходит путь create → results → edit → rename → delete и заявлен одним сценарием, потому что пользователь не воспринимает его как пять отдельных задач.

### Session 2026-11-23

- Q: Inbox как «список без даты»? → A: **Да — вопрос переоткрыт.** Ответ сессии 2026-10-12 описывал Inbox как полную agenda, но с тех пор три нижние вкладки agenda стали непересекающимся разбиением задач (ADR `docs/decisions/2026-10-31-the-agenda-tabs-are-disjoint.md`): Inbox — только недатированные задачи, Today — просроченные и сегодняшние, Upcoming — всё будущее. Датированная задача уходит из Inbox в Today или Upcoming. Ответ ниже и требования FR-001/FR-002 приведены к этому правилу; правка — [#3114](https://github.com/gazon1/sing/issues/3114).

## User Scenarios & Testing *(mandatory)*

### User Story 1 - The Inbox holds what is not scheduled yet (Priority: P2)

A user opens Inbox to see what is unprocessed. The tab holds the tasks that are not scheduled yet — the undated ones — so the list answers "what is not scheduled yet" without filtering. A task that gets a date leaves the Inbox: it appears in Today when it is overdue or due today, and in Upcoming when it is due later.

**Why this priority**: The bucketing is what makes Inbox usable at volume, but a user with fewer than a handful of tasks reads the flat list fine.

**Independent Test**: Can be validated by creating one dated and one undated task and confirming the undated one leaves the Inbox when a date is set and returns to it when the date is removed.

**Acceptance Scenarios**:

1. **Given** one undated task in Inbox, **When** the user gives it a date, **Then** it leaves the Inbox and appears in the Today tab, or in the Upcoming tab when the date lies further out.
2. **Given** an empty Inbox, **When** the user opens it, **Then** the No Date section is shown with its count rather than the screen being blank.

---

### User Story 2 - Reuse a filter set as a named view (Priority: P1)

A user keeps revisiting the same filter combination — active tasks, tasks for this project, anything overdue. Saving it under a name turns the repetition into one tap, and the saved view survives the session, the restart, and being copied to another profile.

**Why this priority**: This is the feature's whole reason for existing. Without save, the agenda is a filter the user re-applies by hand every time.

**Independent Test**: Can be validated by saving the current agenda under a name, leaving it, and confirming it can be reopened by name with the same results.

**Acceptance Scenarios**:

1. **Given** an active agenda filter, **When** the user saves it under a name, **Then** the view appears in the saved-views list and the user returns to the agenda rather than to the list.
2. **Given** a saved view exists, **When** the user opens it, **Then** a results screen lists the tasks matching that view's definition, not the view's name alone.
3. **Given** a saved view exists, **When** the app is restarted without clearing data, **Then** the view is still listed and still opens.
4. **Given** a saved view exists, **When** the user copies it to another profile, **Then** the copy appears in the destination profile's list.

---

### User Story 3 - Manage what is saved (Priority: P1)

Saved views outlive their usefulness. A user needs to rename one, delete one, and see the empty state before they have saved anything — each without losing the rest of their list.

**Why this priority**: Delete and rename are how the list stays trustworthy. A saved-views list that can only grow is a list nobody trusts enough to read.

**Independent Test**: Can be validated by walking one view through create → rename → results → delete and confirming the list returns to its empty state.

**Acceptance Scenarios**:

1. **Given** no saved views exist, **When** the user opens the list, **Then** the empty state is shown rather than a blank screen.
2. **Given** a saved view, **When** the user deletes it, **Then** it is gone from the list and the others are untouched.
3. **Given** a saved view, **When** the user renames it, **Then** the new name is what the list shows and what the results screen is reached by.

---

### Edge Cases

- **Deleting from the list vs from edit mode**: list-level delete is immediate, edit-mode delete asks for confirmation. Two different behaviours under one word; only the list-level path is asserted here.
- **Save pre-fills the name with the agenda's title**: the editor arrives with "Today" in the name field, so a user typing without clearing appends to it. Asserted in the flows because it is a real interaction hazard, not because it is desirable.
- **Non-agenda bottom tabs**: Plans, Pomodoro and Calendar do not carry the agenda, so the saved-views entry is not expected there.

## Scenario Matrix

```yaml
scenarios:
  - id: AGENDA-01
    title: A dated task leaves the Inbox
    area: feature.agenda
    priority: P2
    status: confirmed
    targets: [android]
  - id: AGENDA-02
    title: The saved-views list shows its empty state
    area: feature.agenda
    priority: P1
    status: confirmed
    targets: [android]
  - id: AGENDA-03
    title: Saving the current agenda as a named view, then deleting it
    area: feature.agenda
    priority: P1
    status: confirmed
    targets: [android]
  - id: AGENDA-04
    title: Opening a saved view lists the tasks matching its definition
    area: feature.agenda
    priority: P1
    status: confirmed
    targets: [android]
  - id: AGENDA-05
    title: Saved views are reachable from every agenda bottom tab
    area: feature.agenda
    priority: P2
    status: confirmed
    targets: [android]
  - id: AGENDA-06
    title: A saved view survives create, rename, reopen and delete
    area: feature.agenda
    priority: P1
    status: confirmed
    targets: [android]
  - id: AGENDA-07
    title: A saved view can be copied to another profile
    area: feature.agenda
    priority: P2
    status: confirmed
    targets: [android]
  - id: AGENDA-08
    title: A saved view survives an app restart
    area: feature.agenda
    priority: P1
    status: confirmed
    targets: [android]
```

## Requirements

### Functional Requirements

- **FR-001**: Inbox MUST hold the tasks that are not scheduled yet — the undated ones — and MUST show a count for its No Date bucket.
- **FR-002**: Setting a task's date MUST take it out of Inbox into the Today or Upcoming tab; clearing the date MUST bring it back.
- **FR-003**: The saved-views list MUST show an explicit empty state when no view is saved.
- **FR-004**: Saving the current agenda MUST create a named view and return to the agenda.
- **FR-005**: Opening a saved view MUST list the tasks matching that view's definition.
- **FR-006**: A saved view MUST survive an app restart that preserves the database.
- **FR-007**: A saved view MUST be renameable, deletable, and copyable to another profile.
- **FR-008**: The saved-views entry MUST be reachable from every agenda-carrying bottom tab.

### Success Criteria

- **SC-001**: A user can reach a saved view's results from the agenda in at most three interactions.
- **SC-002**: A saved view created today is still usable tomorrow without any migration or repair step.
- **SC-003**: Deleting a saved view never disturbs the others in the list.

## Out of Scope

- Shared or synchronised saved views — copying is explicit and per-profile; there is no sync path.
- Edit-mode delete's confirmation dialog, which differs from list-level delete and is not asserted here.
- Plans, Pomodoro and Calendar tabs, which do not carry the agenda.

## Risks

- Four of the eight flows depend on seeded tasks. A dropped seed turns a saved-views scenario into one that asserts nothing and still passes, because the empty state is itself a valid outcome.

## Notes

`AGENDA-06` overlaps `AGENDA-03` and `AGENDA-04` deliberately: it is the regression guard for a save that navigated nowhere, and for a delete that asked for confirmation when the list level says it should not.
