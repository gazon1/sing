# projects — Observable Behavior

**capability:** `projects` | **status:** accepted

---

## ADDED Requirements

### Requirement: REQ-PROJECTS-REWRITE-1

The project detail SHALL tell a replacement from a keystroke.

The project detail holds a **bridged** text field — a Compose `TextFieldState`
fed by a draft in the ViewModel — and the draft observes a project the
repository owns. That project can be renamed or re-described from outside: a
sync from another device, a change made on another screen. A replacement and a
keystroke arrive the same way, as a new value, so the draft SHALL carry a
revision token alongside the value, and the token SHALL move only when the
content is replaced.

The draft SHALL tell four cases apart:

- **the first seed** initialises the draft;
- **the echo of the draft's own save** — the draft already holds exactly that
  text — SHALL change nothing and SHALL NOT move the revision;
- **a replacement from outside** SHALL be taken, and SHALL move the revision;
- **the user's unsaved edits** SHALL win: a dirty draft SHALL NOT be re-seeded,
  and the replacement SHALL wait for the draft to become clean.

The repository's own echo SHALL mark the draft clean: when the repository holds
exactly what the draft holds, the write landed, and the baseline moves with it,
so that a later replacement is not refused as "the user is typing". A write
that landed while the user kept typing does not match the draft, so the newer
text wins and the draft stays dirty.

The project's name and description fields SHALL pass the draft's revision to the
bridge, so that the bridge's echo guard is lifted for a replacement that lands
on a value the field produced earlier.

#### Scenario: A project renamed from outside

- **Given** the project detail holds a project and the draft is clean
- **And** the project's name is rewritten by something outside the screen
- **When** the screen observes the new name
- **Then** the name field shows the new name
- **And** the draft's revision has moved

#### Scenario: A rewrite that arrives while the user is typing

- **Given** the project detail holds a project with unsaved edits
- **And** the project's description is rewritten by something outside the screen
- **When** the screen observes the new description
- **Then** the description field still holds the user's edits
- **And** the draft's revision has not moved

#### Scenario: The screen's own save

- **Given** the project detail holds a project and the draft is clean
- **When** the user edits the name and the write is accepted
- **And** the repository echoes the saved project back
- **Then** the draft's revision has not moved
- **And** the name field is unchanged
