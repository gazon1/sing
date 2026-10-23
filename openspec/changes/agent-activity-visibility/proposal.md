# The agent stops working in the dark

Issue: — · Related: `docs/decisions/2026-10-23-acp-client-and-agent-activity.md`

## What

The built-in agent calls tools. It reads the user's tasks, writes them, updates them, and
proposes changes. The person asking sees prose.

That is a deliberate position — the code says so in as many words: tool calls and tool
outcomes are the conversation's business, not the reader's. The person asked a question and
wants the answer.

The position stops working the moment the question is about the user's own data. "What did
you just do to my tasks?" has no answer on this screen. Neither does "that tool you called —
did it work?", and neither does "you've been busy for two minutes, are you stuck?" A failed
tool is invisible. A cancelled turn leaves the spinner running. A plan the agent made is
invisible.

The tools were added to the chat after it was designed, and the visibility question was
never re-asked. This change re-asks it and answers it.

## The change in behaviour

- A turn produces a stream of **activity**, not a stream of text: text as it arrives, tool
  calls with their outcome, and the agent's plan. Four kinds of event, and no more.
- The chat shows one **card per tool call** — the tool's name, whether it is running, done
  or failed, and a short summary of what it touched. A failed call is shown as failed.
- The chat shows the **plan**, with each step's state.
- Every turn ends with exactly **one** outcome: completed, failed, or cancelled. A turn
  whose stream dies is failed, never completed. Cancelling stops the spinner and keeps the
  answer so far.
- Cards show a **bounded, sanitized summary**. The raw payload is hidden until the user
  asks for it, and it never reaches the log.

The same cards are reused by the external-agent screen that lands in the same feature, so a
reader who has learned one screen has learned both.

## Out of scope

- The external agent itself, and the process it runs in.
- Changing what any tool does, or the set of tools.
- Showing the agent's private reasoning. The event vocabulary is small on purpose.

## Why this lands first

It needs nothing from the external-agent work, and it is the thing that makes the external
agent trustworthy: an agent the user cannot watch is an agent the user cannot approve.
