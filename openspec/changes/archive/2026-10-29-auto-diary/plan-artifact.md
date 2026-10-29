# Plan artifact — Auto-diary (Автодневник)

## Bounded scope

In-app daily standup notes scoped to a project, plus an agent-facing (MCP) trigger that prompts creation when the previous day's standup is missing. No AI-generated content, no cross-project aggregation, no new UI beyond reusing the daily-notes surface.

## Decisions (grilled)

1. **Surface**: in-app `NoteKind.Daily` scoped to a project; the agent writes on the user's behalf via MCP.
2. **Project linkage**: add nullable `projectId` column to `Note` (migration + backup DTO), matching the existing `taskId` structural-linkage pattern (`Ids.kt:119`).
3. **Trigger owner**: external agent hook calls MCP tool; app stays trigger-agnostic.
4. **Factory**: no `DailyNoteFactory` revival; use `NotesRepository.getOrCreateDailyNote` directly, wrapped in a use case only if real logic is added.
5. **Scope of "previous day"**: every non-archived project; device timezone via `TimeZoneProvider`.
6. **Migration**: bump to SCHEMA_VERSION v23 via `AutoMigrationSpec` adding nullable `project_id`; no backfill.
7. **Concurrency**: DB unique index (partial, `kind = 'Daily'`) on `(title, project_id)`; repository lock insufficient for cross-process MCP.
8. **MCP read tool**: reuse `get_daily_note` (null ⇒ missing); no redundant `has_*` tool.
9. **Project enumeration**: reuse `list_projects` with `includeArchived=false` filter.
10. **Timezone**: device timezone via `TimeZoneProvider`.

## Required deliverables

- Migration: add `project_id TEXT` (nullable) to `notes` table + SCHEMA_VERSION bump + backup DTO field + unique index.
- Repository: project-scoped daily note query/creation; idempotent `getOrCreateDailyNote(dateKey, projectId?, templateId?)`.
- MCP tool: expose `get_daily_note` and `list_projects(includeArchived)`; reuse `create_note`/`get_or_create_daily_note`.
- Trigger logic (agent-side, out of repo): given project list, determine missing previous-day standup and surface prompt.

## Out of scope

- Agent hook implementation (lives outside this repo).
- Compose UI changes beyond existing daily-notes list.
- AI content generation.

## Acceptance

- Idempotent creation per (project, date).
- Missing-standup detection returns null/absent.
- Migration preserves existing notes (`projectId = null`).
- Unique index prevents duplicate daily notes per (project, date).