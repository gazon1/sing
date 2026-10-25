# Design: the chat's turn is one model call

## The shape of the change

The chat's turn becomes one thing with three owners, and the order matters:

```
ChatViewModel ──▶ ChatTurn ──▶ TextGenPort ──▶ KoogAgentService ──▶ ToolCallLoop
     │               │              │
     │               │              └── the model, with the tools
     │               │
     │               └── SurfaceTurn: frames the answer's JSON-Lines, applies them
     │
     └── folds the events into the state: text, cards, one outcome
```

`TextGenPort.streamChat` gains one parameter — the instructions for that one call — and
the chat passes the A2UI catalogue through it. The port's implementors are the production
agent, the usage decorator, the shared fake, and two test doubles.

`ChatTurn` owns the model call, the correction loop and the conversation's context.
`SurfaceTurn` owns the screen: the framer, the parser, the processor and the controller,
and nothing else. It is created per answer, because a screen belongs to the answer that
drew it.

`ChatViewModel` no longer knows whether a screen is possible. It asks the turn, folds the
events into the state, and records one outcome.

## What is deleted, and why

| Deleted | Because |
|---|---|
| `GenuiTransport`, `BaseGenuiTransport` | They existed so that a screen could be asked for. The turn asks the model. |
| `KoogGenuiTransport` | It was a second model call with no tools. |
| `UsageRecordingGenuiTransport` | The `TextGenPort` decorator already records the turn; the surface is part of the answer, not a second expense. |
| `GenuiSession` | It owned a model call, a conversation memory and a correction loop. The model call and the memory move to the turn; the correction loop stays with the screen, because a rejected screen is what it corrects. |

What stays: the parser, the catalogue, the processor, the surface controller, the
renderer, the surface identifier's ownership rule, and the correction feedback the model
receives.

## The turn

```kotlin
internal class ChatTurn(
    private val agent: TextGenPort,
    private val surface: SurfaceTurn?,
    private val history: List<ChatTurnRecord>,
)
```

`run(prompt)` emits the turn's activity:

1. the request is composed — the conversation so far, then the question;
2. the model is asked once, through the port, with the catalogue as the call's
   instructions;
3. every event is passed on: text deltas, tool calls, the plan, the end of the turn;
4. the text deltas are also handed to the screen, which frames and applies them;
5. if the screen was rejected, the model is asked again — with the same question and the
   rejections named — up to the same number of corrections as before;
6. the events of every attempt are one stream, so the reader sees one turn.

The outcome is not an event. The stream ends with or without the completion event, and
the ViewModel derives the outcome from that: completed when the completion event arrived,
failed when it did not, cancelled when the turn was stopped.

## The screen

```kotlin
class SurfaceTurn(
    val id: SurfaceId,
    private val parser: A2uiParser,
    private val processor: A2uiMessageProcessor,
    private val controller: SurfaceController,
)
```

`accept(chunk)` frames and applies; `finish()` flushes the last line; `drawn` says whether
the answer drew anything; `feedback(errors)` says what to tell the model. The rules that
move here unchanged: one framer per attempt, the surface is deleted before it is drawn
again, the prose and the messages are told apart, and an advisory error is not a
rejection.

## Cancellation

The ViewModel keeps the job of the turn in flight and cancels it when the reader asks.
The cancellation propagates through the port, through the turn and through the screen:
`runCatchingCancellable` re-throws it, so it is never mistaken for a failure. The
outcome is recorded as cancelled, the partial answer stays, and the spinner stops.

## The tests that prove it

- A test that drives the ViewModel with a fake agent that calls a tool, and asserts that
  the card appears and the turn ends with one outcome.
- A test that cancels a running turn and asserts the outcome is cancelled, the answer so
  far is still on screen, and the spinner is gone.
- A test that the screen still renders when the answer draws one, and that a rejected
  screen is corrected — the two behaviours the deleted session used to own.
- A test that a provider with no credentials ends the turn as failed.
- A test that the cost of a turn is recorded once.

## The risk this change takes

The chat's behaviour changes for every reader: the model is now offered tools it was not
offered before, and it may draw a screen or answer in words. That is the point of the
change, and it is the risk of it. The safety net is that the tool set is the same one
the kill switch already filters, and that a write still goes through the proposal
pipeline before it happens.
