---
change: agent-activity-visibility
verdict: pass
date: 2026-11-06
pr: 2608
reviewed: 901c43b8b79275c8f0087b9e604dfe960aac9846
documents: sha256:8f66527283fe835b78b1d8ecfc7dfe65d7df1ca245219b6bf59a0d7f8746019b
---

## What was reviewed

The contract of the change `agent-activity-visibility` — its proposal and its
spec delta `specs/ai/agent-activity/spec.md` — as the digest above records them.

## Why this verdict is re-issued

The previous verdict was written on 2026-10-23 against PR #1028 and carried the
digest `sha256:8c6fbcb8…`. The contract moved afterwards, in PR #2608
(`901c43b8b`, "chore(tooling): add rumdl markdown checks"), and a contract that
moves is re-issued, not re-read.

The move was made by the markdown linter, not by the change. PR #2608 added an
empty line after every `#### Scenario:` heading — ten lines in
`specs/ai/agent-activity/spec.md` — so that the list under the heading is not
read as part of it. No requirement was added, removed or reworded: the
scenarios are the same scenarios, with the same wording, in the same order. The
claim is checkable:

    git diff 901c43b8b^ 901c43b8b -- openspec/changes/agent-activity-visibility

is ten added blank lines and nothing else.

The change's `design.md` was written after the first review as well, and it is
outside the digest by design — the contract is the proposal and the spec delta.

## What the review found

Nothing. The findings the review of PR #1028 raised were about the *other* two
halves of the feature — the client's lifecycle and the data-safety work — and
are recorded in their own verdicts. About this change specifically, the review
confirmed the properties that matter: the vocabulary is four kinds, the raw
payload of a tool call does not travel with the event at all, a failure is the
absence of the completion event rather than an event of its own, and the
outcome semantics are verified behaviourally rather than by asserting literals.

Those properties are stated across the change's documents — the spec delta
carries the rule that the raw payload does not travel, the proposal names the
four kinds, and the change's `design.md` says why the vocabulary is that small
and why a failure is the absence of the completion event. None of those
documents changed in the move that made this re-issue necessary, and the
contract the digest records is the one the pass speaks for.
