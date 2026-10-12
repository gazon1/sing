# Feature Specification: Data Portability

**Feature Branch**: `005-data-portability`
**Created**: 2026-10-12
**Status**: Draft

**Input**: User description: "систематизировать тестовое покрытие через матрицы тест-кейсов: backup round-trip and archive restore have no scenarios attached"

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Take their data somewhere else (Priority: P1)

A user is moving to a new phone, or copying to a second device. They need the app's data to survive leaving the device, and to come back when the device is wiped.

**Why this priority**: It is the only path by which a user can lose everything at once. Everything else degrades; a failed restore does not.

**Independent Test**: Can be validated by seeding a task, taking a backup, wiping app data, and restoring.

**Acceptance Scenarios**:

1. **Given** a task exists, **When** the user creates a backup, wipes the app's data, and restores from that backup, **Then** the task is back.
2. **Given** a backup exists, **When** the user restores it, **Then** the restore reports what it did rather than failing silently.

---

### User Story 2 - Undo an archive (Priority: P2)

A user archives something by accident, or archives something and changes their mind a week later. Archive is supposed to be reversible; a task in the Archive is out of sight but not gone.

**Why this priority**: Archiving without a way back is data loss with extra steps. But the archive itself is a deliberate action, so the cost of getting it wrong is lower than for backup.

**Independent Test**: Can be validated by archiving a task from its detail menu, finding it in Archive, and restoring it.

**Acceptance Scenarios**:

1. **Given** a task is in Inbox, **When** the user archives it and then restores it from the Archive screen, **Then** it is back in Inbox.

### Edge Cases

- Restoring from a backup written by a different version of the app. Not covered by either flow; both use a backup this version wrote.
- An archived task that was also deleted. Restore has nothing to restore.
- Archive holds projects and notes as well as tasks; both flows cover tasks only.

## Scenario Matrix

```yaml
scenarios:
  - id: BACKUP-01
    title: A task survives a create-backup, wipe and restore round trip
    area: feature.backup
    priority: P1
    status: confirmed
    targets: [android]
  - id: ARCHIVE-01
    title: An archived task is restored to Inbox
    area: feature.archive
    priority: P2
    status: confirmed
    targets: [android]
```

## Requirements

### Functional Requirements

- **FR-001**: The user MUST be able to create a backup of the app's data.
- **FR-002**: Restoring a backup MUST return the data it contained.
- **FR-003**: An archived task MUST be restorable to Inbox.
- **FR-004**: A restore MUST report what it did.

### Success Criteria

- **SC-001**: A user who backs up, loses their device and restores gets their data back without manual steps.
- **SC-002**: No user action in the app is one-way; archive in particular has a visible way back.

## Out of Scope

- Automatic or scheduled backup. Both flows are user-initiated.
- Cross-version restore compatibility.
- Archive of notes, projects and tags.

## Risks

- `archive/01-restore.yaml` is labelled "Smoke" in its header but exercises a real round trip; the label is older than what the flow does. The scenario here claims the round trip, not a smoke check.
- A backup that restores nothing would pass if the seed were dropped, because an empty restore looks the same as a correct one from the flow's side. The seed is therefore load-bearing.