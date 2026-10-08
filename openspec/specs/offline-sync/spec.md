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

### Requirement: REQ-OS-020

A change received from the server SHALL be reported as applied only when it was applied.
An event whose payload is absent, or is not a document, SHALL NOT be reported as
applied, and the download position SHALL move past it so the rest of the account's
changes continue to arrive.

The position moving past it is deliberate. A payload that is absent will be absent again
on the next delivery, so waiting cannot help, and a position that waits would end the
account's sync permanently over one unusable row.

#### Scenario: An event with no payload is not applied
- The server delivers a change that carries no document
- The change is not written to this device
- It is reported as dropped, with the reason, rather than as applied

#### Scenario: The position moves past an unusable change
- An event arrives with no payload, followed by further changes this device can apply
- The further changes are applied
- The device is not left waiting on the unusable one forever

#### Scenario: An unusable change does not fail the whole cycle
- A cycle delivers one unusable change and otherwise completes
- The cycle reports completion
- The unusable change is still counted as dropped

#### Scenario: A payload that is not a document is treated the same
- The server delivers a change whose payload is not an object
- It is reported as dropped and not applied

---

### Requirement: REQ-OS-021

A delete that did not happen SHALL NOT be reported as applied, and the download position
SHALL NOT move past it, because that event is the only one that would ever remove the
row.

#### Scenario: A failed delete leaves the position where it was
- The server delivers a delete and this device fails to carry it out
- The position stays before the delete
- The delete is delivered again on the next cycle

#### Scenario: A failed delete is not counted as applied
- A delete did not happen
- The cycle's counts do not include it as applied

---

### Requirement: REQ-OS-022

A cycle that stopped before finishing SHALL be reported as a failure, and SHALL NOT
report a successful time, so that a device which is stuck is not shown as synchronised.

#### Scenario: A stopped cycle is a failure
- A change this device cannot apply is reached and the cycle stops there
- The cycle reports a failure naming the position it is stuck at
- The engine does not report itself idle

#### Scenario: The position is still the last one that was applied
- A cycle stops early
- The stored position is the last change that was actually applied
- Nothing after the stopping point is consumed

#### Scenario: A cycle that finishes is still a success
- Every change in the page was dealt with
- The cycle reports completion and a successful time

#### Scenario: A failure to apply is not confused with a skipped change
- One change could not be applied but a retry might succeed
- A different change can never be applied and is stepped over
- The two are reported differently, because only the first is worth retrying

---

### Requirement: REQ-OS-023

A document type this device uploads SHALL be one it can also apply from a received
change. A type that is uploaded without a way to apply it SHALL NOT be uploaded, because
an unappliable change stops the receiving account's download position for everything, not
only for that type.

#### Scenario: Every uploaded type has a way to apply it
- The set of types this device can upload
- Each one has a handler that can apply a received change of that type

#### Scenario: A type that cannot be applied is not uploaded
- A type exists that a received change could not be applied as
- This device does not upload it, so no other device receives one

### Requirement: REQ-OS-025

A change sent to the server SHALL state the version of the row it was built against, and
that SHALL be the version the server last reported for the row. A change built against a
version the server has not reported SHALL state that the server has never seen the row.

The second half is why the first matters. A change that claims a version the server has
already moved past is a change the server refuses, and a refused change is one the user
does not get to keep.

#### Scenario: The first change for a row says the server has not seen it
- A row has never been uploaded
- The change sent for it states no version
- The server answers it as the first change for that row

#### Scenario: A later change states the version the server reported
- The server reported a version for a row
- A later change to that row is built on the same version
- The version sent is the one the server reported, not a local copy of it

#### Scenario: A change made after an answer uses that answer
- A change was sent and the server answered with a version
- The user edits the same row again
- The next change is built on the version from the answer

#### Scenario: A response that reports no version does not erase one already known
- The client holds a version for a row
- A later response reports no version
- The stored version is unchanged, and the next change still states it

#### Scenario: A superseded answer does not set the version
- Two changes for the same row are in flight and the newer one supersedes the older
- The older one's answer arrives
- Neither the row's state nor its version is settled from that answer

### Requirement: REQ-OS-024

A rejected change SHALL be retried only when a second identical attempt could receive a
different answer. A refusal that is a property of the request rather than of the moment
SHALL NOT be retried, and the local change SHALL be kept where the user can see it.

#### Scenario: A missing row is not waited for
- The server refuses a change because the row it targets is not there
- The change is not retried
- It is moved where the user can see it, rather than being retried for hours

#### Scenario: A change that is too large is not retried
- The server refuses a change because it exceeds a limit
- The change is not retried
- Its size does not shrink by waiting, so a retry could not succeed

#### Scenario: A state the client no longer expected is not retried
- The server refuses a change because its state moved on
- The change is not retried, and a fresh diff is what would resolve it

#### Scenario: A clock behind the server's is not retried
- The server refuses a change because a newer one arrived first
- The change is not retried

#### Scenario: A refusal the server states deliberately is not retried
- The server refuses a change it will not accept, naming why
- The change is not retried

#### Scenario: A refused change is never silently discarded
- The server refuses a change and it is not retried
- The change is not lost, and the user can find it and act on it

### Requirement: REQ-OS-015

A download cycle SHALL keep reading until the feed reports that it has no more, so that a
backlog larger than one page is applied in one cycle. A cycle SHALL report how many
changes it received in total, across every page it read.

#### Scenario: A backlog larger than one page is applied in one cycle
- More changes are waiting than fit in a single page
- The cycle applies all of them
- The stored position is the end of the last page, not the end of the first

#### Scenario: A backlog that fits one page does not cost an extra request
- The whole backlog fits in a single page
- The cycle asks once
- A short page is taken as the end of the feed

#### Scenario: A page that comes back full is followed by one more request
- A page arrives with exactly as many changes as were asked for
- The cycle asks once more from just after the last change it read
- An empty answer ends the cycle

#### Scenario: The total received counts every page
- The cycle read three pages
- The reported number of changes received is the sum of the three

---

### Requirement: REQ-OS-016

A cycle SHALL stop rather than ask again for a page it has already read. A feed that
answers with nothing past the position asked from SHALL end the cycle, and the cycle
SHALL still report what it read.

The position stored SHALL be the last change that was dealt with, so a cycle that stops
early leaves the rest of the feed for the next one and nothing is lost.

#### Scenario: A feed that will not advance ends the cycle
- The server answers with changes at or before the position asked from
- The cycle stops instead of asking again for the same page
- What it did read is still reported, and the cycle is not reported as a failure

#### Scenario: A cycle that stopped early stores the last change it dealt with
- The cycle stopped part way through what it had read
- The stored position is the last change that was dealt with
- Everything after it is read again next cycle, and nothing is skipped
