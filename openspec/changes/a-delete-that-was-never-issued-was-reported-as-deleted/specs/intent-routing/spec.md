# Intent routing — Observable Behavior

**capability:** `intent-routing` | **status:** proposed

---

## ADDED Requirements

### Requirement: REQ-IR-001

A screen-triggered state change **SHALL** be expressed as an intent and delivered
through the ViewModel's single intent dispatcher. A screen **MUST NOT** reach a
state change by any other route.

**Rationale:** a second route to the same change has no single place to record
what happened, so the two routes drift — one gains validation, ordering or error
handling, and the other does not. The behaviour that diverged in this change was
invisible at the call site: both routes were present, both looked correct, and
only one of them was exercised.

#### Scenario: A screen action travels the dispatched route
- The user activates a control on a screen
- The action is expressed as an intent
- The dispatcher is the only route by which the ViewModel receives it

#### Scenario: The two routes cannot diverge
- Two paths would otherwise reach the same state change
- Only one path exists
- A change to the behaviour is made once

#### Scenario: A change of origin is attributed to the dispatcher
- The state change arrives through the dispatcher
- It is handled identically regardless of which screen sent it

---

### Requirement: REQ-IR-002

A mutation handler **MUST NOT** be reachable by any caller other than the intent
dispatcher, and an intent **MUST NOT** be declared that no screen dispatches.

**Rationale:** these two shapes are the same defect seen from opposite ends — a
handler with a public entry point can be bypassed, and an intent nobody sends is
a promise the interface never keeps. Both read as correct code and both are how
this change came to contain a mutation that could never happen.

#### Scenario: A mutation handler has one entry point
- A user action mutates state
- The only route to the mutation is an intent
- No public method offers a second route

#### Scenario: A declared intent is dispatched
- An intent is declared
- A screen dispatches it
- It is not a declaration with no route

#### Scenario: An unreachable declaration is caught
- An intent has no dispatching screen
- The omission is detectable without reading every call site

---

## Notes

The mechanism each screen uses to *express* an intent is not fixed by these
requirements and may differ between features — a sealed intent type per ViewModel,
a shared action interface, or another arrangement, provided the dispatcher stays
the only route to a state change.