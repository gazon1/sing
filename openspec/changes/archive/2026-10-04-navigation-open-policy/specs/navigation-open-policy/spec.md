# Navigation Open Policy — Observable Behavior

**capability:** `navigation-open-policy` | **status:** new

---

## ADDED Requirements

### Requirement: REQ-NAV-001

Every request to open a screen **SHALL** be resolved by a single policy that maps the
current context and the requested target to exactly one action: activate the target's
top-level destination, place the target on the current back stack, or open the target
feature's graph on the current back stack.

**Rationale:** Today the same question ("may this screen open that screen?") is answered by
per-feature allow-list branches duplicated across two platform entry files, so a new
cross-feature action must be edited in two places and the platforms can drift.

**Test coverage:** `NavigationPolicyTest` (action table), desktop `NavigationFlowTest` and
`PlatformParityTest` (end-to-end behaviour).

#### Scenario: Tab activation
- The user is on the Agenda top-level destination
- They activate a different bottom-bar tab
- That tab becomes active with its own back stack restored
- The previous tab's stack is preserved for return

#### Scenario: Cross-feature open keeps the origin underneath
- The user is on the Calendar top-level destination viewing a day
- They open a task shown on that day
- The tasks feature opens showing that task, on top of the calendar's stack
- Back returns to the calendar day view

#### Scenario: Menu destination activation
- The user is on any top-level destination
- They activate a menu destination (for example Search)
- That destination becomes active

---

### Requirement: REQ-NAV-002

A request to open a screen of a different feature than the current context **SHALL** open
that screen. It **SHALL NOT** degrade into back-navigation.

**Rationale:** Several entry points currently fall through an allow-list to "go back", so
tapping a linked note inside a task, or a task inside a project opened from the Plans tab,
closes the current screen instead of opening the requested one.

**Test coverage:** desktop `NavigationFlowTest` (project → task from the Plans tab).

#### Scenario: Project list → task detail
- The user is on the Plans top-level destination with a project open
- They tap a task in that project
- The task detail opens
- Back returns to the project

#### Scenario: Task detail → linked note
- The user has a task detail open
- They tap a note linked to the task
- The note preview opens
- Back returns to the task detail

---

### Requirement: REQ-NAV-003

Addressing a nested feature's start screen directly as an app-level target (bypassing that
feature's graph wrapper) **SHALL** fail with an error naming both the source context and
the requested target, and **SHALL** leave the back stack unchanged.

**Test coverage:** `NavigationPolicyTest` (invalid-target case).

#### Scenario: Bare start screen requested
- The user is on any screen
- A bare start screen of a nested feature is requested as an app-level target
- The open fails with a message naming the source context and the target
- The back stack is unchanged

---

### Requirement: REQ-NAV-004

Every navigation key **SHALL** classify into exactly one feature family, and the
classification **SHALL** be enforced at build time: adding a new navigation key without a
family **SHALL** break compilation.

**Test coverage:** `ScreenFamilyTest` (every declared key classified); compiler exhaustiveness
of the classification itself.

#### Scenario: New route added
- A developer adds a new navigation key
- The code is compiled
- Compilation fails until the new key is assigned a feature family

#### Scenario: Classification drives the open decision
- Two keys of the same feature family are involved in an open request
- The policy resolves to an in-place push (no feature switch)
- Two keys of different feature families are involved in an open request
- The policy resolves to a cross-feature open on the current stack

---

### Requirement: REQ-NAV-005

Routes that address a stored entity **SHALL** accept only that entity's identifier type;
supplying an identifier of a different entity **SHALL** fail at build time. Identifiers
**SHALL** survive process-death back-stack restore unchanged.

**Rationale:** Raw string identifiers let a note id be passed where a task id is expected;
the mistake surfaces only at runtime, deep inside the opened screen.

**Test coverage:** `NavSavedStateConfigTest` + `NavKeyRegistrationTest` (round-trip
inventory); Android `assembleDebug` + Maestro smoke (restore).

#### Scenario: Wrong identifier type rejected
- A route addresses a task
- A note identifier is supplied for it
- The build fails

#### Scenario: Restore after process death
- Android has restored the back stack after process death
- A restored route carries an entity identifier
- The restored identifier equals the identifier before process death

---

### Requirement: REQ-NAV-006

Back **SHALL** pop the current screen when the current stack has more than one entry; at a
top-level destination's root Back **SHALL** activate the previously active top-level
destination; reselecting the already-active tab **SHALL NOT** alter any stack.

**Test coverage:** `Nav3StateReselectTest` (reselect + tab return), desktop
`NavigationFlowTest` (back inside features).

#### Scenario: Back inside a feature
- A feature stack holds two entries
- The user presses Back
- The top entry is removed and the one below it is shown

#### Scenario: Back at a tab root
- The active top-level destination is at its stack root and a different destination was
  active before it
- The user presses Back
- The previously active destination is restored
- The default destination is NOT shown

#### Scenario: Tab reselect
- The active tab is displayed
- The user taps it again
- No stack change occurs

---

### Requirement: REQ-NAV-007

The same (current context, target) pair **SHALL** resolve to the same action on Android
and on Desktop.

**Test coverage:** desktop `PlatformParityTest`.

#### Scenario: Identical pair, identical outcome
- The same current context and target are used
- The open is requested on Android and on Desktop
- Both resolve to the same action
