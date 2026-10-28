# Auto-diary (Автодневник) — design

## Summary

Add nullable `projectId` to `Note` via Room migration v23, extend `NotesRepository.getOrCreateDailyNote` with a project parameter, expose `get_daily_note` and `list_projects(includeArchived)` through MCP, and rely on a DB unique index for idempotency. No new abstractions beyond the migration.

## Current architecture and affected modules

- `feature/notes` — `Note`, `NotesRepository`, `NotesRepositoryImpl`, Room entity/DAO, migration, backup DTO.
- `feature/projects` — project listing (existing MCP tool).
- `mcp` — tool registration snapshot.

## Proposed solution

1. Migration v49→v50: add `project_id TEXT` nullable + partial unique index on `(title, COALESCE(project_id, '')) WHERE kind='Daily'` so global (null-project) daily notes are also deduplicated.
2. `Note.projectId: ProjectId?` (nullable, default null) — serializable.
3. `NotesRepository.getOrCreateDailyNote(dateKey, projectId, fromTemplateId)`; keep old overload delegating with null.
4. Backup DTO: `projectId: String? = null`.
5. MCP: expose `get_daily_note` (returns note or null) and add `includeArchived` param to `list_projects`.

## Alternatives considered

- **Encode project in title** (e.g. `YYYY-MM-DD — <project>`): rejected — breaks the date-key identity invariant and complicates queries; a structural FK matches existing `taskId` pattern.
- **In-memory repository lock for idempotency**: rejected — does not cover cross-process MCP callers; DB unique index is the only reliable guard.
- **Revive `DailyNoteFactory`**: rejected — adds an abstraction with no defect to remove; repository method suffices.

## Why this solution is the simplest

It reuses the existing daily-note machinery and the existing `taskId` linkage pattern; the only new surface is a nullable column + index + parameter. No new classes or ports.

## Component and dependency changes

- `Note` gains `projectId: ProjectId?` (nullable) — required for project scoping; defect removed: notes cannot be associated with a project.
- Migration v23 — required for persistence; defect removed: no project column.
- Unique index — required for INV-01/INV-03; defect removed: duplicate daily notes under concurrency.

## State-model changes

See `model.md`: `Absent`/`Present`/`Creating`/`Failed` with idempotent create and unique-violation path.

## Persistence and compatibility impact

- Existing notes: `projectId = null` (backward compatible).
- Backup/restore: new optional field with default null.
- Sync: `Note` serializable; new field serializes.

## Testing strategy

Unit tests with `FakeNotesRepository` (V-01..V-06), integration concurrency test with in-memory DB + unique index, migration round-trip test (slow).

## Rollout or migration

AutoMigration; no manual data migration. Unique index creation is part of migration.

## Risks and mitigations

- Risk: none remaining — COALESCE in the index covers the null case.
- Risk: backup from newer version restored on older app ignores unknown field — default null.

## Task dependencies

T001 (migration+entity) → T002 (repository) → T003 (MCP) and T004 (tests) in parallel where files disjoint.

## Non-goals

- No agent hook implementation.
- No UI changes.
- No AI content.

## Validation commands

- `./gradlew :shared:jvmTest --tests 'AutoDiary*'`
- `./gradlew :shared:jvmTest -Ptest.tags=slow --tests 'Migration22To23Test'`
- `./gradlew :shared:compileKotlinJvm`