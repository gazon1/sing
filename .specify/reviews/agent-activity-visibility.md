---
change: agent-activity-visibility
verdict: pass
date: 2026-10-23
pr: https://github.com/gazon1/sing/tree/feature/the-external-agent-is-hosted
reviewed: c220e32de4c3934205ab47d3e6852323555e423b
documents: sha256:8c6fbcb894fd65791a38470f3925931ff152e06b57b315b8334cdf13d7b46333
---

## What was reviewed

The built-in agent's activity, on the branch `feature/the-external-agent-is-hosted`:

- `shared/src/commonMain/kotlin/com/singularity/todo/core/llm/AgentEvent.kt`,
  `AgentTurnOutcome.kt` and `AgentActivity.kt` — the vocabulary.
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/ai/ui/AgentActivityCard.kt`
  — the shared card.
- The chat's ViewModel and screen, and the chat's turn-outcome test.
- The specification and the change's own design document.

The review was a second agent's, given the diff and the decision record rather
than the session that wrote it.

## What was found

Nothing that needed fixing.

The findings the review raised about this change were about the *other* two
halves of the feature — the client's lifecycle and the data-safety work — and
are recorded in their own verdicts. About this change specifically, the review
confirmed the properties that matter: the vocabulary is four kinds, the raw
payload of a tool call does not travel with the event at all, a failure is the
absence of the completion event rather than an event of its own, and the
outcome semantics are verified behaviourally rather than by asserting literals.

## What was added while the change was being reviewed

The change's registry entry named a design document that had never been
written, and the gate that reads the registry said so. The document exists now,
and it states the four kinds of activity and why the vocabulary is that small,
why a tool call's raw payload does not travel, why a failure is the absence of
the completion event, and why the card is shared between the built-in chat and
the external-agent screen.
