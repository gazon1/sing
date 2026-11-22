---
title: "A knowledge brief is a note attached to its owner, and the proposal is the candidate"
date: 2026-11-22
status: accepted
tags: [ai, notes, mcp, knowledge, sync]
---

# A knowledge brief is a note attached to its owner, and the proposal is the candidate

## Context

An external agent researches a topic — through 10x-chat's NotebookLM integration, or
anything else that can read sources and answer questions — and has to put the result
somewhere the user will find it. The request ([#3071](https://github.com/gazon1/sing/issues/3071))
names the seam precisely: reuse notes, repositories, the MCP server and the
proposal-write lifecycle, and do not invent a document system.

Two things were missing, and neither was a new entity:

1. **Nothing could attach a note to a project.** `notes.project_id` and `notes.task_id`
   have existed since the attachment work, and both write paths that set them are
   internal — `createForTask` for a task note, `getOrCreateDailyNote` for a daily note
   scoped to a project. `ProposedNote` — the only shape a creation proposal carries —
   had `title`, `bodyHtml`, `isFolder` and `parentNoteId`, so a note proposed by an
   agent could not be the brief *of* anything.
2. **Nothing could find a note by that attachment.** `watchForProject` answers a
   different question: it joins `notes.task_id → tasks.project_id`, so it returns the
   notes of a project's *tasks*. A note attached to the project itself was invisible to
   every read in the app.

A third gap was found while reading, and it is worth naming because it was invisible:
`config/docs/sync-field-allowlist.json` lists `note.projectId` as server-writable and
the server's table did not carry the row. `scripts/check-sync-allowlist-regenerated.py`
reports it as a deployment gap (the same shape as issue #1050) rather than a failure —
a note the agent writes on one device would not have carried its attachment to the
other. The migration that closes it is in this change.

## Idea

1. **A new entity — `KnowledgeBrief`, `Source`, `Citation`.** Answers the vocabulary
   honestly, and adds a table, a migration, a sync type, a repository, a screen and a
   second place where "what does the app know about this project" is answered.
2. **A parallel MCP surface of four tools** (`get_project_knowledge`,
   `propose_project_knowledge_update`, `get_task_knowledge`,
   `propose_task_knowledge_update`), as the issue sketches.
3. **The attachment on the note that already exists**, plus one read tool, and the
   proposal pipeline as the only write path.

## Decision

Option 3.

**The brief is a note.** A note has a Markdown/HTML body, an owner, a title and a
modified time — everything a source-attributed summary needs. `ProjectedNote` gained
`projectId` and `taskId`; `ProposalPlanner.buildNote` writes them onto the row, and
`ApplyProposalItemUseCase` resolves the ids against the real rows before the claim, so a
hallucinated project id is refused where the user can still be told why.

The two attachments are mutually exclusive, and that is enforced in three places rather
than assumed: the tool refuses a call that names both, `buildNote` requires it, and the
foreign keys make an unknown id fail. A note that hung on both would answer "which
project's brief is this?" with two rows.

**The candidate is the proposal.** A refresh already produces something the user has to
approve — that is what `PROPOSAL_WRITE` means — and the proposal card is where a diff
between the approved brief and the new extraction is decided item by item. No new
lifecycle, no second "candidate" state, and a failed or partial extraction cannot erase
the brief: it produces no proposal, and the note the proposal would have replaced is
untouched.

**One read tool, not two.** `get_knowledge_brief(projectId | taskId)` answers one
question: *what does the app already know about this?* The issue's four candidate
operations collapse to this and the existing note writes, because
`create_note`/`update_note` already accept the content and already propose. Two more
names for the same two acts would be the split
[2026-11-06-one-name-one-question.md](2026-11-06-one-name-one-question.md) refuses.
The read returns `updatedAt` so the next `update_note` can pass it back as
`expectedUpdatedAt`.

**Stale means refused, not merged — at both ends.** The version travels with the
change: `update_note` compares `expectedUpdatedAt` with the note it just read and refuses a
mismatch before anything is proposed, and `NoteValue.Body` carries the same value into the
proposal so `ProposalPlanner.withNoteValue` refuses again at apply time. Both checks are
needed and neither is redundant: the first stops a summary written from an old note from
ever becoming a proposal, the second stops a proposal from landing on an edit the user
made while the card was waiting. The value is optional — a `Body` built by the in-app
editor carries none, because a human who pressed "improve" and then confirms the result is
the decision itself.

Applying a note write also advances `updatedAt`. The task path already did this through
its repository, so the note path's silence was an inconsistency rather than a choice: a
write that did not move the version meant two proposals built from one read could both
land.

**The provider is not in the domain.** Nothing in `commonMain` names NotebookLM,
10x-chat, a notebook id or a source id. Provenance is Markdown the agent writes: the
canonical URL and the citation text live in the brief's body, where a note already
carries links. A provider that disappears costs the agent its tool, not the app its data
model.

**The read is bounded.** `watchAttachedToProject` takes a limit, like `watchForProject`:
a project that accumulated dozens of attached notes costs one small query, not a table
scan.

## Rationale

The issue's own findings say the model already fits — Markdown, structural
`taskId`/`projectId`, an MCP surface that delegates to the shared graph. The gap was
narrower than the sketch assumed: one field on a proposal DTO, one missing query, and a
staleness guard. A new entity would have been a second answer to "what is a document",
which the issue explicitly rules out.

The four-tool sketch in the issue comes from reading the write tools as CRUD. They are
not: `create_note` and `update_note` are the same acts the sketch proposes, under names
the client already knows. Adding aliases would make the surface larger without making it
capable of anything new — and the surface is a contract with clients, where every name
is a promise somebody keeps.

`expectedUpdatedAt` is the caller's statement rather than a version column because the
note already has `updatedAt`, the sync path already maintains it, and a new column would
need a migration to answer a question this one answers. Carrying it inside `NoteValue.Body`
followed the same rule: the proposal row already stores the kind as JSON, so the version
needed no schema change, and a proposal is not synced (`DocType` has no proposal entry), so
the field cannot reach a device running a build that does not know it.

## Consequences

- A brief is a note: it appears in the notes list, is searchable, syncs, backs up, and
  can be edited by hand — for free, because it is not a new thing.
- `get_knowledge_brief` is a new MCP tool. It is in `McpToolCatalog` as `READ_ONLY`, wired
  on both platforms, exported by `:shared:exportToolSurface`, and the generated ADR/flags
  sections are regenerated in this change. `ToolSurfaceWiringTest` (Android and desktop
  halves) and `check-skill-drift.py` hold that.
- A note attached to a project is *not* the same read as the notes of its tasks. Both
  queries stay, and the KDoc on each says which question it answers, because the next
  person to read `watchForProject` will otherwise assume it covers attachments.
- `update_note` gained an optional field. A caller that omits it keeps the old behaviour;
  a caller that states it gets the refusal. The payload only grows, so no client breaks.
- A note write now advances `updatedAt` when a proposal is applied, which it did not do
  before. Any reader that treated a note's `updatedAt` as "the last time the editor
  autosaved" now also sees agent-approved changes there — which is what the field already
  meant on the task side.
- "One brief per owner" is a convention, not a constraint: nothing prevents a second
  attached note, and `get_knowledge_brief` answers with the newest and reports how many it
  saw. A database rule would refuse a note the user attaches by hand.
- Closing the `note.projectId` allowlist gap means a brief survives the trip between
  devices. It is a deployment step, and it is reported by the existing gate until applied.
- No Android process launch, no Python, no NotebookLM client in Kotlin: the agent does the
  research, the app stores and shows what it produced.

## Links

- Issue [#3071](https://github.com/gazon1/sing/issues/3071)
- Provider contract: [gazon1/10x-chat#62](https://github.com/gazon1/10x-chat/issues/62)
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/ai/tools/GetKnowledgeBriefTool.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/ai/tools/CreateNoteTool.kt`
- `core-domain/src/commonMain/kotlin/com/singularity/todo/feature/proposals/domain/model/ProposalValues.kt` — `ProposedNote`
- [2026-11-06-one-name-one-question.md](2026-11-06-one-name-one-question.md) — the naming rule the tool count follows
- [2026-10-25-task-logbook-note-attachment.md](2026-10-25-task-logbook-note-attachment.md) — how a note is attached to a task
- `docs/architecture/knowledge-briefs.md` — the workflow an external agent follows
