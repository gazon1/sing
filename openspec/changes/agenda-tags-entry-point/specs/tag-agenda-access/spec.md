# Tag-Driven Agenda Access — Observable Behavior

**capability:** `tag-agenda-access` | **status:** proposed

---

## ADDED Requirements

### Requirement: REQ-TA-001

From a screen that displays a filtered set of items, the user **SHALL** be able
to open that set as an agenda.

**Rationale:** the filtered set is already on screen and already scoped. Requiring
the user to reconstruct it elsewhere is the gap this change closes.

#### Scenario: Open a filtered set as an agenda
- A search returns items matching a set of tags
- The user invokes the agenda action on the result
- An agenda opens containing exactly those items

---

### Requirement: REQ-TA-002

A single tag and a set of tags **MUST** reach the same agenda, with the same
behavior, differing only in which items the agenda contains.

**Rationale:** two routes to the same screen will diverge. The single-tag case is
the one that stops being maintained.

#### Scenario: One tag and several behave identically
- A user opens an agenda from a single tag
- A user opens an agenda from a selection of several tags
- Both produce the same screen, the same actions, and the same empty state

---

### Requirement: REQ-TA-003

An agenda opened this way **MUST** be savable, and the user **MUST** be able to
use it without saving it.

**Rationale:** the multi-tag view is the kind a user builds once and reuses, so
requiring a save makes the action a worse experience than the path it replaces;
requiring persistence on every use fills the saved list with near-duplicates.

#### Scenario: Save is offered, not required
- A user opens a multi-tag agenda
- The user is offered the option to keep it
- Declining still leaves a usable agenda for the current session

---

### Requirement: REQ-TA-004

An agenda containing no matching items **MUST** state that, and **MUST** name the
tags it is filtering by.

**Rationale:** a filter can resolve to nothing — a tag deleted since the view was
saved, or a combination that matches no items. An agenda that silently shows
nothing reads as the app having lost the data, and the user cannot tell which
condition they are in.

#### Scenario: Empty result is explained
- An agenda is built from a tag combination that matches no items
- The agenda states that nothing matches
- The agenda names the tags it is filtering by
