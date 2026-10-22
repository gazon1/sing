# One nav graph per feature — Observable Behavior

**capability:** `navigation-open-policy` | **status:** proposed

---

## ADDED Requirements

### Requirement: REQ-NAV-011

A nested navigation graph **SHALL** have exactly one implementation, in common code.
Every difference between the platforms **SHALL** be expressed as a seam — a function
or a parameter whose two sides differ — and **SHALL NOT** be a second copy of the
graph.

**Rationale:** two near-identical files look intentional. A difference between them
reads as a decision, so an accidental difference survives review, survives the next
change, and is never noticed — the two copies are close enough that comparing them
was nobody's job.

**Test coverage:** `AppEntryParityTest` (the same destinations on both platforms),
`NestedEntryViewModelScopeTest` (the decorator is on both platforms).

#### Scenario: A new screen inside a nested graph is registered once
- A screen is added to a feature's nested graph
- It is added to that graph's entry provider — one file
- It opens on both platforms without a second edit

#### Scenario: The platforms cannot drift apart
- A platform difference is needed
- It is expressed as a seam
- The graph itself stays one implementation

---

### Requirement: REQ-NAV-012

Every entry of a nested navigation graph **SHALL** resolve its own ViewModel store.
Two entries of the same graph **SHALL NOT** share one store.

**Rationale:** a ViewModel resolved through a shared store is keyed by its class
alone — not by its parameters. Two entries that share a store and ask for the same
ViewModel class therefore get the *same* instance: the second screen silently
reuses the first one's state, with the first one's parameters.

**Test coverage:** `NestedEntryViewModelScopeTest`.

#### Scenario: Two screens of one graph keep their own state
- A graph shows two screens, each with a ViewModel of the same class
- The second screen resolves its own ViewModel
- It does not reuse the first one's state or its parameters

#### Scenario: The scope is the same on every platform
- The graph runs on Android and on Desktop
- Both scope the ViewModel store per entry
- Neither platform resolves a ViewModel from the store of whatever is above it

---

### Requirement: REQ-NAV-013

A gesture that opens a surface **SHALL** be a platform's business, expressed as a
parameter. The set of places where the surface is offered **SHALL** be one decision,
made in common code, and **SHALL** be the same on every platform.

**Rationale:** a gesture and the policy behind it have different owners. A long
press and a right click are the platform's; "a blocked task can be pinned and
completed" is the app's. When the list of places lives in two files side by side, a
gap in one of them looks deliberate.

**Test coverage:** `AgendaContextMenuParityTest`.

#### Scenario: The same surface is offered everywhere it applies
- A task can be acted on from any task list
- Every task list offers the same actions
- The gesture that opens them differs per platform, and only the gesture does

#### Scenario: A surface that does not apply is not offered
- A screen lists saved views rather than tasks
- It offers no task actions
