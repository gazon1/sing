---
summary: Реструктуризация feature/projects по Clean Architecture: domain/data/presentation слои, перемещение Ids.kt, ProjectsRepository, UseCase-файлов, UI-state, экранов
---

# ADR: feature/projects — Clean Architecture рефакторинг

## Context

Фича `feature/projects` имела плоскую структуру без разделения на слои:

```
feature/projects/
├── Ids.kt, Project.kt, ProjectsDomain.kt, ProjectsRepository.kt, ProjectsUseCase.kt
├── ProjectDetailUi.kt, ProjectEditorUiEvent.kt, ProjectsUiEvent.kt
├── ProjectDetailIntent.kt, ProjectsViewModel.kt, ProjectDetailViewModel.kt, ProjectEditorViewModel.kt
├── ProjectColorPalette.kt, ProjectIconRegistry.kt
├── ProjectsScreen.kt, ProjectDetailScreen.kt, ProjectEditorScreen.kt
├── components/ (3 файла)
└── presentation/nav/ (expect/actual NavGraph, Navigator, Route, helpers)
```

Все типы лежали в корне пакета `feature.projects`, без `domain/data/presentation` разделения.

## Decision

Привести `feature/projects` в соответствие со структурой `feature/tasks` (эталон).

### Целевая структура

```
feature/projects/
├── data/
│   └── ProjectsRepositoryImpl.kt          # impl + private/internal extension-мапперы
├── domain/
│   ├── model/
│   │   └── Project.kt                     # ProjectId + Project + ProjectWithCounts + CreateProjectInput
│   ├── port/
│   │   └── ProjectsRepository.kt           # interface
│   ├── usecase/
│   │   ├── CreateProject.kt
│   │   ├── UpdateProject.kt
│   │   └── DeleteProject.kt               # содержит task-guard business rule
│   └── ProjectsDomain.kt                   # pure validation/build object
└── presentation/
    ├── components/
    ├── model/                             # ProjectDetailUi, ParentOption
    ├── nav/                               # NavGraph expect/actual, Navigator, Route
    ├── screen/
    ├── state/                             # sealed UiState/Intent/UiEvent
    ├── theme/
    └── viewmodel/
```

### Ключевые решения при миграции

1. **`Ids.kt` + `Project.kt` + `ProjectWithCounts` + `CreateProjectInput` → `domain/model/Project.kt`**
   Все типы, связанные с identity, слиты в один файл.

2. **`ProjectsRepository.kt` разрезан:**
   - interface → `domain/port/ProjectsRepository.kt`
   - impl + mappers → `data/ProjectsRepositoryImpl.kt` (мапперы `internal`)

3. **`ProjectsUseCase.kt` разрезан на 2 файла в `domain/usecase/`:**
   - `CreateProject.kt` — CreateProjectUseCase
   - `UpdateProject.kt` — UpdateProjectUseCase
   - `DeleteProject.kt` — DeleteProjectUseCase (бизнес-правило: нельзя удалить проект с задачами)

4. **UI-state вырезан из VM-файлов → `presentation/state/`:**
   - `ProjectsUiState.kt` — enum `ProjectSortOrder` + sealed `ProjectsUiState`
   - `ProjectsUiEvent.kt`
   - `ProjectDetailIntent.kt`, `ProjectDetailUiState.kt`, `ProjectDetailUiEvent.kt`
   - `ProjectEditorUiState.kt` + `ProjectEditorIntent`, `ProjectEditorUiEvent.kt`

5. **`toProject()` mapper:**
   - Добавлен в `core/database/Mappers.kt` как `internal fun ProjectEntity.toProject()`
   - Импортируется в `presentation/viewmodel/ProjectsViewModel.kt` и `data/ProjectsRepositoryImpl.kt`

6. **FQN обновлены во всех 30+ внешних файлах:**
   - `core/di/ProjectsDiModule.kt`, `AiToolsDiModule.kt` (+ platform actual)
   - `core/ui/components/ProjectPickerSheet.kt`, `ProjectPickerViewModel.kt`
   - `core/ui/preview/PreviewSamples.kt`
   - `feature/tasks/**` (~13 файлов)
   - `feature/search/**` (3 файла)
   - `feature/ai/tools/**` (~9 файлов)
   - `feature/nav/AndroidNavEntries.kt`, `JvmNavEntries.kt`
   - `test/fakes/FakeRepositories.kt`, `CommonFakes.kt`
   - 6 тестовых файлов (jvmTest + commonTest)

## Rationale

- Единообразие с `feature/tasks`: любой разработчик, знающий один feature-модуль, найдёт аналогичную структуру в любом другом.
- `domain/model/` содержит только чистые типы — `ProjectId`, `Project`, `CreateProjectInput`. Нет зависимостей от Room, Koin, Compose.
- `DeleteProjectUseCase` сохраняет task-guard бизнес-правило (нельзя удалить проект с задачами) — это гарантия, которую нельзя обойти из VM.

## Consequences

- Все импорты в 30+ файлах обновлены на новые FQN (`.domain.model`, `.domain.port`, `.domain.usecase`, `.data`, `.presentation.state`, `.presentation.viewmodel`).
- `DeleteProjectUseCase` конструктор теперь `(projectRepo: ProjectsRepository, taskRepo: TaskRepository)` — DI модуль обновлён соответственно.
- `ProjectsDiModule.kt` подключён через `domainModule` в `Modules.kt`.

## Links

- Эталонная структура: `feature/tasks/`
- DI паттерн: `singularity-todo-koin-di` skill
- Decision log: `docs/decisions/DIGEST.md`
