# Proposal — genui-answer-contract

## Why

The GenUI layer was wired to the chat and proved itself on the first real screen: a task list draws,
a card is pressable, a field writes into the model. Three defects only visible once a second answer
arrived, and none of them reachable by a test that used one surface and one answer.

**The identifier inside a message decided where the surface was filed.** `createSurface` carried a
`surfaceId` chosen by the model, and the controller used it verbatim. A model asked twice calls both
screens the same name, so the second answer collided with the first — `SURFACE_ALREADY_EXISTS` — and
then, having been told to correct itself, kept colliding. The correction loop's own feedback was
driving it into a loop. Meanwhile the identifier a chat message points at was per-session, so any
answer with a surface redrew every earlier message's screen.

**An unreadable message was not a rejection.** The parser reported a line that was not valid JSON as
*skipped*, the correction loop saw an empty rejection list, and the answer was accepted as a clean
success with nothing on screen. The prompt had also been rewritten to encourage prose-only answers,
so this was the common case rather than the exotic one.

**A value the user typed could never be submitted.** Fields wrote into the surface's data model and
a button's `data` payload was passed through verbatim. The values a user produces exist in that
model and nowhere else — the model never saw them — so "fill it in and send it" was not expressible
in the catalog at any level.

## What changes

- A surface belongs to the answer that drew it. `GenuiSession.respond` requires a `SurfaceId`, and
  `UiEvent.retargeted` makes the caller's identifier the surface's address while the identifier
  inside a message groups that one answer's lines.
- An answer that drew nothing names no surface, so a prose reply does not leave a message pointing at
  a surface that was never created.
- A message-shaped line that cannot be read is a rejection, not a skip. Blank lines and prose remain
  skips.
- A truncation is `ADVISORY` once something has applied, and a rejection only when nothing arrived.
- A button's payload is resolved against the data model when it is pressed, so a form submits what
  the user typed.
- Rejections are counted by `A2uiErrorCode`, so "which mistake does the model actually make" has an
  answer that is not an impression.

## Impact

- `GenuiSession.respond` gained a required parameter; every caller now chooses an owner.
- Wire format unchanged. `surfaceId` is still required — within one answer it is the only thing tying
  messages together.
- `SurfaceController` forgets the oldest surface past 24, so a long conversation loses a screen rather
  than accumulating one per answer for the life of the process.
- Every renderer that draws text now resolves through one `TemplateResolver`, which also runs outside
  composition — the press-time case cannot be a composable.

## Non-goals

- A dedicated action subsystem. A press is the next turn of the conversation.
- Changing the private wire dialect.
- Persisting rejection counts. The counter is a development instrument.