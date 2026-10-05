# Tasks — genui-answer-contract

Spec: `genui-answer-contract`, REQ one per section.
ADR: `docs/decisions/2026-10-05-genui-surface-belongs-to-one-answer.md`.

Each task names the test that pins it. Every one of them was found by a test that failed first.

## A surface belongs to one answer

- [x] `shared/` Require a `SurfaceId` in `GenuiSession.respond`, chosen by the caller.
      **Test:** the session is constructed in tests without a surface identifier no longer compiles.
- [x] `shared/` Add `UiEvent.retargeted(surfaceId)` and apply every event through it, so the
      identifier inside a message groups one answer's lines without deciding where the surface lives.
      **Test:** two answers declaring the same `"surfaceId":"test"` produce two surfaces, and the
      first is not redrawn by the second.
- [x] `shared/` Report a surface identifier only when the answer applied something.
      **Test:** a prose-only answer reports null and leaves an earlier screen untouched.
- [x] `shared/` Bound the surfaces a controller holds, forgetting the oldest past 24.
      **Test:** a session that answers more than the bound leaves the newest intact.
- [x] `shared/` Allocate a fresh `SurfaceId` per answer in `ChatViewModel`.
      **Test:** two answers produce two distinct identifiers on their messages.

## The correction loop

- [x] `catalog/` Allow a prose-only answer in the prompt, so a reply without a screen is not a
      failure. **Test:** the prompt says most questions need no screen.
- [x] `shared/` Send the model only the rejections from the attempt it just made, not everything
      accumulated. **Test:** a corrected error is absent from the following request.
- [x] `shared/` Report a message-shaped line that will not parse as a rejection rather than a skip.
      **Test:** a response of unreadable JSON is retried instead of accepted.
- [x] `shared/` Make a truncated tail `ADVISORY` once something has applied, and a rejection when
      nothing arrived. **Test:** both shapes, one attempt each.

## A press is a turn

- [x] `shared/` Route a control's action into the next turn of the same conversation rather than
      dropping it. **Test:** the press reaches the transport, by the name the model gave it.
- [x] `shared/` Keep bounded conversation history so a follow-up and a press are both answerable in
      context. **Test:** the second request carries the first prompt; the oldest turn falls out.
- [x] `shared/` Do not add a user message for a press — the user typed nothing.
      **Test:** the transcript still holds exactly one user message.

## Submitting what the user typed

- [x] `render/` Resolve a button's payload against the data model at press time.
      **Test:** a form round trip — type, press, and the payload carries the typed text.
- [x] `render/` Keep a payload with no templates byte-identical.
      **Test:** a literal payload is reported unchanged.
- [x] `schema/` Write through an array index as an array, not as an object with a numeric key.
      **Test:** a value written at `/t/0/title` reads back; a gap is filled with nulls.
- [x] `catalog/` Tell the model that payload strings may contain templates.
      **Test:** the generated prompt says so.

## Seeing the failures

- [x] `core/` Count rejections by `A2uiErrorCode` and render the running distribution.
      **Test:** a code's tally reflects how often it occurred, and the snapshot is ordered by it.
- [x] `di/` Register the counter and thread it through the session.

## Seeing the surface

- [x] `desktopApp/` A harness that plays JSON Lines through the real pipeline and composes the
      result, so a component can be asserted to draw. **Test:** a task card's node exists with the
      title its data model resolved.
- [x] `jvmTest/` The same corpus offline, without a Compose runtime, so the catalog's expressiveness
      is checked in the fast loop too.

## Structure

- [x] `shared/` Split `A2uiNodeFactory` along the catalog's own groups — atoms, layout, domain — so
      the decoder and the dispatcher are not one 28-function class.
- [x] `shared/` Move the operations of a recognised message into their own decoder: the envelope and
      the contract are different questions.
- [x] `shared/` Move the surface's observations out of the controller, which owns only state.
- [x] `shared/` Split the built-in functions by what they do to their argument.
- [x] `shared/` Rewrite the template scanner's loop so it has two decisions rather than four jumps.

## Still open

- [ ] A measurement of first-attempt validity against a real model. The corpus pins what the catalog
      can express; it cannot say whether a model produces it. Needs a provider key and a runner that
      reports attempts-per-answer, not a unit test.
- [ ] `just gate` end to end. Run after the splits.