---
title: "The chat's turn is one model call, and the screen is a format of it"
date: 2026-10-25
status: accepted
tags: [ai, genui, chat, activity]
---

## Context

The built-in chat had two answers, and the one it used was the one nobody tested.

`ChatViewModel.turn` branched: if a GenUI session was present it asked *that* for a
surface, and otherwise it asked the `TextGenPort` for text. In production the branch
always went one way — both platform DI modules inject `genui = get()`, so the session was
never absent — and the way it went was a model call of its own:

```kotlin
// KoogGenuiTransport.kt
promptExecutor.executeStreaming(p, model, emptyList())
```

**No tools.** The chat's model was asked a question with an empty tool list, so it could
not read the user's tasks, could not write them, and had nothing to show. The other
branch — `TextGenPort`, with the tool registry and the `ToolCallLoop` that emits
`AgentEvent`s — was the one every test drove, because the tests constructed the
ViewModel with a `FakeTextGen` and no session.

So the activity vocabulary this feature built — the tool-call card, the plan card, the
single terminal outcome — had no producer in the shipped application. The feature's own
specification says "the chat MUST render one activity card per tool call"; the chat
rendered none, because no call ever happened.

Two model calls also meant two ledgers: the GenUI transport recorded its own usage row
under the name `genui.surface`, and the `TextGenPort` decorator recorded another one.
Two owners for one fact, which is how a number becomes wrong without anybody deciding
it should be.

And the second call was a second *set of rules*. The GenUI session had its own
conversation memory, its own correction loop, its own idea of when a turn had ended —
while the `TextGenPort` path had a different one. A cancellation on the surface path
escaped the session's error handling and left the turn with no outcome at all.

## Idea

One turn, one model call, and the screen is one of the things the answer may be.

## Decision

1. **The chat asks one model, through one port.** The turn calls `TextGenPort`, the
   port that owns the tools, and the call emits the activity events. There is no second
   model call in the chat, and no seam that could grow one.

2. **The A2UI screen is a format of that call, not a call of its own.** The model's
   answer carries the screen as JSON-Lines inside its text; the turn's consumer frames
   those lines, applies them to the surface controller, and files the result under the
   identifier of the answer that drew it. The screen renders exactly as it did before,
   because the renderer was already addressed by that identifier.

3. **The transport seam is gone.** `GenuiTransport`, `BaseGenuiTransport`,
   `KoogGenuiTransport` and `UsageRecordingGenuiTransport` are deleted. What they
   existed for — a way to ask a model for a screen — is now what the turn does.

4. **`TextGenPort.streamChat` takes the instructions for one call.** The parameter is
   additive and defaults to nothing, so every other caller of the port is unchanged.
   The A2UI catalogue is what the chat passes through it.

5. **One ledger.** The usage row for a chat turn is written by the `TextGenPort`
   decorator, which already wraps the production agent. A surface is not a second
   expense; it is part of the answer to the question the reader asked.

6. **The conversation belongs to the chat.** The GenUI session kept the memory of the
   last turns so a follow-up question could be answered in context. That memory now
   comes from the chat's own messages, and the turn is asked with it.

7. **One owner of how a turn ended.** The turn reaches exactly one terminal outcome —
   completed, failed or cancelled — on every path, including a cancelled surface and a
   provider that died. The reader is told which one, and the spinner stops.

8. **The reader can stop a turn.** A turn in flight can be cancelled from the screen,
   and a cancelled turn keeps the answer it had already produced.

## Rationale

**Why one call.** Two calls meant two sets of rules for the same question, and the one
that ran in production was the one with no tools. The rule this change restores is
simple: the chat's agent is offered the same tools as every other agent in the app, so
a question about the user's own tasks is answered from the user's own tasks.

**Why the screen is a format.** The screen was never a different *kind* of answer — it
is a JSON document in the model's text, and the code that reads it already worked on
text. What made it a separate call was the transport, which existed to make a screen
request possible at all. Once the turn can ask the same model the same question, the
screen is what the answer may contain.

**Why the seam is deleted rather than repointed.** Repointing the transport at the
agent would have kept a layer whose only job is to forward a call, and the layer would
still be a place where a second call could grow. The deletion is the check.

**Why the instructions are a parameter.** The catalogue that describes the screen
format belongs to the screen's owner, and it has to reach the model somehow. A
parameter says "these instructions apply to this call"; a decorator would have said it
for every call, including the ones that are not about drawing anything.

**Why one ledger.** Two writers for one fact is not a redundancy but a disagreement
waiting to happen. The cost of a turn is the cost of the turn.

**Why the outcome has one owner.** The reader asks three questions of a turn: what did
you do, did it work, and are you still going. The first two are cards, and the third is
the outcome. One owner for the outcome means it cannot be set twice, cannot be lost on
a path nobody thought about, and cannot be left unset while the spinner turns.

## Consequences

- The chat's agent can call tools, and the cards show it: the activity vocabulary has a
  producer in the shipped application.
- The screen still renders, and a rejected screen is still corrected, up to the same
  number of corrections as before.
- A follow-up question is still answered in the context of the conversation, and the
  context now comes from the chat's own messages.
- The GenUI transport layer, its usage recorder and the session's model call are gone.
  The parser, the catalogue, the renderer and the surface controller stay.
- The usage ledger has one writer for a chat turn.
- A cancelled turn is cancelled on every path, and the screen offers the control that
  cancels it.
- A provider with no credentials is a failed turn, not a completed one that says so in
  its text.
- The built-in agent still has no plan producer: `PlanUpdated` is emitted by the
  external-agent adapter only, and the plan card is unreachable for the built-in
  agent. That gap is recorded in the feature's task list rather than fixed here.

## Links

- ADR `2026-09-23-genui-server-driven-ui` — the screen as a server-driven answer; this
  decision supersedes its transport seam and keeps its parser, catalogue and renderer
- ADR `2026-09-26-genui-subsystem-applied-r23` — the screen's own error handling
- ADR `2026-10-05-genui-surface-belongs-to-one-answer` — a screen belongs to the answer
  that drew it
- ADR `2026-10-23-acp-client-and-agent-activity` — the activity vocabulary this turn
  finally produces
- `specs/014-acp-client-agent-activity/spec.md` — FR-017 … FR-027, the requirements
  this decision delivers
- the open change `the-orphan-bindings-are-bound-but-never-injected` — it asked
  whether the transport seam gets a consumer or goes; it goes
- `shared/src/commonMain/kotlin/com/singularity/todo/core/llm/TextGenPort.kt` — the
  port, and the instructions parameter
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/ai/chat/ChatTurn.kt` —
  the turn
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/genui/engine/SurfaceTurn.kt`
  — the screen as a format of the answer
