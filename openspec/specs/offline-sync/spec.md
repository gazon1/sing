# offline-sync Specification

## Purpose
Keep local edits safe when the network is not there. Every change to a synchronised
document is queued durably before it is reported as saved, queued work is sent by the
account that made it and answered under that same account, ordering survives clock
drift, and a pull cycle either applies a page completely or says plainly that it could
not.

## Requirements

### Requirement: REQ-OS-001

Every local change to a synchronised document type SHALL be written to a durable
outbox before the change is reported as saved, and SHALL survive process death.

#### Scenario: Change survives process kill
- User edits a task title on device A
- The app is killed before any network call completes
- The app is restarted
- The pending change is still queued and is delivered on the next sync

#### Scenario: Offline edit
- The device has no network connectivity
- User edits a task
- The edit is stored locally and the task remains visible and editable
- Sync status reports no connection as an operational state, not a failure

---

### Requirement: REQ-OS-002

An outgoing patch SHALL carry the values of the fields that changed, each with a
logical clock reading, and SHALL NOT carry a full snapshot of unchanged fields.

#### Scenario: Single-field edit
- User changes only a task's due date
- The patch sent to the server contains exactly one field operation
- The patch does not contain a value for the task title

#### Scenario: Clock skew between devices
- Two devices have wall clocks that differ by several minutes
- A patch authored on the clock-behind device is delivered after a patch authored on
  the clock-ahead device for the same field
- Ordering is decided by the logical clock, not by wall-clock time

---

### Requirement: REQ-OS-003

The server SHALL apply field operations independently: a field is overwritten only when
the incoming patch's logical clock is newer than the version already stored for that
field.

#### Scenario: Two devices, two different fields (merge)
- Task has title "Buy milk" and no due date
- Device B changes the due date and its patch is delivered first
- Device A changes the title and its patch is delivered second, from a base state
  where the due date is still empty
- After both patches, the task on every device has the new title AND the new due date
- Both devices are told their patch was applied

#### Scenario: Delivery order does not matter
- The same two patches from the previous scenario are delivered in the opposite order
- The final state is identical

---

### Requirement: REQ-OS-004

When two devices change the same field concurrently, the value with the newer logical
clock SHALL win and the other value SHALL be discarded.

#### Scenario: Same field, same moment
- Device A sets a task's title to "Oat milk" and device B sets it to "Almond milk",
  both from the same base state
- After both patches, all devices show the same title
- The discarded value is reported in the patch outcome so the client can tell that a
  concurrent write happened

---

### Requirement: REQ-OS-005

Delivering the same patch identity more than once SHALL NOT change any server state and
SHALL return the outcome of the first application.

#### Scenario: Lost response
- The client sends a batch; the server applies it; the response is lost in transit
- The client retries the identical batch
- Every patch in the retry is reported as already applied, and no document version
  advanced a second time

---

### Requirement: REQ-OS-006

If any patch in a batch fails, the entire batch SHALL be rolled back and the client
SHALL retain all of its queued changes.

#### Scenario: Failure in the middle of a batch
- A client sends ten patches; the third cannot be applied
- None of the ten are applied
- All ten remain queued on the client

---

### Requirement: REQ-OS-007

A document type with no registered local handler SHALL NOT advance the download cursor,
and the number of such events SHALL be reported to the caller.

#### Scenario: Server knows a type the client does not
- The server returns an event for a document type this client version cannot apply
- The download cursor is not advanced past that event
- The sync result reports at least one unapplied event

#### Scenario: Unapplied events do not block progress forever
- A client that cannot apply a type is retried and reports the unapplied count on
  every cycle
- Documents of types it *can* apply, that arrive after the unappliable one, are not
  silently consumed by the same cycle

---

### Requirement: REQ-OS-008

Concurrent requests to start a sync SHALL be coalesced such that at most one sync
cycle is in progress, plus at most one follow-up cycle for requests that arrived while
it was running.

#### Scenario: Burst of ten requests
- Ten callers request a sync while no sync is running
- Exactly one cycle runs, followed by at most one more
- No two cycles push or pull at the same time

---

### Requirement: REQ-OS-009

Download state SHALL be isolated per owner and per profile: changing the active profile
or the signed-in account SHALL NOT reuse another combination's download position.

#### Scenario: Switching profiles
- Profile A syncs to completion, then the user switches to profile B
- Profile B's first sync starts from a position of zero, not from profile A's position

#### Scenario: Second device of the same client
- Device A downloads events up to sequence 120
- Device B, freshly installed and signed into the same account and profile, starts from
  zero and receives the full history rather than skipping it

---

### Requirement: REQ-OS-010

A patch that has failed repeatedly SHALL be retried with an increasing delay and
SHALL be moved to a dead-letter store once it exceeds a maximum attempt count, after
which it is no longer retried automatically.

#### Scenario: Repeated failure
- A patch fails ten times
- The delays between attempts grow exponentially up to a cap
- After the tenth failure the patch is moved to the dead-letter store
- Subsequent sync cycles do not attempt it again
- The dead-lettered patch is still visible to the user, and can be discarded or
  retried explicitly

---

### Requirement: REQ-OS-011

A pulled event that belongs to a different profile than the active one SHALL NOT be
applied to local data.

#### Scenario: Event for an inactive profile
- The device is signed in with two profiles and profile A is active
- The server returns an event belonging to profile B for a task that exists in both
- Profile B's data is updated; profile A's copy of the same task is unchanged

---

### Requirement: REQ-OS-012

One user SHALL NOT observe or modify another user's synchronised documents, by any
supported path.

#### Scenario: Direct read attempt
- User B issues a direct read of user A's documents
- No rows are returned

#### Scenario: Read through the event feed
- User B requests the event feed
- No event belonging to user A is returned

#### Scenario: Write attempt
- User B submits a patch naming a document owned by user A
- The write is rejected and user A's document is unchanged

---

### Requirement: REQ-OS-013

A successful sign-in SHALL upload the data that already exists on the device exactly
once, through the same push path used for any other change.

#### Scenario: First sign-in with existing data
- The device holds tasks, notes, projects, tags, tag groups and time entries created
  while signed out
- After the first successful sign-in those documents are uploaded exactly once
- A second sign-in on the same account uploads nothing additional

#### Scenario: Seeding has no separate failure mode
- The upload is interrupted partway
- The remaining documents are uploaded on the next sync, and no duplicates are created

---

### Requirement: REQ-OS-014

The application SHALL keep all knowledge of the sync backend vendor inside the
transport and authentication implementations. The sync engine, the pull handlers, the
domain repositories and the view models SHALL NOT depend on the vendor SDK.

#### Scenario: Architecture guard
- A static analysis rule asserts that no production file outside the transport and
  authentication seams imports the vendor SDK

---

### Requirement: REQ-OS-026

When the server reports that a change lost a per-field race, the device SHALL adopt the
state the server holds for that row, SHALL NOT record the change as delivered, and SHALL
report the outcome as distinct from a delivered one.

The state the server holds is the device's own record of it — the last state the server
confirmed for the row — and not the state the losing change would have produced. That
distinction is the whole requirement: promoting the losing change's state is what makes
the device believe it holds something the server refused.

#### Scenario: A change that loses the race is not recorded as delivered
- A device sends a change to a field the server already holds at a later clock
- The server reports the change as lost
- The device does not record the change as delivered
- The queued change is not left in place as though it were still owed to the server

#### Scenario: The row returns to what the server holds
- A change to a row lost a per-field race
- The row on the device holds the state the losing change would have produced
- The row ends up holding the last state the server confirmed for it

#### Scenario: The outcome is reported as a loss, not as a delivery
- A change is reported lost
- The summary of that push counts the result as neither delivered nor refused by the
  server
- The count is distinguishable from both, so that neither reading is available by
  accident

#### Scenario: The next change to that row is built on the server's state
- A change lost a race and the row was returned to the server's state
- The device later edits that row again
- The change sent describes the state the server holds, not the state that was lost

#### Scenario: A change that wins is unaffected
- A change to a field the server holds at an earlier clock
- The server accepts it
- The behaviour is exactly as before this requirement: the change is recorded as
  delivered and the row's record of the server's state advances to it

---

### Requirement: REQ-OS-027

A change to the auto-sync settings SHALL take effect without requiring a change of
profile or a restart of the application.

The runner SHALL react to the settings of the active scope, not only to the identity of
that scope. Collecting only the scope is not sufficient and does not look insufficient:
a `StateFlow` does not emit when it is set to the value it already holds, so a settings
change produces no emission at all.

Re-arming the periodic trigger SHALL be caused only by a change to the fields the
schedule depends on. The cursor and the last-successful timestamp change on every cycle,
and treating those as schedule changes would restart the trigger after each sync, so an
interval could pass without one ever completing.

#### Scenario: A changed interval reaches the trigger
- The user changes the sync interval for the active profile
- The scope does not change
- The periodic trigger is restarted at the new interval

#### Scenario: Turning auto-sync off stops the trigger
- The user turns auto-sync off for the active profile
- The scope does not change
- The periodic trigger is stopped

#### Scenario: A completed sync does not re-arm the trigger
- A sync completes and advances the download cursor
- The interval is unchanged
- The periodic trigger is left running as it was

#### Scenario: A profile switch still reads the new profile's settings
- The active scope moves to another profile
- The trigger is started or stopped according to that profile's settings

#### Scenario: Two collectors are never live at once
- The active scope moves while the previous scope's settings are still being observed
- The observation of the previous scope is cancelled rather than left running
