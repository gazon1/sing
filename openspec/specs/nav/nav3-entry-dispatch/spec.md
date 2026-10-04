# nav/nav3-entry-dispatch

## Purpose

The app uses Navigation 3 with a multi-back-stack pattern: a top-level destination holds a
map of top-level routes (tabs and menu entries) to nested back-stacks, and each nested
back-stack belongs to exactly one feature.

This spec defines the contract for **entry dispatch** — how a top-level route decides which
screen a nested back-stack opens on, how a nested graph hands navigation to another feature,
and how back navigation behaves at the root of a nested stack. It covers both the Android
and JVM Desktop shells, which share this logic.

It does not define the internals of any individual feature graph, nor the visual design of
any screen.

## Requirements

### Requirement: The active screen is the top of the back-stack

The system SHALL render the screen that corresponds to the current top entry of a nested
back-stack, regardless of any start value supplied when the graph was created.

Rationale: the start value only seeds the stack. The top of the stack is the single source of
truth for which entry is active, on both platforms.

#### Scenario: Seed and top agree

- **WHEN** a feature graph is created with a start value
- **THEN** the screen for that start value is displayed

#### Scenario: A later entry is pushed

- **WHEN** a start value was supplied at creation and a different entry is pushed afterwards
- **THEN** the screen for the pushed entry is displayed, not the start value

#### Scenario: An explicit add overrides the start value

- **WHEN** a start value was supplied at creation and a different entry is explicitly added
  before first render
- **THEN** the screen for the explicitly added entry is displayed

### Requirement: Entry block obligation when start differs from seed

WHEN a graph entry creates a back-stack with a seed of one route type and the incoming route
specifies a start value of a different route type, the entry SHALL add the start value to the
back-stack before the graph is rendered.

Rationale: without this, a cross-feature navigation that asks to open a specific existing item
would land on the feature's default screen instead.

#### Scenario: Cross-feature navigation to an existing item

- **GIVEN** the user is on one tab viewing its list
- **WHEN** they follow a link to an item that belongs to another feature
- **THEN** that feature's graph is entered with a start value naming the item
- **AND** the item's screen is displayed rather than that feature's default screen

#### Scenario: Cross-feature navigation to a new item

- **GIVEN** the user is on one tab viewing its list
- **WHEN** they follow a link to create a new item in another feature
- **THEN** that feature's graph is entered with a creation start value
- **AND** the creation screen is displayed

### Requirement: Graph seed always matches the incoming start value

A graph entry SHALL use the incoming route's start value when creating the back-stack, so the
seed and the requested start value cannot diverge.

#### Scenario: Entering a tab from the tab bar

- **WHEN** the user selects a top-level tab
- **THEN** that tab's back-stack is seeded with the start value from its route

### Requirement: Cross-graph navigation uses the exit callback

WHEN a screen inside a nested graph needs to navigate to a screen belonging to a different
feature, it SHALL request that navigation through the exit callback, and the outer graph SHALL
handle the request by entering the target graph with the requested start value.

Rationale: a nested graph holds no reference to the outer navigator, so the callback is the
only channel between them.

#### Scenario: Following a link to another feature's item

- **GIVEN** the user is viewing a detail screen
- **WHEN** they follow a link to an item owned by a different feature
- **THEN** the exit callback is invoked with the target item
- **AND** the owning feature's graph is entered on that item

#### Scenario: Following a link to another feature's preview

- **GIVEN** the user is viewing a detail screen
- **WHEN** they follow a link to a linked item shown in preview form
- **THEN** the exit callback is invoked with a preview start value
- **AND** the owning feature's graph is entered in preview mode

### Requirement: Same-graph navigation pushes onto the shared stack

WHEN a screen navigates to another screen within the same feature graph, the navigation SHALL
push the new entry onto that graph's existing back-stack rather than exiting the graph.

Rationale: the exit callback is for crossing graph boundaries only.

#### Scenario: Opening a detail screen from a list

- **GIVEN** the user is on a feature's list screen
- **WHEN** they open an item
- **THEN** the item's entry is pushed onto the current back-stack
- **AND** the item's screen is displayed

#### Scenario: Returning to the list

- **GIVEN** the user is on a detail screen with more than one entry on the stack
- **WHEN** they navigate back
- **THEN** the top entry is removed
- **AND** the previously displayed screen is displayed again

### Requirement: Back navigation at the root of a graph exits it

WHEN back navigation is requested and the back-stack holds at most one entry, the graph SHALL
request exit rather than removing the remaining entry.

Rationale: an empty back-stack cannot be rendered, so the seed entry must not be popped.

#### Scenario: Back from the root entry

- **GIVEN** the user is on a feature's root screen with a single entry on the stack
- **WHEN** they navigate back
- **THEN** the graph requests exit
- **AND** the previous top-level destination is shown

### Requirement: Reselecting the active tab emits a reselect event

Tapping the currently active top-level tab SHALL emit a reselect event for that tab instead of
re-navigating to it.

Rationale: reselect lets the active screen reset its scroll position or refresh, matching
platform expectations for tab bars.

#### Scenario: Reselecting the active tab

- **GIVEN** the user is viewing one tab
- **WHEN** they tap that same tab again
- **THEN** a reselect event is emitted for that tab
- **AND** the top-level destination does not change

#### Scenario: Selecting a different tab

- **GIVEN** the user is viewing one tab
- **WHEN** they tap a different tab
- **THEN** the active top-level destination changes to the tab they tapped
- **AND** the previous tab's stack is retained rather than discarded

### Requirement: Desktop keeps stacks in memory, Android persists them

On the JVM Desktop shell, nested graphs SHALL use an in-memory back-stack. On the Android
shell, nested graphs SHALL use a back-stack that is persisted and restored across process
death.

Rationale: the desktop shell has no process death, so persistence is unnecessary there;
Android can kill and restore the process, so the stack must survive it.

#### Scenario: Desktop session

- **WHEN** a nested graph is entered on the desktop shell
- **THEN** its back-stack is held in memory for the lifetime of the session

#### Scenario: Android process death and restoration

- **GIVEN** the user was on a detail screen when the process was killed
- **WHEN** the application is relaunched
- **THEN** the back-stack is restored to the entry the user was on
- **AND** the same screen is displayed
