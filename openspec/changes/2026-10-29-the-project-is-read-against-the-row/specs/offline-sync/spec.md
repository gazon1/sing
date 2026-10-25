# offline-sync — Observable Behavior

**capability:** `offline-sync` | **status:** proposed

---

## ADDED Requirements

### Requirement: REQ-OS-031 The inherited tag groups of a project are a claim, and a document that makes no claim about them leaves them alone

The inherited tag groups of a project live outside the row of the project: they
live in a cross-reference table, and the row has no column for them. A document
that carries the groups SHALL have them applied, and a document that carries no
key for them SHALL make no claim about them — the groups the project inherits on
this device SHALL NOT be replaced by an empty set, and the pull SHALL NOT report
success while it did so.

The absence of the key is not a claim that the project inherits nothing. An empty
set is a claim, and it SHALL be taken as one: a document that carries an empty set
takes the groups away.

The row of the project SHALL make no claim about the inheritance: a row read on
this device cannot see the table, so its empty set is an artefact of the reading
and not a statement about the project.

#### Scenario: A project document that says nothing about the inheritance

- **Given** a project that inherits two tag groups on this device
- **When** a document arrives that carries no `inheritedTagGroupIds` key
- **Then** the project still inherits those two tag groups
- **And** the fields the document does carry are applied

#### Scenario: A project document that claims an empty inheritance

- **Given** a project that inherits two tag groups on this device
- **When** a document arrives that carries `inheritedTagGroupIds: []`
- **Then** the project inherits no tag group on this device

#### Scenario: A project document that claims other groups

- **Given** a project that inherits two tag groups on this device
- **When** a document arrives that carries `inheritedTagGroupIds: ["g9"]`
- **Then** the project inherits exactly that tag group on this device

---

## MODIFIED Requirements

### Requirement: REQ-OS-030 A field a document does not carry is not reset on the receiving device, for every synchronised type

The rule of REQ-OS-029 is the rule for every synchronised document type and
not only for the task. A document that arrives from another device SHALL be
applied against the row that is already on this device — for a note, a tag, a
tag group, a time entry and a project as well as for a task: the document SHALL
win wherever it carries a value, and a field the document does not carry SHALL
NOT be reset, cleared or defaulted by the arrival of that document.

The absence of a key is not a claim that the field is empty. A device that
predates a field sends no key for it, and the allow-list says what a device
sends, and it grew over time. Reading the missing key as an emptiness turns an
older device into a deletion: a note loses its body, a tag loses its group, a
time entry loses its note, a project loses its description, and the pull reports
success.

A document that carries the key with a null value SHALL be taken as the claim
it is — the note has no body, the tag belongs to no group — and SHALL reset
the field on the receiving device.

A field that holds a set cannot be claimed as null. The inherited tag groups of
a project are either absent from the document or carried as a set of groups, and
a document that carries the key with a null value does not decode: it is not a
claim the model can hold, and the document is not a partial write.

The row that is read SHALL be this profile's row. A document of another profile
SHALL be written as it says: this device does not own that row, so it does not
read it and does not speak about the fields the document is silent about.

A document that omits a field the model cannot do without SHALL be read
against the row that is already on this device and SHALL be applied as far as
it goes: the missing field takes the row's own value, and the rest of the
document is applied. Such a document is a partial write, not a dropped event.

The fields that live outside the row are not covered by this requirement:
their claims are separate, and the merge of a document with a row SHALL NOT
add the local row's values for them. Which keys are parts is per-type
knowledge, and it is the caller's.

#### Scenario: A note document from a device that predates the body field

- **Given** a note with a body on this device
- **When** a document arrives that carries no `bodyMarkdown` key
- **Then** the note still has its body
- **And** the fields the document does carry are applied

#### Scenario: A tag document that takes the tag out of its group

- **Given** a tag that belongs to a group on this device
- **When** a document arrives that carries `groupId: null`
- **Then** the tag belongs to no group on this device

#### Scenario: A time entry document that carries no note

- **Given** a time entry with a note on this device
- **When** a document arrives that carries no `note` key
- **Then** the entry still has its note

#### Scenario: A tag group document that omits a field the model cannot do without

- **Given** a tag group with a name on this device
- **When** a document arrives that carries no `name` key
- **Then** the document is applied against the row that is already here, and the
      group keeps its name

#### Scenario: A project document that carries no description

- **Given** a project with a description on this device
- **When** a document arrives that carries no `description` key
- **Then** the project still has its description
- **And** the fields the document does carry are applied

#### Scenario: A document of another profile

- **Given** a note of another profile on this device
- **When** a document about it arrives that carries no `bodyMarkdown` key
- **Then** the row of that note is written as the document says, and the fields
      the document is silent about take the model's defaults

#### Scenario: A document about a note this device has never seen

- **Given** a device that holds no row for the note a document is about
- **When** that document arrives
- **Then** the note is written as the document says, and the fields the
      document does not carry take their model's defaults
