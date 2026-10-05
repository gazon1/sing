# A lost race resolves to the server's state

**capability:** `offline-sync` | **status:** proposed

**Issue:** #203

---

## ADDED Requirements

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

## Notes on scope

The requirement names the device's own record of the server's state rather than a
document sent with the response. `PatchResult` carries a `serverState` field and the
server never populates it: `sync_batch_apply` returns `patchId`, `ok`, `cached`, `lost`,
`legacy` and `newVersion`, and nothing else. Requiring adoption of a server-sent
document would mean a wire-format change for a value the device already holds — see
#207 for the separate question of `newVersion`.

Whether the user is told is not specified here. The sync layer's obligation is to report
the outcome; the surface that shows it is a separate decision, and freezing one here
would record a choice that has not been made.