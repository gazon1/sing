# desktop-navigation

**capability:** `desktop-navigation` | **status:** proposed

---

## ADDED Requirements

### Requirement: REQ-DN-005

A screen's state that is derived from an asynchronous source **SHALL** have a
defined value before the first read, and the screen **SHALL** not render a
half-initialised state.

**Rationale:** a property seeded by a collector is `null` until that collector
emits. Reading it earlier — from an initialiser, or from a state-building
expression that runs before the collector's first emission — yields a null that
later surfaces as a crash far from its cause. One observed occurrence of this in
five runs is enough to justify the invariant.

**Test coverage:** the companion graph test for the projects screen is the
regression net; the existing task-detail graph test is the pattern to mirror.

#### Scenario: Derived state is read before it is seeded
- A screen declares state derived from a repository or auth source
- That state is read before the source has emitted
- The screen presents a defined loading state instead of dereferencing the
  unseeded value
- No null is observed

---

### Requirement: REQ-DN-006

A screen **SHALL** be reachable in its loaded state within the navigation timeout
regardless of whether a previous screen in the session already loaded it.

**Rationale:** the observed failure did not reproduce across repeated runs, which
is consistent with a warm-versus-cold state difference. Whichever cause turns out
to be right, the user-visible requirement is the same: a screen that has loaded
once in a session does not become slower or less reliable on a second visit.

**Test coverage:** to be added — the flow run twice within one session.

#### Scenario: Second visit is as reliable as the first
- A screen is opened, loaded, and navigated away from
- The same screen is opened again in the same session
- It reaches its loaded state within the timeout
- The second visit is no less reliable than the first
