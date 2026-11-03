---
name: singularity-todo-text-field-states
description: How to add or change a text field in Singularity Todo. Covers the three field shapes (local, bridged, anchored), the state-based API the detekt rule enforces, the shared bridge and its push/pull guards, InputTransformation filtering, reacting to an edit, secrets, and where each piece is tested.
---

# State-Based Text Fields

Every text field in this app is **state-based**: the field owns a `TextFieldState`, and the
ViewModel owns a plain `String`. The value-based shape (`value: String` + `onValueChange`) is
banned by the detekt rule `NoValueBasedTextField` — see
`detekt-rules/src/main/kotlin/com/singularity/todo/detekt/NoValueBasedTextFieldRule.kt`.

The decision this skill encodes is **who owns the text**. There are three answers, and
picking the wrong one is the defect this skill exists to prevent.

## 1. Pick the shape

| Shape | Who owns the text | Helper | When |
|---|---|---|---|
| **local** | the field itself | `rememberTextFieldState` (Compose's) | nothing outside the field reads or writes the text |
| **bridged** | the field *and* a ViewModel-owned string, in sync | `rememberBridgedTextFieldState(source, onTextChanged)` | the ViewModel must validate, persist or report the text |
| **anchored** | an outside value; the field only shows it | `rememberAnchoredTextFieldState(text)` | the field is a display of something else (a dropdown, a read-only value) |

**Bridged is the default.** If the ViewModel has to know the text, the field is bridged —
a local field whose text is read at submit time is a local field, and a local field whose
text the ViewModel needs *while the user types* is a bridged field that was written wrong.

**Anchored is read-only.** An anchored field follows the value it is anchored to, and the
value is never pushed anywhere. If the field is editable, it is not anchored.

**Local is for text that never leaves the field.** A filter in a sheet that is applied on
submit, a one-off search box whose result is read at the moment of use.

**The helpers live in `com.singularity.todo.core.ui.textfields`.** The bridged and the
anchored helper are there, together with the source types and the transformations. The
package is the reason the shape of a field can be *derived* rather than guessed: a file
that uses one of the helpers has to import it, so the import list is the compiler's own
record of the shape. A file in the helpers' own package could call one without an import
— which is why the package holds no fields.

### The procedure

The shape is not a taste. It follows from one question: **does anything outside the
field read or write the text?**

1. **Nothing does** → **local**. The field owns the text. A filter in a sheet that is
   applied on submit, a one-off search box whose result is read at the moment of use.
2. **Something does, and the field only shows what it is given** → **anchored**. The
   field is a display of something else — a dropdown, a read-only value — and the value
   is never pushed anywhere.
3. **Something does, and the field is editable** → **bridged**. A ViewModel that has to
   know the text *while the user types* gets a source, and the source is the answer to
   "who may rewrite this?".

The order matters. An anchored field that somebody also wants to write is not anchored
any more — it is bridged, because the person who wants to write it needs the value to
travel back and forth.

**The procedure has a mechanical trace, and the trace is checked.** A file that calls
the bridged helper has to import it, so the import list is the compiler's own record of
the shape: `TextFieldShapeConventionTest` reads that record, and
`TextFieldRewriteContractTest` reads the rewrite table in [`FIELDS.md`](FIELDS.md). So a
field whose shape was picked by taste fails the build — and the failure it used to cause
was the quiet one: the value dropped on the way in, and the screen stopped saving.

## 2. Write the field

```kotlin
// ── bridged ──────────────────────────────────────────────────────────────────
val state = rememberBridgedTextFieldState(
    source = viewModel.state.value.titleSource,          // what the ViewModel holds
    onTextChanged = { viewModel.onIntent(SettingsIntent.Title(it)) },  // where it goes
)
OutlinedTextField(state = state, label = { Text("Title") })

// ── local ────────────────────────────────────────────────────────────────────
val state = rememberTextFieldState()
OutlinedTextField(state = state)

// ── anchored ─────────────────────────────────────────────────────────────────
val state = rememberAnchoredTextFieldState(currentProvider)
OutlinedTextField(state = state, readOnly = true)
```

**A `TextFieldState` never enters a ViewModel or a `UiState`.** The ViewModel keeps a
`String`. The detekt rule `NoTextFieldStateInViewModel` enforces it — a `TextFieldState` is
a mutable, snapshot-backed holder, and putting one inside an immutable `UiState` creates a
second source of truth for the same value.

**The source is built by the owner, and the field passes it through.** A bridged field never
builds its own source: the source says *who may rewrite this field*, and only the owner knows
the answer. See the rewrite contract in [`FIELDS.md`](FIELDS.md).

**The source's text must be the whole value, not a fragment of it.** The bridge compares
the upstream's value with the field's own to decide who won, so an upstream that hands the
field a *different* string is a rewrite, not an update. This is why the projects list's
`Empty` state carries its query: an `Empty` that dropped the query would make the screen
pass an empty string to the field, and the field would answer with an empty string that
the ViewModel would read as "the user cleared the search" — while the ViewModel still
filtered by the old one. The list could never come back.

## 3. Filter at the point of entry

Filtering is an `InputTransformation`, not a guard in the change handler:

```kotlin
OutlinedTextField(state = state, inputTransformation = digitsOnlyTransformation())
```

The named transformations live in
`shared/src/commonMain/kotlin/com/singularity/todo/core/ui/textfields/TextFieldTransformations.kt`:

| Transformation | Rule | Checked |
|---|---|---|
| `digitsOnlyTransformation(maxDigits)` | drops non-digits, keeps at most `maxDigits` digits, rewrites the field with them | `drops=non-digit; limit=maxDigits; rewrite` |
| `maxLengthTransformation(maxLength)` | rejects the whole edit when the text grows past `maxLength` | `rejects=edit; limit=maxLength` |
| `recurrenceIntervalTransformation()` | drops non-digits, keeps at most two digits, rewrites the field with them, and rejects the edit unless the result is a whole number of at least one | `drops=non-digit; limit=2; rewrite; rejects=edit; min=1` |
| `dayOfMonthTransformation()` | drops non-digits, keeps at most two digits, rewrites the field with them, and rejects the edit unless the result is between 1 and 31 | `drops=non-digit; limit=2; rewrite; rejects=edit; range=1..31` |

**This table is checked.** `scripts/check-text-field-transformations-table.py`
reads it and compares it with `TextFieldTransformations.kt`: a transformation
the code declares and the table does not name fails the gate, a row that names
a transformation the code does not declare fails it, and the third column —
the facts the code enforces — is derived from the source and compared with what
the row claims. The second column is checked as far as a sentence can be: it
has to *mention* the fact (`drops`, `maxDigits`, "two", "31"), so a sentence
that stops saying what the code does fails too.

What the gate does not check: whether the rule is the right rule for the field
it is used in, the order in which the filter and the range check run, and
whether a transformation is used where this table implies. Those are
judgements; the rules themselves are tested in
`shared/src/commonTest/kotlin/com/singularity/todo/core/ui/textfields/TextFieldTransformationsTest.kt`.

**The rules are plain functions on `String`; the transformation is a thin adapter.** The
buffer an `InputTransformation` receives cannot be constructed from a test, so a rule that
lives inside the transformation cannot be tested at all. Put the decision in a function
over `String` (`filterDigits`, `isWithinLength`, …), test that, and let the transformation
apply it.

**Reject or drop, deliberately.** `digitsOnlyTransformation` *drops* a non-digit — the user
can type into the field without the keyboard fighting them. `maxLengthTransformation`
*rejects* the edit — the field snaps back to the last valid value, which is what the old
`onValueChange` guard did. The difference is visible to the user; pick on purpose.

## 4. Reacting to an edit

The state-based API has no `onValueChange`, so a screen that used to clear a stale error
when the user started typing observes the field instead:

```kotlin
ObserveTextFieldEdits(state) { viewModel.onIntent(SettingsIntent.ClearError) }
```

`ObserveTextFieldEdits` lives in
`shared/src/commonMain/kotlin/com/singularity/todo/core/ui/textfields/TextFieldEdits.kt`.
It **skips the first emission** — the field's seed is not an edit, and reacting to it would
clear an error the user has not touched yet.

## 5. Secrets

Passwords use Material3's `OutlinedSecureTextField` with `TextObfuscationMode.Hidden`. The
API key field toggles between `Hidden` and `Visible` on an explicit request. A secret is
never a plain `OutlinedTextField` with a masked `String` — the masking belongs to the field.

## 6. What the bridge guarantees, and what it does not

`rememberBridgedTextFieldState` pushes the field's text upstream and pulls an upstream
rewrite back into the field, with a guard on each direction:

- **Push** — only when the upstream does not already hold the value. A value the upstream
  holds is its own copy coming back around, and writing it again marks the field dirty for
  nothing. The field's *seed* is such an echo: it came from the upstream, so pushing it
  would write the upstream's own value back to it — and would wipe a loaded title.
- **Pull** — only when the upstream's value is neither the text the field currently holds
  nor a value the field pushed earlier. A **lagging** upstream (it still holds the text
  from before the last keystroke) must not overwrite the field: that is the live-lock the
  naive rule "overwrite whenever the upstream differs" produces, and it eats keystrokes.
  A value the field pushed and no longer holds is the lagging case; a value the field never
  pushed is a genuine rewrite and wins.

**The known trade-off, and the way out of it:** an upstream that *deliberately* rewrites the
field to a value the field pushed earlier is not applied. The two are indistinguishable from
the field's side, and the lagging case is the one that destroys work, so it wins. A rewrite
to a value the field never pushed always lands.

**The way out is the source.** The party that performs the rewrite says so *in the type of the
source it hands the field*:

- a **static** source (`StaticText`) — the upstream replaces the value only with one the field
  never produced, so the echo guard applies it as it is;
- an **owned** source (`OwnedText`) — the upstream owns the value and may replace it with
  anything, including a value the field itself produced. The source carries the count of the
  replacements, and the guard is lifted for the value that arrives with a new count.

The count is a property of the source, not a parameter of the bridge: a call site cannot
freeze it, and it cannot forget the question, because the source is required and its type
says what it means. **A caller that rewrites and hands the field a static source gets the
old behaviour, silently** — the owner has to build an owned source and advance the count.

The GenUI renderer is the reference implementation: the surface counts the *server's* writes
(`SurfaceController.serverRevision`) and the field's own writes are not counted, because
only the server rewrites the model.

**The last edit is flushed when the field leaves the composition.** The push is
asynchronous, so a user who types and navigates away in the same frame would leave the
upstream one character behind. The bridge pushes the field's final text on the way out —
but only when the upstream is *behind* (its value is one the field pushed). An upstream
that changed from the outside — a discarded draft, a cleared query — is ahead, and the
text on screen is the text that was discarded; pushing it back would resurrect it.

**The price of the flush is one character, in the rarest case, and it is accepted.** A
field whose upstream has received *no* push at all — the user typed one character and left
in the same frame — has an upstream value the field never pushed, so the flush declines and
that character is lost. The alternative would resurrect a discarded draft every time a
user cleared a field and navigated away, which is a far larger loss. The behaviour is
pinned by `flushDeclinesWhenTheUpstreamNeverReceivedAPush`; a change to it has to change
that test on purpose.

## 7. Testing

| What | Where |
|---|---|
| a filtering or validation rule | `shared/src/commonTest/kotlin/com/singularity/todo/core/ui/textfields/TextFieldTransformationsTest.kt` — pure functions |
| a push/pull guard | `shared/src/commonTest/kotlin/com/singularity/todo/core/ui/textfields/TextFieldStateBridgeTest.kt` — pure functions |
| the bridge as a composed field | `desktopApp/src/jvmTest/kotlin/com/singularity/todo/core/ui/textfields/TextFieldStateBridgeUiTest.kt` |
| a screen's own field | `desktopApp/src/jvmTest/kotlin/com/singularity/todo/feature/flows/` |

**`shared` has no Compose test capability.** Compose UI tests live in `desktopApp/src/jvmTest`.

### Asserting a field's text

Use `assertTagTextEquals(tag, expected)` from
`desktopApp/src/jvmTest/kotlin/com/singularity/todo/test/helpers/DesktopAssertions.kt`. Do
**not** use Compose's `assertTextEquals` directly:

> A state-based field keeps its value in `EditableText`, not in `Text`. Its decoration does
> carry `Text` — the label is composed whether or not the field holds anything, and so is
> the placeholder while the field is empty. Compose's `assertTextEquals` requires the node's
> *whole* text list to match, so it fails on a labelled field whose value is exactly right.
> `assertTagTextEquals` reads `EditableText` for an editable node and falls back to the
> plain text for everything else.

`awaitTagTextEquals(tag, expected)` is the waiting counterpart, for a field whose text is
changed by something other than the test's own input.

### The push is asynchronous

A value-based field reported every edit through a callback, in the same frame as the
keystroke. A state-based field's text is *observed* — the bridge reads it from a
`snapshotFlow` — so the ViewModel hears about an edit a beat later.

Nothing in the app can act on the stale value: a button's enabled state is derived from the
state that has not been updated yet, so it stays disabled until the edit lands. A **test**
that types and then asserts on the app's state has to let the composition settle first
(`waitForIdle()`, or the scope's own `settle()`).

## 8. The inventory and the rewrite contract

The per-field data — the shape of every text field in the app, the rewrite contract of
every bridged one, and the source types and write names that contract is written in —
is published in [`FIELDS.md`](FIELDS.md), beside this body. Read it when you need a
specific field; a field that is not in the inventory has not been classified yet, and
adding a field means adding a row there.

## 9. Traps

- **A field's text is not a parameter.** A caller that needs the value reads the field's
  state; it does not pass the value in and expect to get it back.
- **`rememberTextFieldState(initialText = …)` is a seed, not a binding.** Change the seed
  and the field does not follow — that is what the bridged and anchored helpers are for.
- **`TextFieldBuffer` is not a `CharSequence`** and its constructor is internal. Do not
  write a transformation that reads it as one; use `asCharSequence().toString()`.
- **`setTextAndPlaceCursorAtEnd` / `clearText` / `delete` are top-level extensions** and
  need an explicit import.
- **A field that is `enabled = false` cannot be typed into** — and a screen that disables a
  field while a request is in flight also has to decide what the field shows afterwards.
- **A field that holds its own content has no upstream to pull from.** The note editor's
  body lives inside the rich editor's state object, not in a string the screen can read
  back. A rewrite of the note reaches it only when the session is *re-seeded* — and
  without that, the next keystroke dispatches the old body over the new one. The session
  knows it must re-seed because the body's **own** count moved: the body is a
  `Revisioned<String>` like the title, and its count is what says "the content was
  replaced, take it". A body's count that moved because the *title* was rewritten says
  nothing about the content.
- **A field never builds its own source.** The source says who may rewrite the field, and
  only the owner knows. A field that constructs `StaticText(...)` or `OwnedText(...)` is
  answering a question it cannot know the answer to — there is no exception, not even for
  a surface, whose owner is a server the field does not own.
- **`OwnedText` without a moving count is a lie.** The type says the upstream may replace
  the value with anything; the count says it actually did. An owner that hands out
  `OwnedText(text, 0)` forever has claimed an authority it never uses.
- **A static owner that holds a plain `String` has lost the promise.** The static kind says
  the upstream replaces the value only with one the field never produced. A `String` in the
  owner's state cannot say whether a write was a seed, an echo, or a replacement, so the
  promise survives only as a sentence in a reviewer's head. Hold a `FieldOwnedText` and name
  every write: `seeded`, `echoed`, `cleared`.
- **A parameter of the sealed parent is a runtime question.** `TextSource` in a parameter
  means the receiver has to decide which kind it was given, and the decision is a `when`
  somewhere far from the place that knows. Split the component instead: one half takes an
  `OwnedText`, the other a `StaticText`, and the caller that holds the wrong kind does not
  compile.

## Related

- `docs/decisions/2026-10-19-text-field-state-migration.md` — the ADR this skill encodes
- `docs/decisions/2026-10-21-the-static-kind-is-a-guarantee.md` — why the static owner's
  value is a `FieldOwnedText` and not a `String`
- `docs/decisions/2026-10-21-the-kind-of-the-source-is-a-property-of-the-parameter.md` —
  why a component that serves two kinds of upstream is split
- `openspec/changes/archive/2026-10-25-text-field-state-migration/` — the behavioural spec
- `singularity-todo-compose-overview` — the router over the Compose skills
- `singularity-todo-desktop-compose-ui-tests` — the desktop harness and its conventions
- `singularity-todo-detekt-rules-authoring` — how the two text-field rules are written
