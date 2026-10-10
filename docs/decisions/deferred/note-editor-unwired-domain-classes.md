---
title: "Note Editor Unwired Domain Classes"
date: 2000-01-01
status: CLOSED
tags: ["deferred"]
---

**Found in:** 2026-10-04 verifiability audit, same detector as above. Three
baseline lines shared a reference to this entry; none existed.

**Status: CLOSED (deleted)**

**Symptom:** three classes in `feature/notes/domain/` have test references but no
production call sites:
- `NoteEditorState` (52 lines, 18 test refs) — the real editor is
  `NoteEditor` in `presentation/viewmodel/`, which does not use this state class.
- `DailyNoteFactory` (67 lines, 1 ref) — a pure passthrough to
  `NotesRepository.getDailyNote` / `getOrCreateDailyNote`.
- `TemplatePicker` (34 lines, 1 ref) — a pure passthrough to
  `NotesRepository.watchTemplates` / `createFromTemplate` / `saveAsTemplate`.

The two passthroughs would additionally be flagged by the `PassThroughUseCase`
rule's sibling concern if ever promoted to use cases; they are domain classes
today, so no rule fires.

**Already ruled out:** `NoteEditorState` is not an alias — the VM keeps its own
state, and the test refs are the tests written against the unused class, not
against the shipped one.

**Try next:** delete `DailyNoteFactory` and `TemplatePicker` (they add an
indirection with no behaviour) and either delete `NoteEditorState` or move the
editor's real state into it. The last part is a behaviour change and belongs in
its own change, not a sweep.

---
