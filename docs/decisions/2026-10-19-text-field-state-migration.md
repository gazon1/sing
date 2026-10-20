---
title: "The text field owns its text; the ViewModel owns a string, and a bridge keeps them in agreement"
date: 2026-10-19
status: accepted
tags: [ui, compose, viewmodel]
---

## Context

Compose ships two text field APIs. The value-based one takes a `value: String` and an
`onValueChange: (String) -> Unit`; the state-based one takes a `TextFieldState` and lets the
field own its own buffer. The state-based API is the direction the platform guide points at
(https://developer.android.com/develop/ui/compose/text/migrate-state-based).

Every text field in this app used the value-based shape: 51 call sites across
`shared/src/commonMain`. That shape is not wrong, but it puts two specific obligations on
every caller, and the app was paying both of them repeatedly.

**The caller owns the text, so text injected from outside the field can be lost.** The field
renders exactly the string it is handed. When the string arrives from a source that did not go
through the field — a paste, an autofill, an automated input action — the field has no way to
merge it, and the caller's copy wins.

**The caller owns the filtering, so the filtering is re-implemented at every call site.** A field
that accepts only digits, or only a bounded length, had to say so in its `onValueChange`:

```kotlin
onValueChange = { if (it.length <= 2) emoji = it }
```

Fifty-one call sites, each of which had to get that guard right, in the shape where getting it
wrong is invisible until a user types one character too many.

The project's ViewModel contract sits on the other side of this. A ViewModel exposes a single
immutable `UiState`, and the text of a field was a `String` field in it. That contract is the
reason the value-based API fitted so easily — and it is also the reason hoisting the state-based
API into the ViewModel is not free.

## Idea

Let the field own the buffer, and let the ViewModel keep a plain `String`.

The field is where the text actually lives while the user types: it can reject a character at
the point of entry, it can mask a secret, and it can be read by the screen without a round trip
through the ViewModel. The ViewModel still needs a `String` — it has to compare, validate,
persist and report the value, and it must not hold a Compose type — so a small composable helper
reconciles the two in both directions.

## Decision

**Every Compose text field in `shared/src/commonMain` uses the state-based API.** The GenUI
renderer was the one exception; it has been migrated since, and no exception is left.

**A `TextFieldState` never enters a ViewModel or a `UiState`.** ViewModels keep plain `String`
fields in their immutable state, and the field's text is pushed to the ViewModel one way while
the user types.

**Three field shapes, chosen per call site:**

| Shape | Where the text lives | When |
|---|---|---|
| Local | the field's own state | the screen needs the text only at the field |
| Bridged | the field's state, reconciled with a ViewModel-owned string | the ViewModel must validate, persist or report the text |
| Anchored | an external value, rendered as-is | the field is a display of something else (a resolved title, a stored value) |

**The bridge is one helper**, `rememberBridgedTextFieldState(externalText, onTextChanged)` in
`shared/src/commonMain/kotlin/com/singularity/todo/core/ui/components/TextFieldStateBridge.kt`.
It pushes the field's text upstream and pulls an upstream rewrite back into the field, with a
guard on each direction:

- **The push is guarded by "the upstream does not already hold this value".** A value the
  upstream already holds is an echo of its own copy coming back around, and writing it again
  would mark the field dirty for nothing. The field's *initial* text is such an echo: it is
  seeded from the upstream, so pushing it would write the upstream's own value back to it.
- **The pull is guarded by "this is not the value the field last held".** The upstream may lag behind
  what the user has typed. The naive rule — "overwrite whenever the upstream differs from the
  field" — live-locks: while the upstream is still catching up it holds the *previous* text, the
  comparison passes, and the keystrokes the user just made are reverted. Guarding on the last
  value the field held is what keeps the two in agreement without fighting the user. The seed
  counts as a value the field held: a field that was seeded with a loaded value and never edited
  must still follow an upstream that clears its own copy, or the field keeps showing a title the
  app no longer holds.

**Filtering and validation live in `InputTransformation`, at the point of entry.** The
`TextFieldBuffer` that a transformation receives is not constructible from a test, so the rules
themselves live as plain string functions in
`shared/src/commonMain/kotlin/com/singularity/todo/core/ui/components/TextFieldTransformations.kt`
(`filterDigits`, `isWithinLength`, `isValidRecurrenceInterval`, `isValidDayOfMonth`) and the
transformation applies them. The buffer is the delivery mechanism; the rule is the decision, and
the rule is the part that has to be testable.

**Display-only formatting would live in `OutputTransformation`**, which changes what is shown
without changing what is stored. No call site in the tree needed one at the time of this
migration; the distinction is recorded so the next field that needs it does not push a
formatting rule into the input path.

**Secrets use the dedicated field.** Passwords are rendered with Material3's
`OutlinedSecureTextField` and `TextObfuscationMode.Hidden`; the AI provider's API key toggles
between `Hidden` and `Visible` on an explicit request.

**The value-based shape is banned by a detekt rule**, `NoValueBasedTextField` in
`detekt-rules/src/main/kotlin/com/singularity/todo/detekt/NoValueBasedTextFieldRule.kt`,
registered in the `no-value-based-text-field` rule set and enabled in `config/detekt/detekt.yml`.
The migration is one line away from being undone by the next feature, and the rule is what keeps
it undone-free.

### The GenUI exception, and its removal

`shared/src/commonMain/kotlin/com/singularity/todo/feature/genui/render/material3/input/TextFieldRenderer.kt`
was value-based, and the detekt rule exempted that path explicitly. It is state-based now, and
the exemption is gone.

The value there is not a local string. It is a `JsonPrimitive` at a path in a surface's shared
data model, and that model can be rewritten by an incoming server message *while the user is
typing*. The state-based API needs a conflict rule — who wins when the server and the keyboard
disagree — and the surface protocol does not define one. It also needs a `UiPath` →
`TextFieldState` registry so the decoded initial value is applied once per path rather than on
every recomposition.

Both turned out to be unnecessary. The bridge already carries the conflict rule, and the
registry is not needed because the field state is remembered by the composable rather than
looked up by path. The renderer uses `rememberBridgedTextFieldState` with the model as the
external value, so the rule it applies is the one this ADR describes above: the field owns the
text while the user types, and a server value that is not an echo of what the field itself
holds replaces it — which is what the value-based field did too, because the model always won.
The one difference is that a model value the field itself pushed — the last one, and the
recent values before it — is not written back into the field; that difference is benign,
because the field already displays that text.

### Alternatives that were considered and rejected

**Hoist the `TextFieldState` into the ViewModel** — the platform guide's "recommended simple
approach". Rejected. A `TextFieldState` is a mutable, snapshot-backed holder; putting one inside
the immutable `UiState` this project's ViewModels expose creates a second source of truth for
the same value, and the state is no longer a value that can be compared, saved or restored as
one. The canonical ViewModel pattern in this repo keeps Compose out of the ViewModel, and the
state-based API does not require it: the state can be created and owned in the composable and
the ViewModel can still hold the `String` it needs. The guide's recommendation is about *where
the state object is created*, not about putting it in the ViewModel, and this app took the
former without the latter.

**Keep the value-based API.** Rejected. It is the source of both defects above: text injected
from outside the field can be lost, and the filtering is re-implemented by every caller. It is
also the shape the new detekt rule bans, so keeping it would mean the rule and the code
disagree.

**Give the field a single generic wrapper that owns the filtering**, so no call site writes a
transformation. Rejected. The rules are not uniform — one field accepts digits, another accepts
a bounded range, another accepts at most two characters — so the wrapper would need a parameter
per rule, and a call site would end up describing its rule in a wrapper argument rather than
where the rule lives. Keeping the rule next to the field that has it is cheaper than a
generalisation that has to carry every rule at once.

**Migrate the GenUI renderer at the same time.** Rejected: it needs a conflict policy the
surface protocol does not have, and inventing one inside a UI migration would be deciding the
server's data model by accident. It was migrated afterwards, once the bridge supplied that
policy — see "The GenUI exception, and its removal" above.

## Rationale

**The field owns the buffer because that is what the state-based API is for.** The field can
reject a character as it is entered, mask a secret, and be read without a round trip. The
value-based API cannot do any of those without the caller's cooperation, and the caller's
cooperation is precisely what was failing.

**The ViewModel owns a `String` because the ViewModel's contract is a value.** The canonical
pattern in this repo is a `MutableStateFlow<UiState>` over an immutable data class. A `String`
is comparable, saveable and reportable; a `TextFieldState` is none of those in a useful sense.
Keeping the `String` in the ViewModel means validation, persistence and reporting continue to
work exactly as before, and the migration does not reach into the MVI layer at all.

**The bridge is a workaround, and it has a failure mode.** It exists because a snapshot-backed
field and an immutable `UiState` are two different kinds of holder, and the app needs both. The
guards above are what make the reconciliation safe; without them the two can fight. The honest
statement of the remaining failure mode: if the upstream rewrites the value to something that is
neither the last value the field held nor the value the field currently holds, the field is
overwritten and the last keystroke is lost. That is the intended outcome — the upstream's
rewrite wins — but it means a rewrite that lands mid-typing costs the user a character. The
guards remove the *echo* and the *lag* cases, which are the ones that happen constantly; this one
is a deliberate rewrite and is rarer.

**The rules are plain string functions because the buffer is not testable.** An
`InputTransformation` receives a `TextFieldBuffer` that cannot be constructed from a test, so
testing the transformation directly would mean testing nothing. Extracting the rule into a
function over `String` puts the behaviour where a test can reach it, and the transformation
becomes a one-line adapter.

**The detekt rule is the enforcement mechanism.** A migration that is only a migration is undone
by the next feature. The rule names the shape and explains the alternative in its message. It
exempts test sources, and nothing else: the GenUI renderer's exemption was removed together
with its migration, so a value-based field there is a finding like anywhere else.

## Consequences

- 51 value-based call sites in `shared/src/commonMain` were migrated, and the GenUI renderer —
  the one documented exception — was migrated after them. No value-based text field is left in
  that tree, and the detekt rule no longer exempts a path.
- Two shared helpers were added:
  `shared/src/commonMain/kotlin/com/singularity/todo/core/ui/components/TextFieldStateBridge.kt`
  and
  `shared/src/commonMain/kotlin/com/singularity/todo/core/ui/components/TextFieldTransformations.kt`.
- Dead code was deleted rather than migrated: `MagicLinkFormState.kt` and
  `ServerConfigFormState.kt` in `feature/auth/`, and
  `shared/src/commonMain/kotlin/com/singularity/todo/core/ui/detail/InlineTextField.kt`.
- `LoginFormState` no longer carries the email or the password. The fields own their text. One
  consequence beyond the migration itself: the plaintext password is no longer part of the
  saved-instance-state bundle.
- `ChatInputBar` now takes a `TextFieldState` rather than a value and a change callback.
- **A field no longer loses text that the ViewModel's state forgot.** The value-based field
  rendered exactly the string it was handed, so a ViewModel state that replaced the whole state
  object took the typed text with it. The projects list is the case in point: its ViewModel
  replaces its state with `Empty` when the filter matches nothing, and `Empty` carried no
  query, so the search field lost the query that had just been typed. The state-based field keeps
  its own text and the query survives in the field. The defect in the projects ViewModel — the
  `Empty` state carrying no query — was a defect of the projects list and was not fixed by this
  migration; what the migration removes is the field's part in it. It was fixed afterwards, in
  `2026-10-19-text-field-bridge-guards-and-field-shapes`: `ProjectsUiState` now carries the
  query and the sort order on every variant, so the screen hands the search field the query it
  is actually filtering by.
- The `readOnly` / `onValueChange` exemption in `NoEmptyOnClickLambdaRule` was removed. It
  existed because Material3 still required an `onValueChange` parameter on a read-only field;
  the state-based overload does not, so the exemption no longer has a live reason to exist.
- **The pure predicates in `TextFieldTransformations.kt` and the two guard predicates in
  `TextFieldStateBridge.kt` are unit-tested.**
  `shared/src/commonTest/kotlin/com/singularity/todo/core/ui/components/TextFieldTransformationsTest.kt`
  and `.../TextFieldStateBridgeTest.kt` cover the rules and the guards as pure functions, and
  `desktopApp/src/jvmTest/kotlin/com/singularity/todo/core/ui/components/TextFieldStateBridgeUiTest.kt`
  covers the bridge as a composed field: what the user typed shows up, an upstream rewrite
  reaches the field, a cleared field reaches the upstream, and the value the field pushed is not
  written back to it.
- **A field that reacts to an edit observes the field, it does not get a callback.**
  The value-based API handed every caller an `onValueChange`, and two screens used it to clear a
  stale error message when the user started typing. The state-based overload has no such
  parameter, so the behaviour was restored with `ObserveTextFieldEdits(state) { ... }` — a
  `snapshotFlow` over the field's text, skipping the first emission. Without it, "clear the
  error when the user starts typing" would have been lost in the migration, and nothing would
  have said so. The primitive now lives in its own file,
  `core/ui/components/TextFieldEdits.kt`, next to the `fieldTextFlow` it shares with the
  bridge.
- A caller that needs the value of a field now reads the field's state rather than a parameter
  it passed in. Where the value is needed outside the field, the bridged shape is the one to
  use.

## Links

- https://developer.android.com/develop/ui/compose/text/migrate-state-based — the platform
  guide this migration follows
- `shared/src/commonMain/kotlin/com/singularity/todo/core/ui/components/TextFieldStateBridge.kt`
  — the bridge and its two guard predicates
- `shared/src/commonMain/kotlin/com/singularity/todo/core/ui/components/TextFieldTransformations.kt`
  — the filtering and validation rules
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/genui/render/material3/input/TextFieldRenderer.kt`
  — the server-driven renderer, migrated once the bridge supplied the conflict rule
- `detekt-rules/src/main/kotlin/com/singularity/todo/detekt/NoValueBasedTextFieldRule.kt` —
  the rule that keeps the migration in place
- `openspec/changes/text-field-state-migration/` — the behavioural spec for the change
