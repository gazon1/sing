---
title: "The saved draft is the one the save left"
date: 2026-11-09
status: accepted
tags: ["ui", "draft"]
---

# The saved draft is the one the save left

## Context

`DraftMviViewModel.save()` persists the draft, moves the checkpoint, and then
calls the subclass's `onSaved()`. A subclass may adjust the draft in that
hook — `NoteEditor` clears its own dirty flag there, because an explicit save
stores the text, and the editor's back guard asks only while there is
unstored text.

Before this change only the autosave cleared that flag (`onAutosaved`). An
explicit save left it set, so a back press right after the Save button still
asked "Discard changes?" about text that was already on disk.

Clearing the flag in `onSaved` alone was not enough: the base had already
taken the checkpoint, so the adjusted draft no longer matched it and the base
answered "the draft differs from what is saved" — `isDirty` true for a draft
that had just been saved.

## Idea

The checkpoint is the base's answer to one question: is the draft different
from what is stored? A save that a subclass finished by adjusting the draft
has stored *that* draft, so the checkpoint belongs after the save's last word,
not before it.

## Decision

**`save()` takes the checkpoint twice.** Once before `clearAutosave()` and
`onSaved()`, as before, and once after `onSaved()` returns.

**A subclass that adjusts the draft in `onSaved` gets its adjustment
checkpointed.** The saved state is the state the save left, and `isDirty` is
false right after it.

## Rationale

**The first checkpoint stays first.** It is what makes a throwing hook safe:
if `clearAutosave()` or `onSaved()` throws, the checkpoint has already moved,
so the draft reads clean and the error is what the user is shown. Moving it
after the hooks would leave a saved-but-dirty draft behind every failure.

**The second checkpoint is the save's last word.** Nothing after `onSaved()`
can change the draft, so the checkpoint taken after it is the one the saved
state should be compared against.

**The alternative was rejected.** The flag could have been kept out of the
draft — but the flag *is* the draft's field, and the editor's guard reads it
from there. Weakening the base's dirty answer for one subclass would have
made the answer false for that subclass, which is the one thing the base's
answer must not be.

**The autosave path is untouched.** The checkpoint does not move on autosave:
`discard()` must revert to the last *explicitly saved* state, not to the last
autosaved one. The autosave clears the draft-local flag without moving the
checkpoint, and that asymmetry stays.

## Consequences

- A back press after an explicit save leaves the editor without asking, and a
  `discard()` after a save reverts to the state the save left.
- A subclass that adjusts the draft in `onSaved` no longer has to reason about
  the checkpoint: the base takes it for it.
- A save whose hooks throw still leaves a clean draft and a visible error, as
  before.
- The autosave keeps its silence about the checkpoint.

## Links

- `shared/src/commonMain/kotlin/com/singularity/todo/core/ui/DraftMviViewModel.kt` — `save()`
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/notes/presentation/viewmodel/NoteEditor.kt` — `onSaved()`
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/notes/presentation/screen/NoteEditorScreen.kt` — the back guard
- `shared/src/commonTest/kotlin/com/singularity/todo/feature/notes/presentation/viewmodel/NoteEditorTest.kt` — the save that clears the flag
