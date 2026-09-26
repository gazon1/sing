---
status: accepted
---

# Bugfixes in DraftMviViewModel and NoteEditor (post-MR-4 audit)

## Context

After completing MR-4 (NoteEditor → DraftMviViewModel), a code audit revealed several bugs
in the framework and its sole consumer at the time. All were found in `DraftMviViewModel`
and `NoteEditor`.

---

## Findings

### 1. `NoteEditor.save()` bypasses `persist()` — dead override

**Severity: High**

`NoteEditor` overrode `save()` completely rather than relying on the framework's
`persist()` + `onSaved()` pattern:

```kotlin
// BEFORE (broken — persist() never called, onSaved() never runs)
override fun save() {
    val currentDraft = draft
    val validationError = validate(currentDraft)
    if (validationError != null) {
        vmScope.launch { emit(NotesUiEvent.SaveFailed(validationError)) }
        return
    }
    vmScope.launch {
        try {
            repo.upsert(editingAsNote(currentDraft, readExisting = true))
            emit(NotesUiEvent.SavedPulse)
        } catch (e: Exception) {
            emit(NotesUiEvent.SaveFailed(e.message ?: "Save failed"))
        }
    }
}
```

`persist()` was never called, making ~15 lines of framework integration dead code.
The override also duplicated error handling logic already handled by `DraftMviViewModel.save()`.

**Fix:** Remove the override. Rely on `persist()` + `onSaved()`:
- `persist()` → `repo.upsert()` + `Either<AppError, Unit>`
- `onSaved()` → `emit(NotesUiEvent.SavedPulse)`

### 2. `NoteEditor` missing `onSaved()` — SavedPulse never emitted on framework save

**Severity: High**

Since `save()` was overridden, `onSaved()` was never called, and `SavedPulse` was only
emitted from the broken override. Any code path that called the framework's `save()`
(including the base class implementation) would NOT emit `SavedPulse`.

**Fix:** Add `onSaved()` override:
```kotlin
override suspend fun onSaved() {
    emit(NotesUiEvent.SavedPulse)
}
```

### 3. `NoteEditor` entity-switch uses `cachedNote` to avoid redundant DB read in `persist()`

**Severity: Medium**

The `persist()` method needs `createdAt` of the existing note to avoid overwriting it.
The original `editingAsNote(draft, readExisting = true)` re-read from DB on every explicit save.
A `cachedNote` field is now set when opening an editor, allowing `persist()` to use it
instead of an extra DB round-trip:

```kotlin
private var cachedNote: Note? = null

fun openEditor(noteId: String) {
    vmScope.launch {
        val note = repo.get(NoteId.fromString(noteId)) ?: return@launch
        cachedNote = note  // cache for persist() to preserve createdAt
        // ...
    }
}

private fun editingAsNote(draft: Editing): Note {
    val existing = cachedNote
    val now: Instant = Clock.now()
    return Note(
        // ...
        createdAt = existing?.createdAt ?: now,  // preserves original for existing notes
        updatedAt = now,
    )
}
```

### 4. `pendingAutosaveJob` dead code in `DraftMviViewModel`

**Severity: Low**

`DraftMviViewModel.open()` called `cancelPendingAutosave()`, and the field
`pendingAutosaveJob: Job?` existed, but the autosave debounce collector never assigned
to it. The job was always null, making `cancelPendingAutosave()` a no-op:

```kotlin
// BEFORE — set but never used in the debounce collector
private var pendingAutosaveJob: Job? = null

private fun cancelPendingAutosave() {
    pendingAutosaveJob?.cancel()  // always null — no-op
    pendingAutosaveJob = null
}

// Autosave collector — never assigns pendingAutosaveJob:
vmScope.launch {
    _draft.drop(1).debounce(autosaveDebounceMs).collect { current ->
        runCatching { autosave(current) }.onFailure { onAutosaveError(it) }
    }
}
```

**Fix:** Remove `pendingAutosaveJob`, `cancelPendingAutosave()`, and the call in `open()`.
The `open()` call to `cancelPendingAutosave()` was harmless (a no-op), so removing it has
no behavioral effect.

---

## Consequences

- `NoteEditor` now fully integrates with `DraftMviViewModel` instead of bypassing it
- Explicit save correctly emits `SavedPulse` through the `onSaved()` hook
- `createdAt` is preserved for existing notes via `cachedNote` in `persist()`
- `DraftMviViewModel` framework is cleaner — dead code removed

---

## Pattern for future DraftMviViewModel consumers

When implementing a new editor with `DraftMviViewModel`:

```kotlin
class MyEditor(...) : DraftMviViewModel<MyDraft, MyIntent, MyEvent>(
    autosave = { draft -> deps.draftStore.save(KEY, draft, MyDraft.serializer()) },
    restore = { deps.draftStore.load(KEY, MyDraft.serializer()) },
    logger = logger,
    scope = scope,
) {
    override fun validate(draft: MyDraft): String? = ...

    override suspend fun persist(draft: MyDraft): Either<AppError, Unit> =
        deps.save(draft)  // your use case

    override suspend fun onSaved() {
        emit(MyEvent.Saved)  // only for explicit save, NOT for silent autosave
    }
}
```

**Never override `save()`** — the framework handles it. Override `persist()` and `onSaved()`.
