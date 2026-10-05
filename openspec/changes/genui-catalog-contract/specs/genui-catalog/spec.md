# genui-catalog — Observable Behavior

**capability:** `genui-catalog` | **status:** proposed

> New capability. `genui-catalog` does not exist in `openspec/specs/` yet, and `feature/genui` is
> absent from the module index, so this is a delta introducing the capability rather than a
> modification of one. Requirements describe observable behavior; the declarations and packages
> that implement them are named in the ADR, not here.

---

## ADDED Requirements

### Requirement: REQ-GC-001

The set of components a generated surface may use, and the properties each component accepts, SHALL
be declared once. The instructions given to the model, the validation applied to the model's output,
and a machine-readable schema of the component set SHALL each be derived from that single
declaration, and SHALL NOT be maintained independently of one another.

The declaration SHALL state, for each component: its name, the properties it accepts with their
types, which properties are required, the legal values of any enumerated property, and — for
components that contain other components — which components may be placed inside it.

#### Scenario: A component is added to the declaration and appears everywhere
- A component is added to the declaration
- The model is instructed about it, its output is validated against it, and it is present in the
  exported schema, with no other edit required

#### Scenario: A component in the declaration has no renderer
- A component is named in the declaration but nothing can render it
- The discrepancy is detected and reported by an automated check, rather than appearing later as a
  surface that silently omits part of itself

#### Scenario: The exported schema is regenerated
- The machine-readable schema is regenerated from the declaration
- Two regenerations of an unchanged declaration produce identical output

---

### Requirement: REQ-GC-002

Input that does not satisfy the declaration SHALL be rejected with a machine-readable reason, a
location identifying the offending part of the message, and a severity. Rejection SHALL NOT be
silent, and SHALL NOT be reported by discarding the input without a recorded reason.

#### Scenario: The model names a component that does not exist
- A message contains a component name that is not in the declaration
- The component is rejected, and the reason names the component and lists the components that do
  exist

#### Scenario: A required property is absent
- A component omits a property the declaration marks as required
- The component is rejected, and the reason names the missing property

#### Scenario: A property has the wrong type or an illegal enumerated value
- A component carries a value of the wrong type, or a value outside the declared set
- The component is rejected, and the reason states which property was wrong and what was expected

#### Scenario: A component is placed inside one that may not contain it
- A component appears as a child of a component whose child rule excludes it
- The rejection reason identifies both the containing component and the child that was refused

#### Scenario: A component references another that has not arrived yet
- A message arrives whose component references a sibling that a later message will define
- The surface renders what it has, a placeholder is shown in place of the missing part, and the
  condition is recorded as a reason rather than passed over

#### Scenario: The components form a cycle
- Two or more components reference each other so that rendering them would not terminate
- The cycle is detected, the components that close it are rejected, and the rest of the surface
  still renders

#### Scenario: The same message declares one component id twice
- A single message defines the same component identifier more than once
- The duplication is reported and the last definition is the one that applies

#### Scenario: A message re-creates a surface that already exists
- A creation message names a surface identifier that is already open
- The condition is reported, and the surface is not left half-replaced

#### Scenario: A message is malformed or is not a message at all
- A line of the model's output is not a well-formed message
- The line is skipped, the reason is recorded, and the remaining lines are still processed

#### Scenario: A line uses a newer version of the message schema than the client understands
- A message declares a schema version the client does not support
- The message is skipped and the version mismatch is recorded

#### Scenario: The model emits text that is not a message
- The output contains prose around or instead of the structured messages
- The prose is kept as the assistant's text and the messages around it are still processed

---

### Requirement: REQ-GC-003

A rejection confined to a single component SHALL NOT prevent the remaining components of the same
surface from rendering. A rejection that invalidates the message as a whole MAY discard the whole
message, and the severity of each rejection SHALL determine which of the two happens.

#### Scenario: One component in a list of ten is invalid
- A message defines ten components, one of which violates the declaration
- The other nine render, and the surface is usable

#### Scenario: A message whose surface identifier is unusable
- A message cannot be attributed to a usable surface
- The whole message is discarded and the reason is recorded, and previously rendered surfaces are
  left intact

---

### Requirement: REQ-GC-004

Rejection reasons SHALL be reported back to the model, and the model SHALL be given a bounded number
of further attempts to produce a valid surface. A model that does not produce a valid surface
within that bound SHALL end at a reported failure, with any partially rendered surface left
visible.

#### Scenario: The first attempt contains an invalid component
- The model's first response is rejected
- The model is asked again with the reasons from the first response, and the surface it produces
  instead is rendered

#### Scenario: The model never produces a valid surface
- Every allowed attempt is rejected
- The attempt stops, the user is told the surface could not be generated, and whatever rendered
  remains on screen

#### Scenario: A further attempt is requested
- A valid surface exists and the model is asked for another
- No further corrections are sent, and the existing surface is replaced as a whole rather than
  merged into

#### Scenario: A model call fails outright
- The model provider reports a failure
- The failure is reported to the user, and no surface is left in a partially applied state

---

### Requirement: REQ-GC-005

An update to a single component SHALL NOT require the rest of the surface to be re-rendered, and a
component's own observation of the data model SHALL be limited to the values it binds.

#### Scenario: One component of a large surface changes
- A message updates one component of a surface containing many components
- The updated component re-renders, and the others do not

#### Scenario: A data value changes and only some components bind to it
- A data update changes one value in the surface's data model
- Only the components bound to that value re-render

#### Scenario: A user edits a form field
- The user types into a field bound to a data path
- The value reaches the surface's data model, and the field reflects later updates to that path
  instead of continuing to show its initial value

#### Scenario: A read-only surface receives a field edit
- The surface is displayed read-only and a field reports an edit
- Nothing is persisted, and no failure is raised

---

### Requirement: REQ-GC-006

A generated surface SHALL be reachable from the application, and the path that produces it from a
model response SHALL be the same path used in an automated check.

#### Scenario: A user asks for a generated surface in the assistant
- The user submits a request in the assistant conversation
- The surface appears in the conversation as part of the assistant's reply

#### Scenario: A surface component invokes an action
- The user activates a control inside a generated surface
- The action is reported to the application with the surface and component it came from, and the
  data bound to that component at the time

#### Scenario: The layer's dependencies are resolved
- The application's dependency graph is built
- The generated-UI layer resolves to one definition per platform, and a check that looks for
  definitions nothing consumes reports nothing for this layer
