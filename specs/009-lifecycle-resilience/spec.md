# Feature Specification: App Lifecycle Resilience

**Feature Branch**: `009-lifecycle-resilience`
**Created**: 2026-10-12
**Status**: Draft

**Input**: User description: "систематизировать тестовое покрытие через матрицы тест-кейсов: lifecycle has two flows — kill/restart persistence and rotation with an open editor — and no scenarios"

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Lose the app without losing the work (Priority: P1)

A user is using the app and it dies — the OS reclaims it, the phone runs out of memory, the process is killed. When they reopen it, everything they typed is still there. An app that loses work on a kill looks exactly like an app that never saved, and the user cannot tell the difference.

**Why this priority**: It is the single most damaging failure mode for a note-taking app, and it is invisible until the moment it happens.

**Independent Test**: Can be validated by seeding data, killing the process without clearing state, and relaunching.

**Acceptance Scenarios**:

1. **Given** data has been created, **When** the app's process is killed and relaunched without clearing state, **Then** the data is still there — so it was persisted rather than held in memory.

---

### User Story 2 - Keep working while the screen changes shape (Priority: P2)

A user is mid-edit when the device rotates, or a window is resized. The editor stays open and usable, and what they typed stays in it. Losing a half-written task to a rotation is one of the most avoidable losses in the app.

**Why this priority**: It shares the failure mode above — silent loss of typed work — but the trigger is common enough that it belongs in the same spec, and the assertion is different: state survives the *event*, not the process.

**Independent Test**: Can be validated by opening a task editor, rotating, and checking the editor and its title.

**Acceptance Scenarios**:

1. **Given** a task editor is open with a title, **When** the device rotates, **Then** the editor is still open, still usable, and still holds the title.

### Edge Cases

- A configuration change that recreates the editor is the classic way a Compose screen loses its field state; this scenario is the standing guard against it.
- Killing the app while a *sync* is in flight is a different question, owned by the sync scenarios rather than here.

## Scenario Matrix

```yaml
scenarios:
  - id: LIFECYCLE-01
    title: Data survives a process kill and a relaunch that does not clear state
    area: feature.lifecycle
    priority: P1
    status: confirmed
    targets: [android]
  - id: LIFECYCLE-02
    title: The task editor stays open and keeps its content through rotation
    area: feature.lifecycle
    priority: P2
    status: confirmed
    targets: [android]
```

## Requirements

### Functional Requirements

- **FR-001**: Data MUST survive a process kill and a relaunch that preserves app state.
- **FR-002**: A configuration change MUST NOT discard an open editor or its content.
- **FR-003**: A relaunch that does not clear state MUST NOT be mistaken for a first launch.

### Success Criteria

- **SC-001**: No user input is lost to a process kill or a rotation.
- **SC-002**: Reopening the app after a kill is indistinguishable from reopening it after a normal exit.

## Out of Scope

- Clearing app data, which is a different operation with a different expectation.
- Multi-window and split-screen resizing — the flows cover rotation only.
- Whether a killed sync resumes; that belongs to the sync scenarios.

## Risks

- `lifecycle/01-kill-restart-persist.yaml` is the only flow that proves persistence against a real kill. Anything that persists on a clean exit but not a kill would pass every other flow in this repository, because every other relaunch is a clean one.