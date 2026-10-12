# Feature Specification: Pomodoro Focus Timer

**Feature Branch**: `002-pomodoro-timer`

**Created**: 2026-10-12

**Status**: Draft

**Input**: User description: "систематизировать тестовое покрытие через матрицы тест-кейсов: pomodoro — первая область, у которой уже есть пять e2e-потоков Maestro, но ни один не связан со сценарием, поэтому матрица показывает 5 дыр вместо пяти проверок"

## Clarifications

### Session 2026-10-12

- Q: Это новая функциональность или спецификация существующего поведения? → A: **Спецификация существующего поведения.** Pomodoro-таймер, чипы фокус-задач и пропуск фазы уже реализованы; здесь они впервые описаны как проверяемые сценарии. Новый код не требуется.
- Q: Заявлять ли desktop, где нет ни одного потока? → A: **Нет.** Заявка без носителя — это дыра `○`, а не покрытие. Desktop будет заявлен, когда появится носитель.
- Q: Границы Pomodoro? → A: **Только таймер.** Как зафиксировано в потоке `Maestro/flows/pomodoro/01-open-tab.yaml`: истории и экрана статистики нет, добавлять их — другая спека.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Start a focus block on a chosen task (Priority: P1)

A user opens the Pomodoro tab and starts working on something specific. The tab offers the tasks available to focus on as chips; selecting one both selects it and starts the timer, so the user does not configure the task and then press play as two separate steps.

**Why this priority**: Choosing what to work on is the entire point of a focus timer. Without task binding the feature is a countdown widget, and the chip list is the only thing that makes it a Pomodoro.

**Independent Test**: Can be validated by seeding one task, opening the tab, and selecting the task's chip — the timer must show that task as the focus and the phase must already be running, without a second tap.

**Acceptance Scenarios**:

1. **Given** a task exists, **When** the user opens the Pomodoro tab and selects that task's chip, **Then** the timer shows the task as the focus and the work phase is already running.
2. **Given** no task exists, **When** the user opens the Pomodoro tab, **Then** the timer is usable and no task chip is offered, because there is nothing to focus on.

---

### User Story 2 - Control a running cycle (Priority: P1)

A user works in 25-minute blocks and breaks between them. They need to pause when interrupted, resume when they return, skip a block they do not need to finish, and abandon the session entirely. Each control leaves the user able to tell which phase they are in and which cycle of the session they have reached.

**Why this priority**: Without controls the timer only ever counts down one block. Pause/resume/skip are the difference between a countdown and a cycle.

**Independent Test**: Can be validated by starting a work block and exercising each control in turn, checking the phase label and the cycle counter after each.

**Acceptance Scenarios**:

1. **Given** a running work block, **When** the user pauses and then resumes it, **Then** the timer and the work phase survive both transitions and the cycle counter does not advance.
2. **Given** a work block on cycle 1, **When** the user skips it, **Then** a break phase begins and the cycle counter reads cycle 2 with one completed block recorded.
3. **Given** a running session with a focus task, **When** the user stops the session, **Then** the timer returns to a fresh paused work cycle and the focus task is released.

---

### User Story 3 - See the timer's state at a glance (Priority: P2)

A user returning to the app after a break needs to know, without reading numbers, whether the timer is running or paused, which phase it is in, and how many work blocks they have completed.

**Why this priority**: The state has to be legible for the controls above to be usable, but the first-run state is the least interesting moment in the lifecycle.

**Independent Test**: Can be validated by opening the tab on a fresh install and confirming the phase, cycle counter and both controls are visible without scrolling.

**Acceptance Scenarios**:

1. **Given** a fresh install, **When** the user opens the Pomodoro tab, **Then** the timer shows the full work duration, paused, on cycle 1, with stop, play and skip all available.

---

### Edge Cases

- **No tasks at all**: the chip list is empty rather than absent, so the tab still reads as usable — the timer is a valid thing to run with no task bound.
- **Tapping a chip when already running**: selection and start are one action, so this switches the focus task rather than restarting from zero. No flow asserts this today, which is why it is an edge case and not an acceptance scenario.
- **Skipping a break**: returns to Work and advances the counter; symmetric with skipping work, but only the work direction is asserted today.

## Scenario Matrix

```yaml
scenarios:
  - id: POMODORO-01
    title: A fresh Pomodoro timer sits paused on work cycle 1
    area: feature.pomodoro
    priority: P2
    status: confirmed
    targets: [android]
  - id: POMODORO-02
    title: Selecting a task chip starts the timer on that task
    area: feature.pomodoro
    priority: P1
    status: confirmed
    targets: [android]
  - id: POMODORO-03
    title: Pause and resume survive the phase and cycle state
    area: feature.pomodoro
    priority: P1
    status: confirmed
    targets: [android]
  - id: POMODORO-04
    title: Skipping a work block starts a break and advances the cycle
    area: feature.pomodoro
    priority: P1
    status: confirmed
    targets: [android]
  - id: POMODORO-05
    title: Stopping the session releases the focus task
    area: feature.pomodoro
    priority: P1
    status: confirmed
    targets: [android]
```

## Requirements

### Functional Requirements

- **FR-001**: The Pomodoro tab MUST offer the currently available tasks as selectable chips, and MUST offer none when no task is available.
- **FR-002**: Selecting a chip MUST both bind that task as the focus task AND start the timer, without a separate start action.
- **FR-003**: The timer MUST render its phase, its remaining duration and its cycle number, and MUST show both a play and a stop control in every state.
- **FR-004**: Pause MUST preserve the remaining duration and MUST NOT advance the cycle counter.
- **FR-005**: Skip MUST advance the phase from Work to a break and MUST increment both the cycle counter and the completed-work counter.
- **FR-006**: Stop MUST return the timer to a fresh paused Work cycle and MUST release the focus task.

### Success Criteria

- **SC-001**: A user can go from opening the app to a running work block bound to a chosen task in at most three interactions.
- **SC-002**: Every Pomodoro phase is identifiable from the screen alone, without relying on colour or animation.
- **SC-003**: A user can tell from the screen whether the timer is running or paused at a glance.

## Out of Scope

- Pomodoro history, statistics or reporting — the timer has no such surface today and adding one is its own feature.
- Desktop coverage: claimed targets carry a carrier, and no desktop Pomodoro carrier exists yet.

## Dependencies

- A seeded task is required by four of the five scenarios (`Maestro/helpers/seed-task.yaml`), because the chip list is populated by the task provider.

## Risks

- The chip list is empty on a fresh install, which makes FR-002 unobservable without a seed. Two scenarios would silently pass for the wrong reason if a seed were dropped: they would read the timer as usable and assert nothing about selection.

## Notes

The desktop column stays empty on purpose. Claiming `desktop` without a carrier would produce five `○` cells that read as outstanding work rather than as a decision not to claim the platform.