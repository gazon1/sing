# Design — the write path has one shape

The rationale lives in
`docs/decisions/2026-10-22-the-write-path-has-one-shape.md`. This file records the
shape of the implementation and what would have to be undone.

## The shape

Four pieces, in four places.

### The vocabulary

`ProposalItemKind` is the only place the proposal vocabulary is enumerated, and
every kind is dispatched in exactly one `when` — the apply use case. Adding a
kind therefore forces the compiler to point at every site that must learn about
it: the planner, the plan, the dispatcher, the fingerprint, the summary, the
database codec, and the tests.

What was added:

| Kind | Why it did not exist before |
|---|---|
| `SetTaskField(TaskField.Kind / DueTime / Project / Someday)` | the tool could set those fields and the vocabulary could not name them |
| `SetTaskTags(tagIds)` | `AddTags` and `RemoveTags` cannot say "these tags and no others" |
| `SetProjectField(ProjectField, value)` | a project could be deleted and nothing else |
| `CreateTask(ProposedTask)` | a proposal describes a change to something that exists |
| `CreateProject(ProposedProject)` | the same |
| `CreateNote(ProposedNote)` | the same |

The payloads are typed data classes rather than a bag of strings, because a
creation is a fact about a task and the compiler should know its shape. The
values inside them are still strings — the model produces text, and parsing it
is the planner's job.

### The creation's id

A proposal names a target, and the target of a creation does not exist yet. The
answer is that the id is named in advance: the proposal says "this task, with
this id, with these fields". The id is generated when the proposal is made, it
is the item's target, and the thing that comes into being later has exactly
that id. The proposal and the task are the same task, not a task and a plan for
another.

The consequence: the apply path for a creation does not fetch a target. It
builds the entity from the proposal and the owner, and the dispatcher creates
it. That is why the planner's create branch takes the user id — the entity
needs an owner, and the planner is the one that builds it.

### The tools

Seven tools changed shape: `create_task`, `update_task`, `create_project`,
`update_project`, `create_note`, `update_note` and `decompose_and_create`. They
share one path — `proposeChange` in `ProposalTools.kt` — because a hand-built
proposal is a proposal that can forget its summary or its fingerprint, and a
proposal without a fingerprint is a proposal the user can turn down and be
asked about again.

`update_task` proposes the *difference*: it reads the task, works out what
actually differs, and proposes one item per changed field. A proposal that
carries the whole task is a different proposal from a proposal that carries a
field, and the user reads "Update title" rather than a task to compare.

`decompose_and_create` proposes N tasks in one proposal — the decomposition is
the model's reading, and a reading the user never saw is not something to write
into their list.

### The one card

The shared card lives in `feature/proposals/presentation/components/` and draws
an `AiProposal`. The screen used to draw its own card with its own buttons, so
it drew a projection (`ReviewProposal`, `ReviewItem`) that existed only to feed
that card. The projection is gone: the screen's state holds the proposals
themselves, and the card draws them.

The ViewModel grew the two intents the shared card needs and the old card did
not have: `ConfirmAll` and `Dismiss` — the whole proposal at once.

### The naming

The screen was called "Review" and listed proposals. It is now called
"Proposals", and the code names moved with it (`AppDestination.Proposals`,
`ProposalsScreen`, `ProposalsViewModel`, `ProposalsUiState`,
`ProposalsIntent`). The stable content key moved from `review` to `proposals`.

## The contract

Six tool names changed meaning under the same names, so
`SURFACE_CONTRACT_VERSION` goes from 1 to 2. The rules the surface already
states apply: a new field is allowed, a renamed or removed field is not, and
the version is bumped when the shape changes in a way a client can observe.

What a client observes:

| Tool | Before | After |
|---|---|---|
| `create_task` | `{"taskId": "..."}` | `{"proposalCreated": true, "proposalId": "...", "title": "..."}` |
| `update_task` | `{"updated": true, "taskId": "..."}` | `{"proposalCreated": true, "proposalId": "...", "taskId": "..."}` |
| `create_project` | `{"projectId": "..."}` | `{"proposalCreated": true, "proposalId": "...", "name": "..."}` |
| `update_project` | `{"updated": true, "projectId": "..."}` | `{"proposalCreated": true, "proposalId": "...", "projectId": "..."}` |
| `create_note` | `{"noteId": "..."}` | `{"proposalCreated": true, "proposalId": "...", "title": "..."}` |
| `update_note` | `{"updated": true, "noteId": "..."}` | `{"proposalCreated": true, "proposalId": "...", "noteId": "..."}` |
| `decompose_and_create` | `{"parentTaskId": "...", "subTaskIds": [...]}` | `{"proposalCreated": true, "proposalId": "...", "proposedTaskCount": 3}` |
| `search_tasks` | `{"tasks": [...]}` | `{"tasks": [...]}` — and the query language works |

The id a client used to get back named a thing that had been written without
anyone asking. There is no id to give back now, because there is nothing yet —
and a client that waits for the proposal to be confirmed gets the id then.

## The registry

`config/docs/ai-direct-writes.txt` lists the tools that still write directly,
with the reason each one does. It went from eight entries to one:
`create_tag`, because the proposal vocabulary has no tag kinds at all — only a
tag can be deleted.

The gate that reads it is the same gate, and its positive control follows the
registry: removing the one entry makes the gate fail, because the tool still
writes and the registry stops saying why.

## Rollback risk

Low, and bounded in three places.

- **The write tools propose instead of writing.** A rollback is the previous
  behaviour — but the vocabulary, the plan and the dispatcher keep the creation
  shapes, so the rollback is the tools' behaviour, not the model's.
- **The contract version moves.** A client that reads `taskId` from
  `create_task` breaks — loudly, because the field is gone rather than wrong.
- **The screen's stable content key moves.** A saved back stack that remembered
  `review` will not restore that screen's state. The screen holds no state of
  its own.
