---
title: "MR-5 retro — R7 was closed twice on a grep, and a phantom note id was hiding in plain sight"
date: 2026-09-28
tags: [retro, tech-debt, viewmodel, coroutines, notes]
status: accepted
epic: refactor/tech-debt-roadmap-v3
---

Retro after MR-5 of the tech-debt roadmap (`mr-5-vm-hygiene`). The verification that
preceded it is in `2026-09-28-mr5-verification.md`; this records what shipped and what the
work turned up.

## Inventory

| Metric | Value |
|---|---|
| Production files changed | 8 (6 slot files, `NotesListViewModel`, 1 test) |
| Deprecated `UpdateTaskUseCase.invoke(task)` call sites | 7 → **0** |
| Unreachable `else -> Unit` branches | 7 → 0 |
| `repeat(3)` settling loops | 15 → 0 |
| Production `Dispatchers.Unconfined` in VMs | 3 → 0 |
| `:shared:jvmTest` | 1140 tests, 0 failed |
| detekt (shared) | 0 findings |
| Production compile warnings | 81 → 67 |

## CRITICAL — a note id that never existed

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

`NotesListScreen.kt` consumes it on the empty state:

```kotlin
onClick = {
    val id = onCreateNote("")
    navigator.openEditor(id)               // opens id A — which was never created
}
```

**Tapping "Create your first note" opens the editor for a note that does not exist.** The
note the repository did create is orphaned, and the user's first note is lost. The
quick-add row was affected the same way.

**Fixed.** The write is launched, so there is nothing to return synchronously and
nothing correct to generate locally. Following the existing
`CalendarUiEvent.NavigateToTask` shape, `NotesUiEvent.NavigateToEditor(noteId)` was
added; the ViewModel emits it with the repository's id and the screen navigates from
the event. Failures now surface as `NotesUiEvent.Error` rather than opening an editor
for a note that was never created. `idGen`, now unused in the ViewModel, is removed
from the constructor, the Koin binding and the test rather than left as speculative
API. See `2026-09-28-notes-create-navigation.md`.

The bug is confined to this call site: `TemplatePickerTest` and
`RoomNotesRepositorySyncTest` both use the repository's returned id correctly. Only the
ViewModel discards it.

The chosen fix is the third option above — deliver the real id through the event bus —
because the project already had the pattern in `CalendarUiEvent.NavigateToTask`, so
adopting it was cheaper than inventing a suspend-based screen API. The invariant it
records: **a ViewModel that surfaces a created entity's id must surface the one the
write used**, and a generated id that no repository call ever saw is worse than no id
at all.

## R7 — closed twice, on a grep

`UpdateTaskUseCase.invoke(task)` was recorded as open in
`2026-09-28-mr2-retro-findings` (R7), then closed in two places on the claim that a clean
`:shared:compileKotlinJvm` emits no warning for it.

There were **7 call sites across 5 slot files** still on the deprecated form:
`TaskDraftSlot` (×2), `TaskAiSlot` (×2), `TaskEntitySlot`, `TaskCompletionSlot`,
`TaskChildrenSlot`. All now call `invoke(id) { copy(...) }`, which re-reads through
`repo.get(id)` before transforming — the stale-snapshot write R7 described cannot happen
any more.

The reason the closure was wrong is the part worth keeping. The check was

```bash
grep -rn "updateTask\.invoke" …    # matches invoke(id) { … } — the *replacement*
```

The offending sites are `deps.updateTask(task.copy(…))` — single-argument, no `.invoke` —
so the grep matched almost nothing, and the compile warnings were never looked at. Both
closures were mine.

**Rule:** a compile warning is the evidence. A grep for the identifier you *expect* proves
only that the expectation holds.

## NotesListViewModel — the plan's fix was rejected

The plan proposed injecting a `CoroutineDispatcher` defaulting to `Dispatchers.Main`. It
was not done, for reasons in the verification ADR: `Dispatchers.Main` appears nowhere in
production today, and `Unconfined` runs inline while `Main` always posts — a behavioural
change on a path whose synchronous return value turned out to be wrong anyway.

What shipped is the smaller change: the three `Dispatchers.Unconfined` launches became
`scope.launch { }`, so all four launches in the file use the injected scope, and the file
no longer contradicts the `AGENTS.md` rule that production ViewModels do not hardcode a
dispatcher. Nothing enforces that rule mechanically; a detekt check would be worth adding.

## Rules

- **A ViewModel that returns an entity id must return the id the write used.** Generating
  one locally and discarding the repository's result is a silent data-loss bug that
  compiles, passes tests, and only shows up as an empty editor.
- **Read the compile warnings before closing a finding.** Two closures here rested on a
  grep that matched the replacement form.
- **A defensive `else` in an exhaustive `when` hides the compiler.** Removing the seven
  makes a future intent variant fail at the slot that must handle it.

## Open

- 12 deprecated Nav2-era `AppDestination` variants, still referenced (2–5 call sites
  each) — a migration, not a deletion.
- 7 `kotlinx.datetime` warnings in `CalendarEventMapper`, entangled with the deferred R26
  `Instant` migration; doing them means doing that.

## Links

- `2026-09-28-mr5-verification` — the check that caught all of this before implementation
- `2026-09-28-mr2-retro-findings` — R7, reopened and now closed for real
- `2026-09-28-roadmap-status` — consolidated done/remaining list
- `2026-09-25-remaining-test-debt` — O1, now closed
