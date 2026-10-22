---
title: "The write path asks first"
date: 2026-10-22
status: superseded
superseded-by: 2026-10-22-the-write-path-has-one-shape
tags: [ai, tools, proposals, contract, safety]
---

# The write path asks first

## Context

The chat can call tools, and among the tools it can call are the ones that change the
user's tasks. `update_task` changes a task the moment the model calls it.

Sometimes that is right. The user said "rename it to X", the model renamed it, and
the user saw the result they asked for. Sometimes it is not: the model, in the middle
of a conversation about something else, decided that a task ought to have a different
due date, and called the tool, and the change landed. The user never asked for that
change and never saw it.

The difference between the two is not visible in the tool call. Both are the same
model, the same tool, the same arguments. What separates them is whether a person
agreed to the change first — and nothing in the write path asked.

The app already has the machinery for a change nobody has agreed to yet. There are
proposals, a proposal repository, an apply use case that validates before it claims
and compares-and-sets so a double-tap cannot apply twice, a rejection fingerprint
that stops a change the user already turned down being proposed again, and a
confirmation screen where the user decides item by item. The delete tools use it. The
chat does not, and the chat is the path a model reaches for on its own initiative.

## Idea

**Add a tool that proposes, and let the app's existing proposal machinery hold it.**

The alternative is to move the write tools themselves onto proposals. That is the
honest version — a tool called `update_task` that does not update a task is a tool
whose name lies — and it is a change to the server's contract, because the meaning of
the tool changes under the same name. The contract carries a version, and the version
is bumped when a field's meaning changes. So that path is available and it is a
different piece of work: a contract change, a version bump, a change to the server's
documentation, and a migration story for the clients that call the tool today.

Adding a tool is not a contract change. The version stays, the old tools keep their
meaning, and the new tool is available to every client that wants to use it.

**The proposal is a real proposal, not a new kind of pending change.** It goes into
the same repository, with the same item kind, the same fingerprint, the same
confirmation screen, and the same apply use case. The difference is where it came
from, which the proposal already records.

**The operation vocabulary is written once, in the domain.** The tool's input names an
operation — `set_title`, `add_tags` — and the mapping from an operation name to the
proposal kind lives in the domain, next to the kinds themselves. Not in the tool: the
server's tools will want the same vocabulary, and the tool classes are not shared
between the app and the server.

**The tool says what it did.** "Proposed", not "changed". A model that believes it has
changed a task will say so to the user, and the user will believe it.

**And the copy-paste goes.** The four delete tools each built their proposal by hand,
and so did the task's AI slot — five copies of the same construction, while the
builder that exists for exactly that purpose was used by nobody. The new tool is the
first caller of the builder, and the delete tools join it. Their proposals also carry
a fingerprint now, which they did not before: the rejection guard was inert for every
delete proposal the app had ever made.

## Decision

**A new tool, `write_task_proposal`, proposes a change to a task and applies
nothing.** It takes a task, an operation, a value or a list of items, and a reason.
It answers with the proposal it made and the line the user will read.

**The proposal is the app's own kind of proposal**, stored in the proposal repository,
decided in the app's confirmation screen, applied by the app's apply use case.

**The operation vocabulary lives in the domain**, and the tool's description lists the
operations it accepts.

**The four delete tools build their proposals through the same builder**, and their
proposals carry a fingerprint.

**The write tools that already write keep writing.** Their contract, and its version,
are unchanged.

## Rationale

The honest version — moving the write tools onto proposals — is right in the end, and
it is a bigger change than it looks: the same name would mean something different, so
every client of the server's tool has to be looked at, and the version has to move.
Doing that as part of "let the chat propose things" would put a contract change inside
a feature change, where nobody is looking for it.

Adding a tool gets the safety without the contract change, and it gets it *now*. A
client that wants the chat to propose rather than perform can say so by using the new
tool; a client that wants the old behaviour keeps it. The cost is that both exist for
a while, and that the chat has to be told to prefer the proposing one — which is what
the system prompt is for, and what the tool descriptions are for.

Putting the operation vocabulary in the domain rather than in the tool is the smaller
decision but the more important one. The tool classes are not shared between the app
and the server, and the server will want the same operations; a vocabulary that lives
in the tool is a vocabulary the server cannot have.

Routing the delete tools through the builder is worth doing on its own terms: the
builder exists, five copies of its job exist, and the copies are worse than the
builder — they leave the fingerprint empty, which is the one field the rejection guard
is built on. A delete the user rejected could be proposed again, and again.

**A proposal about a note is a proposal the user can see.** The note editor's AI
actions save a proposal and wait — that half worked. The other half did not: nothing
watched for those proposals, so the user asked for a rewrite, the rewrite was written,
and nothing appeared. A proposal nobody can reach is a proposal the user can only
answer by telling the assistant to stop. So the repository can now watch a note's
proposals, the note editor shows the same card the task detail shows, and the card
lives with the proposals — because a proposal is a change to something, not a note's
property. The editor's own unused `applyProposal` dependency went with it: the
application goes through the proposals' own use case, in one place.

## Consequences

- The chat can propose a change to a task, and the change waits for the user.
- The proposals the chat makes appear in the app's confirmation screen, next to the
  ones the app makes, and are decided the same way.
- A change the user rejected cannot be proposed again — including the deletes, which
  could be before.
- The tool's answer says "proposed", and the model says the same.
- There are two ways to change a task from the server, and the write one still
  writes. The version of the contract is unchanged, and the clients are unaffected.
- The operation vocabulary is in the domain, and the server's tools can use it.
- The delete tools' proposals now have fingerprints, so their behaviour changes in
  one way that is visible: a rejected delete is not proposed again.
- The AI's proposed changes to a note now appear in the note editor, waiting for the
  user's word, and are decided the same way the task's are.

## Links

- `shared/src/commonMain/kotlin/com/singularity/todo/feature/ai/tools/WriteTaskProposalTool.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/proposals/domain/logic/ProposalOperation.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/proposals/domain/logic/ProposalBuilder.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/notes/presentation/viewmodel/NoteProposalsViewModel.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/proposals/presentation/components/ProposalCard.kt`
- `openspec/changes/2026-10-22-the-write-path-asks-first/`
