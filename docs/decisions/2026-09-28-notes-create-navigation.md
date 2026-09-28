---
title: "A created note is opened on an id the repository never used"
date: 2026-09-28
tags: [notes, viewmodel, navigation, mvi, bug]
status: accepted
epic: refactor/tech-debt-roadmap-v3
---

## Context

`NotesRepository.createNoteWithTitle(title): Result<NoteId>` generates its own id and
returns it. `NotesListViewModel` did not use that result:

```kotlin
fun createNoteWithTitle(title: String): String {
    val id = NoteId(idGen.next())          // id A
    scope.launch {
        repo.createNoteWithTitle(title)    // creates id B; Result discarded
    }
    return id.value                         // returns A
}
```

`NotesListScreen` consumed it in two places, both of which navigated immediately:

```kotlin
QuickAddRow(onSubmit = { title ->
    val id = onCreateNote(title)
    navigator.openEditor(id)
})
// and
FilledTonalButton(onClick = {
    val id = onCreateNote("")
    navigator.openEditor(id)
})
```

So both the quick-add row and the empty-state **"Create your first note"** button opened
the editor for a note that does not exist, while the note the repository did create was
orphaned. The user's first note was lost.

The bug survived because nothing asserted the relationship. A test that verified
"calling create produces a note" passes; the id mismatch is invisible unless the returned
id is compared against the persisted one.

## Decision

`NotesUiEvent.NavigateToEditor(noteId)` was added, following the existing
`CalendarUiEvent.NavigateToTask` shape rather than inventing a new mechanism. The
ViewModel emits it with the repository's id; the screen collects events and navigates.

```kotlin
fun createNoteWithTitle(title: String) {
    scope.launch {
        repo.createNoteWithTitle(title)
            .onSuccess { emit(NotesUiEvent.NavigateToEditor(it)) }
            .onFailure { emit(NotesUiEvent.Error(it.toMessage("Create note failed"))) }
    }
}
```

Two things follow from the shape. The method returns `Unit`, because the write is
launched and there is nothing to return. And a failure no longer navigates — before, a
failed create still produced an id and still opened an editor.

`idGen` became unused in this ViewModel and was removed from the constructor, the Koin
binding and the test, rather than left as speculative API.

## Why an event and not a suspend call

Three options were available: make `onCreateNote` a suspend call awaiting the
`Result<NoteId>`; have the ViewModel own the id and pass it to the repository; or carry
the real id through the event bus.

The event was chosen because the project **already had the pattern**.
`CalendarUiEvent.NavigateToTask` and `CalendarScreen`'s `LaunchedEffect` do exactly this,
so adopting it was cheaper than introducing a second mechanism and left the screen's
navigation in one place. It also keeps the screen free of coroutine plumbing for a
one-shot side effect, which is what the `MviEvent` / `EventBus` split already exists for.

The id-ownership option was rejected because `createNoteWithTitle` is one of several
repository creates; making this single path take an id while the rest generate internally
would make the contract inconsistent for no gain.

## Verification

The test was written before the fix and **confirmed to fail against the old
implementation** — it compared the surfaced id against the persisted one:

```
expected: <[test-1]> but was: <[01M3KVWWAD0J6CK8W3HP6G719D]>
```

`test-1` came from the ViewModel's `SequenceIdGenerator`; the ULID came from the
repository. After the fix the test passes, and removing the `emit` alone makes it fail
again.

## Consequences

- **A ViewModel that surfaces a created entity's id must surface the one the write used.**
  A generated id that no repository call ever saw is worse than no id, because the screen
  navigates to it.
- **A create that navigates should carry the navigation in the result path**, so a
  failure surfaces as an error rather than an editor over nothing.
- `NotesIntent.CreateNote` has no dispatcher anywhere in the tree — the screen calls the
  ViewModel method directly. It routes to the same method, so it is harmless, but it is
  dead and could be removed.
- `NotesUiEvent` is shared between the list and editor ViewModels, so adding a variant
  required a branch in `NoteEditorScreen.toNotification()`. The compiler enforced it,
  which is the right failure mode.

## Links

- `2026-09-28-mr5-vm-hygiene` — the retro that found this while removing a hardcoded
  dispatcher
- `2026-09-27-feature-slot-pattern` · `2026-09-28-mr4-combine-soundness` — the other
  places a projection was made to do more than it should
