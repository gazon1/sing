# Feature Specification: Profiles and Projects

**Feature Branch**: `007-profiles-projects`
**Created**: 2026-10-12
**Status**: Draft

**Input**: User description: "систематизировать тестовое покрытие через матрицы тест-кейсов: profile (create, isolation) and projects (open detail, add task) have four flows and no scenarios"

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Keep two lives apart (Priority: P1)

A user wants work tasks and home tasks in one app without them mixing. Profiles are separate containers, and the promise is that data in one is invisible in the other — including after a switch.

**Why this priority**: Isolation is the entire value of a profile. Two profiles that show each other's tasks are worse than one profile, because the user is now filtering manually and trusting a promise the app does not keep.

**Independent Test**: Can be validated by seeding a task in a second profile and checking it is invisible from the first, then visible again on the way back.

**Acceptance Scenarios**:

1. **Given** the Menu sheet, **When** the user creates a second profile by supplying a name, **Then** the new profile is listed.
2. **Given** a task seeded in the second profile, **When** the user switches to the default profile, **Then** the task is not visible, and switching back makes it visible again.

---

### User Story 2 - Group work under a project (Priority: P1)

A user has tasks that belong together. A project holds them, and its detail screen is where the grouped list lives.

**Why this priority**: Projects are the primary organising tool for tasks; without them the task list is the only structure available.

**Independent Test**: Can be validated by opening a project's detail from Plans and adding a task to it.

**Acceptance Scenarios**:

1. **Given** a project exists, **When** the user opens it from the Plans tab, **Then** its detail screen shows the project's own task list.
2. **Given** a project's detail is open, **When** the user adds a task from the quick-add field, **Then** the empty state is left and the task is listed under that project.

### Edge Cases

- The create-profile dialog pre-fills an emoji, so only the name has to be supplied. Asserting the emoji would pin a default nobody relies on.
- The quick-add field submits on the IME "Done" action, as the notes quick-add does.
- Opening a project detail with no tasks shows an empty state — asserted only incidentally, since NOTES/empty-state coverage lives with the empty-state scenarios.

## Scenario Matrix

```yaml
scenarios:
  - id: PROFILE-01
    title: A second profile is created and listed
    area: feature.profile
    priority: P1
    status: confirmed
    targets: [android]
  - id: PROFILE-02
    title: Tasks in one profile are invisible in another
    area: feature.profile
    priority: P1
    status: confirmed
    targets: [android]
  - id: PROJECTS-01
    title: A project's detail screen shows its own task list
    area: feature.projects
    priority: P1
    status: confirmed
    targets: [android]
  - id: PROJECTS-02
    title: A task added from a project's detail is listed under it
    area: feature.projects
    priority: P1
    status: confirmed
    targets: [android]
```

## Requirements

### Functional Requirements

- **FR-001**: A user MUST be able to create a profile from the Menu sheet.
- **FR-002**: Data in one profile MUST NOT be visible in another.
- **FR-003**: Switching profiles MUST return to the previous profile's data intact.
- **FR-004**: A project's detail screen MUST list that project's tasks.
- **FR-005**: A task added from a project's detail MUST belong to that project.

### Success Criteria

- **SC-001**: A user can run two separate task lists in one app and never has to filter to tell them apart.
- **SC-002**: Switching profiles takes no more steps than switching back.

## Out of Scope

- Renaming or deleting a profile.
- Archiving a project.
- Ordering, colours and icons on a project — not asserted by any flow here.

## Risks

- Profile isolation is asserted with tasks only. Whether isolation holds for notes, projects and tags is not covered, and a partial isolation bug would pass all four scenarios here.