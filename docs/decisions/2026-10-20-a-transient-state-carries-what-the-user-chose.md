---
title: "A transient state carries what the user chose, and the compiler says so"
date: 2026-10-20
status: accepted
tags: [ui, viewmodel, detekt, testing]
---

## Context

ADR `2026-10-19-text-field-bridge-guards-and-field-shapes` moved the projects list's
search query and sort order onto the `ProjectsUiState` interface, because a state that
describes a filtered list but drops the filter cannot be rendered correctly and cannot
be fed back to the field that owns the filter.

That fix was made in one place, by hand. The same shape of defect existed in the notes
list, where `NotesListState` held the filter, the sort order and the query — and where
the error handler emitted a *fresh* `NotesListState()`, which reset all three. A user
who searched for "groceries", filtered to Pinned, sorted by title, and then hit a
repository failure landed on an error screen that had quietly thrown all three choices
away, and a list that was no longer filtered at all.

Both defects are the same defect: a *transient* variant of a state — `Loading`,
`Empty`, `Error` — is not the state the user is looking at, and it was allowed to be
built without the things the user chose. Nothing made that a mistake. The
constructor took a message and a list, and the continuity fields were simply not part
of it.

## Idea

Make the compiler the enforcement. A state that owns a choice must *declare* that
choice, in every variant, with no default — so that a variant that forgets it does not
compile.

## Decision

**The continuity fields live on the state interface, and every variant declares them as
a constructor parameter without a default.**

```kotlin
sealed interface NotesUiState {
    val filter: NoteFilter
    val sortOrder: NoteSortOrder
    val searchQuery: String

    data class Loading(
        override val filter: NoteFilter,
        override val sortOrder: NoteSortOrder,
        override val searchQuery: String,
    ) : NotesUiState
    // …Content, Error: the same three
}
```

No defaults, on purpose. A default is a hole in the contract: `Error(message = "…")`
would compile, and the error would carry `NoteFilter.All` — a value nobody chose. With
no default, the compiler asks the question at every construction site, and the answer
is in the code.

**The ViewModel carries the current values into the failure.** The notes list's error
handler used to emit `Content(NotesListState())` — an error that *looked* like success.
It now emits `Error(message = …, filter = …, sortOrder = …, searchQuery = …)`, reading
the current values from the same flows that produced them.

**The query carried on the error path is the raw keystroke value, not the debounced
one.** The list is filtered by the debounced query; the *field* shows the raw one. A
handover of the debounced value would wipe the characters the user had typed but that
the debounce had not yet applied — a different, quieter version of the same defect.

**A detekt rule, `NoDroppedUiStateProperty`, enforces the same shape mechanically.**
The compiler catches a variant that forgets the field. It cannot catch a variant that
declares the field as a *constant in the class body* (`override val filter get() =
NoteFilter.All`) — that compiles, and it is exactly the defect, frozen into the type.
The rule reports three shapes of the same mistake:

- the property is not declared at all;
- it is declared in the class body instead of the constructor;
- it is declared as a constructor parameter *with a default*.

The rule's scope is a **sealed interface named `*UiState`**. A sealed interface with
another name, or an open one, is not checked. That is a known limit, and it is a limit
of the rule, not a licence: the rule names the convention, and the convention says what
a UI state is called.

**The projects list follows the same shape**, and the notes list joins it. Both states
now declare their continuity fields on the interface, and every variant — `Loading`,
`Empty`, `Content`, `Error` — carries them.

## Rationale

**The compiler is the only enforcement that cannot be forgotten.** A rule can be
skipped with a suppression; a required constructor parameter cannot be skipped at all
without changing the type. The detekt rule exists for the cases the compiler cannot
see, not as the primary defence.

**A default value is a lie the type tells.** `Error(message = "…")` compiles, and the
error carries a filter nobody chose. The type says "this state describes a list
filtered by X"; the default says "…unless you forget".

**The transient variants are where the choices are lost.** A `Content` state is built
from the data, so it has the filter it was produced with. `Loading`, `Empty` and
`Error` are built from *an event*, and an event has no opinion about the filter. That
is precisely why they have to be given one.

**The rule is the enforcement.** The same argument that produced
`NoValueBasedTextField` and `NoTextFieldStateInViewModel` produces this one: a decision
that only exists in an ADR is undone by the next feature. A decision that only exists
in a constructor signature is not.

## Consequences

- `shared/src/commonMain/kotlin/com/singularity/todo/feature/notes/Ids.kt` —
  `NotesListState` keeps the *list*; `filter`, `sortOrder` and `searchQuery` move to
  the `NotesUiState` interface, and every variant declares them without a default.
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/notes/presentation/viewmodel/NotesListViewModel.kt`
  — the initial state, the content state and the error state all carry the current
  values; the error handler stops emitting an empty content state.
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/notes/presentation/screen/NotesListScreen.kt`
  — binds the three values from the interface; the `?: NoteFilter.All` fallbacks are
  gone, because the interface makes the fallback impossible.
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/projects/presentation/state/ProjectsUiState.kt`
  — the same shape, for the same reason.
- `detekt-rules/src/main/kotlin/com/singularity/todo/detekt/NoDroppedUiStatePropertyRule.kt`
  — new: the rule, with its own tests.
- `shared/src/jvmTest/kotlin/com/singularity/todo/feature/notes/NotesListViewModelTest.kt`
  — a test that a repository failure keeps the query, the filter and the sort order.
- `shared/src/jvmTest/kotlin/com/singularity/todo/feature/projects/ProjectsViewModelTest.kt`
  — the same for the projects list.
- **The states are breaking changes for anything that constructs them.** `Loading` is a
  data class in both, not a data object; the construction sites are the ViewModel, the
  screen's previews and the tests, all updated here.
- The rule only sees sealed interfaces named `*UiState`. A state that is sealed and
  named differently is not checked — the convention is a name, and a name can be
  wrong.

## Links

- `docs/decisions/2026-10-19-text-field-bridge-guards-and-field-shapes.md` — the ADR
  that moved the projects list's query onto the interface
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/notes/Ids.kt` — the state
- `detekt-rules/src/main/kotlin/com/singularity/todo/detekt/NoDroppedUiStatePropertyRule.kt`
  — the rule
