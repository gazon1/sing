# ai-tool-outcomes

## ADDED Requirements

### Requirement: REQ-x An AI write tool does not report a write it did not perform

A tool in `feature/ai/tools/` that creates or updates an entity SHALL NOT return
a success payload when the underlying write returned `Result.failure`. A failed
write SHALL surface as a failed tool call.

The seven tools `CreateNoteTool`, `CreateProjectTool`, `CreateTagTool`,
`CreateTaskTool`, `UpdateNoteTool`, `UpdateProjectTool` and `UpdateTaskTool`
SHALL unwrap the `Result` returned by their repository call. The four
`Delete*Tool` already do and are unchanged.

The output DTO shapes SHALL NOT change: on success the model receives exactly the
JSON it received before. The failure path is a thrown exception, not a new field.

**Rationale:** the tool's output is the model's only evidence about what
happened. All seven build the entity id before the write and return it
unconditionally, so on a failed write the model holds a fabricated fact — an id
that names nothing — and carries it into every later turn of the conversation.
An id generated before the write and returned regardless is the whole defect;
the fix is unwrapping, not reordering.

#### Scenario: A create fails

- **Given** a repository whose `create` returns `Result.failure`
- **When** `CreateNoteTool.execute` is called
- **Then** the call fails with the repository's exception
- **And** no `CreateNoteOutput` naming a note is returned

#### Scenario: An update fails

- **Given** a repository whose `update` returns `Result.failure`
- **When** `UpdateTaskTool.execute` is called
- **Then** the call fails with the repository's exception
- **And** no `UpdateTaskOutput` carrying `updated = true` is returned

#### Scenario: The write succeeds

- **Given** a repository whose `create` succeeds
- **When** `CreateNoteTool.execute` is called
- **Then** it returns the same `CreateNoteOutput` shape it returned before this
  change
- **And** the entity is present in the repository

#### Scenario: The control is removed

- **Given** the `.getOrThrow()` unwrap has been deleted from one of the seven
- **When** the gate runs against a repository returning `Result.failure`
- **Then** that tool's control fails
- **And** the removal cannot be mistaken for the tool being correct