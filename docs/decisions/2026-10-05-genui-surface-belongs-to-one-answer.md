---
title: "A surface belongs to the answer that drew it, not to the session"
date: 2026-10-05
tags: [genui, a2ui, architecture, llm, chat]
status: accepted
---

## Context

`GenuiSession.respond(prompt, surfaceId)` was originally `respond(prompt)`, and the session held one
implicit surface for its whole lifetime. `ChatViewModel` called it once per answer and attached the
same `SurfaceId` to every reply.

That works only while every answer has a screen, and it fails in three ways as soon as one does not.

**An answer with no surface erased the previous one.** Every turn began with
`controller.apply(UiEvent.DeleteSurface(surfaceId))`, so a prose answer — the majority of answers,
which the prompt itself had been rewritten to encourage — deleted the screen an older message in the
scrollback was still pointing at. The user scrolled up and found an empty frame.

**Two screens sharing an address meant the older message redrew the newer screen.** A chat message
holds `surfaceId`, and that message renders whatever the surface holds now. With one shared
identifier, answering a second question changed the first answer's screen underneath the user.

**Nothing ever closed a surface.** `SurfaceController` is a singleton and surfaces accumulated for
the life of the process, each holding its own data model. Harmless while there was one; a
conversation's worth of screens with per-turn identifiers makes it a leak.

There was a fourth, quieter problem underneath the first three: the identifier *inside* a message is
chosen by the model. A model asked for two screens calls them both `"s1"` — or `"surface-1"`, or
whatever it reached for last time — so a session that trusted the wire identifier had no way to tell
its own answers apart, and `createSurface` for the second would collide with the first.

## Idea

Three candidates, considered against the constraint that a surface must be addressable from a
message that may be read long after the answer arrived.

1. **One surface per session, replaced each turn.** What it does today. Cheapest, and the reason the
   three failures above exist.
2. **Explicit lifetime: the screen drops the surface when its message leaves the conversation.**
   Correct, but "leaves the conversation" is not an event anything raises — there is no scroll
   position the layer can observe, and a message stays readable for as long as the transcript does.
3. **A surface belongs to the answer that drew it; the caller chooses its identifier; the model's
   choice only groups the lines of one answer.**

## Decision

Option 3, in four parts.

**`GenuiSession.respond` requires a `SurfaceId`.** Chosen by the caller, not the model. It is a
parameter rather than a return value because the surface has to exist before the answer that
mentions it: `ChatViewModel` allocates `SurfaceId(newId())` per turn, exactly as it already
allocated one id per message.

**The identifier inside a message is not the address.** `UiEvent.retargeted(surfaceId)` rewrites the
identifier of every event before it is applied, so a `createSurface` and the `updateData` lines that
follow it still address each other — they share the model's name — while the surface itself is filed
under the caller's. `A2uiSession` therefore applies `outcome.event.retargeted(surfaceId)`.

**An answer that drew nothing names nothing.** `GenuiOutcome.surfaceId` is null unless the turn
applied something. A message pointing at a surface that does not exist would mount an empty frame,
so a prose answer has to say "no screen" rather than "the screen I was allocated".

**Surfaces are bounded, not owned.** `SurfaceController.prune(maxSurfaces = 24)` forgets the oldest
once more than that are open. Not an explicit close — the layer cannot know when a message stops
being read — but a conversation is not unbounded and neither is what a user scrolls back through.

The wire format is unchanged. `surfaceId` remains a required field of `createSurface`,
`updateComponents` and `updateData`, because within one answer it is the only thing tying those
messages together, and a model that names its own screen consistently will not notice.

## Rationale

Options 1 and 2 both couple the surface's lifetime to something the layer cannot see. Option 1's
coupling is the session, which is too short a life; option 2's is a UI event that does not exist.
Option 3 ties the lifetime to the thing that already owns the surface's reason to exist — the answer
— and makes that ownership explicit in the type: `ChatMessage.surfaceId` and `GenuiOutcome.surfaceId`
are the same `SurfaceId` value class for a reason.

It also fixes the model-identifier collision without a protocol change, which was the reason to
prefer it over renaming anything on the wire: the identifier a model chooses is a grouping key inside
an answer, and treating it as an address is the confusion that caused the problem.

`prune` is deliberately not an explicit close. An LRU bound is honest about what the layer knows —
messages can be read at any time, so nothing is ever certainly dead — while a close-on-exit would be
a guess that discards a screen someone is looking at.

## Consequences

- `GenuiSession.respond` gained a required parameter. Every caller must now think about ownership;
  that is the intended friction, and it is why the tests for it exist.
- A model that reuses `"s1"` across answers, which nearly all of them will, no longer breaks anything.
- `SurfaceController` accumulates at most 24 surfaces. Beyond that the oldest goes; a user scrolling
  back past a very long conversation loses the screen and sees the text.
- `UiEvent` gained a `retargeted` method, which every implementation must provide — a compile error
  rather than a new event type silently ignoring its identifier.
- A session now keeps bounded history (`MAX_HISTORY = 6` turns) so that a follow-up and a press
  inside a surface can be answered in context. This was added alongside the ownership fix because
  per-turn surfaces are only useful if the model knows the earlier turn existed.

## Links

- `shared/src/commonMain/kotlin/com/singularity/todo/feature/genui/engine/GenuiSession.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/genui/parser/UiEvent.kt` — `retargeted`
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/genui/surface/SurfaceController.kt` — `prune`
- `docs/decisions/2026-10-05-genui-catalog-as-contract.md` — the catalog as the contract
- `docs/CONTEXT.md` — Surface