# A patch states the version it was built on

**capability:** `offline-sync` | **status:** proposed

**Issue:** #178 (the version half; the delete half is not covered here)

---

## ADDED Requirements

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
