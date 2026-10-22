---
title: "The write path has one shape: every change to the user's data is a proposal"
date: 2026-10-22
status: accepted
supersedes: 2026-10-22-the-write-path-asks-first
tags: [ai, tools, proposals, contract, safety]
---

# The write path has one shape: every change to the user's data is a proposal

## Context

The write path was half direct. The delete tools proposed; the create and update
tools wrote. The same act — a change to the user's own data — got two treatments,
and which one a change got was decided by which tool the model happened to pick:

| Tool | What it did |
|---|---|
| `delete_task`, `delete_note`, `delete_project`, `delete_tag` | proposed |
| `write_task_proposal` | proposed |
| `create_task`, `update_task` | wrote directly |
| `create_project`, `update_project` | wrote directly |
| `create_note`, `update_note` | wrote directly |

The principle had already been decided — *a proposal is the same kind of act as an
edit, and it is the proposal that gets the user's voice* — and it had been applied
to half the tools. The half that was not applied was the half that writes.

Two things were missing, and both were holes in the model rather than in a tool.

The first: the proposal vocabulary had no shape for a change to a project. It had
`SetTaskField` for a task and `SetNoteField` for a note, and for a project only
`DeleteProject`. So "change the project's name" was impossible — not because
nobody forgot, but because there was no kind for it. The same was true of a tag:
the vocabulary could delete a tag and nothing else.

The second: the vocabulary had no shape for a *creation*. A proposal describes a
change to something that exists — it names a target and says what to do to it. A
creation has no target, so there was nowhere to put "and here is the task I would
like to exist".

And the earlier decision had said, in as many words, that this work was coming:
moving the write tools themselves onto proposals "is the honest version — a tool
called `update_task` that does not update a task is a tool whose name lies — and it
is a change to the server's contract, because the meaning of the tool changes under
the same name. The contract carries a version, and the version is bumped when a
field's meaning changes. So that path is available and it is a different piece of
work: a contract change, a version bump, a change to the server's documentation,
and a migration story for the clients that call the tool today."

## Idea

One shape for the write path: **a change to the user's data is a proposal, and the
proposal waits for the user.** A creation is a change like any other, so it gets
the same treatment — and the proposal vocabulary grows to say it.

## Decision

**The write tools propose.** `create_task`, `update_task`, `create_project`,
`update_project`, `create_note` and `update_note` no longer write. They store a
proposal, and the write happens when the user confirms it.

**The tool names stay, because the act is the same.** `delete_task` proposes a
deletion and is called `delete_task`; `create_task` proposes a creation and is
called `create_task`. The name says what act the tool performs; the proposal says
that the act needs the user's yes first. What changed is the *timing*, and the
timing is what the contract version is for.

**The vocabulary grows to say what it could not say before.**

- `TaskField` gains `Kind`, `DueTime`, `Project` and `Someday` — the task fields a
  tool could set and the vocabulary could not name.
- `SetTaskTags` — a *set* of tags. `AddTags` and `RemoveTags` say what to add and
  what to take away, and neither can say "the task carries these tags and no
  others", which is what a tool that rewrites a task's tags has to say.
- `ProjectField` and `SetProjectField` — the shape a project change needed and did
  not have.
- `CreateTask`, `CreateProject` and `CreateNote`, each carrying the entity it
  proposes to bring into being.

**A creation is a proposal with an id that does not exist yet.** The proposal names
the id the thing *will* have. That id is the target of the item, so the proposal
and the task it talks about are the same task — not one task and a plan for
another. The user reads "Create a task — Buy groceries", and if they say yes,
the task that appears is the one they read.

**The tools report what they did.** Each tool answers with `proposalCreated` and a
`proposalId`, and with no id of a thing that does not exist. The old answer to
`create_task` was `{"taskId": "..."}` — an id naming a task that had been written
without anyone asking. An id that names nothing is worse than no id, so the answer
changed shape with the behaviour.

**The contract version moves.** The meaning of six tool names changed under the
same names, so `SURFACE_CONTRACT_VERSION` goes from 1 to 2, and the tools'
documentation says which tools propose and which write.

**What still writes is written down.** The remaining direct writers are listed in
`config/docs/ai-direct-writes.txt` with the reason each one writes, and a gate
fails when a new tool writes without a reason or when a listed tool stops writing.

## Rationale

A rule that is written down and not checked is a rule that is true in the document
and false in the product, and the review that asked for this change found exactly
that: the principle was decided, half the tools followed it, and nothing said so.
The other half was not an oversight — it was a *deferral*, and a deferral that
never names its own end date becomes a permanent exception.

The reason to finish the job is not tidiness. It is that a user who agrees to one
change cannot tell which of the two they are looking at. The delete flow asks;
the create flow does not. The user's "yes" therefore means two different things
depending on which tool the model reached for, and a consent that means two things
is not consent.

The vocabulary grew because the vocabulary was the blocker. The review was right
that this is a hole in the model rather than in a tool: there was no way to say
"rename the project", and no way at all to say "and here is the task I would like
to exist". A tool cannot propose what has no shape.

And the creation needed the most care, because a proposal normally describes a
change to something that exists. The answer is that the id can be named in
advance: the proposal says "this task, with this id, with these fields". Nothing
is created; the id simply says what would be created, so the user reads the thing
they are agreeing to.

The contract version moves because the contract is the promise to the callers.
Six tools answer differently now under the same names, and a caller that reads
`taskId` from `create_task` will find no such field — which is exactly the kind of
break a version is for. Saying so is cheaper than a silent change.

## Consequences

- **The server's contract changes.** `SURFACE_CONTRACT_VERSION` is 2. The six tools
  answer with a proposal instead of a created entity. A client that reads
  `taskId` from `create_task` has to read `proposalId` and wait for the user.
- **The proposal machinery carries more kinds.** Every new kind is a place where
  the planner, the plan, the dispatcher, the fingerprint, the summary and the
  database codec have to agree — the compiler points at each of them, and the
  tests have to cover each.
- **The list of direct writers gets shorter.** It is the measure of what is left,
  and it is checked: a new direct writer without a reason fails the build.
- **A proposal can now be about something that does not exist.** The review screen
  and the card have to render "Create a task" and the target it names.
- **The tools say "proposed", not "done".** A model that reads its own tool
  output will tell the user that the task was proposed. Before this change it
  would have said it was created — which was true, and is exactly the problem.

## Links

- `docs/decisions/2026-10-22-the-write-path-asks-first.md` — the decision this
  completes, including the deferral it named.
- `docs/decisions/2026-10-02-ai-proposal-confirmation.md` — the confirmation
  screen the proposals wait on.
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/proposals/domain/model/ProposalItemKind.kt`
  — the vocabulary.
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/proposals/domain/usecase/ProposalPlanner.kt`
  — where a proposal becomes a plan.
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/proposals/domain/usecase/ProposalDispatch.kt`
  — where a plan becomes a write.
- `config/docs/ai-direct-writes.txt` — what still writes, and why.
- `scripts/check-ai-write-tools.py` — the gate that keeps the list honest.
