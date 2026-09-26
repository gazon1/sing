---
title: "NoteEditor — extract state holders, save controller, AI controller"
date: 2026-09-22
tags: [notes, architecture, refactor]
status: accepted
---

## Context

`NoteEditor.kt` (180 lines) had five distinct responsibilities mixed into one `ViewModel`:
title/body state management, autosave scheduling, AI improve, link-search, and cleanup. A single
`MutableStateFlow<EditorState>` carried `isDirty`/`isNew` flags alongside the domain state, and the
`save()` method inlined HTML↔Markdown conversion, link extraction, and create-vs-update dispatch.
The class was `open`, used `Dispatchers.IO` in every `scope.launch` (breaking test virtual time),
and accepted `ImproveNoteUseCase?` directly — coupling the domain layer to the Koog AI library.

The user requested grouping common parts, removing duplication, and extracting `NoteAiController`
(Step 8) with create-on-first-save (Step 7).

## Idea

Three alternatives were considered:

1. **Status quo** — keep all logic in `NoteEditor`. Maintenance burden grows with each new feature.
2. **UseCases for every operation** — `SaveNoteUseCase`, `ConvertHtmlUseCase`, etc. Per
   `AGENTS.md`: pass-through CRUD use cases are banned. Also adds 4+ new classes for marginal benefit.
3. **Focused helper objects** — `NoteEditorState` (state holder), `NoteSaver` (save orchestration),
   `NoteContentMapper` (pure HTML↔Markdown), `NoteAiController` (AI with lambda decoupling).
   Single responsibility, each is independently testable.

## Decision

Extract four focused helpers from `NoteEditor`:

- **`NoteEditorState`** — wraps `MutableStateFlow<EditorState>`, exposes `updateTitle`,
  `updateHtml`, `applyImprove`, `markSaved`, `clear`. Holds `isDirty`/`isNew` tracking.
- **`NoteSaver`** — `suspend fun save(id, title, html, isNew): Result<Unit>`. Dispatches to
  `repo.createWithContent` or `repo.updateContent` based on `isNew`, writes outgoing links,
  emits `SavedPulse` on success, logs and emits `SaveFailed` on any error. Single error path.
- **`NoteContentMapper`** — pure `internal object`: `toHtml(markdown)`, `toMarkdown(html)`,
  `outgoingLinkUrls(html)`. No side effects.
- **`NoteAiController`** — accepts `(suspend (String, String) -> Result<NoteAiResult.Improved>)?`.
  Decoupled from the AI library: tests pass a `null` or a lambda stub without pulling in Koog.

`createNote()` no longer calls `repo.createWithContent`. Instead it opens `EditorState.Editing(isNew=true)`
and the first successful `save()` triggers `repo.createWithContent`. Navigation can obtain the id
immediately for back-stack management.

`NoteEditor` remains `class` (not `open`), uses `scope.launch { }` without `Dispatchers.IO`,
receives `ai: NoteAiController` and `log: Logger` via constructor, and delegates all state
mutation to `NoteEditorState`.

## Rationale

`NoteSaver` is not a pass-through use case — it contains real orchestration (HTML→markdown conversion,
link extraction, two repo calls, error routing). Keeping it as an inner class or inline would
duplicate this logic. Extracting it makes the single error path explicit and independently testable.

Lambda-based AI decoupling (`(suspend (String, String) → Result<...>)?` vs `ImproveNoteUseCase?`)
avoids pulling the Koog library into the VM's constructor signature. Tests inject `null`;
the DI module wires the real lambda via `improveNoteLambda(useCase)`.

`Dispatchers.IO` on `scope.launch` caused 6 `NoteEditorTest` failures: `advanceUntilIdle()` in
`runTest` cannot advance real time on `IO`-dispatched coroutines. Removing it makes the tests
deterministic. Repository operations are themselves `suspend` functions — they switch contexts
internally.

`EditorState.Editing.isNew: Boolean = false` is additive (no existing call sites break) and
makes the create-on-first-save intent explicit in the state itself.

## Consequences

- `NoteEditorScreen` (UI) is unaffected — public API (`editorState`, `savedPulse`, `events`,
  `openEditor`, `createNote`, `editTitle`, `editBody`, `saveNow`, `improveNote`,
  `searchNotesForLink`, `searchTasksForLink`, `closeEditor`) is unchanged.
- `NoteSaver.fail()` is the only error path — all save failures emit `NotesUiEvent.SaveFailed`
  and log the exception. No inline error handling anywhere else in `NoteEditor`.
- `SavedPulse` (a `SharedFlow<Unit>`) is the only pulse channel. Phase 1 placeholder;
  do not delete it even though UI maps it to `Notification.None`.
- `OutgoingLinksExtractor` regex now uses two separate `Regex` instances (one for `note://`,
  one for `task://`) because a single regex with alternation only captures the first alternation
  branch in the group. Tests cover both link kinds.
- `FakeNotesRepository` is `final` — do not inherit from it in tests. Override individual methods
  via anonymous object only where the fake's behaviour differs (currently not needed since
  the fake's `updateContent`/`createWithContent` never fail for missing notes).
- All new helpers are `internal` except `NoteAiController` (used in DI) and `NoteContentMapper`
  (used in `NoteSaver` and `NoteEditor`).
- DI in `NotesDiModule` uses explicit `viewModel { NoteEditor(...) }` lambda — never
  `viewModelOf(::NoteEditor)`. See `2026-09-06-koin-vm-viewmodelof-koinviewmodel.md`.

## Links

- Commit: `f3dcf03 someshit` (refactor state + save + AI extraction)
- New files:
  - `feature/notes/domain/NoteContentMapper.kt`
  - `feature/notes/domain/editor/NoteEditorState.kt`
  - `feature/notes/domain/editor/NoteSaver.kt`
  - `feature/notes/domain/editor/NoteAiController.kt`
  - `commonTest/.../NoteContentMapperTest.kt`
  - `commonTest/.../NoteEditorStateTest.kt`
  - `commonTest/.../NoteSaverTest.kt`
  - `commonTest/.../NoteAiControllerTest.kt`
- Modified: `feature/notes/Ids.kt`, `NoteEditor.kt`, `NotesDiModule.kt`,
  `NoteEditorTest.kt`, `OutgoingLinksExtractor.kt`
- Skills: `singularity-todo-vm-migration-playbook`, `singularity-todo-testable-vm`,
  `singularity-todo-test-helpers`, `singularity-todo-pure-formatters`
