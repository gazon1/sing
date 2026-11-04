# The notes screen on a wide window

**capability:** `notes` | **status:** proposed

**Issue:** #1524

---

## ADDED Requirements

### Requirement: REQ-NOTES-WIDE-1

On a window at least 840 dp wide, the notes screen SHALL show the note list and
the selected note at the same time. Selecting a note SHALL fill the detail pane
and SHALL leave the list in place.

Below 840 dp the notes screen SHALL show one pane, and selecting a note SHALL
open it as a screen of its own, as it did before this change.

The width SHALL be measured against one breakpoint, so that the list and the
pane it makes room for appear at the same width.

#### Scenario: A note opened on a wide window

- **Given** the window is at least 840 dp wide
- **And** the note list holds a note
- **When** the note is selected in the list
- **Then** the note's preview is shown beside the list
- **And** the list is still shown, with its search and quick-add controls

#### Scenario: Nothing is selected on a wide window

- **Given** the window is at least 840 dp wide
- **And** no note has been selected
- **When** the notes screen is shown
- **Then** the detail pane states that no note is selected
- **And** the pane is not blank

#### Scenario: A wide window narrowed below the breakpoint

- **Given** a note is open in the detail pane
- **And** the window is narrowed below 840 dp
- **Then** the notes screen shows the list alone
- **And** no note is open

#### Scenario: A note opened on a narrow window

- **Given** the window is narrower than 840 dp
- **When** a note is selected in the list
- **Then** the note's preview replaces the list
- **And** returning goes back to the list

### Requirement: REQ-NOTES-WIDE-2

The detail pane SHALL be the same note preview the narrow layout opens, and
SHALL behave the same from inside it: editing SHALL open the editor, deleting
SHALL remove the note and leave the pane empty, and a link to another note SHALL
open that note in the pane.

Closing the pane SHALL clear the selection. It SHALL NOT leave the notes
screen: a pane is not a screen, and there is no navigation entry behind it.

#### Scenario: Closing the pane

- **Given** a note is open in the detail pane
- **When** the pane's back affordance is used
- **Then** the pane shows that no note is selected
- **And** the notes list is still shown

#### Scenario: Deleting from the pane

- **Given** a note is open in the detail pane
- **When** the note is deleted and the deletion confirmed
- **Then** the note is no longer listed
- **And** the pane shows that no note is selected

#### Scenario: Following a link to another note

- **Given** a note with a link to another note is open in the detail pane
- **When** the link is followed
- **Then** the linked note is shown in the pane
- **And** the list keeps its place
