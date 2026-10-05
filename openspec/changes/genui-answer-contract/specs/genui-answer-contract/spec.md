# Spec — genui-answer-contract

Spec delta for the `genui-answer-contract` change. Adds six requirements to `genui-session` and one
to `genui-surface`.

## ADDED Requirements

### Requirement: A surface belongs to exactly one answer

The system SHALL file every surface under an identifier chosen by the caller of the exchange that
produced it, and SHALL NOT use the identifier written inside a message as the surface's address.

The identifier inside a message SHALL continue to be required, and SHALL continue to identify which
lines of one answer belong together.

#### Scenario: Two answers that name their screens identically

- **WHEN** a model answers twice, both messages declaring `"surfaceId":"test"`
- **THEN** two surfaces exist under the two caller's identifiers
- **AND** neither answer's creation is rejected as a duplicate
- **AND** a message pointing at the first answer still draws the first screen

#### Scenario: The identifier in a message still groups its own lines

- **WHEN** an answer sends `createSurface` and then `updateData` naming the same surface
- **THEN** the data reaches the surface that message created

### Requirement: An answer that drew no surface names no surface

The system SHALL report a surface identifier for an answer only when that answer applied at least one
message.

#### Scenario: A prose-only answer

- **WHEN** the model answers in prose with no message
- **THEN** the answer carries no surface identifier
- **AND** every surface an earlier answer created is left exactly as it was

### Requirement: An unreadable message is a rejection

A line that was presented as a message and cannot be read SHALL be reported as a rejection carrying
`MALFORMED_LINE`. A blank line, and prose around the messages, SHALL remain skips.

#### Scenario: A response made only of unreadable JSON

- **WHEN** the model answers with a line that is not valid JSON
- **THEN** the answer is not accepted as a success
- **AND** the model is told what was wrong on the next attempt

#### Scenario: Prose around a surface

- **WHEN** the model opens with a sentence and then sends a surface
- **THEN** the sentence becomes the reply text
- **AND** the surface renders
- **AND** no rejection is produced for the sentence

### Requirement: Truncation is not a contract violation

A response cut off mid-message SHALL be `ADVISORY` when the turn already applied something, and a
rejection when it applied nothing.

#### Scenario: A usable screen followed by a truncated line

- **WHEN** a surface applied and the response then ends mid-message
- **THEN** the answer is not retried
- **AND** the surface stays on screen

#### Scenario: A response that is nothing but a truncated message

- **WHEN** the response contains no applied message and ends mid-message
- **THEN** the model is asked again, bounded by the correction limit

### Requirement: A press is the next turn of the conversation

A control activated inside a rendered surface SHALL be reported to the model as a named action with
the data it was bound to, and SHALL NOT appear as a message the user typed.

#### Scenario: A task card is pressed

- **WHEN** the user presses a card carrying `action: "open_task"`
- **THEN** the next model call names `open_task`
- **AND** the request carries the earlier turns of the conversation
- **AND** no user message is added to the transcript

### Requirement: A submitted payload carries what the user typed

A button's data payload SHALL be resolved against the surface's data model at the moment it is
pressed. A string in the payload that is exactly one data-model path SHALL resolve to that path's
value, keeping its type; a payload with no templates SHALL be passed through unchanged.

#### Scenario: Filling a form in and submitting it

- **WHEN** the model sends fields bound to `/draft/title` and a button whose payload reads
  `{"title":"${/draft/title}"}`
- **AND** the user types a title and presses the button
- **THEN** the reported action carries that title

#### Scenario: A payload that mentions no path

- **WHEN** the button's payload contains only literals
- **THEN** it is reported unchanged

### Requirement: Rejections are countable by reason

The system SHALL count rejections by `A2uiErrorCode` for the life of the process and SHALL render
the running distribution.

#### Scenario: A model that repeatedly invents a component

- **WHEN** several answers are rejected for an unknown component
- **THEN** the tally for that code is higher than for codes that did not occur
- **AND** the distribution is logged alongside the turn that produced it