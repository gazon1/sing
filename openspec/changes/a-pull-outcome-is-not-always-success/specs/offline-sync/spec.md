# An event that was not applied is not reported as applied

**capability:** `offline-sync` | **status:** proposed

> Split out of `account-switch-and-clock-drift`, which carried the account-switch and
> clock-drift decisions. These three are separate: they are the two data-loss paths the
> audit of the auth/sync test plan found, and neither depends on a product decision.

**Issues:** #175, #177

---

## ADDED Requirements

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
