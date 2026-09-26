---
title: "Fake repository override pattern, LinkSchemes helper, and SavedPulse emission tests"
date: 2026-09-22
tags: [testing, architecture, notes]
status: accepted
---

## Context

Three loosely-coupled problems surfaced during a NoteEditor refactoring review:

1. **Fake repository failure injection was impossible.** `FakeNotesRepository` and its siblings used `runCatching` internally but exposed no mechanism to inject failures for test coverage of error paths (e.g. "save returns failure when updateContent fails"). The only workaround was subclassing each fake individually — boilerplate for every test that needed a failure.

2. **Hardcoded link scheme strings.** Three production files (`NoteContentMapper`, `NoteEditorScreen`, `NotePreviewScreen`) contained raw `"note://"` and `"task://"` string literals. Adding a new link kind (e.g. `checklist://`) required hunting all three sites. `OutgoingLinksExtractor` had two separate regexes, one per scheme — new schemes meant new regex + new branch.

3. **SavedPulse emission was tested indirectly.** `NoteSaverTest` verified the pulse only by checking that `save()` returned success and the note was updated — not by asserting the `SharedFlow` itself emitted anything. The gap: if `emit` were removed from `save()`, the test would still pass.

## Idea

1. **Per-method `var Result<T>?` override fields** on each fake repository, following the existing `FakeBackupRepository` pattern. Methods guard with `override?.let { return it }` before `runCatching`.

2. **`LinkSchemes` object** as single source of truth for scheme constants. `LinkKind.prefix` extension, `LinkKind.urlFor(id)` builder, `parseLinkUrl(url)` extractor. `OutgoingLinksExtractor` uses a `SCHEME_FACTORIES: Map<String, (String) -> LinkRef>` for O(1) dispatch.

3. **`launch { flow.take(1).collect { ... } } + runCurrent()`** on the `TestScope` to subscribe before the synchronous emit fires, then `advanceUntilIdle()` to process it.

## Decision

1. Made `FakeNotesRepository`, `FakeTaskRepository`, `FakeReminderRepository`, `FakeAttachmentRepository` `open`. Added `var XxxOverride: Result<T>? = null` fields for every mutating method (13 for Notes, 9 for Tasks, 5 for Reminders, 4 for Attachments). Methods check `override?.let { return it }` before `runCatching`.

2. Created `LinkSchemes` object with `NOTE_PREFIX = "note://"` and `TASK_PREFIX = "task://"` constants. Added `LinkKind.prefix` extension, `LinkKind.urlFor(id)` extension, and `parseLinkUrl(url): Pair<String, String>?` free function. `OutgoingLinksExtractor` replaced two regexes with one and a `SCHEME_FACTORIES` map. All three screen files use `LinkSchemes` exclusively.

3. Replaced indirect `NoteSaverTest` assertion with `launch { savedPulse.take(1).collect { received += it } }` on `this@runTest` (the `TestScope`), preceded by `runCurrent()` to ensure the collector is subscribed before the synchronous `emit` fires. Added `updateContentOverride` test for the failure path.

## Rationale

**Override fields** — the `FakeBackupRepository` precedent meant the pattern was already accepted. `open class` was chosen over interfaces+implementations because all fakes are in the same file and would require many interface declarations. The `override = null` default means all existing tests are unaffected.

**LinkSchemes** — centralising the strings eliminates a class of bugs where one site uses `"note://"` and another `"note:/"` (missing slash). The `SCHEME_FACTORIES` map makes adding new link kinds a one-line addition to the map rather than a new regex + new branch in `extractOutgoingLinks`. O(1) map lookup is also slightly cleaner than two sequential `startsWith` checks.

**SharedFlow emission testing** — `backgroundScope` (undocumented child of `runTest`'s scope) starts coroutines outside the test's control, making race conditions with synchronous emits likely. Using `this.launch` (the `TestScope` itself) with `runCurrent()` to ensure subscription before the emit is the reliable pattern. `take(1).collect` closes the subscription automatically after the first element, avoiding leaks.

## Consequences

- All new tests that need to verify failure paths use `XxxOverride = Result.failure(...)` on the appropriate fake.
- All link-related string literals in the notes feature must use `LinkSchemes.NOTE_PREFIX` / `LinkSchemes.TASK_PREFIX`. No raw `"note://"` in `feature/notes/`.
- SharedFlow emission tests in this project always use `launch { flow.take(1).collect { ... } }` on `this@runTest`, not `backgroundScope`, with `runCurrent()` before the suspending call that emits.
- `SCHEME_FACTORIES` is the extension point for new link kinds in `OutgoingLinksExtractor` — add one entry, not one regex + one branch.

## Links

- Commits: `18ce2d0` (fake overrides + LinkSchemes production), `babf05c` (LinkSchemesTest + SavedPulse emission tests)
- Files: `FakeRepositories.kt`, `LinkSchemes.kt`, `OutgoingLinksExtractor.kt`, `NoteContentMapper.kt`, `NoteEditorScreen.kt`, `NotePreviewScreen.kt`, `NoteSaverTest.kt`, `NoteEditorTest.kt`
- Test files: `FakeNotesRepositoryTest.kt`, `FakeTaskRepositoryTest.kt`, `FakeReminderRepositoryTest.kt`, `FakeAttachmentRepositoryTest.kt`, `LinkSchemesTest.kt`
