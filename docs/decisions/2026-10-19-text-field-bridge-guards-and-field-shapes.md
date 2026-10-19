---
title: "A lagging upstream must not overwrite the field, the three field shapes are named, and the state-based rule gets a counterpart"
date: 2026-10-19
status: accepted
tags: [ui, compose, viewmodel, detekt]
---

## Context

ADR `2026-10-19-text-field-state-migration` moved every text field in the app to the
state-based API: the field owns a `TextFieldState`, the ViewModel owns a plain `String`,
and a bridge (`rememberBridgedTextFieldState`) reconciles the two.

That ADR settled the *shape*. Three things it left open turned out to matter.

**The bridge's pull guard had a hole.** The guard was "the upstream's value differs from
the last value the field held, and from the value the field currently holds → overwrite
the field". That rule cannot tell a *lagging* upstream from a *rewriting* one, because
both arrive as a string the field has seen before. A ViewModel that is a step behind — a
debounced update, two async paths delivering out of order, a `flatMapLatest` that swapped
results — hands back the text from before the last keystroke, and the field overwrites
the user's newer text with it.

**The three field shapes were described but not named in code.** The ADR tabulated
"local", "bridged" and "anchored", and gave the bridged shape a helper. The other two
had none: an anchored field — one that shows a value from outside and cannot be edited —
was hand-rolled at each call site, and the hand-rolled version was repeated three times
with the same `LaunchedEffect` + `if (text != value)` shape.

**The rule that banned the old shape had no counterpart.** `NoValueBasedTextField` stops
a field from going back to `value =` / `onValueChange =`. Nothing stopped the *new* mistake
in the other direction: keeping the `TextFieldState` in the ViewModel. The ADR rejected
that placement, and nothing enforced the rejection.

## Idea

Give the bridge a rule that can tell a lagging upstream from a rewriting one, name the
three shapes in code so the choice is visible at the call site, and enforce the
placement decision with a rule of its own.

## Decision

**The bridge remembers the values it pushed, and an upstream value that is one of them is
an echo.** The pull guard is now:

> overwrite the field when the upstream's value is neither the text the field currently
> holds nor a value the field pushed earlier.

The two halves are different facts and the difference is the whole point:

- The **last value the field held** — the text it displays now, *including the seed it
  started with*. A field that was seeded and never edited handed out its seed, so an
  upstream that later clears its value is a change from outside the field and must be
  applied. A guard that compared only against the *pushed* values would compare the
  cleared value against an empty string the field never pushed, call it an echo, and
  leave stale text on screen.
- The **values the field pushed**. These are the echoes to recognise. A value the field
  pushed and no longer holds is an upstream that is *behind*, not one that is rewriting:
  applying it would undo the user's most recent edit with a value the app already moved
  past.

The history is bounded (the last 16 pushes). A long typing session must not grow it
without end, and an upstream that echoes a value from seventeen keystrokes ago is not a
lagging upstream any more.

**The trade-off is accepted and documented:** an upstream that *deliberately* rewrites the
field to a value the field pushed earlier is not applied. The two cases are
indistinguishable from the field's side — both arrive as "a value I sent a moment ago" —
and the stale case is the one that destroys work, so it wins. A rewrite to a value the
field never pushed always lands.

**The three shapes are named in code.**

| Shape | Helper | File |
|---|---|---|
| local | `rememberTextFieldState` (Compose's own) | — |
| bridged | `rememberBridgedTextFieldState(externalText, onTextChanged)` | `core/ui/components/TextFieldStateBridge.kt` |
| anchored | `rememberAnchoredTextFieldState(text)` | `core/ui/components/TextFieldShapes.kt` |

The anchored helper is the missing one: a field that shows a value from outside and
cannot be edited. It seeds from the value and follows it when it changes, and it writes
only when the text actually differs — writing the same text again would reset the
cursor of a field that is not read-only. The three hand-rolled call sites now use it.

**`ObserveTextFieldEdits` moved to its own file**,
`core/ui/components/TextFieldEdits.kt`, next to the `fieldTextFlow` primitive both it and
the bridge read the field's text through. The two now agree by construction on what
"the text changed" means — in particular on the seed, which is the field's first value
and not an edit.

**A new detekt rule, `NoTextFieldStateInViewModel`,** bans a `TextFieldState` property or
constructor parameter in a ViewModel (a class named `*ViewModel`, or one extending
`MviViewModel` / `DraftMviViewModel`) and in a class that implements a UI state
interface. It is the counterpart of `NoValueBasedTextField`: one rule keeps the old API
out, the other keeps the new buffer in the layer that must not own it.

**The projects list's `Empty` state carries its query.** `ProjectsUiState` grew a
`searchQuery` and a `sortOrder` on the interface, so every state — `Loading`, `Empty`,
`Content`, `Error` — carries the filter it was produced under. The defect this fixes is
not cosmetic: an `Empty` that dropped the query made the screen hand the search field an
empty string, the field answered with an empty string, the ViewModel read that as "the
user cleared the search" — no, it did not even get that far: the empty string equalled
the upstream's own value, so the bridge treated it as an echo and never pushed it. The
ViewModel kept filtering by the old query and the list could never come back. The empty
state also said "No projects yet" when the user had searched for something, which is a
different screen entirely.

## Rationale

**The stale case has to win the tie.** A lagging upstream happens constantly — every
debounced field, every async write. A deliberate rewrite to a previously-typed value is
rare. The guard cannot tell them apart, so it has to pick, and it has to pick the one
that does not destroy the user's work.

**The revision token that would tell them apart was not available.** A revision counter
would distinguish "the upstream is behind" from "the upstream rewrote it" exactly. It
would also have to travel with every field-backed `UiState`, which contradicts the
decision the previous ADR made — the ViewModel owns a `String`, not a versioned one — and
would have put a counter in every screen's state for a case that a bounded history
covers. The history is the cheaper of the two, and the one that does not change the
ViewModel contract.

**Naming the shapes is what makes the choice reviewable.** With three shapes and one
helper, "which shape is this field?" is answered by reading the call. With a
hand-rolled `LaunchedEffect` at each anchored site, the answer was "whoever wrote it
last" — and the three sites had already drifted into the same shape by copying each
other.

**The rule is the enforcement.** The same argument that produced
`NoValueBasedTextField` produces this one: a decision that only exists in an ADR is
undone by the next feature, and the rule is what keeps it undone-free. The rule's
message names the alternative, so a finding explains itself.

**`Empty` carrying its query is a contract, not a convenience.** A UI state that
describes a list but drops the filter that produced it cannot be rendered correctly and
cannot be fed back to the field that owns the filter. The query is part of what the
state *is*.

## Consequences

- `shared/src/commonMain/kotlin/com/singularity/todo/core/ui/components/TextFieldStateBridge.kt`
  — the pull guard takes the pushed-value history; the push guard is unchanged.
- `shared/src/commonMain/kotlin/com/singularity/todo/core/ui/components/TextFieldShapes.kt`
  — new: the three shapes documented, and the anchored helper.
- `shared/src/commonMain/kotlin/com/singularity/todo/core/ui/components/TextFieldEdits.kt`
  — new: `ObserveTextFieldEdits` and the `fieldTextFlow` primitive it shares with the
  bridge.
- `detekt-rules/src/main/kotlin/com/singularity/todo/detekt/NoTextFieldStateInViewModelRule.kt`
  — new: the counterpart rule, with its own tests.
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/projects/presentation/state/ProjectsUiState.kt`
  — the interface carries `searchQuery` and `sortOrder`; `Empty` is a data class again
  rather than a data object.
- The three anchored call sites — the work-schedule time field, the AI provider dropdown
  and the AI model dropdown — use `rememberAnchoredTextFieldState`.
- The bridge's guards are pinned by four new cases in
  `shared/src/commonTest/.../TextFieldStateBridgeTest.kt` (a lagging upstream, a value
  the field never pushed, a value that was only the seed) and by a new case in
  `desktopApp/src/jvmTest/.../TextFieldStateBridgeUiTest.kt` (a lagging upstream does not
  overwrite the field).
- The procedure is written down in
  `.agents/skills/singularity-todo-text-field-states/SKILL.md`, and the ADR it came from
  is linked from there.
- **Two defects the follow-up work uncovered, fixed here rather than recorded as
  decisions.**
  - The note editor's `RewriteNoteUseCase` was bound with `factoryOf`, whose constructor
    parameter is the `TypedTool` *interface*; the erased type had no binding, so creating
    the `NoteEditor` ViewModel threw on every platform, and `getOrNull` did not swallow it
    because it swallows a missing *definition*, not a definition that fails to be created.
    The binding is now an explicit lambda, like `ImproveNoteUseCase`'s.
  - The desktop harness's failure diagnostics assumed a single Compose root. A `Popup` is
    a root of its own, so `onRoot` refused to dump the tree at all — the bundle meant to
    explain a failure explained nothing. The dump now walks every root, and the
    "all available tags" listing actually lists them.
- **The push is asynchronous, and that is a behaviour change.** A value-based field
  reported every edit through a callback, in the same frame as the keystroke. A
  state-based field's text is *observed*: the bridge reads it from a `snapshotFlow`, so
  the upstream hears about an edit a beat later. Nothing in the app can act on the stale
  value — a button's enabled state is derived from the state that has not been updated
  yet, so it is disabled until the edit lands — but a *test* that types and then asks
  what the model holds has to let the composition settle first. The GenUI render test
  does; see `GenuiRenderScope.settle()`.
- **The `ProjectsUiState` change is a breaking change for anything that constructs it.**
  `Empty` is no longer a `data object`. The only construction sites are the ViewModel and
  the screen's preview, both updated here.

## Links

- `docs/decisions/2026-10-19-text-field-state-migration.md` — the ADR this one refines
- `shared/src/commonMain/kotlin/com/singularity/todo/core/ui/components/TextFieldStateBridge.kt`
  — the bridge and its guards
- `shared/src/commonMain/kotlin/com/singularity/todo/core/ui/components/TextFieldShapes.kt`
  — the three shapes and the anchored helper
- `detekt-rules/src/main/kotlin/com/singularity/todo/detekt/NoTextFieldStateInViewModelRule.kt`
  — the counterpart rule
- `.agents/skills/singularity-todo-text-field-states/SKILL.md` — the procedure
