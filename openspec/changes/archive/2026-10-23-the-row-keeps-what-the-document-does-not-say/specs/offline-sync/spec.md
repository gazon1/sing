# offline-sync — Observable Behavior

**capability:** `offline-sync` | **status:** proposed

---

## ADDED Requirements

### Requirement: REQ-OS-029 A field a document does not carry is not reset on the receiving device

A task document that arrives from another device SHALL be applied against the
row that is already on this device: the document SHALL win wherever it carries
a value, and a field the document does not carry SHALL NOT be reset, cleared or
defaulted by the arrival of that document.

The document SHALL state which fields it carried, and those claims SHALL say
what the document speaks about: a document that carries no `seriesId` key
makes no claim about the series, and the series of the row stays what it is.

The absence of a key is not a claim that the field is empty. A device that
predates a field sends no key for it, and the allow-list says what a device
sends, and it grew over time. Reading the missing key as an emptiness turns
an older device into a deletion: the task leaves its series, or loses its
recurrence, or loses its estimate, and the pull reports success.

A document that carries the key with a null value SHALL be taken as the claim
it is — the task stands alone, or has no recurrence — and SHALL reset the
field on the receiving device.

The row of a task of another profile SHALL be written as the document says:
this device does not own that row, so it does not read it and does not speak
about the fields the document is silent about.

A document that omits a field the model cannot do without SHALL be read
against the row that is already on this device and SHALL be applied as far as
it goes: the missing field takes the row's own value, and the rest of the
document is applied. Such a document is a partial write, not a dropped event.

The fields that live outside the row — the tags and the blocking edges — are
not covered by this requirement: their claims are separate, and the merge of
a document with a row SHALL NOT add the local row's values for them. A part
the document does not carry is left alone, as the rule of the parts says.

#### Scenario: A document from a device that predates the series

- **Given** a task that belongs to a series on this device
- **When** a document arrives that carries no `seriesId` key
- **Then** the task is still in its series
- **And** the fields the document does carry are applied
- **And** the series still says one thing about the work, because the rule of
      the series does not depend on whether the sending device knew about it

#### Scenario: A document that takes the task out of the series

- **Given** a task that belongs to a series on this device
- **When** a document arrives that carries `seriesId: null`
- **Then** the task is no longer in its series on this device

#### Scenario: A document that carries no estimate

- **Given** a task with an estimate of 30 minutes on this device
- **When** a document arrives that carries no `estimateMinutes` key
- **Then** the task still has an estimate of 30 minutes

#### Scenario: A document of another profile

- **Given** a task of another profile on this device
- **When** a document about it arrives that carries no `seriesId` key
- **Then** the row of that task is written as the document says, and the fields
      the document is silent about take the model's defaults

#### Scenario: A document that omits a field the model cannot do without

- **Given** a task with a user id on this device
- **When** a document arrives that carries no `userId` key
- **Then** the document is applied against the row that is already here, and the
      row keeps its user id

#### Scenario: A document about a task this device has never seen

- **Given** a device that holds no row for the task a document is about
- **When** that document arrives
- **Then** the task is written as the document says, and the fields the
      document does not carry take their model's defaults
