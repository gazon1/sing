# A refusal the server means cannot be retried

**capability:** `offline-sync` | **status:** proposed

> Split out of `a-pull-outcome-is-not-always-success`, which covered the pull path.
> This is the push path: what the client does with the answer it gets back.

**Issue:** #180

---

## ADDED Requirements

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
