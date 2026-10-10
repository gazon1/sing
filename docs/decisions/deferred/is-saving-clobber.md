---
title: "Is Saving Clobber"
date: 2000-01-01
status: CLOSED
tags: ["deferred"]
---

**Status (re-verified 2026-10-04):** CLOSED and verified 2026-10-04. `SavedAgendaViewModel.emitEditingState()` carries `existingIsSaving` forward off the replaced state, and `onSave()` returns early on `current.isSaving`. Pinned by `SavedAgendaViewModelTest.anEditDuringAnInFlightSaveDoesNotReEnableTheSaveButton` against the `upsertCount`/`upsertGate` fakes.

**Found in:** MR-0, кодовая разведка `SavedAgendaViewModel.emitEditingState()`.

**Symptom:** `onIntent(NameChanged)` вызывает `emitEditingState()`, который делает `setState(Editing(..., isSaving = current.isSaving))`. Если `NameChanged` приходит во время in-flight `save` (пока `isSaving = true`), новый state перезаписывает `isSaving` в `false` — кнопка Save снова enabled, пользователь может нажать повторно и создать дубликат.

**Status: RESOLVED** (2026-10-04, with a pin test). The symptom above describes
the guard as *absent*; the code already had both halves, and what was missing
was anything proving it:

- `SavedAgendaViewModel.emitEditingState()` (`:234`) reads `isSaving` off the
  state it replaces and carries it forward — so no draft intent can clear it.
- `onSave()` (`:247`) returns early on `current.isSaving`.

Neither was pinned, and a map-backed fake cannot pin it either way: a second
`upsert` of the same row leaves the store byte-identical, so the naive
assertion passes whether the guard exists or not. `FakeSavedAgendaViewsRepository`
gained an `upsertCount` counter and an `upsertGate` hook to hold a write open,
and `SavedAgendaViewModelTest.anEditDuringAnInFlightSaveDoesNotReEnableTheSaveButton`
parks a save inside the repository, fires a `NameChanged` at it, and asserts
`isSaving` is still `true` and `upsertCount == 1`.

Teeth verified 2026-10-04: reverting `isSaving = existingIsSaving` to a
literal `false` turns the test red, which is the only way to know the test is
about the guard rather than about the fake.

---
