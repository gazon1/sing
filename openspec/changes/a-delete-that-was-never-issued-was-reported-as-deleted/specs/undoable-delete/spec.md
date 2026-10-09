# A delete that was never issued was reported as deleted — Observable Behavior

**capability:** `undoable-delete` | **status:** proposed

---

## ADDED Requirements

### Requirement: REQ-UD-001

A delete the user confirms **SHALL** result in the item being deleted before any
affordance of recovery is presented.

**Rationale:** the offer asserts the outcome. An affordance offering recovery from
an action that did not happen is worse than none — the user believes their data
is in a state it is not in.

#### Scenario: Delete reports only a delete that happened
- The user confirms deletion of an item
- The item is deleted at the repository
- A recovery affordance is presented

#### Scenario: A failed delete presents no offer
- The repository rejects the delete
- No recovery affordance is presented
- The failure is reported to the user
- The item remains intact

---

### Requirement: REQ-UD-002

An item deleted with an offered undo **SHALL** be restorable, and **MUST NOT** be
restored by an operation that has nothing to restore.

**Rationale:** undo is offered on the promise of reversal. Issuing a restore
against a live row is a write with no meaning, which can fail in a way the user
cannot interpret.

#### Scenario: Undo reverses a real delete
- An item is deleted and undo is offered
- The user activates undo within the window
- The item is restored

#### Scenario: Undo issues no write against a live item
- The undo window has already closed
- A late undo action calls no restore

---

### Requirement: REQ-UD-003

A reversal that fails **SHALL** leave the recovery affordance available, and
**MUST NOT** be presented as having succeeded.

**Rationale:** clearing the offer before knowing the reversal worked removes the
only recovery path at the exact moment the user needs it, and dismisses the
affordance with it, so the failure becomes invisible.

#### Scenario: A failed reversal keeps the offer
- A reversal fails
- The failure is reported
- The affordance remains available for another attempt

#### Scenario: A failed reversal issues no success indication
- A reversal fails
- No indication that the item was restored is shown

---

### Requirement: REQ-UD-004

A repeated undo **SHALL** be safe. It is **NOT** required to be prevented.

**Rationale:** reversal is idempotent — it re-uses the original identifier — so a
repeated attempt cannot corrupt state. Preventing repetition would add machinery
to solve a problem that does not exist, at the cost of a race between two guards.

#### Scenario: A second undo attempt is harmless
- The user activates undo twice within the window
- The item is restored and stays restored
- No duplicate is created and no error is raised

#### Scenario: A repeated undo after expiry does nothing
- The undo window has closed
- The affordance is no longer presented, so no further attempt reaches it

---

### Requirement: REQ-UD-005

The repository **SHALL** commit a delete at the moment of confirmation, and the
item **SHALL** stop being displayed once the corresponding state update is
received. The two **MUST NOT** be conflated into a single frame-level promise.

**Rationale:** persistence and rendering are different claims. A deferred write
makes an item's visibility disagree with its persistence; a frame-level promise
makes an unmeasurable claim the rendering layer cannot keep.

#### Scenario: The write is committed immediately
- The user confirms a delete
- The repository records the delete without waiting for a timeout

#### Scenario: The interface follows the repository
- The repository commits a delete
- The item is no longer displayed once that change is reflected in state

---

### Requirement: REQ-UD-006

Delete **SHALL** be initiated at the moment of confirmation in every feature
offering undoable delete. The *semantics* of delete, undo and window timeout
**SHALL** be identical across features. The mechanism presenting the affordance —
for example whether it is driven by UI state or by a one-shot event — is NOT
fixed by this requirement and **MAY** differ between features.

**Rationale:** a deferred write was a per-feature invention contradicting an
existing decision record. Presentation mechanism is an implementation detail;
requiring it to match would demand a refactor beyond this change without
improving user-visible behaviour.

#### Scenario: Every feature behaves the same
- Deleting with undo from any screen commits the delete immediately
- The undo window has the same length and the same expiry behaviour everywhere

---

### Requirement: REQ-UD-007

A reminder **MUST NOT** outlive the item it belongs to. Where reminder
cancellation fails, the delete **SHALL** not proceed.

**Rationale:** deleting the item while leaving its reminder scheduled produces a
notification for something the user no longer has, which is the failure this work
exists to remove.

#### Scenario: Cancellation precedes the delete
- A user deletes an item with a scheduled reminder
- The reminder is cancelled before the item is deleted

#### Scenario: A failed cancellation aborts the delete
- Reminder cancellation fails
- The item is not deleted
- The failure is reported

---

### Requirement: REQ-UD-008

Where reminder cancellation succeeds and the subsequent delete fails, the item
**SHALL** remain intact and the failure **SHALL** be reported. The cancelled
reminder **MAY** remain cancelled; restoring it is explicitly out of scope,
because the cancellation API does not return enough information to reschedule it.

**Rationale:** the invariant is deliberately one-directional. A lost reminder on a
failed delete is recoverable — the user sets a new one. A zombie reminder for a
deleted task is not. Requiring restoration would force a repository API change
this work does not justify.

#### Scenario: Cancellation succeeds, deletion fails
- The reminder is cancelled
- The repository rejects the delete
- The item remains, without its reminder
- The failure is reported

---

### Requirement: REQ-UD-009

At most one pending delete **SHALL** be held at a time. A successful delete
**SHALL** supersede any open undo window, and the superseded window's expiry
**MUST NOT** clear the newer pending delete.

**Rationale:** two open windows cannot both be honoured, so the older one silently
loses. Expiry of a superseded operation clearing a newer one is a lost undo the
user never chose to forfeit.

#### Scenario: A second delete supersedes the first
- A delete is pending undo
- The user deletes a different item
- Undo applies to the newer delete only

#### Scenario: A superseded timer is inert
- The first delete's window expires after a second delete has claimed the slot
- The second delete's undo context remains intact

---

### Requirement: REQ-UD-010

The undo affordance **SHALL** remain actionable for exactly as long as the model
retains the deleted item, and **MUST NOT** remain actionable after it.

**Rationale:** an affordance outliving its window is a promise the interface
cannot keep — the user taps undo and nothing happens. An affordance dying before
the window closes denies an undo the user was still entitled to.

#### Scenario: The affordance closes with the window
- The undo window expires
- The affordance is no longer presented
- A late action performs no restore

---

### Requirement: REQ-UD-011

Concurrent delete, undo and window-expiry operations on one ViewModel **SHALL**
resolve deterministically: a delete that completes after a newer delete was
requested **MUST NOT** claim the single affordance, and a superseded window
**MUST NOT** clear it.

**Rationale:** the ViewModel scope runs on a multi-threaded dispatcher, so two
deletes dispatched in quick succession genuinely interleave. Completion order is
not request order, so the older operation would otherwise win the affordance the
user is looking at.

#### Scenario: A late-completing delete does not steal the offer
- Two deletes are dispatched in quick succession
- The first reaches the repository last
- The offer belongs to the delete the user requested last