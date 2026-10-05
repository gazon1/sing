# offline-sync — Observable Behavior

**capability:** `offline-sync` | **status:** proposed

> Split from the `user-authentication` delta in this change: REQ-OS-019 is about
> ordering and about this device's clock, which is the sync capability's business, and
> carrying it in the authentication spec would have put a sync requirement in a file
> whose other requirements are all about the sign-in screen.

---

## ADDED Requirements

### Requirement: REQ-OS-019

Every received change from the server SHALL advance this device's logical clock, so that
a change received later is never ordered before the one that caused it. A server clock
reading that disagrees with this device's by more than five minutes SHALL stop the write
and be reported as a distinct condition, and SHALL NOT be adopted.

The second half is what keeps the first honest: without it, one wrong reading moves this
device's clock permanently and the damage is not attributable to a single event.

#### Scenario: A received change advances the local clock
- A change is received from the server
- This device's clock is at or past the received one
- The next change this device writes is ordered after the one it received

#### Scenario: A clock that disagrees a little is absorbed
- A server reading is within five minutes of this device's
- The clock moves to cover it and the write proceeds

#### Scenario: A clock that disagrees too much stops the write
- A server reading is more than five minutes from this device's
- Nothing is written and the local clock is not moved
- The user is told this device's clock looks wrong

#### Scenario: Refusing for a clock does not look like a server failure
- A cycle is refused because of the drift
- The reason is the device's clock and not the server refusing the request
- The queued work is kept, and local editing continues

#### Scenario: One bad reading does not rewrite the clock
- A single received reading is far in the future
- The clock does not move to it, and the next device after it is unaffected

---

## ADDED Requirements

### Requirement: REQ-UA-019

A change queued by one account SHALL be sent only while that account is the one signed
in, and SHALL be attributable to that account.

This is the sending half of ownership. REQ-UA-018 covers what happens to the *answer* —
a response is applied only by the account that asked for it. It says nothing about the
request, and the request is what leaves the device: a patch queued by one account and
sent inside another account's authenticated request has already gone by the time the
answer comes back, and discarding the answer does not call it back.

The second sentence is what makes the first enforceable. Without a recorded owner, the
rule cannot be checked against anything — which is the state the outbox was in, and why
a switch could not deliver one account's work without also sending another's.

#### Scenario: A queued change is sent only by its own account
- Work is queued while account A is signed in
- The device signs out, and account B signs in
- The push under B's session does not contain A's queued change

#### Scenario: A queued change goes out again when its own account returns
- Work is queued while account A is signed in and the device signs out before it is sent
- Account A signs in again
- The push under A's session contains that work, and it is sent

#### Scenario: One account's work is never erased with another's
- Account A has queued work that has not been delivered
- The device switches to account B and the switch erases A's local data
- B's queued work is still there afterwards

#### Scenario: Work queued before this device could attribute it is not sent as anybody's
- The upgrade to owner-scoped queuing is applied while work is queued
- Those rows are cleared rather than attributed to a guess
- The loss is the cost of not guessing, and not a silent one

#### Scenario: A sign-out keeps the queue
- Work is queued and the user signs out
- Nothing is erased and nothing is attributed to the next account
- The queue is still this device's to send
