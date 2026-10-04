# testtag-identity

## ADDED Requirements

### Requirement: REQ-1 A test tag is a stable identifier, never a displayed string

A `Modifier.testTag` SHALL be built from a constant, an id, or an action
identifier. It SHALL NOT be derived from a `label`, `text` or `title` value that
a user can read on screen.

Where a row carries both display text and a test tag, the row's model SHALL carry
the identifier as a distinct field, and the tag SHALL come from that field. The
existing `TestTags.EditorOverflow.*` constants are the reference shape: a
constant is the identity, a label is only what is displayed.

A static check SHALL reject a tag built from a display string, so the next such
call site is a build failure rather than a selector that quietly stops matching
on a localised device.

**Rationale:** `TaskEditorCallbacks.kt:78` declared
`TaskEditorMenuItem(val label: String, onClick, testTag: String? = null)` — no
stable action id — so `TaskContextMenuSheet.kt:96-100` built
`.testTag(TestTags.taskAction(label))`. The device used by this project's CI is
Russian. A label that translates yields a different tag, and a Maestro selector
written against the English tag stops matching while the flow still runs and
still reports a result. Nothing in the build objects, because the tag is a valid
string either way.

#### Scenario: A user runs the app in a non-English locale

- **Given** a context-menu row whose tag is a constant
- **When** the UI renders in a language other than English
- **Then** the tag is unchanged

#### Scenario: A call site derives a tag from a label

- **Given** a source file containing `testTag(TestTags.…(…label…))` or the same
      shape over `.text` or `.title`
- **When** the check runs
- **Then** it fails and names the file and line

#### Scenario: A new menu row is added

- **Given** a row model that has display text
- **When** its test tag is assigned
- **Then** the tag comes from a constant or an action id on the model
- **And** the row does not need a translated string to be addressable

#### Scenario: The check is disabled

- **Given** the check is removed or its allowlist is widened to accept display
      strings
- **When** a synthetic `testTag(TestTags.taskAction(label))` is introduced
- **Then** the positive control fails
- **And** the removal is not mistaken for the code being clean
