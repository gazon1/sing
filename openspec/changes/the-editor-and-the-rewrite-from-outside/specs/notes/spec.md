# notes — Observable Behavior

**capability:** `notes` | **status:** accepted

---

## ADDED Requirements

### Requirement: REQ-NOTES-REWRITE-1

A bridged text field SHALL know whether the value it received is a
replacement or a keystroke.

A bridged field is a Compose `TextFieldState` fed by a flow. The flow may
replace the field's content, and it may only do so when the content really
changed. A replacement and a keystroke arrive the same way — as a new value —
so a field that needs to tell them apart SHALL carry a revision token
alongside the value, and the token SHALL move only when content is
replaced.

The user's unsaved edits SHALL win over an incoming replacement: a field with
unsaved edits SHALL NOT be re-seeded, and the replacement SHALL wait for the
draft to become clean.

An editor's own save echoing back through the same channel SHALL NOT count as
a replacement: the content is compared, so a round trip leaves the field's
revision and the caret untouched.

#### Scenario: A note rewritten from outside

- **Given** the note editor holds a note and the draft is clean
- **And** the note's content is rewritten by something outside the editor
- **When** the editor observes the new content
- **Then** the editor's title and body show the new content
- **And** the draft's revision has moved

#### Scenario: A rewrite that arrives while the user is typing

- **Given** the note editor holds a note with unsaved edits
- **And** the note's content is rewritten by something outside the editor
- **When** the editor observes the new content
- **Then** the editor's title and body still hold the user's edits
- **And** the draft's revision has not moved

#### Scenario: The editor's own save

- **Given** the note editor holds a note and the draft is clean
- **When** the editor saves the note
- **And** the repository echoes the saved note back
- **Then** the draft's revision has not moved
- **And** the editor's content is unchanged

### Requirement: REQ-NOTES-REWRITE-2

Every bridged field site in the app SHALL have a recorded answer to "who may
rewrite this field?".

The answers are:

- **none** — the value is a one-way projection, and nothing rewrites it;
- **the value itself** — a rewrite arrives as a new value, and the field
  replaces its content;
- **the value and a revision token** — the site carries a counter alongside
  the value, so the bridge can tell a replacement from a keystroke.

The registry of sites and the registry of field shapes SHALL live together,
and the convention test that guards the shape SHALL guard the rewrite answer
as well: a bridged field site that is not in the registry SHALL fail the
build.

#### Scenario: A new bridged field site

- **Given** a new bridged text field is added to the app
- **And** it is not in the rewrite registry
- **When** the convention test runs
- **Then** it fails
- **And** it names the field site it did not find

#### Scenario: A site that cannot tell a replacement from a keystroke

- **Given** a bridged field site whose answer is "the value itself"
- **Then** the registry says so
- **And** the question of whether that site needs a revision token is recorded
      as an open one

### Requirement: REQ-NOTES-LINKS-1

The editor SHALL NOT keep a map of the links it has inserted.

The body session SHALL NOT carry a side table of inserted links: a link in the
body is found by walking the rich-text state's children, and a side table is a
second source of truth that drifts.

A link that cannot be acted on SHALL NOT be presented as one that can.

#### Scenario: A link in the body

- **Given** the body contains a link
- **When** the editor asks where the link is
- **Then** the answer comes from the body's own tree
- **And** the session holds no link table

### Requirement: REQ-NOTES-E2E-1

The flows that edit a note's body SHALL be part of the smoke suite.

The end-to-end job runs on pull requests labelled `run-maestro`. A flow
outside the smoke tag is not part of the promise the suite makes, so a bug in
a note's body can sit in the tree with every other gate green.

#### Scenario: A change to the note body

- **Given** the note body is edited
- **When** the smoke suite is selected
- **Then** the flow that edits a note's body is among the flows that run
