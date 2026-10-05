# The calendar palette follows the theme

**capability:** `app-theming` | **status:** proposed

**Issue:** #197

---

## ADDED Requirements

### Requirement: REQ-THEME-001

Every colour the calendar screen paints SHALL be derived from the active theme's
colour roles, in the light case and the dark case alike. A calendar palette that is
written out as fixed values independent of the active theme SHALL NOT exist.

The requirement covers both modes because the failure is asymmetric and the asymmetry
is what hid it. The light palette was already derived from the theme; the dark palette
was not. A reviewer comparing the two branches would see one following the theme and
one not, and could reasonably conclude the dark one was an intentional
screenshot-matched brand choice rather than an oversight — which is, in fairness,
partly why it exists.

#### Scenario: The dark calendar follows a dark theme

- The app is in its dark mode
- The calendar screen is shown
- The calendar's background, surface, grid lines and text are the ones the active
  theme provides for a dark surface

#### Scenario: The light calendar follows a light theme

- The app is in its light mode
- The calendar screen is shown
- The calendar's background, surface, grid lines and text are the ones the active
  theme provides for a light surface

#### Scenario: Changing the theme changes the calendar

- The user switches the app between light and dark mode
- The calendar is reopened
- Its surfaces and text follow the new mode, with no leftover values from the old one

### Requirement: REQ-THEME-002

A colour that represents a selected item, the current day, or the current time SHALL
be a role the active theme provides, adjusted for legibility on that theme's
background. It SHALL NOT be the accent the user chose, presented unadjusted.

The user chooses an accent to express a preference; they do not choose it as a
background to render text or a chip on. Presenting it unadjusted means the calendar
is legible for some accents and not for others, and the user has no way to know which
before they pick.

#### Scenario: A vivid light accent stays legible as a selection colour

- The user chooses the yellow accent
- The app is in its light mode
- A task is selected on the calendar
- The selection is distinguishable from an unselected task and from the background

#### Scenario: The current-day badge remains readable

- The user chooses the yellow or orange accent
- Today's date is shown on the calendar
- The badge is distinguishable from the surrounding grid

#### Scenario: The current-time indicator remains visible

- The user chooses a vivid light accent
- The current time falls inside the displayed range
- The indicator is visible against the column background

#### Scenario: Every accent is reviewed in both modes

- The palette change is complete
- The calendar is inspected for all nine accents, in light and in dark mode
- No accent produces a selection, badge or indicator that cannot be seen

### Requirement: REQ-THEME-003

A colour that expresses a **semantic** property of an item — whether it is overdue,
whether it is done, what its priority is — SHALL NOT be drawn from the theme, and
SHALL keep its own fixed values independent of the accent and the mode.

This exists to bound the change. A sweep that replaced every fixed colour with a
theme role would make an overdue task indistinguishable from a normal one in a
theme that happens to place those two roles close together. Semantic scales answer
questions the theme has no opinion about.

#### Scenario: Overdue and normal tasks stay distinguishable

- The user chooses any accent, in either mode
- An overdue task and a normal task are both shown
- The two are told apart by their own colours, not by the accent

#### Scenario: Priority remains a fixed scale

- The user chooses any accent
- Tasks of different priorities are shown
- Their priority colours are unchanged by the choice of accent
