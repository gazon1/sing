# spec — split `ProjectDetailViewModel`

The change is a pure internal refactor. Nothing a user or a client can observe
may change. This document exists to make "nothing changes" checkable, and to name
the one real defect being fixed.

## Required behaviour

The refactor is correct if and only if all of the following hold.

### AC-01 — The emitted state is byte-identical

`ProjectDetailViewModel` must emit exactly the same
`ProjectDetailUiState.Loading | NotFound | Content` values, for the same inputs,
as before. `Content` carries tasks, `totalCount`, `completedCount`,
`childProjects`, `parent`, and the option lists (`parentOptions`,
`availableTasks`, `reminderOffsets`, `tagGroupOptions`).

The `take(5)` truncation of tasks and the ordering of the sort at
`ProjectDetailViewModel.kt:273-277` are part of this contract.

### AC-02 — One atomic snapshot, not several

The screen must continue to observe a **single** `Content` value, not a
composition of independently collected pieces.

This is not an accident of the current shape. `ProjectDetailViewModel.kt:100-105`
states the intent directly: the screen "observes one atomic snapshot instead of
collecting four flows independently". Splitting state across sub-ViewModels
would let the screen render a state assembled from two different emissions.

### AC-03 — The public contract is unchanged

`ProjectDetailContent` reads exactly `stateFlow`, `lastEditedAt`,
`draftState.state`, `clock`, `events`, and calls `onIntent`. Every one of these
must keep its current type and semantics after the refactor.

`ProjectDetailScreen.kt:19` obtains the VM with
`koinViewModel { parametersOf(projectId) }` and that must keep working.

### AC-04 — Reminder semantics are preserved exactly

- `fireAt = dueDate − offset` (`ProjectDetailViewModel.kt` reminder collector).
- A project with a due date and no reminder is **not** given one by the default;
  the default only preselects the picker.
- `null` removes the reminder.
- A project with no due date rejects `SetReminder`.

These four are already covered by six tests in `ProjectDetailViewModelTest` and
are the reason the reminder concern is being moved at all — they must keep
passing against the extracted collaborator.

### AC-05 — Task-list derivation is preserved exactly

Hidden-by-dependency filtering (`blockedIds`, `:135`), child-project
derivation, and hide-completed / hide-blocked filtering must produce the same
`visibleTasks`, `completedCount` and `totalCount` for the same inputs.

### AC-06 — The DI graph stays verifiable

`ProjectsDiModule.kt:59-75` binds the VM with a runtime `ProjectId` parameter
and `viewModel { (id: ProjectId) -> … }`. The binding must keep compiling under
`koin-compiler-plugin`. No `factory { }` for a ViewModel, no
`domainModule().toTypedArray()`.

### AC-07 — A declared-but-unread dependency is gone

`log: Logger` is declared at `ProjectDetailViewModel.kt:89` and referenced nowhere
in the file body. `ProjectsDiModule.kt:72` supplies it. After the change, neither
the parameter nor the DI argument remains.

## Non-goals

- No behavioural change of any kind. If a task appears to require one, stop.
- No change to `ProjectDetailDraftState`, which is already extracted.
- `UpdateInheritedTagGroups` writes both `tagGroupRepo.setInheritedForProject`
  and the project row. That dual write is **not** treated as a defect here; it is
  most likely deliberate, since inherited tag groups live in a join table. It is
  out of scope until someone confirms otherwise.
- No change to the screen, the state classes, the intent hierarchy, or Room.

## Known limitations

`model.md` and `verification.md` have not been written; this change has been
planned without running the full Engineering SDD pipeline. `AC-04` and `AC-05`
are pinned by existing tests in `ProjectDetailViewModelTest`, which is why the
plan can cite real test ids today. The remaining acceptance criteria are verified
by the existing suite continuing to pass unchanged.