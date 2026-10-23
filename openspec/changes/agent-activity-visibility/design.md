# Design: The agent stops working in the dark

Related ADR: `docs/decisions/2026-10-23-acp-client-and-agent-activity.md`

## What a turn produces

A turn used to produce text and nothing else, and that was a deliberate position: tool calls
and tool outcomes were the conversation's business, not the reader's. The person asked a
question and wanted the answer.

That position holds while the chat answers questions. It stops holding the moment the
question is about the user's own data, because then three questions have no answer on the
screen: *what did you just do to my tasks?*, *that tool you called — did it work?*, and *you
have been busy for two minutes, are you stuck?*

So a turn produces **activity**, and the text is one kind of it.

## The vocabulary is four kinds

| Kind | What it carries | What it deliberately does not |
|---|---|---|
| text as it arrives | a delta of the answer | the whole message — the deltas concatenate |
| a tool call, and where it got to | id, name, status, a bounded summary | the raw payload, at all |
| the plan | a snapshot of the steps | a delta to apply |
| the end of the turn | that it finished | that it failed — see below |

Four kinds, small enough to enumerate. That is what makes the mapping from a protocol's
many session updates testable **by exhaustion**, including the updates that are deliberately
dropped. A general activity platform, an event bus and a hierarchy of adapters were all
considered and all rejected: each adds a lifecycle to own and a leak to debug, in exchange
for flexibility nothing here uses.

The vocabulary lives in `core/llm`, beside the port it replaces, because `core/ai` already
depends on `core/llm` in one direction only. Declaring it in the feature package and using
it from the port would invert that.

## The raw payload does not travel

Not "not by default" — **not at all**. A payload is where the user's content lives, and an
event that carries one puts that content into the state of every screen that renders it, and
tempts it into the log.

A tool call carries a **summary** instead: identifiers, titles and field names, bounded in
length, built by the adapter that knows the tool. The card shows the summary only when the
reader asks for it, because a card that shows a whole note body puts the user's content on a
screen that was about to be shared, photographed or screenshotted. The reader needs to know
*what* was touched, not *the whole thing*.

## A failure is the absence of the completion event

This is the one rule the vocabulary must not permit to be broken.

`AgentEvent` carries what an agent **did**. `AgentTurnOutcome` carries how the turn
**ended**, and it is a property of the turn rather than something that arrives in the
stream. The distinction exists for exactly one case: a stream that ends without a
completion event. The agent did not finish — the provider died, the transport broke, the
process was killed.

If "the flow ended" were read as "the agent answered", the reader would be told the agent
finished when it did not, and the answer would be missing from a screen that says it is
complete.

So the completion event means success, and **the absence of it is what a failure is**.

## Exactly one outcome per turn

A turn reaches exactly one of completed, failed or cancelled. The transition happens once,
in one place, guarded by a check that the outcome is still unset — a late event arriving
after the turn ended cannot change how it ended.

Cancelling stops the spinner and keeps the answer so far. A failed turn says why, in a
sentence for a person rather than a stack trace.

## One card, two screens

The built-in chat and the external-agent screen show the same thing: an agent working. Two
presentations of it would be two things to keep right and a difference for the reader to
learn for no benefit. So there is one card, and every surface that shows an agent's activity
uses it.

A **failed** call is shown as failed rather than omitted. A card that vanishes leaves the
reader guessing whether the agent did the thing, and the agent's own prose is not a reliable
witness for its own tools.

## The plan is a snapshot

Each plan update replaces the plan wholesale, because a reader looking at the screen wants
the plan as it is now, not a patch to apply to a plan they may no longer be looking at.

The same reasoning applies to a text delta: a delta is an **addition**, never the whole
message. Overwriting with a delta loses the text that came before it.

## Rollback risk

**Low.** The change is additive: a new event type beside the existing text stream, a new
outcome beside the existing end-of-turn, and a new card beside the existing answer.

The one thing that is not trivially reversible is the vocabulary itself. Once the built-in
chat renders activity, removing it means removing what the reader now relies on to know
what the agent did. That is the intent: it is a gain that the reader can see.
