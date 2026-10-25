# The chat can call the tools it was given

Issue: — · Related: `docs/decisions/2026-10-22-the-chat-can-call-tools.md`

## What

Let the chat actually use the tools.

The app has forty-one tools, registered, described, filtered by a kill switch and
wired into a registry. The chat screen — the one a person talks to — could not
call any of them. The streaming path passed an empty list of tools to the model,
so a question like "what are my overdue tasks?" was answered from the model's
imagination, with no lookup behind it.

The other path could. The non-streaming one runs a full agent with the registry
attached, and it was used in production for exactly one thing: a "ping" that
checks whether the API key works. So the tools were reachable in theory, from a
code path nothing else used, and unreachable in practice, from the screen the
user is looking at.

This change makes the streaming path run the tools itself:

1. The model is offered the tools that survived the kill switch, and their
   descriptions — the same ones the registry already holds.
2. When the model asks for a tool, the tool runs, and its answer goes back into
   the conversation, and the model answers from what it was told.
3. The answer arrives as text, word by word, while it is being produced. The
   streaming is not traded away for the tool call.
4. A tool that fails, or a tool that does not exist, is an answer the model can
   read — not the end of the conversation.
5. A model that keeps asking for tools is stopped, and gets a last turn in
   which it must answer in words.

## The change in behaviour

A question about the user's own data is answered from the user's own data.
Before, it was answered from the model's memory of what such a list usually
looks like.

## What is not in this change

- The tools themselves are not changed. What a tool returns, what it asks for
  and how it is described are the subject of the next change.
- The write tools still write. Nothing in this change makes a write ask for
  permission first; that is a separate change, and it changes the contract of
  the write tools rather than the chat's ability to call them.
- The non-streaming path is not changed. It already had the tools.
- The screen is not changed. The chat looks the same; its answers come from
  somewhere.

## Why now

Everything after this is wasted without it. A better description of a tool
that the chat cannot call is a better description of nothing. This is the
defect the project already knows how to name — a feature that is finished and
does nothing — and it is the first one to fix, because the rest of the work
builds on it.
