# Attachment Sync Setting — Observable Behavior

**capability:** `core/sync-state` | **status:** proposed

---

## ADDED Requirements

### Requirement: REQ-13

The attachment sync setting SHALL belong to the account-and-profile pair the data itself
belongs to.

#### Scenario: Two profiles on one device
- The same device is signed into one account with two profiles
- Attachment sync is enabled for the personal profile
- The work profile's setting is unaffected and still off

#### Scenario: Why not a global key
- The one remaining global settings slot is shared by every profile
- A third global key would let one profile's answer describe another's files

---

### Requirement: REQ-14

Changing the attachment sync setting SHALL take effect without an app restart and without a
profile switch.

#### Scenario: The value is observed
- The setting is stored per scope
- Whatever acts on it reads it through the same observed state flow as the other per-scope
  settings, rather than reading it once at startup

#### Scenario: Why this is explicit
- A global setting that no collector watched did not take effect until the app restarted
- That bug is recorded in the code next to the fix
- A setting nothing observes reproduces it

---

### Requirement: REQ-15

While attachment sync is unavailable, the control SHALL NOT appear to work.

#### Scenario: No transport exists yet
- The server has no attachment document type and no binary storage
- The row is visible but disabled
- Its subtitle says the feature is not yet supported
- It does not accept a tap

#### Scenario: The value is still shown
- The stored preference is per scope and is displayed
- Showing it means the day the transport lands, each scope already holds its own answer

---

### Requirement: REQ-15a

The setting SHALL default to off.

#### Scenario: Upgrading an existing install
- An install at the previous schema version is upgraded
- The new column reads as off
- No scope claims a preference it never expressed and that nothing could honour

---

### Requirement: REQ-STAGE-0

Attachment sync SHALL NOT be advertised as available until the server can accept the
document type and store the bytes.

#### Scenario: The client sends a type the server does not know
- The client registers an attachment document type before the server accepts one
- Every attachment is rejected
- The failure is silent from the user's point of view and looks like lost files

#### Scenario: The current state
- `DocType` carries only the six types the Flutter `sync_core` contract fixes
- The word "attachment" does not occur anywhere in the sync core
- The upload service is a stub that returns a local path as though it were a remote one

#### Scenario: Why the row is shown rather than hidden
- A user who notices attachments missing from their sync deserves to learn the capability
  exists and is not ready
- That is different from learning it does not exist
