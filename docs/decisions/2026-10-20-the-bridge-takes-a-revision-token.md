---
title: "The bridge takes a revision token, and the flush trade-off is accepted and pinned"
date: 2026-10-20
status: accepted
tags: [ui, compose, viewmodel]
---

## Context

ADR `2026-10-19-text-field-bridge-guards-and-field-shapes` gave the text-field bridge
a pull guard that can tell a *lagging* upstream from a *rewriting* one — except that
it cannot, and the ADR said so:

> The trade-off is accepted and documented: an upstream that *deliberately* rewrites the
> field to a value the field pushed earlier is not applied. The two cases are
> indistinguishable from the field's side — both arrive as "a value I sent a moment
> ago" — and the stale case is the one that destroys work, so it wins.

And in its rationale:

> The revision token that would tell them apart was not available. A revision counter
> would distinguish "the upstream is behind" from "the upstream rewrote it" exactly. It
> would also have to travel with every field-backed `UiState`, which contradicts the
> decision the previous ADR made — the ViewModel owns a `String`, not a versioned one.

The reasoning was sound but the conclusion was wrong in one respect: the counter does
not have to travel with the *state*. It only has to travel with the *value that is
being rewritten*, and the place that knows "this value is a rewrite" is not the
ViewModel — it is whoever performs the rewrite.

There is such a place in the app. The GenUI surface can be rewritten by an incoming
server message (`UiEvent.UpdateData`) *while the user is typing*, and the surface
knows which writes came from the server and which came from the field. Its renderer
documented the defect in its own KDoc: a model value that equals a value the field
pushed earlier "is treated as an echo and is not written back into the field", and the
difference is benign "in the common case" — the field already shows that text — and
*not* benign when the server rewrites the field to a value the field had already sent
and has moved on since.

The same ADR also left the flush's price unstated as a decision: the flush declines
when the upstream has received *no* push at all, so a user who typed one character and
navigated away in the same frame loses that character. The behaviour was correct and
unpinned, which is indistinguishable from an accident.

## Idea

Let the party that performs a rewrite say so, and settle the flush's price as a
decision rather than a side effect.

## Decision

**The bridge takes an optional revision token.** `rememberBridgedTextFieldState` grows
a third parameter, `revision: Long = 0`. The token is a *counter*, not a value: any
change to it means "the value that arrives with it is a rewrite, not an echo".

```kotlin
@Composable
fun rememberBridgedTextFieldState(
    externalText: String,
    onTextChanged: (String) -> Unit,
    revision: Long = 0,
): TextFieldState
```

The default is `0` and the bridge starts at the token it was created with, so **every
existing bridged call site keeps compiling and keeps behaving exactly as before**: the
first upstream value is never mistaken for a rewrite, and a caller that never rewrites
never advances the token. A caller that *does* rewrite must advance it — or the
rewrite is silently dropped when it lands on a value the field pushed earlier. That is
the price of an optional token, and it is stated in the KDoc.

**The pull guard is lifted for a declared rewrite.** `shouldPullExternalText` grows an
`isDeliberateRewrite` flag: a declared rewrite is applied whenever the field does not
already show that text. Everything else — the pushed-value history, the "the field
already shows it" check — stays as it was. The lagging case is not weakened: without
the declaration, the same value is still an echo.

**The token is consumed once.** The bridge remembers the token it last saw, so the same
declaration arriving twice is not read as two rewrites.

**The GenUI surface provides the token, because the GenUI surface knows.** The
`SurfaceController` counts the *server's* writes to a surface — the field's own writes
go through a different path and are not counted — and exposes that count as
`serverRevision(surfaceId)`. The data context passes it to the renderer, and the
renderer hands it to the bridge as the revision token. A server rewrite now wins over
the keyboard, which is the same rule the value-based field had and the same rule the
surface protocol never spelled out.

**The flush's price is accepted, documented, and pinned by a test.** The flush fires
only when the upstream is *behind* — its value is one the field pushed. A discarded
draft must not be resurrected. The price is one character in the rarest case: a field
whose upstream received no push at all has an upstream value the field never pushed, so
the flush declines and that character is lost. The alternative — pushing on every
dispose — would resurrect a discarded draft every time a user cleared a field and
navigated away. The draft is worth more than the character, and the character can be
typed again.

"Accepted" now means something specific: there is a test named
`flushDeclinesWhenTheUpstreamNeverReceivedAPush` that asserts the price. A change to
the behaviour has to change that test *on purpose*.

## Rationale

**The party that rewrites is the party that knows.** The ViewModel does not know
whether the value it is handing over is a rewrite or a lagging echo — that is the
whole difficulty, and the bridge's history is a guess at it. The surface *does* know:
the write came from the server, not from the field. Putting the token there means the
knowledge stays where it is instead of being pushed up into every UI state.

**The token is optional on purpose.** The previous ADR's argument against it — "it
would have to travel with every field-backed `UiState`" — holds only for a token that
lives in the state. A parameter on the bridge helper is paid for by the callers that
rewrite, and only by them. The twenty-odd call sites that never rewrite are untouched.

**The lagging case still wins when nobody says otherwise.** A declared rewrite is a
*claim*, and the claim is what the flag carries. Without the claim, nothing changes:
the history still decides, and the history still refuses to overwrite the user's newer
text with a value the app has moved past.

**A price that is not pinned is a bug that is waiting to happen.** The flush's
behaviour looked like a corner case of the implementation. It is a decision about
which of two losses is acceptable, and the decision belongs in a test as much as in an
ADR.

## Consequences

- `shared/src/commonMain/kotlin/com/singularity/todo/core/ui/components/TextFieldStateBridge.kt`
  — the revision parameter, the `isDeliberateRewrite` branch in the pull guard, and the
  KDoc that states the flush's price.
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/genui/surface/SurfaceController.kt`
  — counts the server's writes per surface.
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/genui/render/DefaultDataContext.kt`
  — exposes the count to the renderers.
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/genui/render/material3/input/TextFieldRenderer.kt`
  — hands the count to the bridge.
- `shared/src/commonTest/kotlin/com/singularity/todo/core/ui/components/TextFieldStateBridgeTest.kt`
  — three cases for the rewrite (a rewrite to a value the field pushed is applied; a
  rewrite to the text the field shows is not; the same value without the declaration is
  still an echo) and one for the flush's price.
- **A caller that rewrites and forgets to advance the token gets the old behaviour.**
  The failure is silent: the rewrite is dropped, exactly as before. The KDoc says so,
  and the GenUI renderer is the reference implementation.
- The flush's price is unchanged: one character, in the rarest case.

## Links

- `docs/decisions/2026-10-19-text-field-bridge-guards-and-field-shapes.md` — the ADR
  this one completes
- `docs/decisions/2026-10-19-text-field-state-migration.md` — the shape
- `shared/src/commonMain/kotlin/com/singularity/todo/core/ui/components/TextFieldStateBridge.kt`
  — the bridge
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/genui/surface/SurfaceController.kt`
  — the server-write counter
