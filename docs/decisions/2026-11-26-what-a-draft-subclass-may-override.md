---
title: "Draft MVI: what a subclass may override"
date: 2026-11-26
status: accepted
tags: ["ui", "draft", "mvi"]
---

# Draft MVI: what a subclass may override

Answers #3231. Companion to
`2026-11-26-the-draft-keeps-one-value-and-one-snapshot.md`, which settles the
state ownership those seams sit on.

## Context

`DraftMviViewModel` exposed eleven `open` members. Some are real extension
points with a production consumer, some are the mutation path a subclass could
override to bypass the base class's own guarantees, and one was a documented
seam with no implementations at all. The audit asks which is which, and what to
close.

Evidence is the two production subclasses — `TaskCreateViewModel` and
`NoteEditor` — plus `TestDraftVm`, which stands in for "what a test can reach".

## Decision

Every audited member, its consumers, and its disposition. "Method calls" counts
only the draft editor's own code, not the base class.

| Member | Production overrides | Method calls | Disposition |
|---|---|---|---|
| `validate` | both | — | **Keep `abstract`** |
| `persist` | both | — | **Keep `abstract`** |
| `onSaved(saved)` | both | — | **Keep `protected open`** — now returns the draft the save is compared against |
| `onAutosaved` | `NoteEditor` only | — | **Keep `protected open`** |
| `onAutosaveError` | both | — | **Keep `protected open`** |
| `clearAutosave` | none in production | — | **Keep `protected open`** — see below |
| `updateDraft` | none | `TaskCreate` 22, `NoteEditor` 8 | **Close: `fun`** |
| `open` | none | `NoteEditor` 3 | **Close: `fun`** |
| `discard` | none | `NoteEditor` 1 | **Close: `fun`** |
| `dismissError` | none | both, 1 each | **Close: `fun`** |
| `launchDraftEffect` | none | `NoteEditor` 1 | **Close: `fun`** |
| `sameEntity` | none | base only | **Close: `private`** |
| `save` | none | both, 1 each | Already `fun` (2026-09-30) |

`TaskCreateViewModel.Discard` does not call `discard()` — it clears the draft
store through its own `clearDrafts()` and navigates away, so the base's revert
has nothing to revert for a create editor that never held a saved entity.

## Rationale

**Why the five mutation entry points are now `fun` rather than `protected
open`.** They are not merely unused as seams — overriding any of them skips
`pushUiState`, and the UI state's `draft` then describes a value the
`DraftState` no longer holds. That is the same bypass argument that made `save`
non-`open` in `2026-09-30-draft-save-failure-and-testtag-honesty.md`, applied to
the writers instead of the saver. Nothing lost access: the subclasses only ever
*called* these.

**Why the signature change is not a compatibility break.** `:shared` applies no
`maven-publish` plugin — only `:androidApp` and `:desktopApp` do, and they are
applications. So `DraftMviViewModel` is not a published binary surface: its
subclasses are the two in this repository, both updated here. If `:shared` is
ever published, this change is the kind that needs a deprecation bridge, and
`onSaved(saved)` entered at that point would be the baseline to keep.

**Why `onSaved` changed signature.** It used to take nothing and adjust the draft as a
side effect, and the base read the live draft afterwards to build the checkpoint. That
reading is unsound: the hook suspends, and a keystroke during that window is unsaved text
that the checkpoint then blessed. The hook now receives the persisted draft and returns
what the save should be compared against — the side effect without the ambiguity.

**Why `sameEntity` is now `private`.** It was `protected open` and documented as
the seam for "draft types that wrap an entity id". The repo has none: `TaskDraft`
carries its scope in the draft itself (so a different draft *is* a different
editor) and `Editing` carries the note id, both served by plain equality. It is
also unverifiable as a seam — `open()` consults it *before* the draft moves, so
a wrong override wedges the editor on the first entity and nothing distinguishes
that from "the draft did not change". Restore it when a draft type exists whose
two edited copies of one entity must not reset each other.

**Why `clearAutosave` stays even with no production override.** It is what makes
the base's autosave-is-a-safety-net contract expressible: an editor whose
autosave target differs from its real store must delete the autosave entry on a
successful save, or `restore` resurrects a saved draft next time. The two current
editors autosave into the same place they save to — `TaskCreate` clears its keys
from `onSaved` and `NoteEditor` never restores (`restore = { null }`) — so both
legitimately omit it. It is exercised by `TestDraftVm`, i.e. it is a tested
contract, not a guess.

**Why nothing else was removed.** The issue's out-of-scope note is right: LOC is
not the goal. Each remaining `open` has a production override or a documented
test, and the audit found no member whose removal would not cost a real
capability.

## Consequences

- A subclass can no longer silence or reroute the state publication; the only way
  it influences the visible state is through the six remaining seams.
- `sameEntity`'s KDoc now states what would bring it back, so the next editor
  with a different draft identity does not re-derive it from git history.
- No editor behaviour changed: all 52 subclass tests and the 34 base-class tests
  pass unchanged.
- `updateDraft`, `open`, `discard`, `dismissError` and `launchDraftEffect` remain
  public and callable — only their overridability is gone.

## What was not done

- `TaskDraftSlot.seed()` and the two `*DetailDraftState` classes are untouched.
  They are a different shape (a repository-rewrite seed with `Revisioned`
  values), not a copy of this framework.
- `onAutosaveError` is still `(Throwable) -> Unit`, not `(Exception) ->
  Unit`. Narrowing the type would break both subclasses' signatures for no
  behavioural gain, since the base only ever passes what
  `runCatchingCancellable` caught — that is, an `Exception`.

## Links

- `shared/src/commonMain/kotlin/com/singularity/todo/core/ui/DraftMviViewModel.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/tasks/presentation/viewmodel/TaskCreateViewModel.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/notes/presentation/viewmodel/NoteEditor.kt`
- `shared/src/commonTest/kotlin/com/singularity/todo/core/ui/DraftMviViewModelTest.kt`
- `docs/decisions/2026-09-30-draft-save-failure-and-testtag-honesty.md`
- `docs/decisions/2026-11-26-the-draft-keeps-one-value-and-one-snapshot.md`
