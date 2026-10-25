---
title: "The chat can call the tools it was given"
date: 2026-10-22
status: accepted
tags: [ai, koog, streaming, tools, contract]
---

# The chat can call the tools it was given

## Context

The app has forty-one tools. They are registered, described, filtered by a kill
switch, and attached to a registry. The chat screen could not call any of them.

The streaming path — the one the chat screen uses — passed an empty list of
tools to the model, so the model was told, in effect, that it had no hands. A
question like "what are my overdue tasks?" was answered from the model's
imagination, and the imagination was indistinguishable from a lookup: both
produce a list of plausible task titles with plausible dates.

The other path could call tools. It runs a full agent with the registry
attached, and in production it was used for exactly one thing — a "ping" that
checks whether the API key works. So the tools were reachable from a code path
nothing else used, and unreachable from the screen the user is looking at. The
kill switch was honoured on a path nobody took, which is the same shape of
defect as the one the project already knows how to name: a feature that is
finished and does nothing.

Three ways to fix it were available.

**Move the chat onto the agent runtime.** The runtime already knows how to
call tools, feed the outcomes back and continue. It does not stream: it
returns a finished result. The reader currently watches an answer being
written, and that is the behaviour the chat exists for. Trading it for a reply
that appears all at once would make the tool call work by removing the thing
the screen is for. It is also written against the JVM's reflection and its own
event pipeline, and this project runs the same code on Android and on the
desktop.

**Give the chat a second, smaller tool-calling path.** Two paths that both
call tools means two places where the shape of a tool call is decided, and the
two will disagree.

**Write the loop out.** The loop is: stream a turn, run whatever the model
asked for, put the answers back, stream another turn. The library hands over
the pieces — the finished request with its arguments, the two message shapes
for a request and its answer, the way to extend a conversation — so the loop
is assembly, not invention.

## Idea

The loop is small, and the smallness is the point: it is the part of the
answer the project's own tests can reach.

The interesting decisions are not in the loop but around it.

**The conversation has to carry the outcomes.** A lookup that does not come
back is a lookup that did not happen. The assistant's turn keeps both its text
and its requests, and the outcomes arrive as one user turn holding one outcome
per request — the shape the OpenAI-compatible backends read, and the shape the
library's own agent produces.

**A request without an identifier has to be given one, once.** The outcome of a
request is matched to the request by identifier. An identifier invented on the
way out would not match the one the model was told it asked for, and the model
would be reading a conversation in which its own question has no answer. So the
identifier is settled when the request is read, and used on both sides.

**A failure is an answer, not a crash.** The model asked for something; the
honest answer is that it did not work, and the model is the party that can act
on that — by asking differently, or by answering without the lookup. Ending
the conversation on one failed tool shows the reader an error they cannot do
anything about.

**The bound must end in an answer.** A model that asks for a tool, gets an
answer, and asks again would otherwise ask until the app is closed. The bound
exists; what matters is what follows it. The turn after the bound offers no
tools, so the model has to answer in words. A bound that ended in silence
would leave the reader without an answer, which is a worse failure than the
loop it was meant to stop.

**The recording wrapper has to stay transparent.** The wrapper exists to record
that a tool was used. It also inherits the library's generic result encoding,
which wraps a text answer in JSON quotes on the way back to the model — a
difference the model reads as part of the payload rather than as the tool's
answer. The wrapper claims to be transparent, so it now delegates the encoding
to the tool it wraps, which is the only party that knows which of the two
kinds of answer it has.

## Decision

**The chat runs its own tool loop.** One class in the shared AI layer, taking
the tools and a way to ask the model for the next turn, returning the text as
it is produced. The streaming path offers it the same registry the other path
uses — the same tools, the same kill switch, the same recording.

**The loop is bounded**, at four tool-calling turns, and the turn after the
bound offers no tools.

**The library's agent runtime is not used** for the chat. It stays where it is,
for the path that wants a finished result.

## Rationale

The alternative that keeps the library's runtime — and its streaming problem —
is not available at the price the project is willing to pay. The alternative
that writes a second, smaller path next to the library's is worse than either:
two paths that both call tools are two places where the shape of a call is
decided.

Writing the loop out costs about a hundred lines and buys three things the
other options do not: the streaming survives, the same code runs on both
platforms, and the behaviour can be tested without a model.

The decisions around the loop are the ones that would have been expensive to
get wrong silently. A request whose identifier does not match its outcome
fails as a conversation that answers from the wrong premise. A tool result
wrapped in JSON is a payload the model has to read through. A bound that ends
in silence is a question with no answer. A failed tool that ends the
conversation is an error the user cannot act on. None of them announce
themselves; all of them are now stated in the spec and covered by a test.

## Consequences

- The chat answers questions about the user's own data from the user's own
  data. This is the change the whole plan was waiting for.
- The kill switch now governs the path the user is on. It was already applied
  to the registry; the registry was not on the path.
- The tool calls made from the chat are recorded by the same decorator as the
  ones made from the other path, so the report of which tools agents use
  covers both.
- The loop is a second place that knows the shape of a tool call. The library
  is the first. They are kept in step by the spec, and the spec is the one
  that describes the conversation the model reads.
- The number of turns a chat may take is bounded. A question that needs more
  than four lookups gets an answer that says what it found, not a complete
  answer. That is a deliberate trade, and it is a visible one.
- The recording wrapper's answers stop being wrapped in JSON. Any test that
  asserted the wrapped form has to change; none did, because the wrapped form
  was not what anything depended on.

## Links

- `shared/src/commonMain/kotlin/com/singularity/todo/core/ai/ToolCallLoop.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/core/ai/RecordingTool.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/ai/KoogAgentService.kt`
- `shared/src/jvmTest/kotlin/com/singularity/todo/core/ai/ToolCallLoopTest.kt`
- `openspec/changes/archive/2026-10-22-the-chat-can-call-tools/proposal.md`
