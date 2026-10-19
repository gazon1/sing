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
| **bridged** | the field *and* a ViewModel-owned string, in sync | `rememberBridgedTextFieldState(externalText, onTextChanged)` | the ViewModel must validate, persist or report the text |
| **anchored** | an outside value; the field only shows it | `rememberAnchoredTextFieldState(text)` | the field is a display of something else (a dropdown, a read-only value) |

**Bridged is the default.** If the ViewModel has to know the text, the field is bridged —
a local field whose text is read at submit time is a local field, and a local field whose
text the ViewModel needs *while the user types* is a bridged field that was written wrong.

**Anchored is read-only.** An anchored field follows the value it is anchored to, and the
value is never pushed anywhere. If the field is editable, it is not anchored.

**Local is for text that never leaves the field.** A filter in a sheet that is applied on
submit, a one-off search box whose result is read at the moment of use.

## 2. Write the field

```kotlin
// ── bridged ──────────────────────────────────────────────────────────────────
val state = rememberBridgedTextFieldState(
    externalText = viewModel.state.value.title,          // what the ViewModel holds
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

**The `externalText` must be the whole value, not a fragment of it.** The bridge compares
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
`shared/src/commonMain/kotlin/com/singularity/todo/core/ui/components/TextFieldTransformations.kt`:

| Transformation | Rule |
|---|---|
| `digitsOnlyTransformation(maxDigits)` | drops non-digits, keeps at most `maxDigits` |
| `maxLengthTransformation(maxLength)` | rejects the whole edit when the text grows too long |
| `recurrenceIntervalTransformation()` | at most two digits, a whole number of at least one |
| `dayOfMonthTransformation()` | at most two digits, between 1 and 31 |

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
`shared/src/commonMain/kotlin/com/singularity/todo/core/ui/components/TextFieldEdits.kt`.
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

**The way out is the revision token.** `rememberBridgedTextFieldState` takes a third
parameter, `revision: Long = 0`. When the party that performs the rewrite advances it, the
guard is lifted for the value that arrives with the new token — a *declared* rewrite wins
over the keyboard. The token is a counter, not a value; any change to it means "this value
is a rewrite".

The default is `0`, so a field that is never rewritten behaves exactly as before. **A caller
that rewrites and forgets to advance the token gets the old behaviour, silently.** The
GenUI renderer is the reference implementation: the surface counts the *server's* writes
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
| a filtering or validation rule | `shared/src/commonTest/kotlin/com/singularity/todo/core/ui/components/TextFieldTransformationsTest.kt` — pure functions |
| a push/pull guard | `shared/src/commonTest/kotlin/com/singularity/todo/core/ui/components/TextFieldStateBridgeTest.kt` — pure functions |
| the bridge as a composed field | `desktopApp/src/jvmTest/kotlin/com/singularity/todo/core/ui/components/TextFieldStateBridgeUiTest.kt` |
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

## 8. The inventory: every field in the app

Who owns the text is the decision; this is where each field's answer lives. A field that
is not in this table has not been classified yet — classify it before you touch it.

**The table is checked.** `TextFieldShapeConventionTest`
(`shared/src/jvmTest/.../arch/TextFieldShapeConventionTest.kt`) compares it with the
code: a file that owns a field and is missing from this table fails the build, a row
whose shape does not match the helper the file calls fails the build, and a row whose
file no longer exists fails the build. Adding a field means adding a row here.

Paths are relative to `shared/src/commonMain/kotlin/com/singularity/todo/`.

| File | Shape | Field |
|---|---|---|
| `core/ui/components/ChatInputBar.kt` | local | the input bar's state — the bar takes the state, the screen creates it |
| `feature/agenda/presentation/screen/SavedAgendaContent.kt` | bridged | the saved view's name |
| `feature/agenda/presentation/screen/SavedAgendaScreen.kt` | local | the regexp sheet's pattern |
| `feature/ai/chat/ChatScreen.kt` | local, bridged | the chat input (bridged) and the `ChatInputBar` states (local) |
| `feature/attachments/annotation/AttachmentAnnotationPanel.kt` | bridged | start, end, quote, note |
| `feature/attachments/components/AttachmentsSheet.kt` | local | the attachment's caption |
| `feature/auth/AuthFormFields.kt` | local | the shared email and password fields |
| `feature/auth/LoginScreen.kt` | local | the email and password fields |
| `feature/auth/presentation/AttachAccountScreen.kt` | local | the email field |
| `feature/auth/presentation/MagicLinkConfirmScreen.kt` | local | the code field |
| `feature/checklist/components/ChecklistEditorSheet.kt` | local | the item's text |
| `feature/genui/render/material3/input/TextFieldRenderer.kt` | bridged | the GenUI input — the server can rewrite it (see the revision token) |
| `feature/notes/components/InternalLinkPickerSheet.kt` | local | the link's text |
| `feature/notes/presentation/screen/LinkUrlDialog.kt` | local | the URL |
| `feature/notes/presentation/screen/NoteEditorScreen.kt` | bridged | the title — the AI rewrites it |
| `feature/notes/presentation/screen/NotesListScreen.kt` | local, bridged | the quick-add row (local) and the search query (bridged) |
| `feature/profile/ProfileSwitcherScreen.kt` | local | the profile's name |
| `feature/profile/presentation/ChangePasswordScreen.kt` | local | the password field |
| `feature/projects/presentation/screen/ProjectDetailBody.kt` | local | the two fields of the body |
| `feature/projects/presentation/screen/ProjectDetailHero.kt` | bridged | the name, the description |
| `feature/projects/presentation/screen/ProjectEditorScreen.kt` | bridged | the name, the description |
| `feature/projects/presentation/screen/ProjectsScreen.kt` | bridged | the search query |
| `feature/search/SearchScreen.kt` | bridged | the query |
| `feature/search/presentation/SaveSearchDialog.kt` | local | the saved search's name, its query |
| `feature/search/presentation/SavedSearchesRow.kt` | anchored | the rename field of a saved search |
| `feature/settings/screens/AiProviderSettingsScreen.kt` | local, bridged, anchored | the API key (local), the base URL (bridged), the provider and the model (anchored) |
| `feature/settings/screens/WorkScheduleSettingsScreen.kt` | anchored | the time |
| `feature/tags/components/AddTagDialog.kt` | local | the tag's name |
| `feature/tasks/presentation/components/detail/RecurrencePickerSheet.kt` | anchored | the interval amount, the day of month |
| `feature/tasks/presentation/components/detail/SubtasksSection.kt` | local | the new subtask |
| `feature/tasks/presentation/components/detail/TaskDescriptionField.kt` | bridged | the description |
| `feature/tasks/presentation/components/detail/TaskDetailProposalSection.kt` | local | the proposal's text |
| `feature/tasks/presentation/components/detail/TaskEditorEstimateSheet.kt` | local | the estimate |
| `feature/tasks/presentation/components/detail/TaskTitleRow.kt` | bridged | the title |
| `feature/timetracking/presentation/components/TimeEntryEditorSheet.kt` | local | the note |

A local field is still *seeded* from a parameter — the rename dialog's current name, the
regexp sheet's pattern, the tag dialog's name. That is safe only because those
composables are created fresh each time they open (`if (showDialog) { … }`, or a `let` on
a nullable). A field that lives as long as the list does must not be seeded: the
rename field of a saved search is the worked example, and it is anchored for that reason.

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

## Related

- `docs/decisions/2026-10-19-text-field-state-migration.md` — the ADR this skill encodes
- `openspec/changes/text-field-state-migration/` — the behavioural spec
- `singularity-todo-compose-overview` — the router over the Compose skills
- `singularity-todo-desktop-compose-ui-tests` — the desktop harness and its conventions
- `singularity-todo-detekt-rules-authoring` — how the two text-field rules are written
