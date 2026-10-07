# Attachment Viewing and Annotation — Observable Behavior

**capability:** `attachments/storage-and-linking` | **status:** proposed

---

## ADDED Requirements

### Requirement: REQ-1

The task editor SHALL allow the user to choose a file through the system file picker and
attach it to a task.

#### Scenario: Attaching from the task detail
- User opens a task
- User taps "+ Add file" under Attachments
- The system file picker opens, filtered to the formats the app can do something with
- User picks a file
- The file is copied into app storage and appears in the attachment list

#### Scenario: The picker offers only formats the app handles
- The picker opens
- Its filter is derived from the same MIME table the viewer routes on
- A format that would be routed to `External` appears in the list, because the system app
  may still handle it

---

### Requirement: REQ-2

Attaching SHALL work both when creating a task and when editing one.

#### Scenario: Attaching during creation
- User opens the create screen
- No "Add file" control is offered
- The task has no id until it is saved, and an attachment row would have nothing to point at

#### Scenario: Attaching after creation
- The task exists and has an id
- The editor offers "Add file"
- The file is linked to that task

---

### Requirement: REQ-3

Attaching SHALL NOT require access to external storage.

#### Scenario: Picking from a provider, not from a volume
- The picked file is copied into the app's own attachments directory
- The attachment row points at the copy
- Revoking the source app's permission afterwards does not break the attachment

---

### Requirement: REQ-4

Images and text documents SHALL open inside the app.

#### Scenario: Opening an image
- User taps an image attachment
- The viewer opens in the app with pinch-to-zoom
- The zoom transform survives a configuration change

#### Scenario: Opening a text document
- User taps a text attachment
- The viewer opens in the app with selectable text
- A `.md` file is rendered as markdown; any other text format is shown as plain text

#### Scenario: SVG is not treated as an image
- User taps an `.svg` attachment
- It is handed to a system app rather than rendered by the image viewer

#### Scenario: A generic MIME type falls back to the extension
- The picked file reports `application/octet-stream`
- The route is decided by its extension instead

---

### Requirement: REQ-5

Formats with no built-in viewer SHALL be handed to a system application.

#### Scenario: Opening a PDF
- User taps a PDF attachment
- The platform opens it with the user's default PDF application

---

### Requirement: REQ-6

When no system application exists for a format, the system SHALL say so and offer to pass
the file to another application, rather than failing.

#### Scenario: No handler installed
- User taps an attachment nothing on the device can open
- The app explains that it has no application for this format
- The screen offers a share action
- Nothing crashes and no error dialog appears with a stack trace

---

### Requirement: REQ-7

A document larger than the in-app limit SHALL NOT open in the app without an explanation.

#### Scenario: Over the limit
- The user taps a text attachment of 8 MiB
- The viewer reports that the file is too large to open here
- The file is not read into memory
- The user is still offered the system handler and Share

#### Scenario: Exactly at the limit
- A text attachment of exactly the limit opens in the app

#### Scenario: The limit is checked before the read
- The size is taken from the file's metadata
- The read is not attempted for a file over the limit

---

### Requirement: REQ-8

The attachment list in the task detail SHALL show every attachment or offer an explicit way
to see the rest.

#### Scenario: More than five attachments
- The task has nine attachments
- All nine are listed
- The list is not silently cut at five

---

### Requirement: REQ-9

The user SHALL be able to select a fragment of a text attachment and keep a note with it.

#### Scenario: Creating an annotation
- User opens a text attachment
- User opens the annotations panel for that attachment
- User sets the start and end of the range and sees the quoted text
- User writes a note and saves
- The annotation is listed against that attachment

---

### Requirement: REQ-10

An annotation SHALL persist across app launches and SHALL belong to one specific attachment
of one specific task.

#### Scenario: After a restart
- The app is killed and relaunched
- The annotation is still listed against its attachment

#### Scenario: Two tasks with the same file
- Two tasks each have an attachment
- An annotation created on one does not appear on the other

---

### Requirement: REQ-11

When the attachment's text changes, an annotation SHALL be marked stale and remain available
to edit or delete.

#### Scenario: The quote is still present
- The file's text changed elsewhere
- The annotation's quote still appears in the text
- The annotation resolves to that occurrence and is not stale

#### Scenario: The quote has moved
- The quote is still present but at different offsets
- The annotation resolves by quote and is not stale

#### Scenario: The quote is gone
- The quote no longer appears in the text
- The annotation is marked stale
- The user can still edit or delete it
- It is not deleted automatically, because an automatic deletion is indistinguishable
  from data loss

---

### Requirement: REQ-12

Annotations SHALL be included in a data export and restored on import.

#### Scenario: Export then import
- A task has one attachment carrying two annotations
- Data is exported
- Data is restored into an empty install
- Both annotations are present against the same attachment

---

### Requirement: REQ-16

An annotation SHALL travel with its attachment rather than as a document of its own.

#### Scenario: Attachment sync becomes available
- Attachment sync is enabled for a scope
- An attachment and its annotations are transferred as one unit
- No separate document type is registered for annotations

#### Scenario: Why not a separate type
- A separate type could deliver an attachment without its annotations
- That would present as data loss to the user, with no error anywhere
