# The task surfaces follow the theme

**capability:** `app-theming` | **status:** proposed

**Issue:** #198

---

## ADDED Requirements

### Requirement: REQ-THEME-004

The task list and task detail surfaces SHALL take every surface, text, border and
divider colour from the app's active theme, in the light case and the dark case
alike. Neither surface SHALL carry a palette of fixed values that the theme cannot
reach.

The requirement is about the *task* surfaces specifically because they are the ones
that must be true for a user to believe the rest of what they see. A user who
selects a light theme and lands on a dark task list has not been given a preference;
they have been given a contradiction, and it is the first screen they open.

#### Scenario: A light-themed user sees a light task list

- The app is in its light mode
- The task list is shown
- Its background, cards, borders, dividers and text are the ones the light theme
  provides

#### Scenario: A dark-themed user sees a dark task list

- The app is in its dark mode
- The task list is shown
- Its background, cards, borders, dividers and text are the ones the dark theme
  provides

#### Scenario: A light-themed user sees a light task detail screen

- The app is in its light mode
- A task is opened for editing
- The editor's surfaces and text follow the light theme

#### Scenario: Switching the theme updates the task surfaces

- The user switches the app between light and dark mode
- The task list and an open task detail are both revisited
- Both follow the new mode, with no fixed value surviving the switch

### Requirement: REQ-THEME-005

The task list and task detail surfaces SHALL take their accent-derived colours —
the active filter, the selected state, links and focus treatment — from the active
theme's accent role, so that a chosen accent is visible on these screens.

An accent that is chosen and then not applied is worse than no accent control at
all: the user believes they have expressed a preference, and the app has accepted
the gesture and discarded it. This requirement exists because the accent became
genuinely functional app-wide while these screens continued to hardcode a blue.

#### Scenario: The chosen accent reaches the task list

- The user chooses an accent other than the default
- The task list is shown
- Its active-filter and selected states use that accent, adjusted to stay legible

#### Scenario: The chosen accent reaches the task detail screen

- The user chooses an accent other than the default
- A task is opened
- Its links, focus rings and active controls use that accent, not a fixed colour

#### Scenario: A vivid accent stays legible on the task surfaces

- The user chooses the yellow accent
- The task list and task detail are inspected
- Accent-derived elements remain distinguishable from their backgrounds

### Requirement: REQ-THEME-006

A colour that expresses a **semantic** property of a task — its priority, whether it
is overdue, whether it is done — SHALL NOT be drawn from the theme, and SHALL keep
fixed values independent of the accent and the mode.

This bounds the change deliberately. Replacing every fixed colour with a theme role
would tie "this task is urgent" to an arbitrary user preference, so two tasks of
obviously different importance could render in near-identical colours depending on
which accent was chosen.

#### Scenario: Priority does not change with the accent

- The user changes the accent
- Tasks of different priorities are shown on the task list
- Their priority colours are unchanged

#### Scenario: Overdue tasks stay distinguishable

- The user chooses any accent, in either mode
- An overdue task and a normal task are both shown
- The two are told apart by their own colours

#### Scenario: Completed tasks stay distinguishable

- The user chooses any accent, in either mode
- A completed task and an open task are both shown
- The two are told apart by their own colours
