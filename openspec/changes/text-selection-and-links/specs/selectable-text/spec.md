# Selectable text and link dispatch — Observable Behavior

**capability:** `selectable-text` | **status:** proposed

---

## ADDED Requirements

### Requirement: REQ-TEXT-001

Text a person reads **SHALL** be selectable, unless the surface it is drawn on is
excluded with a recorded reason.

**Rationale:** an error message is the one piece of text a person has to take
somewhere else to get help with it. A backup id, a model id, a project name — each
of them is something a person writes down. Without selection they are pictures of
information. The exception is a gesture that is already spoken for: a row whose
long press opens a menu cannot also start a selection, and that is a decision, not
an accident.

**Test coverage:** the inventory table in the selectable-text skill is read by a
test that holds it against the code — a file that uses a text widget and is not in
the table fails the build.

#### Scenario: An error message can be copied
- **Given** the calendar sync has failed
- **And** the settings screen shows the failure
- **When** the person selects the message
- **Then** the message can be selected and copied

#### Scenario: A row whose long press is taken is excluded
- **Given** a task row whose long press opens the task's menu
- **Then** the row is excluded from the inventory, with the reason written down

---

### Requirement: REQ-TEXT-002

An internal link **SHALL** be read by exactly one rule, and that rule **SHALL**
define the schemes the app owns and the shape of an identifier.

**Rationale:** the app had four grammars for the same idea — two copies of the
extractor that differed in the type they returned, a third that read free text, and
a fourth written as a database pattern. The copies that differed in behaviour were
the ones nobody compared, and the one that acted on a link had no test at all. A
vocabulary that three features depend on cannot live in any of them.

**Test coverage:** a test that the marked-up form and the free-text form of the same
link resolve to the same target, and that a foreign scheme is never taken for one of
ours.

#### Scenario: One link, one answer
- **Given** a link to a task, written in the editor as markup
- **And** the same link, written as free text in a task description
- **When** both are read
- **Then** both resolve to the same target

#### Scenario: A foreign scheme is not ours
- **Given** the text `xnote://abc`
- **When** the links in it are read
- **Then** no internal link is found

---

### Requirement: REQ-TEXT-003

Tapping a link **SHALL** open the thing it names: an internal link inside the app,
a web address outside it.

**Rationale:** an internal address has no meaning outside the app, and handing one
to the platform produces either an error dialog or a browser search for the string.
A link that only looks like ours must not be opened as ours, either — a foreign
scheme is somebody else's thing.

**Test coverage:** a test that an internal link opens the screen it names and that a
web address is handed to the platform.

#### Scenario: An internal link stays in the app
- **Given** an answer that contains `note://abc`
- **When** the person taps the link
- **Then** the note opens, and no browser is involved

#### Scenario: A web address leaves the app
- **Given** an answer that contains `https://example.com`
- **When** the person taps the link
- **Then** the address is handed to the platform's handler

---

### Requirement: REQ-TEXT-004

A screen that shows a link **SHALL** be able to decide what happens when that link
is tapped, without knowing how to navigate.

**Rationale:** the alternative is a callback threaded through every screen that
shows a link. The composition local removes the threading; the cost is that the
link leaves the screen through an ambient value rather than through an argument.
The default is a no-op, so a preview, a test, or a screen that was never given a
handler cannot crash.

**Test coverage:** a test that the note preview's own callback is used for its own
links, and that the default handler does nothing.

#### Scenario: The note preview keeps its own navigation
- **Given** a note whose body links to another note
- **When** the person taps the link
- **Then** the other note opens inside the notes graph

#### Scenario: A screen with no handler does nothing
- **Given** a link tapped where no handler was provided
- **Then** nothing opens, and nothing fails
