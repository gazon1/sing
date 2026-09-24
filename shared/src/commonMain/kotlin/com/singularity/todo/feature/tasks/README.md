# Tasks

Core task management: create, edit, delete, search, filter by status/project/tag, recurring tasks, reminders.

## Structure

```
tasks/
├── data/          TaskRepositoryImpl (Room), AttachmentSaverImpl
├── domain/        TaskDomain.kt (validation, business rules), Ids.kt
└── presentation/
    ├── screen/    TaskDetailViewScreen, TaskEditorScreen
    ├── viewmodel/ TaskCreateViewModel, TaskDetailViewModel
    ├── nav/       TasksNavGraph, tasksEntryProvider
    └── components/ Inline editor, TaskRow, DatePicker, etc.
```

## Key entry points

| What | Where |
|---|---|
| Create task | `TaskCreateViewModel` |
| Task detail | `TaskDetailViewScreen` + `TaskDetailViewModel` |
| Repository | `TaskRepository` (interface), `TaskRepositoryImpl` (Room) |
| Nav graph | `TasksNavGraph`, `tasksEntryProvider` |

## AI tools using this domain

`create_task`, `update_task`, `delete_task`, `get_task`, `list_tasks`, `search_tasks`, `list_linked_tasks`, `decompose_task`, `refine_task`, `generate_description`, `generate_checklist`, `pick_time`, `cluster_tasks`

## Relevant ADRs

- `docs/decisions/2026-09-05-ids-and-type-design.md` — TaskId, @JvmInline value classes
- `docs/decisions/2026-09-05-koin-suspend-bridge.md` — CoroutineScope in repositories
- `docs/decisions/2026-09-18-no-pass-through-usecases.md` — why TasksUseCase was removed
