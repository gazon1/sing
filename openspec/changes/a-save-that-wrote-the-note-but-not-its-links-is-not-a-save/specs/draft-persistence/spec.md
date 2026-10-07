# draft-persistence

## ADDED Requirements

### Requirement: REQ-DRAFT-001 A draft save persists every part of the draft, or says it did not

A `persist` implementation on `DraftMviViewModel` SHALL NOT discard the `Result`
of any write it performs. Every write a save performs SHALL be unwrapped, so that a
save which could not fully persist the draft surfaces as a failure rather than as a
success.

`NoteEditor.persist` SHALL unwrap all three of its writes —
`createWithContent`, `updateContent` and `setOutgoingLinks` — with the same form.
The first two already did; the third did not, in the same function, under a comment
naming that call as the write path for the backlinks feature.

The failure SHALL reach the user through the existing channel: `Either.Left` becomes
a visible error on the draft state, and the draft stays dirty.

**Rationale:** the note body and its outgoing links are one draft, persisted by one
call. A save that wrote the body and lost the links reported success while
`[[note://…]]` / `[[task://…]]` links in the note were dead — and the comment above
the line asserted that this call is what keeps them alive, while the two writes above
it on the same path were already unwrapped. The convention was present in the same
function and simply not applied to the third write, which is the strongest available
evidence that the fix is one unwrap and not a redesign.

Outgoing links are deliberately not a sync concern: no `DocType` describes them, as
`SyncedWriteEnqueuesTest` records, so a failure loses the column locally and no later
reconciliation would notice.

#### Scenario: The links cannot be written

- **Given** a repository whose `setOutgoingLinks` returns `Result.failure`
- **When** the editor saves
- **Then** the state carries an error
- **And** the draft remains dirty, because the user's work was not fully persisted

#### Scenario: Every write succeeds

- **Given** a repository that accepts all three writes
- **When** the editor saves
- **Then** no error is reported
- **And** the draft is no longer dirty

#### Scenario: The unwrap is removed

- **Given** the `.getOrThrow()` on `setOutgoingLinks` has been deleted
- **When** the gate runs against a repository returning `Result.failure`
- **Then** that control fails
- **And** the removal cannot be mistaken for the write being handled elsewhere

#### Scenario: The control cannot bypass the failure handling

- **Given** the control asserts through `save()`
- **When** `save()` renders an `Either.Left` from `persist`
- **Then** the error is visible on the state
- **And** no control reaches into `persist`, which is `protected` precisely so that
  `2026-09-30-draft-save-failure-and-testtag-honesty.md` cannot be undone by
  bypassing the base body that implements the handling