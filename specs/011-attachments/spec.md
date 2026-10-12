# Feature Specification: Attachments

**Feature Branch**: `011-attachments`
**Created**: 2026-10-12
**Status**: Draft

**Input**: User description: "systematize test coverage for attachments feature — URL attachments on tasks, delete, persistence"

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Attach a URL to a task (Priority: P1)

A user wants to keep a link alongside the task they are working on. They add the URL and it appears in the task's attachment list. Without this, the user has to switch to a notes app or re-find the link every time.

**Why this priority**: Attachments are a common way to keep context with a task, and URL is the simplest attachment type that requires no file picker.

**Independent Test**: Add a URL to an existing task and verify it appears in the list.

**Acceptance Scenarios**:

1. **Given** a task exists, **When** the user adds a URL with a title, **Then** the attachment appears in the task's Attachments section with the correct title and a link icon.
2. **Given** an attachment exists, **When** the user taps delete, **Then** the attachment is removed from the list.
3. **Given** the attachments sheet is open, **When** the URL field is blank and the user taps Add URL, **Then** no attachment is created.
4. **Given** a task has an attachment, **When** the app is restarted, **Then** the attachment is still present.
5. **Given** a URL attachment exists, **When** the user views it, **Then** the row shows title, URL text, link icon, and a delete affordance but no open affordance (URL has no file behind it).

## Scenario Matrix

```yaml
scenarios:
  - id: ATTACH-01
    title: Adding a URL attachment makes it appear on the task
    area: feature.attachments
    priority: P1
    status: confirmed
    targets: [android]
  - id: ATTACH-02
    title: Deleting an attachment removes it from the list
    area: feature.attachments
    priority: P1
    status: confirmed
    targets: [android]
  - id: ATTACH-03
    title: Submitting an empty URL field does not add an attachment
    area: feature.attachments
    priority: P1
    status: confirmed
    targets: [android]
  - id: ATTACH-04
    title: An attachment persists after app restart
    area: feature.attachments
    priority: P1
    status: confirmed
    targets: [android]
  - id: ATTACH-05
    title: A URL attachment shows title, URL text and a link icon
    area: feature.attachments
    priority: P2
    status: confirmed
    targets: [android]
```

## Requirements

### Functional Requirements

- **FR-001**: The user MUST be able to add a URL attachment to any saved task.
- **FR-002**: The attachment MUST be saved to local storage and persist across restarts.
- **FR-003**: The user MUST be able to delete any attachment from a task.
- **FR-004**: A blank URL MUST NOT create an attachment.

### Success Criteria

- **SC-001**: A user who adds a URL attachment can see it again after force-stopping the app.
- **SC-002**: A URL attachment renders with a link icon, the title, and the URL text, not a generic placeholder.

## Out of Scope

- File attachments (require platform file picker, cannot be automated in Maestro).
- Opening URL attachments (no in-app browser in this feature).
- Attachment previews for image types.
