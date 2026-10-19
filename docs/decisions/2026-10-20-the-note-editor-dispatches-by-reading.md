---
title: "The note editor dispatches by reading its state, and the save button flushes it"
date: 2026-10-20
status: accepted
tags: [ui, compose, notes]
---

## Context

The note editor's body is a rich-text editor, not a text field. The difference matters
for how the content reaches the ViewModel: a text field owns its text and reports
changes through a callback, while a `RichTextState` *contains* its text and reports
nothing. The editor screen therefore has to watch the state and push the changes
upstream itself.

It did not, and the body was never saved.

The dispatch was written as a `LaunchedEffect` keyed on the `RichTextState`:

```kotlin
LaunchedEffect(state.id, richTextState) {
    if (!session.isFirstLoadSkipped) {
        session.notifyFirstLoadSkipped()
        return@LaunchedEffect
    }
    session.dispatchHtml()
}
```

`RichTextState` is the same instance for the whole editing session and has identity
equality. The effect therefore started once, at the first composition, and never
restarted. On that single run it dispatched the *loaded* content, which the session's
own comparison discarded because it equalled the baseline. No keystroke ever reached
the ViewModel.

The user typed, the editor showed the text, and the note came back empty. Nothing on
the screen said so: the editor held the text, the save button reported success, and
the note had no body. The e2e job never caught it because the notes flows were not in
the smoke suite, and the desktop suite had no test that looked at the *note* — only at
the editor.

The code was identical on `main`. The bug was not a regression; it was a bug that had
always been there, in a feature the suite did not cover.

## Idea

Make the editor report its content by *reading* it, and make the save action flush the
editor before it persists. Then pin the whole chain with a test that looks at the note
rather than at the editor.

## Decision

**The dispatch reads the state; it is not keyed on it.** The effect observes the text
through `snapshotFlow`, which re-emits whenever what it reads changes:

```kotlin
LaunchedEffect(state.id, session) {
    snapshotFlow { session.richTextState.annotatedString }
        .map { session.richTextState.toHtml() }
        .distinctUntilChanged()
        .collect { session.dispatchHtml() }
}
```

**There is no positional "skip the first emission" guard.** The first emission *is* the
loaded content, and `dispatchHtml()` compares it against the baseline — the loaded
content normalised through `toHtml()` — so it dispatches nothing. The old
`firstLoadSkipped` flag existed to do the same job positionally, and it was wrong in
the way a positional guard always is: an edit made before the flow's first emission is
a real edit, and a `drop(1)` drops it along with the phantom. Comparing content
against a baseline distinguishes "nothing changed" from "something changed", and does
not have to guess which emission was the phantom.

**The save button flushes the editor before it saves.** The dispatch is asynchronous —
it is delivered on the next frame — so a save issued in the same frame as the last
keystroke would persist the *previous* body. The user typed, pressed save, and the
note came back without the text. `NoteEditorScreen` now calls `session.dispatchHtml()`
before `onSaveNow()`; the flush is a no-op when the dispatch has already happened,
because the session compares against the baseline it has already dispatched.

**The regression test asserts on the note, not on the editor.** A test that asserts the
editor shows the text passes on the broken code — the editor always showed the text.
`NoteEditorFieldFlowTest.the_body_typed_in_the_editor_reaches_the_note` types into the
body and then asserts the text reached the *note*, through the repository. It fails on
the old code with an empty body.

## Rationale

**Keying an effect on an object is a statement about when to *start*, not about what
to *watch*.** `LaunchedEffect(state.id, richTextState)` reads as "when the state
object changes, do X" — and the state object never changes. The intent was "when the
state's *contents* change, do X", and the only way to say that is to read the
contents. `snapshotFlow` is the tool for that: it re-reads on every change to what it
observed and re-emits.

**A comparison is a better guard than a position.** The phantom the old guard was
fighting — echoing the loaded content back as an edit — is exactly "the html is
unchanged". The content comparison answers that question directly and needs no
bookkeeping about which emission came first. It also cannot lose an edit, which the
positional guard could and did.

**A save must not depend on a race.** The flush makes the save deterministic: the
content the editor is showing is the content the save writes, whichever order the
dispatch and the click happen to arrive in. The alternative — relying on the debounced
autosave to eventually catch up — leaves the user with a save button that can save
the wrong thing.

**A test that asserts on the wrong side of the boundary is not a test.** The editor
showing the typed text is the *editor's* contract; the note carrying it is the
*product's*. The bug lived exactly in the gap between them, and the old test was on
the wrong side of it.

## Consequences

- `shared/src/commonMain/kotlin/com/singularity/todo/feature/notes/EditorSession.kt` —
  the session no longer carries the title (the title is a bridged text field now) and
  no longer carries a first-load flag; the baseline is the loaded content.
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/notes/presentation/screen/NoteEditorScreen.kt`
  — the save button flushes the editor before it saves.
- `desktopApp/src/jvmTest/kotlin/com/singularity/todo/feature/flows/notes/NoteEditorFieldFlowTest.kt`
  — the regression test. It waits on a *real* dispatcher: the autosave runs on the
  ViewModel's own background scope, and a suspending timeout on the test's virtual
  clock fires long before a real debounce window can elapse.
- `.agents/skills/singularity-todo-rich-editor/SKILL.md` — documented the old
  pattern, including the first-load guard, so it taught the bug. It now documents the
  pattern that works and says why the keyed effect and the positional guard are wrong.
- The `EditorToolbar` and the link dialogs keep calling `dispatchHtml()` directly:
  a formatting action changes the html without changing the text the flow observes
  in every case, so the toolbar still flushes explicitly.

## Links

- `docs/decisions/2026-10-19-text-field-state-migration.md` — the shape the title
  follows, and the reason the body cannot
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/notes/EditorSession.kt` —
  the session
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/notes/presentation/screen/NoteEditorScreen.kt` —
  the screen and the flush
- `desktopApp/src/jvmTest/kotlin/com/singularity/todo/feature/flows/notes/NoteEditorFieldFlowTest.kt` —
  the regression test
- `Maestro/flows/notes/01-edit-body.yaml` — the Android flow that survives a process
  restart
