# The theme follows a chosen mode

**capability:** `app-theming` | **status:** proposed

**Issue:** #210

---

## ADDED Requirements

### Requirement: REQ-THEME-008

The user SHALL be able to select one of three theme modes: follow the system, always
light, or always dark. The app SHALL offer all three, and the selection SHALL be
persisted.

#### Scenario: Following the system

- The user selects "follow the system"
- The operating system is in dark mode
- The app paints its dark palette

#### Scenario: The system changes while the app follows it

- The user has selected "follow the system"
- The operating system switches from light to dark
- The app repaints in its dark palette without the user revisiting settings

#### Scenario: Forcing light on a dark system

- The user selects "always light"
- The operating system is in dark mode
- The app paints its light palette

#### Scenario: A forced mode ignores the system

- The user has selected "always dark"
- The operating system is in light mode
- The app paints its dark palette

#### Scenario: The selection survives a restart

- The user selects one of the three modes
- The app is closed and opened again
- The app starts in the mode the user selected

### Requirement: REQ-THEME-009

Every part of the app SHALL derive its palette from the resolved theme mode. The app
SHALL NOT contain a place where the operating system's appearance is consulted directly
outside the single point where the mode is resolved.

This requirement exists because the two conditions — the operating system's setting and
the user's stored choice — were consulted separately and disagreed. A screen that
resolved the mode on its own could show a palette the rest of the app was not using,
which is exactly what happened: the calendar painted a dark palette onto a light app.

#### Scenario: One part of the app disagrees with another

- The user has selected "always light"
- The operating system is in dark mode
- Every screen, including the calendar, paints the light palette

#### Scenario: No screen reads the system setting on its own

- The source is searched for direct readings of the operating system's appearance
- Every hit is the single resolution point, or a test asserting this requirement

### Requirement: REQ-THEME-010

A user whose stored preference predates the three modes SHALL keep the appearance they
had. A stored dark-mode preference SHALL become the "always dark" mode, and a stored
light-mode preference SHALL become the "always light" mode. Neither SHALL become
"follow the system".

Defaulting an existing user to "follow the system" would change their app the first time
it updated, and would undo a dark-mode choice they had made deliberately. The stored
value is read rather than rewritten, so an older version of the app still finds the last
state it understood.

#### Scenario: A user who had dark mode on

- A stored preference of "dark mode on" exists
- The app is updated
- The app starts in "always dark" mode

#### Scenario: A user who had dark mode off

- A stored preference of "dark mode off" exists
- The app is updated
- The app starts in "always light" mode, not "follow the system"

#### Scenario: A user with no stored preference

- No stored preference for appearance exists
- The app starts in "follow the system" mode

#### Scenario: An older version can still read the stored preference

- The appearance preference has been read as a three-mode value
- An older version of the app reads the stored preference
- It finds the last dark-mode value that version understood

### Requirement: REQ-THEME-011

The current theme mode SHALL appear in an exported settings backup, and importing a
backup SHALL restore the mode.

#### Scenario: Export and re-import

- The user selects "always dark"
- The settings are exported and then imported into a fresh install
- The fresh install shows "always dark" selected

#### Scenario: A backup predating the three modes

- An exported backup carries the older two-state preference
- The backup is imported
- The imported preference is interpreted under REQ-THEME-010