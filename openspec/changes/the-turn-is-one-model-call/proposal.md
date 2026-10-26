# The chat's turn is one model call, and the screen is a format of it

Related: `docs/decisions/2026-10-25-the-turn-is-one-model-call.md` · the feature
specification `specs/014-acp-client-agent-activity/spec.md` (FR-017 … FR-027) · the open change
`agent-activity-visibility`, which built the vocabulary this change finally gives a producer

## What

The built-in chat had two answers, and the one the application used was the one no test
drove.

`ChatViewModel.turn` branched. With a GenUI session present — which is always the case in
production, because both platform DI modules inject one — the turn asked *that* for a
screen, and the session's transport asked the model with an empty tool list:

```kotlin
// KoogGenuiTransport.kt
promptExecutor.executeStreaming(p, model, emptyList())
```

No tools. So the chat's agent could not read the user's tasks, could not write them, and
had nothing to show. The other branch — `TextGenPort`, with the tool registry and the
`ToolCallLoop` that emits `AgentEvent`s — was the one every test drove, because the
tests built the ViewModel with a fake agent and no session.

The activity vocabulary this feature built has therefore had no producer in the shipped
application. FR-025 says the chat MUST render one activity card per tool call. The chat
rendered none, because no call ever happened.

Two calls also meant two ledgers: the GenUI transport wrote its own usage row under the
name `genui.surface`, and the `TextGenPort` decorator wrote another. Two owners for one
fact.

## The change in behaviour

- **The chat's agent is offered the tools.** One turn, one model call, through the port
  that owns the tool registry. A question about the user's own tasks is answered from
  the user's own tasks.

- **The tool calls are visible.** A call that happens in the chat appears as a card with
  its name and its outcome, in the same presentation the external-agent screen uses.

- **The screen is a format of the answer, not a call of its own.** The model's text
  carries the JSON-Lines; the turn frames them, applies them, and files the surface
  under the answer that drew it. A rejected screen is still corrected, the same number
  of times as before.

- **One owner of how a turn ended.** Every turn reaches exactly one terminal outcome —
  completed, failed or cancelled — including a cancelled screen and a provider that
  died. Previously the surface path could end with no outcome at all.

- **The reader can stop a turn.** The screen offers the control, and a cancelled turn
  keeps the answer it had already produced.

- **One ledger.** The cost of a chat turn is recorded once, by the decorator that
  already wraps the production agent.

- **A provider with no credentials is a failed turn.** It used to be a completed turn
  whose text said the key was missing.

## What was found

The gap was not in the activity vocabulary, the cards, or the outcome rules. All of that
was built, tested and merged. The gap was one `if` in the ViewModel and one empty list
in a transport:

| What | Where | What it meant |
|---|---|---|
| The branch | `ChatViewModel.kt` — `if (session == null)` | The tested path and the shipped path were different paths |
| The empty tools | `KoogGenuiTransport.kt` — `executeStreaming(p, model, emptyList())` | The chat's model could not touch the user's data, so there was nothing to show |
| The second ledger | `UsageRecordingGenuiTransport.kt` | One turn, two usage rows, two names |
| The lost outcome | `ChatViewModel.kt` — the surface path's cancellation | A cancelled turn ended with no outcome at all |

## What this change does not do

It does not give the built-in agent a plan. `PlanUpdated` is emitted by the
external-agent adapter only, so the plan card remains unreachable for the built-in
agent. That gap is recorded in the feature's task list (T074), not quietly closed here.

It does not change what a tool does, how a write is proposed, or what the reader is told
when a tool fails. Those are the tool registry's and the write pipeline's contracts.

It does not change the screen's own machinery: the parser, the catalogue, the renderer
and the surface controller stay exactly as they are. What changes is who asks the model
and what they are asked.
