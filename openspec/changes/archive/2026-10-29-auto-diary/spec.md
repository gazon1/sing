# Auto-diary (Автодневник)

## Summary

A daily standup note per project, with a trigger that prompts creation when the previous day's standup is missing. This spec defines the contract for the in-app daily-note machinery and the open decisions around project linkage and triggering.

## Problem

Returning to a deferred project after weeks or months is painful because context is lost. Daily standups per project preserve context. Today the app has daily notes and projects, but they are not linked, and nothing prompts for a missing standup.

## Scope

In scope:
- Daily notes (`NoteKind.Daily`) already exist; this change may extend them to be project-scoped.
- A trigger that detects a missing standup for the previous day and surfaces a prompt.

Out of scope (this change):
- Implementing the agent-side hook described in the request; that is a separate surface (see Open questions).
- Changing the sync protocol or backup format beyond adding an optional field.

## Non-goals

- No AI-generated standup content in this change.
- No cross-project aggregation view.

## Current behavior

- Daily notes are keyed by ISO date string and created idempotently via `NotesRepository.getOrCreateDailyNote(dateKey, fromTemplateId)` (`NotesRepository.kt:123-127`).
- `Note` has `taskId: TaskId?` but no `projectId` (`Ids.kt:119`).
- Projects are a separate feature with MCP exposure (`ProjectReviewTool.kt`).
- No "new day" trigger exists in application code.

## Required behavior

- RB-01: The system must be able to associate a daily note with a project.
- RB-02: Given a project and a date, `getOrCreateDailyNote` (or its project-scoped successor) must return the existing daily note for that project+date if one exists, otherwise create one.
- RB-03: The system must expose a query "is there a daily note for project P on date D" returning a boolean (or the note).
- RB-04: A trigger must be able to determine, on a new day, whether a standup exists for the previous day for a given project, and if not, surface a prompt to create one.
- RB-05: Creation of a standup must be idempotent: repeated triggers for the same project+date must not create duplicate notes.

## Invariants

- INV-01: At most one daily note per (project, date). Why: duplicates would fragment context and break the "return to project" flow.
- INV-02: A daily note's project association, once set, is immutable. Why: reassignment would corrupt historical standups.
- INV-03: Idempotency of `getOrCreateDailyNote` must hold under concurrent calls for the same (project, date). Why: triggers and UI may race.

## Failure semantics

- If note creation fails, the trigger must report the failure and not silently retry in a loop.
- If the project no longer exists, the trigger must no-op (no orphan notes).

## Cancellation and lifecycle

Applies: triggers run on a coroutine scope. The trigger must respect cancellation and not leak scopes. Use `AutoCloseableCoroutineScope` ownership convention.

## Concurrency and ordering

Applies: concurrent `getOrCreate` calls for the same key must serialize (repository-level or DB unique constraint). Ordering of prompt emission relative to note creation is not specified.

## Persistence and recovery

Applies: if a note is created, it must survive process restart and sync. A new `projectId` column requires a Room migration + `SCHEMA_VERSION` bump and backup DTO update.

## Compatibility constraints

- Existing daily notes (without project) must remain valid (nullable `projectId`).
- Backup/restore must preserve the new field with a default.

## Acceptance criteria

- [ ] AC-01: When a daily note is created for project P on date D, a second call for the same P+D returns the same note id.
  Verification: `AutoDiaryRepositoryTest::getOrCreate_isIdempotent` — `./gradlew :shared:jvmTest --tests 'AutoDiaryRepositoryTest'`
- [ ] AC-02: When no daily note exists for project P on previous day, the trigger reports "missing".
  Verification: `AutoDiaryTriggerTest::reportsMissingWhenAbsent` — `./gradlew :shared:jvmTest --tests 'AutoDiaryTriggerTest'`
- [ ] AC-03: When a daily note already exists for project P on previous day, the trigger does not prompt.
  Verification: `AutoDiaryTriggerTest::noPromptWhenPresent` — `./gradlew :shared:jvmTest --tests 'AutoDiaryTriggerTest'`

## Open questions

1. Is "Автодневник" an in-app feature or an agent/CLI feature? — unresolved; spec assumes in-app but may be revised.
2. Should `Note` gain `projectId` or use a separate join table? — data-model decision pending.
3. Who owns the "new day" trigger — app scheduler (reminders) or agent hook? — architecture decision pending.
4. Was `DailyNoteFactory` (ADR) intentionally dropped, leaving creation only via repository? — needs git-history check.

## References

- `openspec/changes/auto-diary/investigation.md`
- `docs/decisions/2026-09-25-note-templates-daily-design.md`
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/notes/domain/port/NotesRepository.kt:100-127`
EOF
echo done