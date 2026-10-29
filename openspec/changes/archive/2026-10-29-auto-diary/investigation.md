# Auto-diary (Автодневник)

## Request

Working across several projects, returning to a deferred project after weeks/months is painful. The proposed remedy is a daily standup note per project. A hook on the first message of a new day checks whether a standup exists for the previous day; if not, it prompts the agent to request writing one. This investigation establishes what the codebase already provides toward that and what is still unverified.

## What the code actually does

- Daily notes already exist as a first-class note kind: `NoteKind` enum with `Plain`, `Daily`, `Template` is defined in `shared/src/commonMain/kotlin/com/singularity/todo/feature/notes/Ids.kt:55-68`.
- `NotesRepository` exposes daily-note operations: `watchDailyNotesInRange(from, to)` (`NotesRepository.kt:105-106`), `getDailyNote(dateKey)` (`NotesRepository.kt:108-109`), `getOrCreateDailyNote(dateKey, fromTemplateId)` (`NotesRepository.kt:123-127`).
- Daily notes are keyed by an ISO date string used as the title anchor; `getOrCreateDailyNote` is idempotent (`NotesRepository.kt:123-127`).
- The ADR `docs/decisions/2026-09-25-note-templates-daily-design.md` specifies the `kind` column (migration v21→v22) and `createFromTemplate`/`getOrCreateDailyNote` semantics, but references `DailyNoteFactory.kt` and `TemplatePicker.kt` which are not present in the tree (grep for `DailyNoteFactory` returned only docs).
- Notes can be linked to a task via `taskId: TaskId?` (`Ids.kt:119`), but there is no `projectId` field on `Note` (grep for `projectId` in `feature/notes` returned no matches).
- Projects exist as a feature module `shared/.../feature/projects/` and an MCP-facing toolset (`ListProjectsTool`, `GetProjectTool`, `CreateProjectTool`, `UpdateProjectTool`, `DeleteProjectTool`, `ProjectReviewTool` under `feature/ai/tools/`).
- `ProjectReviewTool` (`ProjectReviewTool.kt:28-57`) reviews a project and suggests next actions; it does not create or check daily notes.
- MCP tool catalog exposes a `notes` domain and a `projects` domain (`ToolboxTool.kt:75` description lists "tasks, projects, notes, tags, adr, docs, ai").
- Reminders/alarms exist as features (`feature/reminders`, `feature/alarms`) but no "daily standup" or "new day" trigger was found (grep for standup/diary returned no application code).
- There is no hook or scheduler in this repo that fires on "first message of a new day" (grep for "new day" / "first message" returned no matches in shared code).

## Reproduction

Not reproduced — this is a feature request with no existing symptom. Searched the codebase for `standup`, `diary`, `Автодневник`, `new day`, `first message`; only a recurring-tasks ADR mention and a reminder test matched. No implementation to run.

## Verified facts

1. `NoteKind.Daily` exists with idempotent `getOrCreateDailyNote(dateKey, fromTemplateId)` — `NotesRepository.kt:105-127`.
2. Notes have no `projectId` linkage; only `taskId` — `Ids.kt:119` and grep in `feature/notes`.
3. Daily-note factory classes referenced by the ADR (`DailyNoteFactory`, `TemplatePicker`) are absent from the source tree — grep returned only docs.
4. Projects are exposed to agents via MCP tools (`ProjectReviewTool.kt`, `ListProjectsTool`, etc.) — `feature/ai/tools/`.
5. No "new day" / standup hook exists in application code — grep returned no matches.

## Hypotheses

### H-1 — The feature is primarily agent-side (MCP tooling), not in-app UI
- Evidence for: request mentions "хук", "агент", "первое сообщение нового дня".
- Evidence against: daily notes are an in-app feature and the request says "по каждому проекту".
- Would be confirmed by: a spec that names an agent hook / MCP tool rather than a screen.
- Would be refuted by: a spec that names a Compose screen and notification.

### H-2 — Daily notes need a project linkage to become "per-project standups"
- Evidence for: `Note` has `taskId` but no `projectId`.
- Evidence against: projects could be encoded in the title or via tags.
- Would be confirmed by: a migration adding `project_id` to `notes`.
- Would be refuted by: reuse of title convention `YYYY-MM-DD — <project>`.

### H-3 — `DailyNoteFactory` from the ADR was never implemented, so "auto" creation is unbuilt
- Evidence for: grep found no `DailyNoteFactory` source file.
- Evidence against: the repository method exists and may be called directly.
- Would be confirmed by: `getOrCreateDailyNote` having no caller beyond tests.
- Would be refuted by: a ViewModel or use case calling it.

## Open questions

1. Is "Автодневник" an in-app feature or an agent/CLI feature? — needs product decision.
2. Should standups be per-project notes, and does `Note` need `projectId`? — needs data-model decision.
3. Who owns the "new day" trigger — app scheduler (reminders) or agent hook? — needs architecture decision.
4. Was `DailyNoteFactory` intentionally dropped? — check git history / ADR follow-ups.

## Constraints found

- Koin DI: `viewModelOf(::Vm)`, no `factory` for VMs, no `*.toTypedArray()` in `modules()` — `AGENTS.md`.
- Notes are Room-synced entities; new columns need a migration + `SCHEMA_VERSION` bump — `AGENTS.md`, Room migration skill.
- `Note` is `@Serializable` for sync; any new field must be serializable — `Notes.kt` KDoc.
- Tests use fakes, not MockK, for state — `AGENTS.md`.
- Daily notes identity is the ISO date string title — ADR `2026-09-25-note-templates-daily-design.md`.

## References

- `docs/decisions/2026-09-25-note-templates-daily-design.md` — daily notes + templates design.
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/notes/domain/port/NotesRepository.kt:100-127` — daily/template methods.
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/notes/Ids.kt` (NoteKind) — `NoteKind` enum.
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/ai/tools/ProjectReviewTool.kt` — project review tool.
- `shared/src/jvmMain/kotlin/com/singularity/todo/mcp/ToolboxTool.kt:75` — tool domains.
EOF
echo done