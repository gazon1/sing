# Features

> User-facing feature overview. For implementation details see `ARCHITECTURE.md`.

---

## Tasks

Full task management: create, edit, delete, search, filter by status/project/tag. Recurring tasks (daily, weekly, monthly, custom). Reminders via Android AlarmManager or desktop `at` scheduler.

**Key screens:** `TaskDetailViewScreen`, `TaskEditorScreen`
**Entry points:** `TasksNavGraph`, `tasksEntryProvider`

---

## Notes

Rich-text WYSIWYG editor (bold, italic, code, lists). Export to HTML. Notes can link to tasks via `parentId` or URL scheme (`singularity://task/<id>`).

**Key screens:** `NotesListScreen`, `NoteEditorScreen`, `NotePreviewScreen`
**Entry points:** `NotesNavGraph`

---

## Projects

Folder-like task grouping with color and icon. Task counts shown on project row. Per-profile isolation.

**Key screens:** `ProjectsScreen`
**Entry points:** `ProjectsNavGraph`, `projectsEntryProvider`

---

## Tags

Global tags shared across all entities. Per-profile isolation. Inline creation from task/note editors.

**Key screens:** Tag picker sheet (shared across task/note editors)

---

## Agenda

Calendar view with daily and weekly schedules. Saved agenda configurations per profile. `SavedAgendaViewsRepository` persists user-selected date ranges and filters.

**Key screens:** `AgendaScreen`
**Entry points:** `AgendaNavGraph`, `agendaEntryProvider`

---

## AI Assistant

30 Koog-powered tools: task refinement, decomposition, clustering, description generation, weekly planning. LLM provider configurable (OpenAI, Anthropic, etc.).

**Key screens:** `ChatScreen`
**Platform split:** JVM uses `MultiLLMPromptExecutor`; Android uses `AndroidKoogFactory` (stub)
**Tools:** 30 SimpleTool implementations in `feature/ai/tools/`

---

## Sync

Offline-first Supabase REST sync with HLC (Hybrid Logical Clock) timestamps. Conflict resolution: Last-Writer-Wins per field, remote precedence on tie. Exponential back-off on failure.

**Key components:** `SyncEngine`, `SyncRunner`, `ConflictResolver`
**ADR:** `docs/decisions/2026-09-05-sync-conflict-resolution.md`

---

## Backup

JSON export/import via `BackupCodec`. Per-profile. Includes all entities and sync metadata.

**Key components:** `BackupExporter`, `BackupImporter`, `BackupCodec` (platform-specific)

---

## MCP Server

AI agent control via stdio (JSON-RPC). 30 read/write/list tools exposed. Multi-profile support.

**JAR:** `mcp-server/build/libs/mcp-server-jvm-*.jar --profile=<name>`

---

## Multi-profile

Isolated data per profile (Personal, AI Agent, etc.). Profile-aware `CurrentUser` injection. Room database per profile. Supabase tenant isolation.

**Key components:** `ProfileAwareCurrentUser`, `ProfileRepository`
**ADR:** `docs/decisions/2026-09-21-profile-repository-migration.md`
