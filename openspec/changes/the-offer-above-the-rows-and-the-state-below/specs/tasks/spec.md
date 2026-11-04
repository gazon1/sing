# tasks

## ADDED Requirements

### Requirement: An ask is drawn above the attribute rows and the task's state below them

The task editor SHALL offer its caller two distinct places for content it does not render
itself: an upper place for content that asks the person for a decision, and a lower place
for content that shows the task's own state.

The upper place SHALL be drawn directly under the description. The lower place SHALL be
drawn after the attribute rows. Within a screen the order SHALL therefore be: title,
description, the asking content, the attribute rows, the state content.

A caller SHALL be able to fill either place, both, or neither. A caller that fills neither
SHALL see the screen unchanged, with no space reserved for the places it did not fill.

Each place SHALL be documented for what it carries: the upper for content that asks, the
lower for content that shows. No documentation of the lower place SHALL describe it as
content the editor does not know about.

**Rationale:** the two kinds were one place, so the order that suits one had to suit the
other. Moving the child work below the attribute rows — so the fields a person fills in
stay on the first screen — moved the offers with it, and an offer under a screen of fields
is an offer nobody reads.

#### Scenario: An offer is waiting for an answer

- **Given** a task whose first-run offer is unanswered, or that carries a proposal waiting
  for a decision
- **When** the task's detail screen opens
- **Then** the offer and the proposal are drawn above the attribute rows
- **And** a phone-sized screen shows them without scrolling

#### Scenario: A task's child work

- **Given** a task with a checklist, subtasks, attachments, a logbook or time entries
- **When** the task's detail screen opens
- **Then** those sections are drawn below the due date, the priority and the project

#### Scenario: A caller fills nothing

- **Given** an editor opened by a caller that fills neither place
- **When** its screen is drawn
- **Then** nothing is added between the description and the attribute rows, nor below them
- **And** the attribute rows keep the order they had

#### Scenario: A proposal's answer

- **Given** a pending proposal drawn in the upper place
- **When** the person confirms one item, confirms all of them, rejects one with a reason,
  edits an item's text, or dismisses the proposal
- **Then** the answer is applied exactly as it was when the proposal shared a place with
  the task's state
