# Design — the write path asks first

The rationale for this change lives in
`docs/decisions/2026-10-22-the-write-path-asks-first.md`. This file records the shape
of the implementation and what would have to be undone.

## The shape

- `feature/proposals/domain/logic/ProposalOperation.kt` — the operation vocabulary and
  the mapping from an operation name to a proposal kind. It lives in the domain
  because the server's tools will want the same vocabulary, and the tool classes are
  not shared between the app and the server.
- `feature/ai/tools/WriteTaskProposalTool.kt` — the tool. It resolves the operation,
  checks the task exists, builds the proposal through `ProposalBuilder`, stores it,
  and reports the line the user will read.
- `feature/proposals/domain/logic/ProposalBuilder.kt` — gains `forTarget`, the general
  form for the targets that are neither a task nor a note. The four delete tools move
  onto it.

## Why the write tools are not moved

Moving `update_task` onto proposals changes what the name means, and the server's
surface carries a version that is bumped when a field's meaning changes. That is a
contract change with its own version bump and its own client story, and it is not
this change's work. The new tool is additive.

## The rejections

Three refusals, each before anything is stored: an operation that is not one of ours,
a missing value or item, and a task that does not exist. Each says which part was
wrong, because the caller is the model, and the model can fix all three.

## Rollback risk

**Low.** The change adds a domain file and a tool, and edits four tools to call the
builder instead of building proposals by hand. Undoing it means deleting the two
files, putting the four tools back, and removing the tool from the app's list and the
server's catalog.

The one behaviour change that is not trivially undone is the delete tools' fingerprint:
before this change their proposals carried an empty fingerprint, and the rejection
guard did not apply to them. Undoing the change would restore the empty fingerprint
and the guard would stop applying to deletes again. That is a change in what the model
is prevented from proposing, not in stored data.
