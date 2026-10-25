# Design — the chat runs its own tool loop

The rationale for this change lives in
`docs/decisions/2026-10-22-the-chat-can-call-tools.md`. This file records
the shape of the implementation and what would have to be undone.

## The shape

One class, `ToolCallLoop`, in `core/ai`. It takes the tools, a bound on the
number of tool-calling turns, and a way to ask the model for the next turn. It
returns a flow of text.

```
streamChat
  └── ToolCallLoop.run(prompt, model, stream)
        ├── turn 1: stream → text + requests
        │     └── requests present?
        │           ├── no  → the answer is done
        │           └── yes → run them, put the outcomes back, turn 2
        └── turn N (the bound): stream with no tools offered → the answer is done
```

The class is in `core/ai` rather than in `feature/ai` because it is shared
machinery with no knowledge of the chat, the screen or the conversation model —
the same place the tool registry and the recording wrapper already live.

## The conversation shape

The model reads a conversation. The conversation grows in the shape the
OpenAI-compatible backends expect:

- the assistant's turn — its text and its tool requests — as one assistant
  message;
- the outcomes of those requests, as one user message holding one outcome per
  request.

The outcomes are a user message rather than a message of their own kind because
that is the only shape the transport has, and because it is the shape the
library's own agent produces. A reader of the repository can compare the two.

## Why the loop is not the library's agent

The library's agent runtime would have given all of this for free. It is not
used, for two reasons:

1. It does not stream. Moving the chat onto it would trade the streaming the
   reader watches for a reply that appears at the end. The requirement is
   explicit that the text reaches the reader as it is produced.
2. It is written against the JVM's reflection and its own event pipeline, and
   this project runs the same code on Android and on the desktop.

The loop is small enough to read in one sitting, and it is the part of the
answer that the project's own tests can reach.

## The bound

Four tool-calling turns. Two is enough for the common case ("which of my
overdue tasks is in the project I am about to archive?" is two lookups); four
leaves room for a question that needs a third. The turn after the bound offers
no tools, so the model has to answer in words — a bound that ended in silence
would leave the reader without an answer.

## Rollback risk

**Low, and one line to undo.** The change adds a class, changes the call in
`streamChat` to use it, and adds an override to the recording wrapper. Undoing
it means: put the empty tool list back, and delete the class and the test. The
registry, the tools and the non-streaming path are untouched, so no data is
left in a state the rollback cannot reach.

The one thing that is not trivially undone is the recording wrapper's override:
tools that were being recorded with their answers wrapped in JSON will, after
the rollback, be wrapped again. That is a difference in what the model reads,
not in what the app stores.
