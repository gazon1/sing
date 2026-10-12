# Feature Specification: Notes Editing

**Feature Branch**: `006-notes-editing`
**Created**: 2026-10-12
**Status**: Draft

**Input**: User description: "систематизировать тестовое покрытие через матрицы тест-кейсов: notes has three flows — create, edit body, filter — and no scenarios"

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Write a note and keep it (Priority: P1)

A user captures a note from the Menu sheet and comes back to it later. There is no save button: the editor autosaves. That makes persistence the thing to verify, because a note that only lives in memory until an explicit save would lose everything the user typed.

**Why this priority**: Losing a note is silent and unrecoverable. There is no moment where the app tells you it did not save.

**Independent Test**: Can be validated by creating a note, editing its body, leaving and reopening it.

**Acceptance Scenarios**:

1. **Given** the user is on the notes list, **When** they create a note through the quick-add row and submit it, **Then** the note exists and is reachable.
2. **Given** a note exists, **When** the user edits its body and reopens the note, **Then** the edited body is still there.

---

### User Story 2 - Narrow a long notes list (Priority: P2)

A user with many notes wants only the pinned ones, or only the archived ones. The filter chips switch the visible set, and each of the three filters has to produce its own set rather than all three looking alike.

**Why this priority**: Filtering is the difference between a list you keep and a list you scroll. It is P2 because the unfiltered list still works.

**Independent Test**: Can be validated with two notes, neither pinned nor archived, and stepping through all three chips.

**Acceptance Scenarios**:

1. **Given** two notes, neither pinned nor archived, **When** the user switches through All, Pinned and Archived, **Then** All shows both and the other two show neither.

### Edge Cases

- Quick-add lands on the note's preview, not its editor — the editor is reached via the "Edit" action on the preview. A flow that assumed quick-add opens an editor would fail for a reason unrelated to this feature.
- Quick-add submits on the IME "Done" action, which is also how the projects quick-add behaves. The two are consistent by accident of implementation; neither flow pins that as a contract.
- There is no FAB on the notes list. A note is created through the quick-add row.

## Scenario Matrix

```yaml
scenarios:
  - id: NOTES-01
    title: A note is created from the notes list
    area: feature.notes
    priority: P1
    status: confirmed
    targets: [android]
  - id: NOTES-02
    title: An edited note body survives reopening the note
    area: feature.notes
    priority: P1
    status: confirmed
    targets: [android]
  - id: NOTES-03
    title: The All, Pinned and Archived chips show different sets
    area: feature.notes
    priority: P2
    status: confirmed
    targets: [android]
```

## Requirements

### Functional Requirements

- **FR-001**: A note MUST be creatable from the notes list without leaving it.
- **FR-002**: Note body edits MUST be persisted without an explicit save action.
- **FR-003**: The notes list MUST filter by All, Pinned and Archived, and each filter MUST show its own set.

### Success Criteria

- **SC-001**: A user who types into a note and leaves loses nothing.
- **SC-002**: Switching between the three filters always changes what is shown, including to nothing.

## Out of Scope

- Note search, note linking, and the rich-text toolbar. No flow exercises them.

## Risks

- `notes/create-note.yaml` is labelled "Smoke" in its header, but it does assert creation. The scenario claims creation rather than a smoke pass, because that is what the flow actually does.