# navigation-open-policy Specification

## Purpose

The app uses Navigation 3 with one nested back stack per feature and a top-level
destination per tab. The question every screen implicitly asks — "may I open that
screen from here, and what should happen when I do?" — used to be answered by
per-feature allow-list branches duplicated across the two platform entry providers,
so a new cross-feature action had to be edited in two places and the platforms could
drift apart silently.

These requirements pin the contract that replaced them: one policy that resolves
every open into exactly one of three actions, a facade the screens call instead of
navigating directly, and a rule for which shell owns a back stack.

## Requirements

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

### Requirement: REQ-NAV-008

A deep link **SHALL** resolve to exactly one target — a top-level tab and a screen —
and opening it **SHALL** make that tab active with a back stack of exactly
`[tab, screen]`.

**Rationale:** a link that opens a screen without naming a tab opens it on
whatever the app happened to be showing, so "back" returns to an unrelated
screen. The link is the app's only public surface; it has to say where it goes.

The link is resolved by a registry that answers from an explicit, ordered list of
the app's links. The order is part of the contract: the first matching link
wins, because "the best match" is not defined when two templates accept the same
URI.

**Test coverage:** `DeepLinkRegistryTest` (the link table, the order of the
checks), `Nav3StateDeepLinkTest` (the stack and the tab), `DeepLinkGraphTest`
(the registry resolves from the real DI graph).

#### Scenario: A task link opens the task on its tab
- A link `singularity://task/{taskId}` arrives
- The task detail is opened on the Agenda tab
- The Agenda tab's stack holds the tab and the task screen, in that order

#### Scenario: Back from a linked screen returns to the tab's own screen
- A link opened a task detail on the Agenda tab
- The user presses Back
- The Agenda tab's own screen is shown

#### Scenario: A link on another tab switches to it
- A link names a screen on a tab that is not the active one
- The named tab becomes active
- That tab's stack holds the tab and the screen, in that order

#### Scenario: An unknown link changes nothing
- A link arrives that matches no registered link
- The app stays where it is
- No stack is rebuilt

#### Scenario: The first matching link wins
- Two registered links would accept the same URI
- The one that comes first in the list is the one that opens

---

### Requirement: REQ-NAV-009

The grammar of a link — its URI template and the reverse parsing of it —
**SHALL** be declared once, by the feature that owns the link, and **SHALL** be
read from there by both the producer of the link and the matcher that consumes
it.

**Rationale:** the task link's format lived in three places at once — the
producer, a constant nobody read, and a second parser that read the link out of
free text by hand. A change to one of them left the other two in the old
format, and the link stopped opening with no error anywhere.

**Test coverage:** `TaskLinkSpecTest` (the round trip, in a description and on
its own).

#### Scenario: The producer and the matcher agree
- The calendar sync writes a link from the spec
- The matcher parses a link with the same spec
- The identifier that goes in is the identifier that comes out

#### Scenario: There is one parser
- A link is found inside a free-text description
- The identifier is read by the spec, not by a second hand-written parser

---

### Requirement: REQ-NAV-010

An entry point that receives a link **SHALL NOT** parse it. It **SHALL** build a
request from the incoming URI and ask the registry what it opens, and a URI that
matches nothing **SHALL** leave the app on its current screen without an error.

**Rationale:** the magic-link reader pulled two query parameters out of any
intent data, without checking the scheme or the host — so a link with the right
parameters but the wrong authority was a magic link. The grammar belongs to the
feature; an entry point that parses it is a second grammar.

**Test coverage:** `DeepLinkRegistryTest` (unknown host, other scheme, missing
argument, extra path segment — no match).

#### Scenario: A link with the wrong authority is not a link
- An intent carries `?email=` and `?token=` but its authority is not the one the
  magic link declares
- Nothing is opened

#### Scenario: A malformed link is not a crash
- A link arrives that cannot be read as a URI
- The app opens on its start tab
- No exception reaches the user

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
