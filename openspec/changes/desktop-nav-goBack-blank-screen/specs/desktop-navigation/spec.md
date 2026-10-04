# desktop-navigation

**capability:** `desktop-navigation` | **status:** proposed

---

## ADDED Requirements

### Requirement: REQ-DN-001

After the user navigates back from any pushed screen, the shell **SHALL** present a
renderable screen within the navigation timeout.

**Rationale:** a completed back navigation that leaves an empty tree is worse than a
failed one, because every subsequent test assertion fails with "could not find any
node" — the failure points at the test rather than at the navigation.

**Test coverage:** `SavedAgendaCreateFlowTest`, `SavedAgendaEditFlowTest`,
`CreateTaskFlowTest` (all three currently blocked by this defect).

#### Scenario: Back from a pushed screen
- The user pushes a screen from a list
- The user activates the back control
- The list is presented again
- The presented tree contains at least one node
- Every tag the list applies is present

---

### Requirement: REQ-DN-002

The shell **SHALL** present a renderable screen after a back navigation that
follows a write, on both a fresh session and a session whose navigation state was
restored.

**Rationale:** the observed failure follows a save, not navigation alone. A write
changes what the destination would resolve to, which makes a restored or cached
entry a plausible contributor. The two cases must be distinguishable in testing,
or a fix verified in one will be assumed to cover the other.

**Test coverage:** to be added — the same flow run twice in one process, once
against a fresh navigation state and once against a restored one.

#### Scenario: Back after a write, restored state
- A task is created and saved
- The user navigates back
- The destination is presented with the new task visible
- No screen is blank

---

### Requirement: REQ-DN-003

A back-stack entry that the shell cannot resolve to a renderable destination
**SHALL** fall back to a known-good screen rather than leaving the shell empty.

**Rationale:** whatever the underlying cause turns out to be, the user-visible
invariant is that the app always shows something. A fallback makes the shell robust
to the whole class of unresolvable-entry causes, not just one instance.

**Test coverage:** to be added — construct an unresolvable entry and assert a
fallback is presented.

#### Scenario: Unresolvable entry falls back
- The navigation state contains an entry the shell cannot resolve
- The shell presents the default destination
- The presented tree contains at least one node

---

### Requirement: REQ-DN-004

The shell **SHALL** remain diagnosable while its tree is empty.

**Rationale:** the diagnostic approach rejected in #45 — a debug modifier on the
navigation display — cannot render precisely when it is needed, because the tree it
would draw into is empty. Diagnostics for this class of failure have to observe
state from outside the tree.

**Test coverage:** not a behavioural requirement; see the shell-layer diagnostic
in the proposal.

#### Scenario: Diagnostic fires when the tree empties
- A back navigation leaves the tree empty
- The diagnostic records the current route and back-stack contents
- The diagnostic runs despite the tree being empty
