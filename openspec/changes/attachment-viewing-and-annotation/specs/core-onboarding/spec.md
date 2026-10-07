# Spotlight Onboarding — Observable Behavior

**capability:** `core/onboarding` | **status:** proposed

---

## ADDED Requirements

### Requirement: REQ-ONB-1

The onboarding system SHALL resolve a step's target by identity rather than by a hard-coded
screen position.

#### Scenario: The target moves
- The highlighted action is moved to a different position in the app bar
- The tour still points at it
- The hole is recomputed from the target's measured bounds, not from the position it used
  to occupy

#### Scenario: The window is resized
- The window changes size while the tour is on screen
- The hole follows the target rather than staying where it was drawn

---

### Requirement: REQ-ONB-2

The onboarding system SHALL NOT draw a scrim while a step's target has not been laid out.

#### Scenario: Early frame
- The screen has composed but the target has not been measured
- Nothing is drawn
- A scrim with no hole would cover the app and give no clue what it is waiting for

---

### Requirement: REQ-ONB-3

The onboarding system SHALL record which version of the tour the user has seen, using the
existing settings store.

#### Scenario: No second mechanism
- The seen-version lives in the settings namespace the app already uses
- Clearing settings resets the tour along with everything else
- There is no separate store with its own migration and its own sign-out behaviour

---

### Requirement: REQ-ONB-4

The onboarding system SHALL allow a tour to be replayed without altering the record of
what the user has been shown.

#### Scenario: Replay
- The user finished version 1
- They replay the tour
- The stored version is unchanged
- Showing the tour again does not un-show it

---

### Requirement: REQ-ONB-5

The onboarding system SHALL keep the geometry that decides where the hole and the card go
independent of the graphics stack.

#### Scenario: The geometry is tested on the JVM
- The hole, the morph and the card placement are pure functions over rectangles
- They are covered by tests that run without a composition
- A `Path` is built from their results inside the composable and nowhere else

---

## ADDED Requirements

### Requirement: REQ-1

On first run the app SHALL show a short tour that highlights real interface elements.

#### Scenario: First run
- The user launches the app for the first time
- The agenda screen shows a dimmed scrim with a hole around the Saved Views action
- A card explains what the action does
- Advancing moves the hole to the next action

#### Scenario: The tour waits for its targets
- The screen composes before its highlighted elements are laid out
- Nothing is drawn
- The tour appears once the target has been measured

---

### Requirement: REQ-2

The tour SHALL be shown no more than once for the same version of its content.

#### Scenario: Second launch
- The user has finished the tour at version 1
- The app is relaunched
- Nothing is shown

#### Scenario: Skipping counts as having seen it
- The user taps Skip on the first step
- The tour does not appear again at version 1

---

### Requirement: REQ-3

Changing the content of the tour SHALL cause it to be shown again.

#### Scenario: A step is added
- Version 2 is shipped with an extra step
- A user who finished version 1 sees the tour again

#### Scenario: The version does not go backwards
- A user on version 2 who records version 1 stays at 2
- Two devices disagreeing about the current version cannot make the tour reappear

---

### Requirement: REQ-4

The user SHALL be able to skip the tour and run it again from settings.

#### Scenario: Replaying
- The user opens Settings → Interface → Help
- User taps "Show tips again"
- The tour runs from the first step

#### Scenario: A step whose target is missing
- One of the tour's targets is not on screen
- That step is skipped rather than shown with a hole around nothing
- The remaining steps still run
