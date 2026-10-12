# Feature Specification: Settings Preferences

**Feature Branch**: `008-settings-preferences`
**Created**: 2026-10-12
**Status**: Draft

**Input**: User description: "систематизировать тестовое покрытие через матрицы тест-кейсов: settings has three flows — theme, notifications, tab cycling — and no scenarios"

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Make the app look right (Priority: P2)

A user wants a dark app at night and a light one in the morning. The theme selector is a three-way segmented button — System, Light, Dark — and the choice should survive and not leave the screen broken.

**Why this priority**: A theme preference that breaks navigation is worse than no theme preference. Getting it *applied* is a visual question this test surface cannot answer, which is stated plainly below.

**Independent Test**: Can be validated by toggling each segment and continuing to use the screen.

**Acceptance Scenarios**:

1. **Given** Settings > Interface, **When** the user changes the theme mode, **Then** the screen stays intact and navigable afterwards.

---

### User Story 2 - Control notifications (Priority: P2)

A user wants to decide what the app is allowed to interrupt them about. The notification surface has to render its controls at all.

**Why this priority**: P2 because the flows assert presence, not effect — see the risks.

**Independent Test**: Can be validated by opening the Notifications tab and confirming its controls are there.

**Acceptance Scenarios**:

1. **Given** Settings > Notifications, **When** the user opens the tab, **Then** its controls are present and usable.

---

### User Story 3 - Get to any settings tab (Priority: P2)

Settings is a rail of 11 tabs. Every one of them has to open without crashing, because the rail is how all of the above are reached.

**Why this priority**: It is the regression guard for a crash that already shipped once, which is exactly the kind of failure worth a cheap standing check.

**Independent Test**: Can be validated by opening Settings from the menu and stepping through every tab.

**Acceptance Scenarios**:

1. **Given** Settings is open, **When** the user visits every tab in the rail, **Then** each opens without a crash.

### Edge Cases

- 11 tabs is a count that will grow. The scenario claims each existing tab opens; it does not pin the number.
- Theme is visual, so neither the a11y tree nor Maestro exposes the selected segment.

## Scenario Matrix

```yaml
scenarios:
  - id: SETTINGS-01
    title: Changing the theme mode leaves Settings intact and navigable
    area: feature.settings
    priority: P2
    status: confirmed
    targets: [android]
  - id: SETTINGS-02
    title: The notification surface renders its controls
    area: feature.settings
    priority: P2
    status: confirmed
    targets: [android]
  - id: SETTINGS-03
    title: Every tab in the Settings rail opens without crashing
    area: feature.settings
    priority: P2
    status: confirmed
    targets: [android]
```

## Requirements

### Functional Requirements

- **FR-001**: The theme mode MUST be selectable as System, Light or Dark.
- **FR-002**: Changing the theme MUST NOT prevent further navigation.
- **FR-003**: The Notifications settings surface MUST render its controls.
- **FR-004**: Every tab in the Settings rail MUST open without crashing.

### Success Criteria

- **SC-001**: A user can change any preference and continue working without the app breaking.
- **SC-002**: No Settings tab is unreachable.

## Out of Scope

- Persistence of a preference across a restart.
- What each notification toggle actually does when a notification fires — no flow triggers one.
- The specific copy of the theme selector's segment labels.

## Risks

**These three scenarios are the weakest in the corpus, and the reason is stated rather than hidden.**

- `SETTINGS-01` asserts that the screen *survives* the toggle. The theme is visual: neither the a11y tree nor Maestro exposes which segment is selected, so a build that ignores the user's theme choice entirely would still pass. The scenario claims the toggle is safe, not that it works. Fixing that needs a different surface — a setting read back through instrumentation, or a screenshot comparison.
- `SETTINGS-02` asserts controls are present. A control that renders and does nothing passes.
- `SETTINGS-03` asserts no crash. It cannot assert that a tab shows the right content.

Recording the weak claims here is the point: an unstated weak scenario reads as a strong one until it fails to catch the bug it was supposed to catch.