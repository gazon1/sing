---
Context: ProjectDetailScreen.kt was essentially empty (22 lines of real UI). ProjectsScreen.kt used a dead onNavigateToCreateProject parameter. ProjectCard.kt always rendered Icons.Filled.Home regardless of project.icon. ProjectEditorScreen.kt supported create-only (no edit mode), had an 80-line preview duplication, and used the legacy CurrentUser (not ProfileAwareCurrentUser). No ability to view tasks linked to a project. No navigation from TaskDetailScreen → ProjectDetailScreen. No task counts on project cards. ProjectDao had no JOIN-based counts, no hierarchy queries, no idempotency_key, and is_notebook was still present as a dead column.
Decision: Full Projects UX rework in 11 stages: (1) Room schema v10→v11 (add idempotency_key, drop is_notebook, add hierarchy DAO methods), (2) Domain layer (ProjectIconRegistry with 18 Material icons, ProjectColorPalette, UpdateProjectUseCase overload, ProjectDetailUi data class), (3) ProjectDetailViewModel with silent debounce, TaskRepository combine, all picker/save/archive events, (4) ProjectDetailScreen TickTick-style 4-section rewrite (Hero + MetaChips + Body[QuickAdd+TaskList≤5] + BottomBar), (5) TasksByProjectScreen route for "See all", (6) TaskDetailScreen → ProjectDetailScreen cross-link via IconButton(ChevronRight) adjacent to project chip + overflow menu item, (7) ProjectEditorScreen create+edit mode with 4 sub-components and ProfileAwareCurrentUser, (8) ProjectsScreen polish (StatefulContent, search, sort, hierarchy indent), (9) AI/MCP tools (DeleteProjectTool, ListProjectsTool), (10) ~20 new tests (VM/UseCase/Repo/Widget/Instrumented/DI), (11) Migration and verification. Key constraints: 18 Material icons (not 25-30), 1-level parent hierarchy (cycle prevention structurally impossible), JOIN+GROUP BY for task counts (not denormalized), silent debounce (no Saved-spam), no combinedClickable on chips.
Rationale: ProjectDetailScreen was the most visually broken screen in the app — essentially a blank shell. TickTick and Todoist both have rich project detail screens with inline task previews, quick-add, and project-level metadata. The 4-section document-style pattern (Hero/Meta/Body/BottomBar) is the established standard (per TaskDetail ADR). 1-level hierarchy is the agreed scope (cycle prevention code is eliminated entirely). JOIN for counts is correct for <50k row scale. Silent debounce is required to avoid Regression 5 (Saved-spam bug in TaskDetailViewModel:100 must not be replicated).
Consequences: ProjectRepository gains 7 new methods (watchProject, getById, changes, watchProjectsWithCounts, watchByParent, setParent, setSortOrder, restore, findByIdempotencyKey). ProjectDao gains 5 new queries. UpdateProjectUseCase and UpdateTaskUseCase both gain overloads with transform function. ProjectDetailQuickAddInput is a new inline composable following the silent-save pattern. Color/Icon pickers use ModalBottomSheet with LazyVerticalGrid(columns=6). PreviewSamples.project() adds icon, parentId, taskCount, completedCount. All PreviewProviders updated. DiGraphTest updated to cover edit-mode ProjectEditorViewModel and TasksByProjectViewModel.
Links: skill:singularity-todo-document-style-detail, skill:singularity-todo-cross-feature-navigation, skill:singularity-todo-icon-registry, skill:singularity-todo-inline-edit-saved-feedback, skill:singularity-todo-relational-counts, skill:singularity-todo-task-detail-ux, skill:singularity-todo-shared-ui-components, skill:singularity-todo-ui-event-vs-state, skill:singularity-todo-feature-scaffold, skill:singularity-todo-room-migration, skill:singularity-todo-koin-di, skill:singularity-todo-mcp-server, docs/decisions/2026-09-07-task-detail-document-style.md, docs/decisions/DIGEST.md
Tags: ux, projects, compose, room, multi-profile, koog
---

# Projects UX Rework — TickTick-level Parity

## Context

`ProjectDetailScreen.kt` (105 lines) contained only 22 lines of real UI — essentially an empty shell with a title and description text field. No task list, no metadata chips, no quick-add, no bottom action bar. ProjectsScreen.kt used a dead `onNavigateToCreateProject` parameter that was never wired. ProjectCard.kt always rendered `Icons.Filled.Home` regardless of the project's `icon` field — the icon registry was never connected. ProjectEditorScreen.kt was create-only (no edit mode), had an 80-line preview duplication at lines 178–256, and used legacy `CurrentUser` instead of `ProfileAwareCurrentUser`. No route existed to navigate from a task's detail screen to its project. No way to see tasks belonging to a project without going to the full TasksScreen with a filter.

## Problems Identified

| # | Problem | Severity |
|---|---|---|
| 1 | ProjectDetailScreen is a blank shell | P0 |
| 2 | ProjectCard ignores `project.icon` — always shows Home | P1 |
| 3 | ProjectEditorScreen has no edit mode | P1 |
| 4 | No way to view tasks for a project | P1 |
| 5 | No TaskDetailScreen → ProjectDetailScreen navigation | P1 |
| 6 | No task counts (completed/total) on project cards | P1 |
| 7 | `is_notebook` dead column still in schema | P2 |
| 8 | No `idempotency_key` on projects | P2 |
| 9 | `CurrentUser` (not `ProfileAwareCurrentUser`) in editor | P2 |
| 10 | 80-line preview duplication in editor | P2 |

## Decision

Full 11-stage rework. Key technical decisions:

### Stage 1 — Room Schema v10→v11

Schema migration adds `idempotency_key TEXT UNIQUE` and removes `is_notebook`. One migration (10→11), not two separate migrations. `ProjectDao` gains:
- `watchAllWithCounts`: `SELECT p.*, COUNT(t.id) totalCount, SUM(CASE WHEN t.completed_at IS NOT NULL THEN 1 ELSE 0 END) completedCount FROM projects p LEFT JOIN tasks t ON t.project_id = p.id WHERE p.deleted_at IS NULL AND p.user_id = :uid GROUP BY p.id`
- `watchByParent(parentId)`: children of a given parent
- `setParent(id, parentId)`: move project under new parent (with domain-layer 1-level invariant check)
- `setSortOrder(id, order)`: reorder within parent
- `restore(id)`: undelete
- `findByIdempotencyKey(key)`: for MCP idempotent create

### Stage 2 — Domain Layer

**ProjectIconRegistry**: `object ProjectIconRegistry { val Work = Icons.Filled.Work; val all: List<Pair<String, ImageVector>> }`. 18 icons in v1. DB stores `icon: String?` (the key name, not ImageVector). Picker: `ModalBottomSheet` + `LazyVerticalGrid(columns = 6)`.

**ProjectColorPalette**: 12-entry predefined palette (no custom hex in v1). Stored as `color: Int` (Color.toArgb()).

**UpdateProjectUseCase overload**: `invoke(id, transform: (Project) -> Project, clock): Result<Unit>` — enables callers to do read-modify-write atomically. Also applies to `UpdateTaskUseCase`.

**ProjectDetailUi**: combined read model:
```kotlin
data class ProjectDetailUi(
    val project: Project,
    val tasks: List<Task>,          // ≤ 5 inlined, full list on TasksByProjectScreen
    val totalCount: Int,
    val completedCount: Int,
    val isArchived: Boolean,
    val parent: Project?,           // null if root
    val childCount: Int,
)
```

### Stage 3 — ProjectDetailViewModel

- `combine(projectRepo.watchProject(id), taskRepo.watchTasks(uid, TaskFilter.ByProject(id)), currentUser.scopedUserId)` → `ProjectDetailUi`
- `hideCompletedTasks: StateFlow<Boolean>` — filters the inlined task list
- `updateProject(transform)` — silent: `_lastEditedAt.value = clock.now()` (no `Saved` event on debounce)
- Events: `OpenColorSheet`, `OpenIconSheet`, `OpenDateSheet`, `OpenParentSheet`, `OpenConfirmDelete`, `OpenConfirmArchive`, `NavigateToTasks`, `AddTask`, `Saved` (only for explicit actions)
- `deleteProject()` — propagates `AppError.Validation` to events (not silently)

### Stage 4 — ProjectDetailScreen (TickTick-style, 4 sections)

```
Section 1 — ProjectHeroSection:
  ColorCircle(project.color) + icon + inline-edit name/description
  Progress bar: completedCount / totalCount

Section 2 — ProjectMetaChipsRow:
  [Date chip] [Parent chip: "Work ▸"] [Child count chip: "3 sub-projects"]
  (FlowRow, tap → opens respective sheet)

Section 3 — ProjectBodySection:
  ProjectDetailQuickAddInput (inline, silent debounce)
  ProjectTasksList (≤ 5 tasks, each as TaskCard mini)
  "See all N tasks" text link → TasksByProjectScreen
  "Hide completed ✓" toggle

Section 4 — ProjectBottomActionBar:
  [Remind] [Attach] [MoreVert: Edit / Duplicate / Archive / Delete]
```

`ActiveSheet` sealed interface routes all 5 sheet types from one `sheetState: MutableStateFlow<ActiveSheet?>`.

### Stage 5 — TasksByProjectScreen

New `AppDestination.TasksByProject(projectId: String)` route. `TasksByProjectViewModel` delegates to `TaskRepository.watchTasks(uid, TaskFilter.ByProject(id))`. `BackTopAppBar` shows project color + icon. `Hide ✓` toggle + filter chips. `LazyColumn` of `TaskCard`. Bottom FAB: Add task + Edit project.

### Stage 6 — Cross-link TaskDetailScreen → ProjectDetailScreen

In `TaskDetailScreen` (not in generic `MetaChipsRow`):
```kotlin
Row(verticalAlignment = CenterVertically) {
    FilterChip(project.name, onClick = { /* open project sheet */ })
    IconButton(Icons.AutoMirrored.Filled.ChevronRight, "Open project") {
        onNavigateToProject(project.id)
    }
}
```
Overflow menu item: "Open project" (`Icons.AutoMirrored.Filled.OpenInNew`). `onNavigateToProject: (ProjectId) -> Unit` callback added to `TaskDetailScreen`.

### Stage 7 — ProjectEditorScreen (create + edit, 4 sub-components)

- Edit mode: `koinViewModel { parametersOf(projectId) }` with `Loading` state pre-populated from repo
- Save: `id == null` → `CreateProjectUseCase`; else `UpdateProjectUseCase(id, transform)`
- 4 sub-components (not 6):
  1. `ProjectEditorIdentitySection` (name + description)
  2. `ProjectEditorAppearanceSection` (icon + color — consolidated)
  3. `ProjectEditorOrganizationSection` (parent + dueDate — consolidated)
  4. BottomActionBar (at screen level)
- Uses `ProfileAwareCurrentUser` (per multi-profile ADR)
- 80-line preview duplication removed

### Stage 8 — UI Polish

- `ProjectCard`: `ProjectIconRegistry.iconByKey(project.icon) ?: Icons.Filled.Folder` + `"${completedCount}/${totalCount}"` + `TestTag`
- `ProjectsScreen`: `StatefulContent`, search field, sort menu, hierarchy indent (16dp), dead `onNavigateToCreateProject` removed
- `TestTags.kt`: 14 new constants for projects

### Stage 9 — AI / MCP

- `DeleteProjectTool` (idempotent, checks not null before delete)
- `ListProjectsTool` (read-only, uses `ProjectDao.watchAllWithCounts`)

### Stage 10 — Tests (~20 new)

| Type | Count | Files |
|---|---|---|
| VM | 5 | ProjectsViewModelTest, ProjectEditorViewModelTest, ProjectDetailViewModelTest, TasksByProjectViewModelTest, TaskDetailCrossLinkTest |
| Use case | 3 | CreateProjectUseCaseTest, UpdateProjectUseCaseTest (overload), DeleteProjectUseCaseTest (guard + restore) |
| Repo/DAO | 2 | ProjectRepositoryTest, ProjectDaoTest (JOIN counts) |
| Widget | 4 | ProjectDetailScreenTest, ProjectEditorScreenTest, TasksByProjectScreenTest, TaskDetailCrossLinkTest (chevron click) |
| Instrumented | 3 | CreateProjectFlowInstrumentedTest, EditProjectFlowInstrumentedTest, TaskProjectNavigationInstrumentedTest |
| DI | 2 | DiGraphTest (edit-mode VM + TasksByProject), JvmAiDiGraphTest (DeleteProjectTool + ListProjectsTool) |

### Stage 11 — Migration & Verification

- Room migration v10→v11 + schema 11.json
- `PreviewSamples.project()` adds `icon`, `parentId`, `taskCount`, `completedCount`
- `./check.sh` green
- Manual adb verification per `singularity-todo-adb-workflow`

## 10 Key Corrections from v2 Plan (Senior Review)

| # | v2 Problem | v3 Fix |
|---|---|---|
| 1 | `combinedClickable` on project chip in TaskDetailScreen | Separate `IconButton(ChevronRight)` adjacent to chip |
| 2 | Saved-spam bug replicated from TaskDetailViewModel | Silent `_lastEditedAt` + `formatSavedRelative` |
| 3 | Cycle prevention code (N-level hierarchy) | 1-level = structurally impossible, no such code |
| 4 | Editor: 6 sub-components | Consolidated to 4 |
| 5 | 25-30 Material Icons | 18 icons v1 budget |
| 6 | Denormalized task count column | JOIN+GROUP BY in `ProjectDao.watchAllWithCounts` |
| 7 | UpdateProjectUseCase and UpdateTaskUseCase drifted | Both updated in same PR |
| 8 | `isNotebook` dead field kept | Removed in migration v10→v11 |
| 9 | `ProjectDetailQuickAddInput` missing | Added as part of Section 3 |
| 10 | 6 widget tests missing | Full 20-test plan with widget tests |

## Definition of Done

- [ ] 5 new skills created, 5 existing updated
- [ ] ADR written, DIGEST.md refreshed
- [ ] Room schema v10→v11 migrates without data loss
- [ ] `./gradlew :shared:jvmTest` green (~20 new tests)
- [ ] `./gradlew :androidApp:assembleDebug` builds
- [ ] `./gradlew :desktopApp:test` smoke green
- [ ] ProjectDetailScreen: hero + meta + task preview (≤5) + See all + Hide completed + QuickAdd + BottomBar (adb verified)
- [ ] TasksByProjectScreen: full task list (adb verified)
- [ ] TaskDetailScreen chevron → ProjectDetailScreen (adb verified)
- [ ] ProjectEditorScreen: create + edit (adb verified: create → edit → change color → save)
- [ ] ProjectCard: correct icon + count `5/12` (adb verified)
- [ ] 1-level hierarchy: parent + child with indent (adb verified)
- [ ] Delete project with tasks: shows error (adb verified)
- [ ] DiGraphTest: edit-mode VM + TasksByProject VM + tools resolve
