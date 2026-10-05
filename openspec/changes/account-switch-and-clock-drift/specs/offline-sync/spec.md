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
