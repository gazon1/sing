# Decision Log Digest

Auto-generated consolidated rules from `docs/decisions/`. The agent
reads this at session start. Per-decision entries
(`docs/decisions/YYYY-MM-DD-*.md`) are the human-facing reasoning. Refresh with:

```bash
./scripts/refresh-decisions-digest.sh
```

Markers that surface as Critical: `**Always**`, `**Never**`, `**MUST**`.

## Critical

- **Always** use `koinBridge { ... }` in any Koin factory that calls a `suspend` function. _(from `2026-09-05-koin-suspend-bridge`)_
- **Never** use `GlobalScope.launch { ... }` inside factories — non-deterministic. _(from `2026-09-05-koin-suspend-bridge`)_
- **Never** use raw `runBlocking { ... }` inside `module { ... }` blocks. _(from `2026-09-05-koin-suspend-bridge`)_
- **Always** bind both `single<PromptExecutorPort>` and `single<PromptExecutor>`; `PromptExecutorPort` is for the streaming executor inside `KoogAgentService`, `PromptExecutor` is for the AI tool factories. _(from `2026-09-05-koog-both-platforms`)_
- **Always** declare `ai.koog:http-client-okhttp` in **both** `androidMain.dependencies` and `jvmMain.dependencies`. _(from `2026-09-05-koog-both-platforms`)_
- **Always** check `grep -rn "OpenAIModels" shared/src/commonMain shared/src/jvmMain shared/src/androidMain --include="*.kt"` returns only comments in `KnownModels.kt`. Anything else is a regression. _(from `2026-09-05-koog-test-workarounds`)_
- **Never** add capabilities to `KnownModels` unless a feature needs them — the simple form avoids the static init entirely. _(from `2026-09-05-koog-test-workarounds`)_
- `AiTestResult` is part of `SettingsUiState.Content.aiTestResult` with default `Idle`. **Never** make it a `UiEvent`. _(from `2026-09-05-llm-provider-settings`)_
- **Always** keep the auto-fill rule in `OpenAiConfig.resolveBaseUrl(storedUrl, provider)` only. The UI delegates to it — changing both is a bug. _(from `2026-09-05-llm-provider-settings`)_
- `FakeTextGen` is parametrised: `(success, failureMessage, trackGenerateCalls)`. **Always** use `trackGenerateCalls = true` in VM tests that assert the no-key short-circuit. _(from `2026-09-05-llm-provider-settings`)_
- **Never** add an `aiApiKey` field to `SettingsUiState.Content`. _(from `2026-09-05-secret-storage-split`)_
- **Never** add an `aiApiKey` (or any secret) field back to `SettingsRepository`. Adding one is a regression. _(from `2026-09-05-secret-storage-split`)_
- `SettingsViewModel.processIntent(UpdateAiKey)` **always** writes only to `secureStorage`. **Never** call `settings.setAiKey(...)`. _(from `2026-09-05-secret-storage-split`)_
- The password field on `AiProviderSettingsScreen` is a local `mutableStateOf`. **Never** lift it to the VM. _(from `2026-09-05-secret-storage-split`)_
- **Always** validate `parentTaskId` in `CreateTaskUseCase` and `UpdateTaskUseCase` via `assertNoNesting`. _(from `2026-09-08-task-1-level-subtasks`)_
- **Never** allow a task with `parentTaskId != null` to become a parent — enforce in domain, not just UI. _(from `2026-09-08-task-1-level-subtasks`)_
- **Always** `combine` the debounced draft flow with the entity's source `StateFlow` — **never** use `.first()` re-fetch inside a debounced collector. _(from `2026-09-08-task-detail-critical-fixes`)_
- **Always** silent saves for inline edits — `Saved` event is reserved for explicit user actions only. _(from `2026-09-08-task-detail-critical-fixes`)_
- **Never** leave `|| true` or other tautological conditions in UI conditionals. _(from `2026-09-08-task-detail-critical-fixes`)_
- **Always** use `SnackbarHost` + `SnackbarHostState` for undo, not `AlertDialog`. _(from `2026-09-08-task-restore-undo`)_
- **Never** store more than one recently-deleted task in memory — the most recent overwrite. _(from `2026-09-08-task-restore-undo`)_
- **Always** read entity state from the write-through `_latest<Entity>` cache, never from `state.value` snapshot in mutation methods. _(from `2026-09-09-projectdetail-write-through-fix`)_
- **Always** update `_latest<Entity>` before any async operation that reads it. _(from `2026-09-09-projectdetail-write-through-fix`)_
- **Never** emit `Saved` events for debounced inline edits — update `_lastEditedAt` only. _(from `2026-09-09-projectdetail-write-through-fix`)_
- **Always** mark every `NavKey` subtype that may appear in a stack as `@Serializable`. Without it, there is no `.serializer()` to pass to `subclass(...)`. _(from `2026-09-16-nav3-savedstate-serializers-required`)_
- **Always** provide a `serializersModule` that calls `polymorphic(NavKey::class) { subclass(...) }` for every concrete route type in the stack. _(from `2026-09-16-nav3-savedstate-serializers-required`)_
- **Never** write `SavedStateConfiguration { }` for any `rememberNavBackStack` call — the empty body silently falls back to `DEFAULT.serializersModule` and breaks the polymorphism contract. _(from `2026-09-16-nav3-savedstate-serializers-required`)_

## Per-tag

### `agent`

- AI-агенты получают нативный доступ к данным без UI _(from `2026-09-07-dogfooding-mcp-server`)_
- Dogfooding-профиль "AI Agent" (🤖) изолирует агентские задачи от пользовательских _(from `2026-09-07-dogfooding-mcp-server`)_
- ZCode подключается через `mcpServers.singularity-todo` в настройках _(from `2026-09-07-dogfooding-mcp-server`)_
- Все token usage пишется в `llm_usage` с `profile_id=ai-agent` _(from `2026-09-07-dogfooding-mcp-server`)_
- Новый Gradle-модуль `:mcp-server` с dependency на shared _(from `2026-09-07-dogfooding-mcp-server`)_

### `ai`

- A passing `:androidApp:assembleDebug` is the cross-platform smoke test (it would have failed under the old stub). _(from `2026-09-05-koog-both-platforms`)_
- `SettingsViewModel.testConnection()` **always** short-circuits with `Error("API key not configured")` when no key, **without** calling `textGen`. Tests assert this with `FakeTextGen(trackGenerateCalls = true)` and `assertEquals(emptyList(), textGen.generateCalls)`. _(from `2026-09-05-llm-provider-settings`)_
- The old `JvmPromptExecutorPort` and `AndroidPromptExecutorPort` files are deleted. _(from `2026-09-05-koog-both-platforms`)_
- The Test connection "probe" prompt is hard-coded: `"Reply with the single word: pong."` — change together with the system prompt if needed. _(from `2026-09-05-llm-provider-settings`)_
- The unified `KoogPromptExecutorPort` lives in `commonMain` and exposes `val executor: PromptExecutor` publicly for the platform `single<PromptExecutor>` binding. _(from `2026-09-05-koog-both-platforms`)_

### `android`

- **Adding a new route type on Android**: must still call `navSavedStateConfig(...)` with the new type's serializer in every NavGraph that can contain it. The `subclass(...)` registration requirement (per `2026-09-16-nav3-savedstate-serializers-required`) is unchanged on Android. _(from `2026-09-16-nav3-desktop-in-memory-no-savedstate`)_
- **Adding a new route type on Desktop**: no serializer registration needed; `rememberInMemoryNavBackStack(start)` is untyped and works for any `T : NavKey`. _(from `2026-09-16-nav3-desktop-in-memory-no-savedstate`)_
- All Android NavGraph back stack declarations become `val backStack = rememberNavBackStackTyped(savedStateConfig, start)` — clean, typed, no suppression. _(from `2026-09-16-nav3-type-asymmetry-adr`)_
- **Android build unchanged**: `assembleDebug` still compiles all Android-specific NavGraphs with full `SavedStateConfiguration` for process-death survival. _(from `2026-09-16-nav3-desktop-in-memory-no-savedstate`)_
- Any future code that calls `backStack.last()` on Android must explicitly cast. _(from `2026-09-16-nav3-type-asymmetry-adr`)_
- **Desktop in-memory only**: Closing and reopening the Desktop window resets all nested back stacks. This was already the behavior before this change — `LocalSaveableStateRegistry` was always `null`. The new code makes this explicit. _(from `2026-09-16-nav3-desktop-in-memory-no-savedstate`)_
- Risk of `ClassCastException` if the type parameter is misused. _(from `2026-09-16-nav3-type-asymmetry-adr`)_
- Smoke test: tap FAB on Inbox → verify CreateTask opens; tap FAB on Plans → verify CreateProject opens. _(from `2026-09-16-android-shell-fab-fix`)_
- `@Suppress("UNCHECKED_CAST")` removed from all 5 Android NavGraph files. _(from `2026-09-16-nav3-type-asymmetry-adr`)_
- The Android no-arg overload `rememberNavBackStack(vararg elements)` (reflection path) is **not used** in this project anymore — every call goes through the configuration overload so Android and JVM share one contract. _(from `2026-09-16-nav3-savedstate-serializers-required`)_
- The cast remains in all Android NavGraphs. _(from `2026-09-16-nav3-type-asymmetry-adr`)_
- The inline wrapper is `internal` to the Android source set — no API surface change. _(from `2026-09-16-nav3-type-asymmetry-adr`)_
- Users can now create projects directly from Plans via the FAB. _(from `2026-09-16-android-shell-fab-fix`)_
- Users can now create tasks directly from Inbox/Today via the FAB. _(from `2026-09-16-android-shell-fab-fix`)_
- When adding a new `data object` or `data class` to `AppDestination` (or any sealed route hierarchy that backs a `rememberNavBackStack`), **always** add the matching `subclass(...)` line in every relevant `serializersModule` — the compiler does not enforce this. _(from `2026-09-16-nav3-savedstate-serializers-required`)_

### `"architecture"`

- **+100% testability** — all business logic is in pure Kotlin, testable without Compose. _(from `2026-09-15-viewmodel-state-ownership`)_
- **−100% UDF violations** in this category — the rule is now written and enforced via skill. _(from `2026-09-15-viewmodel-state-ownership`)_

### `architecture`

- ~18 файлов переработано, +5 новых, -2 удалено. _(from `2026-09-14-tasks-feature-nested-nav3`)_

### `"architecture"`

- **+~20% lines in ViewModels** — state that was implicit in Composables must be made explicit in VMs. _(from `2026-09-15-viewmodel-state-ownership`)_
- 4 new files: `AccountSettingsViewModel.kt`, `TagPickerViewModel.kt`, plus DI registrations. _(from `2026-09-15-projects-settings-profile-udf-fixes`)_
- 6 modified files: `ProjectDetailViewModel.kt`, `ProjectDetailScreen.kt`, `ProjectPickerSheet.kt`, `AccountSettingsScreen.kt`, `SettingsScreen.kt`, `Modules.kt`. _(from `2026-09-15-projects-settings-profile-udf-fixes`)_

### `architecture`

- **8 экранов мигрируют одновременно** — невозможно сделать постепенную миграцию из-за смены типа `_events` _(from `2026-09-05-ui-event-per-feature`)_
- `ActiveSheet.kt`: 35 → ~15 lines (`toActiveSheet()` removed). _(from `2026-09-09-task-detail-intent-refactor`)_
- All new screens MUST follow the `PublicScreen` / `PrivateContent` naming pattern _(from `2026-09-09-preview-with-koin-helper`)_
- `AppDestination` пополнился `Notes` (уже был), логика FAB его задействует. _(from `2026-09-07-fab-chrome-level`)_
- `AppShell` — minor change: добавлен `FabAction` parameter. _(from `2026-09-07-fab-chrome-level`)_
- **Breaking:** `coreDomainModule()` удалён; заменён на `domainModule()` (includes everything). Test files обновлены. _(from `2026-09-06-di-module-split`)_
- Caller must provide `MutableStateFlow<String>` and inject `InternalLinkRepository` and `ProfileAwareCurrentUser` — slightly more boilerplate at call site _(from `2026-09-09-internal-link-picker-generic`)_

### `"architecture"`

- Checklist items can be promoted to sub-tasks via "Convert to task" overflow action. _(from `2026-09-08-task-1-level-subtasks`)_
- `collectAsState` replaced with `collectAsStateWithLifecycle` in previews. _(from `2026-09-15-projects-settings-profile-udf-fixes`)_

### `architecture`

- **CollectEvents** в виджетах принимает `Flow<T : UiEvent>` — generic call site остаётся тем же _(from `2026-09-05-ui-event-per-feature`)_
- Consistent API across all shared components _(from `2026-09-09-content-slot-pattern`)_
- `core/draft/DataStoreDraftStore.kt` _(from `2026-09-15-task-editor-unification`)_
- `core/draft/DraftStore.kt` _(from `2026-09-15-task-editor-unification`)_
- `core/draft/FakeDraftStore.kt` _(from `2026-09-15-task-editor-unification`)_
- `core/serialization/StableJson.kt` _(from `2026-09-15-task-editor-unification`)_
- `core/ui/components/` is now free of feature-domain imports _(from `2026-09-09-internal-link-picker-generic`)_
- Cross-screen state (e.g. "did the user just save a note") must flow through navigation callbacks, not shared VM state _(from `2026-09-09-notes-vm-split`)_
- Diff больше, чем чисто миграция tasks — затрагивает общий `Nav3State`. _(from `2026-09-14-nav3-vm-store-decorator-fix`)_
- Do NOT introduce `koinViewModel()` inside any `@Preview` — CI/preview harness does not start Koin _(from `2026-09-09-preview-with-koin-helper`)_
- Each VM is small enough to understand fully (~60-150 lines) _(from `2026-09-09-notes-vm-split`)_
- Easier to extend cards and editors without breaking call sites _(from `2026-09-09-content-slot-pattern`)_
- Editor session state is released when user navigates away _(from `2026-09-09-notes-vm-split`)_
- **Existing tests:** `DiGraphTest`, `JvmAiDiGraphTest`, `AppSmokeTest` обновлены и проходят. _(from `2026-09-06-di-module-split`)_
- FakeRepositories live in `commonMain/test/fakes/` (not `commonTest`) so `commonMain` previews can access them _(from `2026-09-09-preview-with-koin-helper`)_
- Icon per `LinkKind` makes the list scannable _(from `2026-09-09-internal-link-picker-generic`)_
- Instrumented/integration тесты (`CreateTaskFlowInstrumentedTest`) _(from `2026-09-14-nav3-vm-store-decorator-fix`)_
- **Known limitation**: 10 constructor parameters remain; next candidate for `TaskDetailDeps` by analogy with `TaskEditorDeps`. _(from `2026-09-09-task-detail-intent-refactor`)_
- Lifecycle VM становится привязан к lifetime entry — VM очищается _(from `2026-09-14-nav3-vm-store-decorator-fix`)_
- **`LocalNavBackStack` как публичный API** — позволяет экранам _(from `2026-09-14-tasks-feature-nested-nav3`)_

### `"architecture"`

- **Migration cost** — 7 violations across 5 PRs. See the implementation plan for the sequence. _(from `2026-09-15-viewmodel-state-ownership`)_

### `architecture`

- Migration from plain lambdas requires updating call sites _(from `2026-09-09-content-slot-pattern`)_
- Navigation между Detail и подзадачами/проектами становится _(from `2026-09-14-tasks-feature-nested-nav3`)_
- **Negative**: 40+ files had import paths updated; test files also required path corrections _(from `2026-09-09-feature-tasks-clean-architecture`)_
- **Negative**: Deep `domain/model/` import chains if not careful (mitigated by `package com.singularity.todo.feature.tasks.domain.model.*`) _(from `2026-09-09-feature-tasks-clean-architecture`)_
- **New file count:** 8 новых файлов (7 модулей + decision). _(from `2026-09-06-di-module-split`)_
- New file `TaskDetailIntent.kt` (~120 lines). _(from `2026-09-09-task-detail-intent-refactor`)_

### `"architecture"`

- `NoteEditor` now requires `InternalLinkRepository` in its constructor — updated `NotesDiModule` accordingly. _(from `2026-09-15-noteeditor-udf-link-search`)_

### `architecture`

- `NotesRoute` now injects `NotesListViewModel` via `koinViewModel()`, `NoteEditor` and `NotePreview` are injected via their respective screen composables _(from `2026-09-09-notes-vm-split`)_
- **NotificationHost** — финальный widget для всех экранов, заменяет ~64 строк ручного glue кода _(from `2026-09-05-ui-event-per-feature`)_
- `ParentOption` is a `@JvmInline value class` candidate if it grows beyond 3 fields (currently 3 — plain data class is fine) _(from `2026-09-09-parent-picker-contract`)_
- Parent options are reactive (`StateFlow`) — picker updates automatically when projects change _(from `2026-09-09-parent-picker-contract`)_
- `ParentPickerSheet` signature: `options: List<ParentOption>`, NOT `currentParentId: ProjectId?` _(from `2026-09-09-parent-picker-contract`)_
- **Positive**: Cross-feature imports are now compile-time errors if they bypass domain _(from `2026-09-09-feature-tasks-clean-architecture`)_
- **Positive**: Strict layer boundaries enforced by package structure; pure domain logic testable without Android instrumentation _(from `2026-09-09-feature-tasks-clean-architecture`)_
- **Positive**: `TaskDetailUiState.reduce()` is a pure function — covered by unit tests without mocks _(from `2026-09-09-feature-tasks-clean-architecture`)_
- `@Preview` composables are always `private` and call the `*Content` variant with manually constructed VMs _(from `2026-09-09-preview-with-koin-helper`)_

### `"architecture"`

- Preview functions in `TaskDetailViewScreen` updated to pass `emptyFlow()` for `recentlyDeleted`. _(from `2026-09-15-task-detail-drafts-undo-fix`)_

### `architecture`

- Previews for each screen can use `koinViewModel { parametersOf(...) }` without circular dependency _(from `2026-09-09-notes-vm-split`)_

### `"architecture"`

- Previews that don't use Koin continue to work since `searchNotesForLink`/`searchTasksForLink` are nullable. _(from `2026-09-15-noteeditor-udf-link-search`)_
- Previews updated: `AccountSettingsScreenLightPreview` / `DarkPreview` now construct `AccountSettingsViewModel(FakeProfileRepository())`; `SettingsScreen` preview updated similarly. _(from `2026-09-15-projects-settings-profile-udf-fixes`)_

### `architecture`

- `ProjectDetailViewModel(projectId)` — Project X → back → Project Y _(from `2026-09-14-nav3-vm-store-decorator-fix`)_
- Search debouncing (300ms) is now the caller's responsibility (implemented inside the sheet via `LaunchedEffect`) _(from `2026-09-09-internal-link-picker-generic`)_
- Sheet is reusable by any feature that needs internal linking (e.g. TaskEditor) _(from `2026-09-09-internal-link-picker-generic`)_
- `single<DraftStore> { DataStoreDraftStore(get()) }` in `CoreDiModule` _(from `2026-09-15-task-editor-unification`)_
- Single search + merged results = better UX (one tap instead of tab switching) _(from `2026-09-09-internal-link-picker-generic`)_
- **`String`-encoded `initialDueDate`** — заменён на _(from `2026-09-14-tasks-feature-nested-nav3`)_

### `"architecture"`

- Sub-task count is denormalized via `TaskFilter.ByParent` query — no need for recursive count. _(from `2026-09-08-task-1-level-subtasks`)_

### `architecture`

- `TagsScreen` больше не принимает callback — экран не подключён к навигации (menu destination `Tags` отсутствует в `AppDestination`). _(from `2026-09-07-fab-chrome-level`)_
- `TaskCreateContent.kt` _(from `2026-09-15-task-editor-unification`)_
- `TaskCreateDeps` expanded with `draftStore: DraftStore, autosaveScheduler: AutosaveScheduler` _(from `2026-09-15-task-editor-unification`)_
- `TaskCreateViewModel(initialDueDate)` — два последовательных _(from `2026-09-14-nav3-vm-store-decorator-fix`)_
- `TaskCreationTopBar.kt` _(from `2026-09-15-task-editor-unification`)_
- `TaskDetailContent` is now `internal` (stateless, previewable without Koin). _(from `2026-09-09-task-detail-intent-refactor`)_
- `TaskDetailScreen.kt`: `when (action)` on 27 branches → `when (intent)` on 6 branches. Routing now uniform (all `activeSheet = …`). _(from `2026-09-09-task-detail-intent-refactor`)_
- `TaskDetailUiEvent.kt`: 34 → ~18 lines (10 sheet-triggers removed). _(from `2026-09-09-task-detail-intent-refactor`)_
- `TaskDetailViewContent.kt` _(from `2026-09-15-task-editor-unification`)_

### `"architecture"`

- `TaskDetailViewContent` now takes a `recentlyDeleted: Flow<Task?>` parameter — passed from `TaskDetailViewScreen`. _(from `2026-09-15-task-detail-drafts-undo-fix`)_

### `architecture`

- `TaskDetailViewModel.kt`: 450 → ~270 lines, 37 public methods → 3 (`start`, `onTitleChange`, `onIntent`). _(from `2026-09-09-task-detail-intent-refactor`)_
- `TaskDetailViewModel(taskId)` — Task A → back → Task B больше _(from `2026-09-14-nav3-vm-store-decorator-fix`)_
- `TaskDetailViewModelTest`: updated 5 tests to call `vm.onIntent(Domain.X)` instead of `vm.setX(task, value)`. _(from `2026-09-09-task-detail-intent-refactor`)_

### `"architecture"`

- `TasksDiModule` removed now-unused `ProjectsRepository` import. _(from `2026-09-15-task-detail-drafts-undo-fix`)_

### `architecture`

- `TasksFormatters.kt`: added `dueChipColors` formatter and `parseDueTime` utility. _(from `2026-09-09-task-detail-intent-refactor`)_
- **`TasksRoute.Pop` как sentinel** — race condition (см. review rev. 1, _(from `2026-09-14-tasks-feature-nested-nav3`)_
- The "None (root)" option is rendered as a `TextButton` above the `LazyColumn`, not as part of `options` _(from `2026-09-09-parent-picker-contract`)_
- Three Koin registrations instead of one _(from `2026-09-09-notes-vm-split`)_
- Type-safe actions via `sealed class Action` with exhaustive `when` _(from `2026-09-09-content-slot-pattern`)_
- **UiEvent marker** — `ShowDialog/ShowError/NavigateBack` больше не определены глобально _(from `2026-09-05-ui-event-per-feature`)_
- Unit-тесты навигации tasks требуют `Robolectric` или `composeRule` — _(from `2026-09-14-tasks-feature-nested-nav3`)_
- `value class XxxActions` indirection — harder to read at first glance _(from `2026-09-09-content-slot-pattern`)_
- VMs are independently testable with focused test suites _(from `2026-09-09-notes-vm-split`)_
- В `JvmNav3State.kt` для `AppDestination.TasksGraph` / _(from `2026-09-14-tasks-feature-nested-nav3`)_
- В `TasksNavGraph.kt` (для nested `rememberNavBackStack`). _(from `2026-09-14-tasks-feature-nested-nav3`)_
- Все остальные параметризованные VM (~20 callsites). _(from `2026-09-14-nav3-vm-store-decorator-fix`)_
- Дополнительный уровень индирекции для новых разработчиков: «где я?». _(from `2026-09-14-tasks-feature-nested-nav3`)_
- Если какой-то VM был неявно расчитан на per-Activity scope _(from `2026-09-14-nav3-vm-store-decorator-fix`)_
- Необходимо зарегистрировать `TasksRoute` в двух `SerializersModule`: _(from `2026-09-14-tasks-feature-nested-nav3`)_
- **Один плоский AppDestination без nested graph** — не даёт feature _(from `2026-09-14-tasks-feature-nested-nav3`)_
- Первая фича с nested graph — другие фичи (notes/projects/auth/settings) _(from `2026-09-14-tasks-feature-nested-nav3`)_
- Рассмотреть переход на `LocalResultEventBus` + `ResultEffect<T>` для _(from `2026-09-14-nav3-vm-store-decorator-fix`)_
- Сигнатуры экранов tasks упрощаются до 1-2 аргументов. _(from `2026-09-14-tasks-feature-nested-nav3`)_
- Существующие unit-тесты для VM не затрагиваются (тестируют VM _(from `2026-09-14-nav3-vm-store-decorator-fix`)_
- **Существующие тесты** использующие `TasksViewModel`, `NotesViewModel` и т.д. — `_events.emit(UiEvent.ShowDialog(...))` нужно обновить на `TasksUiEvent.AiResult(...)` _(from `2026-09-05-ui-event-per-feature`)_
- Чинится латентный bug для всех `koinViewModel { parametersOf(...) }` _(from `2026-09-14-nav3-vm-store-decorator-fix`)_
- **Чинится латентный VM scoping bug** для `TaskDetailViewModel`, _(from `2026-09-14-tasks-feature-nested-nav3`)_

### `"archive"`

- Archive and Delete have distinct storage semantics — future "Trash" filter can distinguish intentional archive from accidental delete. _(from `2026-09-08-task-archive-restore-contract`)_
- `_recentlyDeleted` in TaskDetailViewModel holds the full task before delete/archive for undo. _(from `2026-09-08-task-archive-restore-contract`)_
- `restore()` is idempotent — calling restore on a non-archived task is a no-op (or returns Result.success if the row simply re-inserted). _(from `2026-09-08-task-archive-restore-contract`)_

### `audit`

- One new e2e test in `mcp-server` (`McpToolRoundTripTest`). _(from `2026-09-08-mcp-server-health-audit`)_
- One new unit test file in `mcp-server` (`KoogJsonSchemaBuilderTest`). _(from `2026-09-08-mcp-server-health-audit`)_
- Three new unit test files in `shared/commonTest` for the read tools. _(from `2026-09-08-mcp-server-health-audit`)_
- `ToolFactories.kt` gets the profile-aware default applied (small diff, _(from `2026-09-08-mcp-server-health-audit`)_
- `ToolRegistrar` catches `McpToolError` first (small diff). _(from `2026-09-08-mcp-server-health-audit`)_

### `backup`

- `BackupRepository` resolves correctly in all environments (JVM desktop, Android). _(from `2026-09-07-backup-directory-via-koin-string`)_
- Settings → Backup tab no longer crashes during composition. _(from `2026-09-07-backup-directory-via-koin-string`)_
- The `desktopApp/build.gradle.kts` change (adding `implementation(project(":shared"))` with kotlinJvmTask) was also part of the desktop build fix. _(from `2026-09-07-backup-directory-via-koin-string`)_

### `bug`

- Diff больше, чем чисто миграция tasks — затрагивает общий `Nav3State`. _(from `2026-09-14-nav3-vm-store-decorator-fix`)_

### `bugfix`

- **4 VM registrations** (`TaskEditorViewModel`, `TasksByProjectViewModel`, `ProjectEditorViewModel`, `ProjectDetailViewModel`) now use `viewModel { (p) → ... }` instead of `factory { (p) → ... }` _(from `2026-09-09-di-factory-viewmodel-fix`)_
- All 3 tools now require `ProfileAwareCurrentUser` in DI — tested via _(from `2026-09-08-mcp-schema-and-profile-userid-fixes`)_
- `list_tasks`, `list_linked_tasks`, and `search_tasks` now return correct results _(from `2026-09-08-mcp-schema-and-profile-userid-fixes`)_
- MCP clients that validate `$schema` as a URI will no longer reject tool schemas. _(from `2026-09-08-mcp-schema-and-profile-userid-fixes`)_
- **No call-site changes** — `koinViewModel { parametersOf(...) }` works with both forms _(from `2026-09-09-di-factory-viewmodel-fix`)_
- **State survives configuration change** on Android — rotation no longer resets these screens _(from `2026-09-09-di-factory-viewmodel-fix`)_
- **Test impact** — tests that relied on a fresh VM instance per `get()` may need updating; prefer stateful testing over instance-fresh guarantees _(from `2026-09-09-di-factory-viewmodel-fix`)_

### `bug`

- Instrumented/integration тесты (`CreateTaskFlowInstrumentedTest`) _(from `2026-09-14-nav3-vm-store-decorator-fix`)_
- Lifecycle VM становится привязан к lifetime entry — VM очищается _(from `2026-09-14-nav3-vm-store-decorator-fix`)_
- `ProjectDetailViewModel(projectId)` — Project X → back → Project Y _(from `2026-09-14-nav3-vm-store-decorator-fix`)_
- `TaskCreateViewModel(initialDueDate)` — два последовательных _(from `2026-09-14-nav3-vm-store-decorator-fix`)_
- `TaskDetailViewModel(taskId)` — Task A → back → Task B больше _(from `2026-09-14-nav3-vm-store-decorator-fix`)_
- Все остальные параметризованные VM (~20 callsites). _(from `2026-09-14-nav3-vm-store-decorator-fix`)_
- Если какой-то VM был неявно расчитан на per-Activity scope _(from `2026-09-14-nav3-vm-store-decorator-fix`)_
- Рассмотреть переход на `LocalResultEventBus` + `ResultEffect<T>` для _(from `2026-09-14-nav3-vm-store-decorator-fix`)_
- Существующие unit-тесты для VM не затрагиваются (тестируют VM _(from `2026-09-14-nav3-vm-store-decorator-fix`)_
- Чинится латентный bug для всех `koinViewModel { parametersOf(...) }` _(from `2026-09-14-nav3-vm-store-decorator-fix`)_

### `build`

- All JetBrains compose library versions MUST track `version.ref = "composeMultiplatform"`. Split-version declarations are forbidden unless the artifact is an AndroidX (not JetBrains) group. _(from `2026-09-06-compose-multiplatform-1.12.0-bump`)_
- The `checkJvmMainComposeLibrariesCompatibility` task must pass silently on every PR. _(from `2026-09-06-compose-multiplatform-1.12.0-bump`)_
- When adding a new third-party Compose dependency, verify its JetBrains compose `requires:` constraint in the Gradle module metadata (`.module` file in cache) before adding — if it demands a version newer than the current pin, either bump or find an alternative. _(from `2026-09-06-compose-multiplatform-1.12.0-bump`)_

### `clean-architecture`

- **Negative**: 40+ files had import paths updated; test files also required path corrections _(from `2026-09-09-feature-tasks-clean-architecture`)_
- **Negative**: Deep `domain/model/` import chains if not careful (mitigated by `package com.singularity.todo.feature.tasks.domain.model.*`) _(from `2026-09-09-feature-tasks-clean-architecture`)_
- **Positive**: Cross-feature imports are now compile-time errors if they bypass domain _(from `2026-09-09-feature-tasks-clean-architecture`)_
- **Positive**: Strict layer boundaries enforced by package structure; pure domain logic testable without Android instrumentation _(from `2026-09-09-feature-tasks-clean-architecture`)_
- **Positive**: `TaskDetailUiState.reduce()` is a pure function — covered by unit tests without mocks _(from `2026-09-09-feature-tasks-clean-architecture`)_

### `"compose"`

- **+100% testability** — all business logic is in pure Kotlin, testable without Compose. _(from `2026-09-15-viewmodel-state-ownership`)_
- **−100% UDF violations** in this category — the rule is now written and enforced via skill. _(from `2026-09-15-viewmodel-state-ownership`)_
- **+~20% lines in ViewModels** — state that was implicit in Composables must be made explicit in VMs. _(from `2026-09-15-viewmodel-state-ownership`)_
- 4 new files: `AccountSettingsViewModel.kt`, `TagPickerViewModel.kt`, plus DI registrations. _(from `2026-09-15-projects-settings-profile-udf-fixes`)_
- 6 modified files: `ProjectDetailViewModel.kt`, `ProjectDetailScreen.kt`, `ProjectPickerSheet.kt`, `AccountSettingsScreen.kt`, `SettingsScreen.kt`, `Modules.kt`. _(from `2026-09-15-projects-settings-profile-udf-fixes`)_

### `compose`

- `ActiveSheet.kt`: 35 → ~15 lines (`toActiveSheet()` removed). _(from `2026-09-09-task-detail-intent-refactor`)_
- All changes are additive; no existing behavior is removed. _(from `2026-09-07-settings-ux-improvements`)_
- All JetBrains compose library versions MUST track `version.ref = "composeMultiplatform"`. Split-version declarations are forbidden unless the artifact is an AndroidX (not JetBrains) group. _(from `2026-09-06-compose-multiplatform-1.12.0-bump`)_
- All new screens MUST follow the `PublicScreen` / `PrivateContent` naming pattern _(from `2026-09-09-preview-with-koin-helper`)_
- Backup confirm dialogs prevent accidental data loss. _(from `2026-09-07-settings-ux-improvements`)_
- **BottomBar taps** now have a single source of truth: `navigator.navigateTopLevel(dest)` — no `selectedIndex` to keep in sync. _(from `2026-09-05-android-bottom-nav`)_
- `Clock.System.now()` must not appear in preview code — use _(from `2026-09-06-compose-previews`)_

### `"compose"`

- `collectAsState` replaced with `collectAsStateWithLifecycle` in previews. _(from `2026-09-15-projects-settings-profile-udf-fixes`)_

### `compose`

- `compose-ui-test:1.12.0` added to `libs.versions.toml` as `composeUiTest` _(from `2026-09-06-desktop-smoke-test-with-koin`)_
- Consistent API across all shared components _(from `2026-09-09-content-slot-pattern`)_
- `core/draft/DataStoreDraftStore.kt` _(from `2026-09-15-task-editor-unification`)_
- `core/draft/DraftStore.kt` _(from `2026-09-15-task-editor-unification`)_
- `core/draft/FakeDraftStore.kt` _(from `2026-09-15-task-editor-unification`)_
- `core/serialization/StableJson.kt` _(from `2026-09-15-task-editor-unification`)_
- Debounce reduces SecureStorage/DataStore writes by ~90% during text input. _(from `2026-09-07-settings-ux-improvements`)_
- Desktop chrome is a 240 dp left rail, VSCode/JetBrains-style. Width is explicit, not derived from drawer measurements. _(from `2026-09-06-desktop-sidebar-replaces-permanent-drawer`)_
- **Desktop chrome** is unchanged from the user's perspective — the drawer still works exactly as before. _(from `2026-09-05-android-bottom-nav`)_
- Do NOT introduce `koinViewModel()` inside any `@Preview` — CI/preview harness does not start Koin _(from `2026-09-09-preview-with-koin-helper`)_
- Easier to extend cards and editors without breaking call sites _(from `2026-09-09-content-slot-pattern`)_
- Every `NavDestination` entry has an `icon` field. When adding a new entry, pick an icon from `androidx.compose.material.icons.Filled` or `Icons.AutoMirrored.Filled`. _(from `2026-09-06-desktop-sidebar-replaces-permanent-drawer`)_
- FakeRepositories live in `commonMain/test/fakes/` (not `commonTest`) so `commonMain` previews can access them _(from `2026-09-09-preview-with-koin-helper`)_
- **Known limitation**: 10 constructor parameters remain; next candidate for `TaskDetailDeps` by analogy with `TaskEditorDeps`. _(from `2026-09-09-task-detail-intent-refactor`)_
- **Menu sheet visibility** is `rememberSaveable` state in `AndroidShell` — survives config changes, not part of the back stack. _(from `2026-09-05-android-bottom-nav`)_

### `"compose"`

- **Migration cost** — 7 violations across 5 PRs. See the implementation plan for the sequence. _(from `2026-09-15-viewmodel-state-ownership`)_

### `compose`

- Migration from plain lambdas requires updating call sites _(from `2026-09-09-content-slot-pattern`)_
- `ModalShell` + `DrawerStyle.Modal` remain in `AppShell.kt`. They are not wired to any platform but are preserved for future modal drawer needs. _(from `2026-09-06-desktop-sidebar-replaces-permanent-drawer`)_
- **`NavDestination` (drawer enum)** remains for the desktop drawer's grouping by `NavGroup` — not removed, just no longer wired to mobile. _(from `2026-09-05-android-bottom-nav`)_
- Navigation interaction tests (click-to-navigate) are out of scope for this smoke test — they require handling NavBackStackEntry lifecycle in `runDesktopComposeUiTest` _(from `2026-09-06-desktop-smoke-test-with-koin`)_
- New file `TaskDetailIntent.kt` (~120 lines). _(from `2026-09-09-task-detail-intent-refactor`)_
- **Per-tab backstacks** work as expected: open TaskDetail on Today, switch to Plans, switch back to Today → TaskDetail is restored. _(from `2026-09-05-android-bottom-nav`)_
- `@Preview` annotation is `@androidx.compose.ui.tooling.preview.Preview` — _(from `2026-09-06-compose-previews`)_
- `@Preview` composables are always `private` and call the `*Content` variant with manually constructed VMs _(from `2026-09-09-preview-with-koin-helper`)_
- Preview functions are `private` and placed at the end of the source file, _(from `2026-09-06-compose-previews`)_

### `"compose"`

- Preview functions in `TaskDetailViewScreen` updated to pass `emptyFlow()` for `recentlyDeleted`. _(from `2026-09-15-task-detail-drafts-undo-fix`)_

### `compose`

- `PreviewParameterProvider` is avoided — individual preview functions used instead _(from `2026-09-06-compose-previews`)_

### `"compose"`

- Previews updated: `AccountSettingsScreenLightPreview` / `DarkPreview` now construct `AccountSettingsViewModel(FakeProfileRepository())`; `SettingsScreen` preview updated similarly. _(from `2026-09-15-projects-settings-profile-udf-fixes`)_

### `compose`

- `single<DraftStore> { DataStoreDraftStore(get()) }` in `CoreDiModule` _(from `2026-09-15-task-editor-unification`)_
- `singularity-todo-shared-ui-components` skill governs decomposition: desktop-only chrome stays in `feature/nav/`, shared widgets go to `core/ui/components/`. _(from `2026-09-06-desktop-sidebar-replaces-permanent-drawer`)_
- Smoke test now passes: `./gradlew :desktopApp:test` → BUILD SUCCESSFUL _(from `2026-09-06-desktop-smoke-test-with-koin`)_
- `sourceSets { test { java.srcDirs("src/jvmTest") ... } }` added to `desktopApp/build.gradle.kts` to wire the `jvmTest` source set to the `test` task _(from `2026-09-06-desktop-smoke-test-with-koin`)_
- `TaskCreateContent.kt` _(from `2026-09-15-task-editor-unification`)_
- `TaskCreateDeps` expanded with `draftStore: DraftStore, autosaveScheduler: AutosaveScheduler` _(from `2026-09-15-task-editor-unification`)_
- `TaskCreationTopBar.kt` _(from `2026-09-15-task-editor-unification`)_
- `TaskDetailContent` is now `internal` (stateless, previewable without Koin). _(from `2026-09-09-task-detail-intent-refactor`)_
- `TaskDetailScreen.kt`: `when (action)` on 27 branches → `when (intent)` on 6 branches. Routing now uniform (all `activeSheet = …`). _(from `2026-09-09-task-detail-intent-refactor`)_
- `TaskDetailUiEvent.kt`: 34 → ~18 lines (10 sheet-triggers removed). _(from `2026-09-09-task-detail-intent-refactor`)_
- `TaskDetailViewContent.kt` _(from `2026-09-15-task-editor-unification`)_

### `"compose"`

- `TaskDetailViewContent` now takes a `recentlyDeleted: Flow<Task?>` parameter — passed from `TaskDetailViewScreen`. _(from `2026-09-15-task-detail-drafts-undo-fix`)_

### `compose`

- `TaskDetailViewModel.kt`: 450 → ~270 lines, 37 public methods → 3 (`start`, `onTitleChange`, `onIntent`). _(from `2026-09-09-task-detail-intent-refactor`)_
- `TaskDetailViewModelTest`: updated 5 tests to call `vm.onIntent(Domain.X)` instead of `vm.setX(task, value)`. _(from `2026-09-09-task-detail-intent-refactor`)_

### `"compose"`

- `TasksDiModule` removed now-unused `ProjectsRepository` import. _(from `2026-09-15-task-detail-drafts-undo-fix`)_

### `compose`

- `TasksFormatters.kt`: added `dueChipColors` formatter and `parseDueTime` utility. _(from `2026-09-09-task-detail-intent-refactor`)_
- **`TasksScreen`** unchanged — it already takes `onNavigateToTask` / `onNavigateToCreateTask` callbacks; the per-tab sub-navigation state now lives in `TasksRoute` inside `AppNavHost` via `rememberSaveable`. _(from `2026-09-05-android-bottom-nav`)_
- Test suite (`SettingsViewModelTest`) updated to work with debounce bypass in test mode. _(from `2026-09-07-settings-ux-improvements`)_
- The `checkJvmMainComposeLibrariesCompatibility` task must pass silently on every PR. _(from `2026-09-06-compose-multiplatform-1.12.0-bump`)_
- Type-safe actions via `sealed class Action` with exhaustive `when` _(from `2026-09-09-content-slot-pattern`)_
- `useSurface = false` when the preview root already contains a `Scaffold` _(from `2026-09-06-compose-previews`)_
- `value class XxxActions` indirection — harder to read at first glance _(from `2026-09-09-content-slot-pattern`)_
- When adding a new third-party Compose dependency, verify its JetBrains compose `requires:` constraint in the Gradle module metadata (`.module` file in cache) before adding — if it demands a version newer than the current pin, either bump or find an alternative. _(from `2026-09-06-compose-multiplatform-1.12.0-bump`)_

### `coroutines`

- `./gradlew :mcp-server:test` now includes a regression test (`McpServerEndToEndTest.server_blocks_until_stdin_closes`) that asserts `process.isAlive` after 3s of empty stdin. If anyone removes the blocking primitive, this test fails. _(from `2026-09-07-mcp-stdio-blocking-lifecycle`)_
- `koinBridge` is for one-shot startup reads only — **not for** hot-path code, **not for** long-running operations. _(from `2026-09-05-koin-suspend-bridge`)_
- MCP client (ZCode CLI) now sees the `initialize` roundtrip succeed and can list/call tools. _(from `2026-09-07-mcp-stdio-blocking-lifecycle`)_
- Process exit semantics change from "instant" to "on stdin EOF or session error". A passing test asserts the process stays alive ≥3s with empty stdin. _(from `2026-09-07-mcp-stdio-blocking-lifecycle`)_
- **Raw `runBlocking` в модулях** — не допускается, `koinBridge` как единая точка входа _(from `2026-09-06-koin-bridge-audit`)_
- `Runtime.getRuntime().addShutdownHook { server.close() }` becomes redundant for normal EOF exits — `onClose → done.complete() → done.join() returns → runBlocking exits → JVM exits cleanly`. We keep the shutdown hook only as a backstop for SIGTERM. _(from `2026-09-07-mcp-stdio-blocking-lifecycle`)_
- **`singleOf` для репозиториев** — architectural limitation; сложные конструкторы не поддерживают constructor-reference форму _(from `2026-09-06-koin-bridge-audit`)_
- The downstream `ToolRegistrar` and tools still run inside `runBlocking { koogTool.execute(args) }` per call — coroutine scope inside the request handler, no change. _(from `2026-09-07-mcp-stdio-blocking-lifecycle`)_
- When the script's grep is broken (a stray `runBlocking` appears), fix it immediately; the helper exists specifically so this is detectable. _(from `2026-09-05-koin-suspend-bridge`)_
- **Правило подтверждено:** `koinBridge` только для one-shot startup suspend reads _(from `2026-09-06-koin-bridge-audit`)_

### `coverage`

- **Configuration cache**: detekt 1.23.x and kover 0.9.9 are both CC-compatible. Verified by running `./gradlew --configuration-cache :shared:detekt`. _(from `2026-09-15-detekt-ktlint-kover-setup`)_
- `:desktopApp:detekt` / `:desktopApp:detektFormat` / `:desktopApp:detektBaseline` _(from `2026-09-15-detekt-ktlint-kover-setup`)_
- `:desktopApp:koverXmlReport` / `:desktopApp:koverHtmlReport` _(from `2026-09-15-detekt-ktlint-kover-setup`)_
- **detekt 2.0.0-alpha.3 vs Kotlin 2.3.21**: this version was chosen because stable 1.23.8 was compiled against Kotlin 2.0.21 and throws "detekt was compiled with Kotlin 2.0.21 but is currently running with 2.3.21". Upgrade to stable 2.x once released. _(from `2026-09-15-detekt-ktlint-kover-setup`)_
- **`.editorconfig` may rewrap existing code** on first `detektFormat` run. Expect a large diff; consider a separate "format" commit before merging. _(from `2026-09-15-detekt-ktlint-kover-setup`)_
- **`ignoreFailures = true`** means violations are reported but never block builds. To enforce violations: set `ignoreFailures = false` in both `shared/build.gradle.kts` and `desktopApp/build.gradle.kts` once baselines are settled. **TODO: tracked in issue tracker — promote after baselines are clean (est. post-format PR).** _(from `2026-09-15-detekt-ktlint-kover-setup`)_
- **New Gradle tasks added**: _(from `2026-09-15-detekt-ktlint-kover-setup`)_
- `:shared:detekt` / `:shared:detektFormat` / `:shared:detektBaseline` _(from `2026-09-15-detekt-ktlint-kover-setup`)_
- `:shared:koverXmlReport` / `:shared:koverHtmlReport` _(from `2026-09-15-detekt-ktlint-kover-setup`)_

### `dead-code`

- One new e2e test in `mcp-server` (`McpToolRoundTripTest`). _(from `2026-09-08-mcp-server-health-audit`)_
- One new unit test file in `mcp-server` (`KoogJsonSchemaBuilderTest`). _(from `2026-09-08-mcp-server-health-audit`)_
- Three new unit test files in `shared/commonTest` for the read tools. _(from `2026-09-08-mcp-server-health-audit`)_
- `ToolFactories.kt` gets the profile-aware default applied (small diff, _(from `2026-09-08-mcp-server-health-audit`)_
- `ToolRegistrar` catches `McpToolError` first (small diff). _(from `2026-09-08-mcp-server-health-audit`)_

### `debugging`

- All new `catch` blocks in ViewModels, repositories, and use cases should inject `Logger` and call `log.e(e) { "..." }` or use `runCatchingLogged`. _(from `2026-09-06-kermit-logging-setup`)_
- `BuildConfig.DEBUG` requires `buildConfig = true` in `androidApp/build.gradle.kts`. No BuildConfig is available in `shared` jvm target. _(from `2026-09-06-kermit-logging-setup`)_
- Existing silent `catch (_: Exception)` (e.g., in `ToolFactories.kt` lines 58, 126, 181) remain unfixed — these require separate investigation (some appear to be copy-paste bugs, not intentional suppression). _(from `2026-09-06-kermit-logging-setup`)_
- Koin logs (`NoDefinitionFoundException`, etc.) now appear in Kermit's output via `KermitKoinLogger`. _(from `2026-09-06-kermit-logging-setup`)_
- On JVM, `ColorizedWriter` uses `\u001B` ANSI escapes. Older Windows terminals (pre-10) will print escape sequences literally. `NO_COLOR` env var is respected. _(from `2026-09-06-kermit-logging-setup`)_
- `RefineTaskTool.kt:34-38` has identical try and catch branches (copy-paste bug) — not fixed in this PR. _(from `2026-09-06-kermit-logging-setup`)_

### `desktop`

- **Adding a new route type on Android**: must still call `navSavedStateConfig(...)` with the new type's serializer in every NavGraph that can contain it. The `subclass(...)` registration requirement (per `2026-09-16-nav3-savedstate-serializers-required`) is unchanged on Android. _(from `2026-09-16-nav3-desktop-in-memory-no-savedstate`)_
- **Adding a new route type on Desktop**: no serializer registration needed; `rememberInMemoryNavBackStack(start)` is untyped and works for any `T : NavKey`. _(from `2026-09-16-nav3-desktop-in-memory-no-savedstate`)_
- **Android build unchanged**: `assembleDebug` still compiles all Android-specific NavGraphs with full `SavedStateConfiguration` for process-death survival. _(from `2026-09-16-nav3-desktop-in-memory-no-savedstate`)_
- `compose-ui-test:1.12.0` added to `libs.versions.toml` as `composeUiTest` _(from `2026-09-06-desktop-smoke-test-with-koin`)_
- Desktop chrome is a 240 dp left rail, VSCode/JetBrains-style. Width is explicit, not derived from drawer measurements. _(from `2026-09-06-desktop-sidebar-replaces-permanent-drawer`)_
- **Desktop in-memory only**: Closing and reopening the Desktop window resets all nested back stacks. This was already the behavior before this change — `LocalSaveableStateRegistry` was always `null`. The new code makes this explicit. _(from `2026-09-16-nav3-desktop-in-memory-no-savedstate`)_
- Every `NavDestination` entry has an `icon` field. When adding a new entry, pick an icon from `androidx.compose.material.icons.Filled` or `Icons.AutoMirrored.Filled`. _(from `2026-09-06-desktop-sidebar-replaces-permanent-drawer`)_
- `ModalShell` + `DrawerStyle.Modal` remain in `AppShell.kt`. They are not wired to any platform but are preserved for future modal drawer needs. _(from `2026-09-06-desktop-sidebar-replaces-permanent-drawer`)_
- Navigation interaction tests (click-to-navigate) are out of scope for this smoke test — they require handling NavBackStackEntry lifecycle in `runDesktopComposeUiTest` _(from `2026-09-06-desktop-smoke-test-with-koin`)_
- `singularity-todo-shared-ui-components` skill governs decomposition: desktop-only chrome stays in `feature/nav/`, shared widgets go to `core/ui/components/`. _(from `2026-09-06-desktop-sidebar-replaces-permanent-drawer`)_
- Smoke test now passes: `./gradlew :desktopApp:test` → BUILD SUCCESSFUL _(from `2026-09-06-desktop-smoke-test-with-koin`)_
- `sourceSets { test { java.srcDirs("src/jvmTest") ... } }` added to `desktopApp/build.gradle.kts` to wire the `jvmTest` source set to the `test` task _(from `2026-09-06-desktop-smoke-test-with-koin`)_

### `detekt`

- **Configuration cache**: detekt 1.23.x and kover 0.9.9 are both CC-compatible. Verified by running `./gradlew --configuration-cache :shared:detekt`. _(from `2026-09-15-detekt-ktlint-kover-setup`)_
- `:desktopApp:detekt` / `:desktopApp:detektFormat` / `:desktopApp:detektBaseline` _(from `2026-09-15-detekt-ktlint-kover-setup`)_
- `:desktopApp:koverXmlReport` / `:desktopApp:koverHtmlReport` _(from `2026-09-15-detekt-ktlint-kover-setup`)_
- **detekt 2.0.0-alpha.3 vs Kotlin 2.3.21**: this version was chosen because stable 1.23.8 was compiled against Kotlin 2.0.21 and throws "detekt was compiled with Kotlin 2.0.21 but is currently running with 2.3.21". Upgrade to stable 2.x once released. _(from `2026-09-15-detekt-ktlint-kover-setup`)_
- **`.editorconfig` may rewrap existing code** on first `detektFormat` run. Expect a large diff; consider a separate "format" commit before merging. _(from `2026-09-15-detekt-ktlint-kover-setup`)_
- **`ignoreFailures = true`** means violations are reported but never block builds. To enforce violations: set `ignoreFailures = false` in both `shared/build.gradle.kts` and `desktopApp/build.gradle.kts` once baselines are settled. **TODO: tracked in issue tracker — promote after baselines are clean (est. post-format PR).** _(from `2026-09-15-detekt-ktlint-kover-setup`)_
- **New Gradle tasks added**: _(from `2026-09-15-detekt-ktlint-kover-setup`)_
- `:shared:detekt` / `:shared:detektFormat` / `:shared:detektBaseline` _(from `2026-09-15-detekt-ktlint-kover-setup`)_
- `:shared:koverXmlReport` / `:shared:koverHtmlReport` _(from `2026-09-15-detekt-ktlint-kover-setup`)_

### `"di"`

- 4 new files: `AccountSettingsViewModel.kt`, `TagPickerViewModel.kt`, plus DI registrations. _(from `2026-09-15-projects-settings-profile-udf-fixes`)_

### `di`

- **4 VM registrations** (`TaskEditorViewModel`, `TasksByProjectViewModel`, `ProjectEditorViewModel`, `ProjectDetailViewModel`) now use `viewModel { (p) → ... }` instead of `factory { (p) → ... }` _(from `2026-09-09-di-factory-viewmodel-fix`)_

### `"di"`

- 6 modified files: `ProjectDetailViewModel.kt`, `ProjectDetailScreen.kt`, `ProjectPickerSheet.kt`, `AccountSettingsScreen.kt`, `SettingsScreen.kt`, `Modules.kt`. _(from `2026-09-15-projects-settings-profile-udf-fixes`)_

### `di`

- `BackupRepository` resolves correctly in all environments (JVM desktop, Android). _(from `2026-09-07-backup-directory-via-koin-string`)_
- **Breaking:** `coreDomainModule()` удалён; заменён на `domainModule()` (includes everything). Test files обновлены. _(from `2026-09-06-di-module-split`)_

### `"di"`

- `collectAsState` replaced with `collectAsStateWithLifecycle` in previews. _(from `2026-09-15-projects-settings-profile-udf-fixes`)_

### `di`

- Cross-screen state (e.g. "did the user just save a note") must flow through navigation callbacks, not shared VM state _(from `2026-09-09-notes-vm-split`)_
- Each VM is small enough to understand fully (~60-150 lines) _(from `2026-09-09-notes-vm-split`)_
- Editor session state is released when user navigates away _(from `2026-09-09-notes-vm-split`)_
- **Existing tests:** `DiGraphTest`, `JvmAiDiGraphTest`, `AppSmokeTest` обновлены и проходят. _(from `2026-09-06-di-module-split`)_

### `di-graph`

- `App.kt` инжектит `SettingsRepository` через Koin — это нормально, Koin доступен в Common startup. _(from `2026-09-07-settings-fixes`)_
- `FakeNotesRepository` и `FakeNoteDao` обновлены同步. _(from `2026-09-07-note-editor-body-load`)_
- `NoteDao.updateContent` сигнатура изменилась: добавлен параметр `html: String`. _(from `2026-09-07-note-editor-body-load`)_
- `NotesRepository.createWithContent` и `updateContent` сигнатуры изменились: добавлен параметр `bodyHtml: String`. _(from `2026-09-07-note-editor-body-load`)_
- `SettingsNavRail` Column теперь содержит Box с CircleShape — Layout инлайн, не refactor. _(from `2026-09-07-settings-fixes`)_
- `TextGenPort.listModels` — добавлен в интерфейс, реализация в `KoogAgentService` и `FakeTextGen`. _(from `2026-09-07-settings-fixes`)_
- Все 6 sub-screens имеют `verticalScroll` — контент больше не обрезается. _(from `2026-09-07-settings-fixes`)_
- Все существующие тесты проходят — никаких изменений в тестовых вызовах не потребовалось (jvmTest зелёный). _(from `2026-09-07-note-editor-body-load`)_
- При первом открытии старой заметки (без `bodyHtml`) — форматирование может отличаться от исходного (round-trip через markdown). Это accepted trade-off для legacy data. _(from `2026-09-07-note-editor-body-load`)_

### `di`

- `koinBridge` is for one-shot startup reads only — **not for** hot-path code, **not for** long-running operations. _(from `2026-09-05-koin-suspend-bridge`)_
- **`koinInject()` для репозиториев/сервисов остаётся** — не VM _(from `2026-09-06-koin-vm-viewmodelof-koinviewmodel`)_
- **`koinViewModel()` для VM в Composable** — `koinInject()` для VM антипаттерн _(from `2026-09-06-koin-vm-viewmodelof-koinviewmodel`)_
- **New file count:** 8 новых файлов (7 модулей + decision). _(from `2026-09-06-di-module-split`)_
- **No call-site changes** — `koinViewModel { parametersOf(...) }` works with both forms _(from `2026-09-09-di-factory-viewmodel-fix`)_

### `"di"`

- `NoteEditor` now requires `InternalLinkRepository` in its constructor — updated `NotesDiModule` accordingly. _(from `2026-09-15-noteeditor-udf-link-search`)_

### `di`

- `NotesRoute` now injects `NotesListViewModel` via `koinViewModel()`, `NoteEditor` and `NotePreview` are injected via their respective screen composables _(from `2026-09-09-notes-vm-split`)_
- Previews for each screen can use `koinViewModel { parametersOf(...) }` without circular dependency _(from `2026-09-09-notes-vm-split`)_

### `"di"`

- Previews that don't use Koin continue to work since `searchNotesForLink`/`searchTasksForLink` are nullable. _(from `2026-09-15-noteeditor-udf-link-search`)_
- Previews updated: `AccountSettingsScreenLightPreview` / `DarkPreview` now construct `AccountSettingsViewModel(FakeProfileRepository())`; `SettingsScreen` preview updated similarly. _(from `2026-09-15-projects-settings-profile-udf-fixes`)_

### `di`

- **@Preview и widget-тесты не затрагиваются** — все preview используют `*Content` helpers (stateless) _(from `2026-09-06-koin-vm-viewmodelof-koinviewmodel`)_
- **Raw `runBlocking` в модулях** — не допускается, `koinBridge` как единая точка входа _(from `2026-09-06-koin-bridge-audit`)_
- Settings → Backup tab no longer crashes during composition. _(from `2026-09-07-backup-directory-via-koin-string`)_
- **`singleOf` для репозиториев** — architectural limitation; сложные конструкторы не поддерживают constructor-reference форму _(from `2026-09-06-koin-bridge-audit`)_
- **`singularity-todo-vm-koin-scoping` skill** — создан как single source of truth _(from `2026-09-06-koin-vm-viewmodelof-koinviewmodel`)_
- **State survives configuration change** on Android — rotation no longer resets these screens _(from `2026-09-09-di-factory-viewmodel-fix`)_
- **`TaskEditorViewModel` special case** — `viewModel { (initialDueDate) -> ... }` + `koinViewModel { parametersOf(initialDueDate) }` _(from `2026-09-06-koin-vm-viewmodelof-koinviewmodel`)_
- **Test impact** — tests that relied on a fresh VM instance per `get()` may need updating; prefer stateful testing over instance-fresh guarantees _(from `2026-09-09-di-factory-viewmodel-fix`)_
- The `desktopApp/build.gradle.kts` change (adding `implementation(project(":shared"))` with kotlinJvmTask) was also part of the desktop build fix. _(from `2026-09-07-backup-directory-via-koin-string`)_
- Three Koin registrations instead of one _(from `2026-09-09-notes-vm-split`)_
- **`viewModelOf(::VM)` для VM без nullable dep** — предпочтительный паттерн _(from `2026-09-06-koin-vm-viewmodelof-koinviewmodel`)_
- **`viewModel { Vm(get(), get(), ...) }`** — для VM с nullable dep + getOrNull() (TasksViewModel, ProjectsViewModel) _(from `2026-09-06-koin-vm-viewmodelof-koinviewmodel`)_
- VMs are independently testable with focused test suites _(from `2026-09-09-notes-vm-split`)_
- When the script's grep is broken (a stray `runBlocking` appears), fix it immediately; the helper exists specifically so this is detectable. _(from `2026-09-05-koin-suspend-bridge`)_
- **Правило подтверждено:** `koinBridge` только для one-shot startup suspend reads _(from `2026-09-06-koin-bridge-audit`)_

### `dogfooding`

- 4 ADR entries created + DIGEST.md refreshed _(from `2026-09-07-multi-profile-and-usage-tracking`)_

### `"dogfooding"`

- ADR пишется в `docs/decisions/{YYYY-MM-DD}-{slug}.md` (server-side date). _(from `2026-09-08-mcp-plan-tracking-via-mcp`)_

### `dogfooding`

- AI Usage screen в Settings _(from `2026-09-07-multi-profile-and-usage-tracking`)_
- AI-агенты получают нативный доступ к данным без UI _(from `2026-09-07-dogfooding-mcp-server`)_
- Dogfooding-профиль "AI Agent" (🤖) изолирует агентские задачи от пользовательских _(from `2026-09-07-dogfooding-mcp-server`)_
- `ProfileAwareCurrentUser` инжектится во все write-tools _(from `2026-09-07-multi-profile-and-usage-tracking`)_
- Room schema v8 с `llm_usage` table + `profiles` table _(from `2026-09-07-multi-profile-and-usage-tracking`)_

### `"dogfooding"`

- tag 'mcp-ux'/'ui-subtask'/'ai-tooling'/'mcp-policy'/'refactor' — 5 persistent categories для фильтрации. _(from `2026-09-08-mcp-dogfooding-round-2`)_

### `dogfooding`

- ZCode подключается с `--profile=ai-agent` → все операции в профиле ai-agent _(from `2026-09-07-multi-profile-and-usage-tracking`)_
- ZCode подключается через `mcpServers.singularity-todo` в настройках _(from `2026-09-07-dogfooding-mcp-server`)_

### `"dogfooding"`

- В профиле ai-agent теперь 5 top-level plans × ~6 sub-tasks = ~30 новых rows. _(from `2026-09-08-mcp-dogfooding-round-2`)_

### `dogfooding`

- Все token usage пишется в `llm_usage` с `profile_id=ai-agent` _(from `2026-09-07-dogfooding-mcp-server`)_

### `"dogfooding"`

- Каждый plan имеет parentTaskId = top-task; UI должен теперь уметь их показать (см. plan 'ui-subtask'). _(from `2026-09-08-mcp-dogfooding-round-2`)_

### `dogfooding`

- Новый Gradle-модуль `:mcp-server` с dependency на shared _(from `2026-09-07-dogfooding-mcp-server`)_

### `"dogfooding"`

- Один прогон драйвера = реальная multi-step демонстрация MCP. _(from `2026-09-08-mcp-plan-tracking-via-mcp`)_
- При недоступности LLM в драйвере зашит fallback sub-task'ов. _(from `2026-09-08-mcp-plan-tracking-via-mcp`)_

### `drafts`

- `core/draft/DataStoreDraftStore.kt` _(from `2026-09-15-task-editor-unification`)_
- `core/draft/DraftStore.kt` _(from `2026-09-15-task-editor-unification`)_
- `core/draft/FakeDraftStore.kt` _(from `2026-09-15-task-editor-unification`)_
- `core/serialization/StableJson.kt` _(from `2026-09-15-task-editor-unification`)_

### `"drafts"`

- Preview functions in `TaskDetailViewScreen` updated to pass `emptyFlow()` for `recentlyDeleted`. _(from `2026-09-15-task-detail-drafts-undo-fix`)_

### `drafts`

- `single<DraftStore> { DataStoreDraftStore(get()) }` in `CoreDiModule` _(from `2026-09-15-task-editor-unification`)_
- `TaskCreateContent.kt` _(from `2026-09-15-task-editor-unification`)_
- `TaskCreateDeps` expanded with `draftStore: DraftStore, autosaveScheduler: AutosaveScheduler` _(from `2026-09-15-task-editor-unification`)_
- `TaskCreationTopBar.kt` _(from `2026-09-15-task-editor-unification`)_
- `TaskDetailViewContent.kt` _(from `2026-09-15-task-editor-unification`)_

### `"drafts"`

- `TaskDetailViewContent` now takes a `recentlyDeleted: Flow<Task?>` parameter — passed from `TaskDetailViewScreen`. _(from `2026-09-15-task-detail-drafts-undo-fix`)_
- `TasksDiModule` removed now-unused `ProjectsRepository` import. _(from `2026-09-15-task-detail-drafts-undo-fix`)_

### `error-handling`

- AI-агент парсит `isError: true` из `result` для business errors и ловит `-32603` из `error` для internal _(from `2026-09-07-mcp-tool-error-model`)_
- `ErrorMapper.kt` маппит `McpToolError` в `CallToolResult` или бросает `McpException` _(from `2026-09-07-mcp-tool-error-model`)_
- `McpToolError.kt` в `mcp-server/src/main/kotlin/com/singularity/todo/mcp/errors/` _(from `2026-09-07-mcp-tool-error-model`)_
- Все write-tools используют `Result<T>` + `mapCatching` для differentiation `Internal` от `Validation`/etc. _(from `2026-09-07-mcp-tool-error-model`)_

### `events`

- **8 экранов мигрируют одновременно** — невозможно сделать постепенную миграцию из-за смены типа `_events` _(from `2026-09-05-ui-event-per-feature`)_
- **CollectEvents** в виджетах принимает `Flow<T : UiEvent>` — generic call site остаётся тем же _(from `2026-09-05-ui-event-per-feature`)_
- **NotificationHost** — финальный widget для всех экранов, заменяет ~64 строк ручного glue кода _(from `2026-09-05-ui-event-per-feature`)_
- **UiEvent marker** — `ShowDialog/ShowError/NavigateBack` больше не определены глобально _(from `2026-09-05-ui-event-per-feature`)_
- **Существующие тесты** использующие `TasksViewModel`, `NotesViewModel` и т.д. — `_events.emit(UiEvent.ShowDialog(...))` нужно обновить на `TasksUiEvent.AiResult(...)` _(from `2026-09-05-ui-event-per-feature`)_

### `fab`

- Smoke test: tap FAB on Inbox → verify CreateTask opens; tap FAB on Plans → verify CreateProject opens. _(from `2026-09-16-android-shell-fab-fix`)_
- Users can now create projects directly from Plans via the FAB. _(from `2026-09-16-android-shell-fab-fix`)_
- Users can now create tasks directly from Inbox/Today via the FAB. _(from `2026-09-16-android-shell-fab-fix`)_

### `feature-tasks`

- **Negative**: 40+ files had import paths updated; test files also required path corrections _(from `2026-09-09-feature-tasks-clean-architecture`)_
- **Negative**: Deep `domain/model/` import chains if not careful (mitigated by `package com.singularity.todo.feature.tasks.domain.model.*`) _(from `2026-09-09-feature-tasks-clean-architecture`)_
- **Positive**: Cross-feature imports are now compile-time errors if they bypass domain _(from `2026-09-09-feature-tasks-clean-architecture`)_
- **Positive**: Strict layer boundaries enforced by package structure; pure domain logic testable without Android instrumentation _(from `2026-09-09-feature-tasks-clean-architecture`)_
- **Positive**: `TaskDetailUiState.reduce()` is a pure function — covered by unit tests without mocks _(from `2026-09-09-feature-tasks-clean-architecture`)_

### `"followups"`

- tag 'mcp-ux'/'ui-subtask'/'ai-tooling'/'mcp-policy'/'refactor' — 5 persistent categories для фильтрации. _(from `2026-09-08-mcp-dogfooding-round-2`)_
- В профиле ai-agent теперь 5 top-level plans × ~6 sub-tasks = ~30 новых rows. _(from `2026-09-08-mcp-dogfooding-round-2`)_
- Каждый plan имеет parentTaskId = top-task; UI должен теперь уметь их показать (см. plan 'ui-subtask'). _(from `2026-09-08-mcp-dogfooding-round-2`)_

### `gradle`

- All JetBrains compose library versions MUST track `version.ref = "composeMultiplatform"`. Split-version declarations are forbidden unless the artifact is an AndroidX (not JetBrains) group. _(from `2026-09-06-compose-multiplatform-1.12.0-bump`)_
- The `checkJvmMainComposeLibrariesCompatibility` task must pass silently on every PR. _(from `2026-09-06-compose-multiplatform-1.12.0-bump`)_
- When adding a new third-party Compose dependency, verify its JetBrains compose `requires:` constraint in the Gradle module metadata (`.module` file in cache) before adding — if it demands a version newer than the current pin, either bump or find an alternative. _(from `2026-09-06-compose-multiplatform-1.12.0-bump`)_

### `idempotency`

- Auto-migration v9 добавляет unique index на `(idempotency_key, user_id)` where not null _(from `2026-09-07-write-tools-in-koog-registry`)_
- `TaskEntity` получает `@ColumnInfo("idempotency_key") val idempotencyKey: String?` _(from `2026-09-07-write-tools-in-koog-registry`)_
- `TaskRepository` получает `findByIdempotencyKey(key, userId)` метод _(from `2026-09-07-write-tools-in-koog-registry`)_
- Все 17+ tools следуют этому контракту _(from `2026-09-07-write-tools-in-koog-registry`)_

### `intent`

- **`createTask` and `moveTaskToProject`** remain in VM (require repository writes) _(from `2026-09-09-project-detail-intent-refactor`)_
- **`NavigateToTasks`** is no longer a VM event — screen handles it as routing _(from `2026-09-09-project-detail-intent-refactor`)_
- **No pure reducer needed** — `ProjectDetailViewModel` is write-through like `TaskDetailViewModel` _(from `2026-09-09-project-detail-intent-refactor`)_
- **`ProjectDetailIntent`** is the canonical list of all project mutations — adding a new field mutation = one `Domain` case _(from `2026-09-09-project-detail-intent-refactor`)_
- **`ProjectDetailUiEvent`** now has only 2 cases: `NavigateBack` (post-delete) and `ShowError` _(from `2026-09-09-project-detail-intent-refactor`)_
- **`toggleArchive`** no longer emits `Saved` — `lastEditedAt` drives "Saved X ago" UI via the `mutate{}` helper _(from `2026-09-09-project-detail-intent-refactor`)_

### `json-rpc`

- AI-агент парсит `isError: true` из `result` для business errors и ловит `-32603` из `error` для internal _(from `2026-09-07-mcp-tool-error-model`)_
- `ErrorMapper.kt` маппит `McpToolError` в `CallToolResult` или бросает `McpException` _(from `2026-09-07-mcp-tool-error-model`)_
- `McpToolError.kt` в `mcp-server/src/main/kotlin/com/singularity/todo/mcp/errors/` _(from `2026-09-07-mcp-tool-error-model`)_
- Все write-tools используют `Result<T>` + `mapCatching` для differentiation `Internal` от `Validation`/etc. _(from `2026-09-07-mcp-tool-error-model`)_

### `jvm`

- **Adding a new route type on Android**: must still call `navSavedStateConfig(...)` with the new type's serializer in every NavGraph that can contain it. The `subclass(...)` registration requirement (per `2026-09-16-nav3-savedstate-serializers-required`) is unchanged on Android. _(from `2026-09-16-nav3-desktop-in-memory-no-savedstate`)_
- **Adding a new route type on Desktop**: no serializer registration needed; `rememberInMemoryNavBackStack(start)` is untyped and works for any `T : NavKey`. _(from `2026-09-16-nav3-desktop-in-memory-no-savedstate`)_
- All Android NavGraph back stack declarations become `val backStack = rememberNavBackStackTyped(savedStateConfig, start)` — clean, typed, no suppression. _(from `2026-09-16-nav3-type-asymmetry-adr`)_
- **Android build unchanged**: `assembleDebug` still compiles all Android-specific NavGraphs with full `SavedStateConfiguration` for process-death survival. _(from `2026-09-16-nav3-desktop-in-memory-no-savedstate`)_
- Any future code that calls `backStack.last()` on Android must explicitly cast. _(from `2026-09-16-nav3-type-asymmetry-adr`)_
- **Desktop in-memory only**: Closing and reopening the Desktop window resets all nested back stacks. This was already the behavior before this change — `LocalSaveableStateRegistry` was always `null`. The new code makes this explicit. _(from `2026-09-16-nav3-desktop-in-memory-no-savedstate`)_
- Risk of `ClassCastException` if the type parameter is misused. _(from `2026-09-16-nav3-type-asymmetry-adr`)_
- `@Suppress("UNCHECKED_CAST")` removed from all 5 Android NavGraph files. _(from `2026-09-16-nav3-type-asymmetry-adr`)_
- The Android no-arg overload `rememberNavBackStack(vararg elements)` (reflection path) is **not used** in this project anymore — every call goes through the configuration overload so Android and JVM share one contract. _(from `2026-09-16-nav3-savedstate-serializers-required`)_
- The cast remains in all Android NavGraphs. _(from `2026-09-16-nav3-type-asymmetry-adr`)_
- The inline wrapper is `internal` to the Android source set — no API surface change. _(from `2026-09-16-nav3-type-asymmetry-adr`)_
- When adding a new `data object` or `data class` to `AppDestination` (or any sealed route hierarchy that backs a `rememberNavBackStack`), **always** add the matching `subclass(...)` line in every relevant `serializersModule` — the compiler does not enforce this. _(from `2026-09-16-nav3-savedstate-serializers-required`)_

### `kermit`

- All new `catch` blocks in ViewModels, repositories, and use cases should inject `Logger` and call `log.e(e) { "..." }` or use `runCatchingLogged`. _(from `2026-09-06-kermit-logging-setup`)_
- `BuildConfig.DEBUG` requires `buildConfig = true` in `androidApp/build.gradle.kts`. No BuildConfig is available in `shared` jvm target. _(from `2026-09-06-kermit-logging-setup`)_
- Existing silent `catch (_: Exception)` (e.g., in `ToolFactories.kt` lines 58, 126, 181) remain unfixed — these require separate investigation (some appear to be copy-paste bugs, not intentional suppression). _(from `2026-09-06-kermit-logging-setup`)_
- Koin logs (`NoDefinitionFoundException`, etc.) now appear in Kermit's output via `KermitKoinLogger`. _(from `2026-09-06-kermit-logging-setup`)_
- On JVM, `ColorizedWriter` uses `\u001B` ANSI escapes. Older Windows terminals (pre-10) will print escape sequences literally. `NO_COLOR` env var is respected. _(from `2026-09-06-kermit-logging-setup`)_
- `RefineTaskTool.kt:34-38` has identical try and catch branches (copy-paste bug) — not fixed in this PR. _(from `2026-09-06-kermit-logging-setup`)_

### `kmp`

- A passing `:androidApp:assembleDebug` is the cross-platform smoke test (it would have failed under the old stub). _(from `2026-09-05-koog-both-platforms`)_
- The old `JvmPromptExecutorPort` and `AndroidPromptExecutorPort` files are deleted. _(from `2026-09-05-koog-both-platforms`)_
- The unified `KoogPromptExecutorPort` lives in `commonMain` and exposes `val executor: PromptExecutor` publicly for the platform `single<PromptExecutor>` binding. _(from `2026-09-05-koog-both-platforms`)_

### `koin`

- ~18 файлов переработано, +5 новых, -2 удалено. _(from `2026-09-14-tasks-feature-nested-nav3`)_
- **4 VM registrations** (`TaskEditorViewModel`, `TasksByProjectViewModel`, `ProjectEditorViewModel`, `ProjectDetailViewModel`) now use `viewModel { (p) → ... }` instead of `factory { (p) → ... }` _(from `2026-09-09-di-factory-viewmodel-fix`)_
- 8 new files (nav package under projects feature) + 2 new ADR records. _(from `2026-09-15-projects-nested-nav3`)_
- **8 экранов мигрируют одновременно** — невозможно сделать постепенную миграцию из-за смены типа `_events` _(from `2026-09-05-ui-event-per-feature`)_
- Additional level of indirection for new developers: "where am I?" _(from `2026-09-15-projects-nested-nav3`)_
- All 3 projects screens use `LocalProjectsNavigator` — no callback parameters. _(from `2026-09-15-projects-nested-nav3`)_
- All changes are additive; no existing behavior is removed. _(from `2026-09-07-settings-ux-improvements`)_
- All new `catch` blocks in ViewModels, repositories, and use cases should inject `Logger` and call `log.e(e) { "..." }` or use `runCatchingLogged`. _(from `2026-09-06-kermit-logging-setup`)_
- All new screens MUST follow the `PublicScreen` / `PrivateContent` naming pattern _(from `2026-09-09-preview-with-koin-helper`)_
- All `@Preview` composables use `ProjectDetailContent(vm, ...)` with `FakeRepositories` — no preview crashes _(from `2026-09-09-project-detail-rework-15-fixes`)_
- All task feature screens (`TaskListScreen`, `TaskDetailViewScreen`, `TaskCreateScreen`) use `LocalTasksNavigator.current` for navigation — no callback parameters. _(from `2026-09-14-nav3-tasks-navigator`)_
- Android system back gesture is handled by `BackHandler` in `TasksNavGraph.android.kt`. JVM has no back handling. _(from `2026-09-14-nav3-tasks-navigator`)_
- Architecture: screens own routing state (`sheetState`), VMs own domain logic, navigation callbacks are passed as parameters _(from `2026-09-09-project-detail-rework-15-fixes`)_
- Backup confirm dialogs prevent accidental data loss. _(from `2026-09-07-settings-ux-improvements`)_
- `BackupRepository` resolves correctly in all environments (JVM desktop, Android). _(from `2026-09-07-backup-directory-via-koin-string`)_
- **Breaking:** `coreDomainModule()` удалён; заменён на `domainModule()` (includes everything). Test files обновлены. _(from `2026-09-06-di-module-split`)_
- `BuildConfig.DEBUG` requires `buildConfig = true` in `androidApp/build.gradle.kts`. No BuildConfig is available in `shared` jvm target. _(from `2026-09-06-kermit-logging-setup`)_
- **`Clock` must be passed to `CreateTaskUseCase` / `UpdateTaskUseCase`** — use the singleton `Clock` from `core.platform`. _(from `2026-09-05-robolectric-widget-tests`)_
- **CollectEvents** в виджетах принимает `Flow<T : UiEvent>` — generic call site остаётся тем же _(from `2026-09-05-ui-event-per-feature`)_
- `compose-ui-test:1.12.0` added to `libs.versions.toml` as `composeUiTest` _(from `2026-09-06-desktop-smoke-test-with-koin`)_
- **`createTask` and `moveTaskToProject`** remain in VM (require repository writes) _(from `2026-09-09-project-detail-intent-refactor`)_
- Cross-feature navigation between projects and tasks uses type-safe `AppDestination` hops. _(from `2026-09-15-projects-nested-nav3`)_
- Debounce reduces SecureStorage/DataStore writes by ~90% during text input. _(from `2026-09-07-settings-ux-improvements`)_
- Diff больше, чем чисто миграция tasks — затрагивает общий `Nav3State`. _(from `2026-09-14-nav3-vm-store-decorator-fix`)_
- Do NOT introduce `koinViewModel()` inside any `@Preview` — CI/preview harness does not start Koin _(from `2026-09-09-preview-with-koin-helper`)_
- Existing silent `catch (_: Exception)` (e.g., in `ToolFactories.kt` lines 58, 126, 181) remain unfixed — these require separate investigation (some appear to be copy-paste bugs, not intentional suppression). _(from `2026-09-06-kermit-logging-setup`)_
- **Existing tests:** `DiGraphTest`, `JvmAiDiGraphTest`, `AppSmokeTest` обновлены и проходят. _(from `2026-09-06-di-module-split`)_
- **Fake repo returns empty by default** — widget tests that check `LazyColumn` with `testTag` will fail when repo is empty (state = `Empty`). Test the `EmptyState` text instead, or seed data via `fakeNotesRepo.seed(note)`. _(from `2026-09-05-robolectric-widget-tests`)_
- FakeRepositories live in `commonMain/test/fakes/` (not `commonTest`) so `commonMain` previews can access them _(from `2026-09-09-preview-with-koin-helper`)_
- Feature isolation: `ProjectsNavGraph` is self-contained and could be ported to iOS or other shells. _(from `2026-09-15-projects-nested-nav3`)_
- Instrumented/integration тесты (`CreateTaskFlowInstrumentedTest`) _(from `2026-09-14-nav3-vm-store-decorator-fix`)_
- `JvmAiDiGraphTest` keeps its `LLModel` override as a safety belt — if someone reintroduces `OpenAIModels.*`, this test fails at graph-build time. _(from `2026-09-05-koog-test-workarounds`)_
- **JVM args for JDK 21+** — add `--add-opens=java.base/jdk.internal.access=ALL-UNNAMED` to `gradle.properties` (`org.gradle.jvmargs`) AND to `shared/build.gradle.kts` via `afterEvaluate` + `tasks.withType<Test>()` for the test worker process. _(from `2026-09-05-robolectric-widget-tests`)_
- `koinBridge` is for one-shot startup reads only — **not for** hot-path code, **not for** long-running operations. _(from `2026-09-05-koin-suspend-bridge`)_
- **`koinInject()` для репозиториев/сервисов остаётся** — не VM _(from `2026-09-06-koin-vm-viewmodelof-koinviewmodel`)_
- Koin logs (`NoDefinitionFoundException`, etc.) now appear in Kermit's output via `KermitKoinLogger`. _(from `2026-09-06-kermit-logging-setup`)_
- **`koinViewModel()` для VM в Composable** — `koinInject()` для VM антипаттерн _(from `2026-09-06-koin-vm-viewmodelof-koinviewmodel`)_
- Lifecycle VM становится привязан к lifetime entry — VM очищается _(from `2026-09-14-nav3-vm-store-decorator-fix`)_
- **`LocalNavBackStack` как публичный API** — позволяет экранам _(from `2026-09-14-tasks-feature-nested-nav3`)_
- **`NavigateToTasks`** is no longer a VM event — screen handles it as routing _(from `2026-09-09-project-detail-intent-refactor`)_
- Navigation interaction tests (click-to-navigate) are out of scope for this smoke test — they require handling NavBackStackEntry lifecycle in `runDesktopComposeUiTest` _(from `2026-09-06-desktop-smoke-test-with-koin`)_
- Navigation между Detail и подзадачами/проектами становится _(from `2026-09-14-tasks-feature-nested-nav3`)_
- **New file count:** 8 новых файлов (7 модулей + decision). _(from `2026-09-06-di-module-split`)_
- **No call-site changes** — `koinViewModel { parametersOf(...) }` works with both forms _(from `2026-09-09-di-factory-viewmodel-fix`)_
- **No pure reducer needed** — `ProjectDetailViewModel` is write-through like `TaskDetailViewModel` _(from `2026-09-09-project-detail-intent-refactor`)_
- **NotificationHost** — финальный widget для всех экранов, заменяет ~64 строк ручного glue кода _(from `2026-09-05-ui-event-per-feature`)_
- On JVM, `ColorizedWriter` uses `\u001B` ANSI escapes. Older Windows terminals (pre-10) will print escape sequences literally. `NO_COLOR` env var is respected. _(from `2026-09-06-kermit-logging-setup`)_
- `@Preview` composables are always `private` and call the `*Content` variant with manually constructed VMs _(from `2026-09-09-preview-with-koin-helper`)_
- **@Preview и widget-тесты не затрагиваются** — все preview используют `*Content` helpers (stateless) _(from `2026-09-06-koin-vm-viewmodelof-koinviewmodel`)_
- **`ProjectDetailIntent`** is the canonical list of all project mutations — adding a new field mutation = one `Domain` case _(from `2026-09-09-project-detail-intent-refactor`)_
- `ProjectDetailScreen` is fully functional: quick-add creates tasks, parent picker works, Remind/Attach/DueDate/Children sheets open, task click navigates to `TaskDetailScreen` _(from `2026-09-09-project-detail-rework-15-fixes`)_
- **`ProjectDetailUiEvent`** now has only 2 cases: `NavigateBack` (post-delete) and `ShowError` _(from `2026-09-09-project-detail-intent-refactor`)_
- `ProjectDetailViewModel(projectId)` and `ProjectEditorViewModel(projectId)` now have correct per-entry VM scoping on Android. _(from `2026-09-15-projects-nested-nav3`)_
- `ProjectDetailViewModel(projectId)` — Project X → back → Project Y _(from `2026-09-14-nav3-vm-store-decorator-fix`)_
- `ProjectPickerSheet` is reactive — newly created projects appear without reopening the sheet _(from `2026-09-09-project-detail-rework-15-fixes`)_
- **Raw `runBlocking` в модулях** — не допускается, `koinBridge` как единая точка входа _(from `2026-09-06-koin-bridge-audit`)_
- `RefineTaskTool.kt:34-38` has identical try and catch branches (copy-paste bug) — not fixed in this PR. _(from `2026-09-06-kermit-logging-setup`)_
- **Robolectric 4.17-beta-4** — `4.16` maxes at SDK 36; `compileSdk=37` requires the beta. The beta is already cached. _(from `2026-09-05-robolectric-widget-tests`)_
- Screens that need `@Preview` use `TasksPreviewWrapper { ... }` which provides a `PreviewTasksNavigator` via `LocalTasksNavigator`. _(from `2026-09-14-nav3-tasks-navigator`)_
- **`Session.Anonymous()` requires `UserId`** — always pass `UserId.anonymous` or `UserId.fromString("...")`. _(from `2026-09-05-robolectric-widget-tests`)_
- Settings → Backup tab no longer crashes during composition. _(from `2026-09-07-backup-directory-via-koin-string`)_
- **`singleOf` для репозиториев** — architectural limitation; сложные конструкторы не поддерживают constructor-reference форму _(from `2026-09-06-koin-bridge-audit`)_
- **`singularity-todo-vm-koin-scoping` skill** — создан как single source of truth _(from `2026-09-06-koin-vm-viewmodelof-koinviewmodel`)_
- Smoke test now passes: `./gradlew :desktopApp:test` → BUILD SUCCESSFUL _(from `2026-09-06-desktop-smoke-test-with-koin`)_
- `sourceSets { test { java.srcDirs("src/jvmTest") ... } }` added to `desktopApp/build.gradle.kts` to wire the `jvmTest` source set to the `test` task _(from `2026-09-06-desktop-smoke-test-with-koin`)_
- **State survives configuration change** on Android — rotation no longer resets these screens _(from `2026-09-09-di-factory-viewmodel-fix`)_
- **`String`-encoded `initialDueDate`** — заменён на _(from `2026-09-14-tasks-feature-nested-nav3`)_
- `TaskCreateViewModel(initialDueDate)` — два последовательных _(from `2026-09-14-nav3-vm-store-decorator-fix`)_
- `TaskDetailIntent` no longer has `NavigateToProject` / `NavigateToTask` routing intents — those are now navigator methods. _(from `2026-09-14-nav3-tasks-navigator`)_
- `TaskDetailViewModel(taskId)` — Task A → back → Task B больше _(from `2026-09-14-nav3-vm-store-decorator-fix`)_
- **`TaskEditorViewModel` special case** — `viewModel { (initialDueDate) -> ... }` + `koinViewModel { parametersOf(initialDueDate) }` _(from `2026-09-06-koin-vm-viewmodelof-koinviewmodel`)_
- `TasksNavGraph` is the `@Composable` nav host — it sets up `LocalTasksNavigator`, `LocalNavBackStack`, and the `BackHandler`. _(from `2026-09-14-nav3-tasks-navigator`)_
- `TasksNavigator` is the only class that mutates `NavBackStack<TasksRoute>`. _(from `2026-09-14-nav3-tasks-navigator`)_
- `TasksRoute` is the sealed interface defining all routes within the tasks graph (Inbox, Today, ByProject, Detail, Create). _(from `2026-09-14-nav3-tasks-navigator`)_
- **`TasksRoute.Pop` как sentinel** — race condition (см. review rev. 1, _(from `2026-09-14-tasks-feature-nested-nav3`)_
- **Test impact** — tests that relied on a fresh VM instance per `get()` may need updating; prefer stateful testing over instance-fresh guarantees _(from `2026-09-09-di-factory-viewmodel-fix`)_
- Test suite (`SettingsViewModelTest`) updated to work with debounce bypass in test mode. _(from `2026-09-07-settings-ux-improvements`)_
- The `desktopApp/build.gradle.kts` change (adding `implementation(project(":shared"))` with kotlinJvmTask) was also part of the desktop build fix. _(from `2026-09-07-backup-directory-via-koin-string`)_
- **`toggleArchive`** no longer emits `Saved` — `lastEditedAt` drives "Saved X ago" UI via the `mutate{}` helper _(from `2026-09-09-project-detail-intent-refactor`)_
- **UiEvent marker** — `ShowDialog/ShowError/NavigateBack` больше не определены глобально _(from `2026-09-05-ui-event-per-feature`)_
- Unit-тесты навигации tasks требуют `Robolectric` или `composeRule` — _(from `2026-09-14-tasks-feature-nested-nav3`)_
- **Use `UserId` from `feature.tasks`** — it's defined in `Ids.kt` there, imported explicitly. _(from `2026-09-05-robolectric-widget-tests`)_
- **`viewModelOf(::VM)` для VM без nullable dep** — предпочтительный паттерн _(from `2026-09-06-koin-vm-viewmodelof-koinviewmodel`)_
- **`viewModel { Vm(get(), get(), ...) }`** — для VM с nullable dep + getOrNull() (TasksViewModel, ProjectsViewModel) _(from `2026-09-06-koin-vm-viewmodelof-koinviewmodel`)_
- **`waitForIdle()` is a method, not a function** — do NOT import it. Call `composeRule.waitForIdle()` directly. _(from `2026-09-05-robolectric-widget-tests`)_
- When adding a new AI tool, **always** bind its use case with **explicit `get<ConcreteTool>()`** if the use case's parameter is `SimpleTool<T>`: _(from `2026-09-05-koog-test-workarounds`)_
- When the script's grep is broken (a stray `runBlocking` appears), fix it immediately; the helper exists specifically so this is detectable. _(from `2026-09-05-koin-suspend-bridge`)_
- В `JvmNav3State.kt` для `AppDestination.TasksGraph` / _(from `2026-09-14-tasks-feature-nested-nav3`)_
- В `TasksNavGraph.kt` (для nested `rememberNavBackStack`). _(from `2026-09-14-tasks-feature-nested-nav3`)_
- Все остальные параметризованные VM (~20 callsites). _(from `2026-09-14-nav3-vm-store-decorator-fix`)_
- Дополнительный уровень индирекции для новых разработчиков: «где я?». _(from `2026-09-14-tasks-feature-nested-nav3`)_
- Если какой-то VM был неявно расчитан на per-Activity scope _(from `2026-09-14-nav3-vm-store-decorator-fix`)_
- Необходимо зарегистрировать `TasksRoute` в двух `SerializersModule`: _(from `2026-09-14-tasks-feature-nested-nav3`)_
- **Один плоский AppDestination без nested graph** — не даёт feature _(from `2026-09-14-tasks-feature-nested-nav3`)_
- Первая фича с nested graph — другие фичи (notes/projects/auth/settings) _(from `2026-09-14-tasks-feature-nested-nav3`)_
- **Правило подтверждено:** `koinBridge` только для one-shot startup suspend reads _(from `2026-09-06-koin-bridge-audit`)_
- Рассмотреть переход на `LocalResultEventBus` + `ResultEffect<T>` для _(from `2026-09-14-nav3-vm-store-decorator-fix`)_
- Сигнатуры экранов tasks упрощаются до 1-2 аргументов. _(from `2026-09-14-tasks-feature-nested-nav3`)_
- Существующие unit-тесты для VM не затрагиваются (тестируют VM _(from `2026-09-14-nav3-vm-store-decorator-fix`)_
- **Существующие тесты** использующие `TasksViewModel`, `NotesViewModel` и т.д. — `_events.emit(UiEvent.ShowDialog(...))` нужно обновить на `TasksUiEvent.AiResult(...)` _(from `2026-09-05-ui-event-per-feature`)_
- Чинится латентный bug для всех `koinViewModel { parametersOf(...) }` _(from `2026-09-14-nav3-vm-store-decorator-fix`)_
- **Чинится латентный VM scoping bug** для `TaskDetailViewModel`, _(from `2026-09-14-tasks-feature-nested-nav3`)_

### `koog`

- AI-агенты получают нативный доступ к данным без UI _(from `2026-09-07-dogfooding-mcp-server`)_
- All 3 tools now require `ProfileAwareCurrentUser` in DI — tested via _(from `2026-09-08-mcp-schema-and-profile-userid-fixes`)_
- A passing `:androidApp:assembleDebug` is the cross-platform smoke test (it would have failed under the old stub). _(from `2026-09-05-koog-both-platforms`)_
- Auto-migration v9 добавляет unique index на `(idempotency_key, user_id)` where not null _(from `2026-09-07-write-tools-in-koog-registry`)_
- Dogfooding-профиль "AI Agent" (🤖) изолирует агентские задачи от пользовательских _(from `2026-09-07-dogfooding-mcp-server`)_
- `JvmAiDiGraphTest` keeps its `LLModel` override as a safety belt — if someone reintroduces `OpenAIModels.*`, this test fails at graph-build time. _(from `2026-09-05-koog-test-workarounds`)_
- `list_tasks`, `list_linked_tasks`, and `search_tasks` now return correct results _(from `2026-09-08-mcp-schema-and-profile-userid-fixes`)_
- MCP clients that validate `$schema` as a URI will no longer reject tool schemas. _(from `2026-09-08-mcp-schema-and-profile-userid-fixes`)_
- `TaskEntity` получает `@ColumnInfo("idempotency_key") val idempotencyKey: String?` _(from `2026-09-07-write-tools-in-koog-registry`)_
- `TaskRepository` получает `findByIdempotencyKey(key, userId)` метод _(from `2026-09-07-write-tools-in-koog-registry`)_
- The old `JvmPromptExecutorPort` and `AndroidPromptExecutorPort` files are deleted. _(from `2026-09-05-koog-both-platforms`)_
- The unified `KoogPromptExecutorPort` lives in `commonMain` and exposes `val executor: PromptExecutor` publicly for the platform `single<PromptExecutor>` binding. _(from `2026-09-05-koog-both-platforms`)_
- When adding a new AI tool, **always** bind its use case with **explicit `get<ConcreteTool>()`** if the use case's parameter is `SimpleTool<T>`: _(from `2026-09-05-koog-test-workarounds`)_
- ZCode подключается через `mcpServers.singularity-todo` в настройках _(from `2026-09-07-dogfooding-mcp-server`)_
- Все 17+ tools следуют этому контракту _(from `2026-09-07-write-tools-in-koog-registry`)_
- Все token usage пишется в `llm_usage` с `profile_id=ai-agent` _(from `2026-09-07-dogfooding-mcp-server`)_
- Новый Gradle-модуль `:mcp-server` с dependency на shared _(from `2026-09-07-dogfooding-mcp-server`)_

### `kotlin-multiplatform`

- **Negative**: 40+ files had import paths updated; test files also required path corrections _(from `2026-09-09-feature-tasks-clean-architecture`)_
- **Negative**: Deep `domain/model/` import chains if not careful (mitigated by `package com.singularity.todo.feature.tasks.domain.model.*`) _(from `2026-09-09-feature-tasks-clean-architecture`)_
- **Positive**: Cross-feature imports are now compile-time errors if they bypass domain _(from `2026-09-09-feature-tasks-clean-architecture`)_
- **Positive**: Strict layer boundaries enforced by package structure; pure domain logic testable without Android instrumentation _(from `2026-09-09-feature-tasks-clean-architecture`)_
- **Positive**: `TaskDetailUiState.reduce()` is a pure function — covered by unit tests without mocks _(from `2026-09-09-feature-tasks-clean-architecture`)_

### `kotlin-sdk`

- `./gradlew :mcp-server:test` now includes a regression test (`McpServerEndToEndTest.server_blocks_until_stdin_closes`) that asserts `process.isAlive` after 3s of empty stdin. If anyone removes the blocking primitive, this test fails. _(from `2026-09-07-mcp-stdio-blocking-lifecycle`)_
- MCP client (ZCode CLI) now sees the `initialize` roundtrip succeed and can list/call tools. _(from `2026-09-07-mcp-stdio-blocking-lifecycle`)_
- Process exit semantics change from "instant" to "on stdin EOF or session error". A passing test asserts the process stays alive ≥3s with empty stdin. _(from `2026-09-07-mcp-stdio-blocking-lifecycle`)_
- `Runtime.getRuntime().addShutdownHook { server.close() }` becomes redundant for normal EOF exits — `onClose → done.complete() → done.join() returns → runBlocking exits → JVM exits cleanly`. We keep the shutdown hook only as a backstop for SIGTERM. _(from `2026-09-07-mcp-stdio-blocking-lifecycle`)_
- The downstream `ToolRegistrar` and tools still run inside `runBlocking { koogTool.execute(args) }` per call — coroutine scope inside the request handler, no change. _(from `2026-09-07-mcp-stdio-blocking-lifecycle`)_

### `kover`

- **Configuration cache**: detekt 1.23.x and kover 0.9.9 are both CC-compatible. Verified by running `./gradlew --configuration-cache :shared:detekt`. _(from `2026-09-15-detekt-ktlint-kover-setup`)_
- `:desktopApp:detekt` / `:desktopApp:detektFormat` / `:desktopApp:detektBaseline` _(from `2026-09-15-detekt-ktlint-kover-setup`)_
- `:desktopApp:koverXmlReport` / `:desktopApp:koverHtmlReport` _(from `2026-09-15-detekt-ktlint-kover-setup`)_
- **detekt 2.0.0-alpha.3 vs Kotlin 2.3.21**: this version was chosen because stable 1.23.8 was compiled against Kotlin 2.0.21 and throws "detekt was compiled with Kotlin 2.0.21 but is currently running with 2.3.21". Upgrade to stable 2.x once released. _(from `2026-09-15-detekt-ktlint-kover-setup`)_
- **`.editorconfig` may rewrap existing code** on first `detektFormat` run. Expect a large diff; consider a separate "format" commit before merging. _(from `2026-09-15-detekt-ktlint-kover-setup`)_
- **`ignoreFailures = true`** means violations are reported but never block builds. To enforce violations: set `ignoreFailures = false` in both `shared/build.gradle.kts` and `desktopApp/build.gradle.kts` once baselines are settled. **TODO: tracked in issue tracker — promote after baselines are clean (est. post-format PR).** _(from `2026-09-15-detekt-ktlint-kover-setup`)_
- **New Gradle tasks added**: _(from `2026-09-15-detekt-ktlint-kover-setup`)_
- `:shared:detekt` / `:shared:detektFormat` / `:shared:detektBaseline` _(from `2026-09-15-detekt-ktlint-kover-setup`)_
- `:shared:koverXmlReport` / `:shared:koverHtmlReport` _(from `2026-09-15-detekt-ktlint-kover-setup`)_

### `ktlint`

- **Configuration cache**: detekt 1.23.x and kover 0.9.9 are both CC-compatible. Verified by running `./gradlew --configuration-cache :shared:detekt`. _(from `2026-09-15-detekt-ktlint-kover-setup`)_
- `:desktopApp:detekt` / `:desktopApp:detektFormat` / `:desktopApp:detektBaseline` _(from `2026-09-15-detekt-ktlint-kover-setup`)_
- `:desktopApp:koverXmlReport` / `:desktopApp:koverHtmlReport` _(from `2026-09-15-detekt-ktlint-kover-setup`)_
- **detekt 2.0.0-alpha.3 vs Kotlin 2.3.21**: this version was chosen because stable 1.23.8 was compiled against Kotlin 2.0.21 and throws "detekt was compiled with Kotlin 2.0.21 but is currently running with 2.3.21". Upgrade to stable 2.x once released. _(from `2026-09-15-detekt-ktlint-kover-setup`)_
- **`.editorconfig` may rewrap existing code** on first `detektFormat` run. Expect a large diff; consider a separate "format" commit before merging. _(from `2026-09-15-detekt-ktlint-kover-setup`)_
- **`ignoreFailures = true`** means violations are reported but never block builds. To enforce violations: set `ignoreFailures = false` in both `shared/build.gradle.kts` and `desktopApp/build.gradle.kts` once baselines are settled. **TODO: tracked in issue tracker — promote after baselines are clean (est. post-format PR).** _(from `2026-09-15-detekt-ktlint-kover-setup`)_
- **New Gradle tasks added**: _(from `2026-09-15-detekt-ktlint-kover-setup`)_
- `:shared:detekt` / `:shared:detektFormat` / `:shared:detektBaseline` _(from `2026-09-15-detekt-ktlint-kover-setup`)_
- `:shared:koverXmlReport` / `:shared:koverHtmlReport` _(from `2026-09-15-detekt-ktlint-kover-setup`)_

### `lifecycle`

- `./gradlew :mcp-server:test` now includes a regression test (`McpServerEndToEndTest.server_blocks_until_stdin_closes`) that asserts `process.isAlive` after 3s of empty stdin. If anyone removes the blocking primitive, this test fails. _(from `2026-09-07-mcp-stdio-blocking-lifecycle`)_
- MCP client (ZCode CLI) now sees the `initialize` roundtrip succeed and can list/call tools. _(from `2026-09-07-mcp-stdio-blocking-lifecycle`)_
- Process exit semantics change from "instant" to "on stdin EOF or session error". A passing test asserts the process stays alive ≥3s with empty stdin. _(from `2026-09-07-mcp-stdio-blocking-lifecycle`)_
- `Runtime.getRuntime().addShutdownHook { server.close() }` becomes redundant for normal EOF exits — `onClose → done.complete() → done.join() returns → runBlocking exits → JVM exits cleanly`. We keep the shutdown hook only as a backstop for SIGTERM. _(from `2026-09-07-mcp-stdio-blocking-lifecycle`)_
- The downstream `ToolRegistrar` and tools still run inside `runBlocking { koogTool.execute(args) }` per call — coroutine scope inside the request handler, no change. _(from `2026-09-07-mcp-stdio-blocking-lifecycle`)_

### `linking`

- Caller must provide `MutableStateFlow<String>` and inject `InternalLinkRepository` and `ProfileAwareCurrentUser` — slightly more boilerplate at call site _(from `2026-09-09-internal-link-picker-generic`)_
- `core/ui/components/` is now free of feature-domain imports _(from `2026-09-09-internal-link-picker-generic`)_
- Icon per `LinkKind` makes the list scannable _(from `2026-09-09-internal-link-picker-generic`)_
- Search debouncing (300ms) is now the caller's responsibility (implemented inside the sheet via `LaunchedEffect`) _(from `2026-09-09-internal-link-picker-generic`)_
- Sheet is reusable by any feature that needs internal linking (e.g. TaskEditor) _(from `2026-09-09-internal-link-picker-generic`)_
- Single search + merged results = better UX (one tap instead of tab switching) _(from `2026-09-09-internal-link-picker-generic`)_

### `lint`

- **Configuration cache**: detekt 1.23.x and kover 0.9.9 are both CC-compatible. Verified by running `./gradlew --configuration-cache :shared:detekt`. _(from `2026-09-15-detekt-ktlint-kover-setup`)_
- `:desktopApp:detekt` / `:desktopApp:detektFormat` / `:desktopApp:detektBaseline` _(from `2026-09-15-detekt-ktlint-kover-setup`)_
- `:desktopApp:koverXmlReport` / `:desktopApp:koverHtmlReport` _(from `2026-09-15-detekt-ktlint-kover-setup`)_
- **detekt 2.0.0-alpha.3 vs Kotlin 2.3.21**: this version was chosen because stable 1.23.8 was compiled against Kotlin 2.0.21 and throws "detekt was compiled with Kotlin 2.0.21 but is currently running with 2.3.21". Upgrade to stable 2.x once released. _(from `2026-09-15-detekt-ktlint-kover-setup`)_
- **`.editorconfig` may rewrap existing code** on first `detektFormat` run. Expect a large diff; consider a separate "format" commit before merging. _(from `2026-09-15-detekt-ktlint-kover-setup`)_
- **`ignoreFailures = true`** means violations are reported but never block builds. To enforce violations: set `ignoreFailures = false` in both `shared/build.gradle.kts` and `desktopApp/build.gradle.kts` once baselines are settled. **TODO: tracked in issue tracker — promote after baselines are clean (est. post-format PR).** _(from `2026-09-15-detekt-ktlint-kover-setup`)_
- **New Gradle tasks added**: _(from `2026-09-15-detekt-ktlint-kover-setup`)_
- `:shared:detekt` / `:shared:detektFormat` / `:shared:detektBaseline` _(from `2026-09-15-detekt-ktlint-kover-setup`)_
- `:shared:koverXmlReport` / `:shared:koverHtmlReport` _(from `2026-09-15-detekt-ktlint-kover-setup`)_

### `llm-usage`

- 4 ADR entries created + DIGEST.md refreshed _(from `2026-09-07-multi-profile-and-usage-tracking`)_
- AI Usage screen в Settings _(from `2026-09-07-multi-profile-and-usage-tracking`)_
- `ProfileAwareCurrentUser` инжектится во все write-tools _(from `2026-09-07-multi-profile-and-usage-tracking`)_
- Room schema v8 с `llm_usage` table + `profiles` table _(from `2026-09-07-multi-profile-and-usage-tracking`)_
- ZCode подключается с `--profile=ai-agent` → все операции в профиле ai-agent _(from `2026-09-07-multi-profile-and-usage-tracking`)_

### `logging`

- All new `catch` blocks in ViewModels, repositories, and use cases should inject `Logger` and call `log.e(e) { "..." }` or use `runCatchingLogged`. _(from `2026-09-06-kermit-logging-setup`)_
- `BuildConfig.DEBUG` requires `buildConfig = true` in `androidApp/build.gradle.kts`. No BuildConfig is available in `shared` jvm target. _(from `2026-09-06-kermit-logging-setup`)_
- Existing silent `catch (_: Exception)` (e.g., in `ToolFactories.kt` lines 58, 126, 181) remain unfixed — these require separate investigation (some appear to be copy-paste bugs, not intentional suppression). _(from `2026-09-06-kermit-logging-setup`)_
- Koin logs (`NoDefinitionFoundException`, etc.) now appear in Kermit's output via `KermitKoinLogger`. _(from `2026-09-06-kermit-logging-setup`)_
- On JVM, `ColorizedWriter` uses `\u001B` ANSI escapes. Older Windows terminals (pre-10) will print escape sequences literally. `NO_COLOR` env var is respected. _(from `2026-09-06-kermit-logging-setup`)_
- `RefineTaskTool.kt:34-38` has identical try and catch branches (copy-paste bug) — not fixed in this PR. _(from `2026-09-06-kermit-logging-setup`)_

### `"mcp"`

- ADR пишется в `docs/decisions/{YYYY-MM-DD}-{slug}.md` (server-side date). _(from `2026-09-08-mcp-plan-tracking-via-mcp`)_

### `mcp`

- AI-агент парсит `isError: true` из `result` для business errors и ловит `-32603` из `error` для internal _(from `2026-09-07-mcp-tool-error-model`)_
- AI-агенты получают нативный доступ к данным без UI _(from `2026-09-07-dogfooding-mcp-server`)_
- All 3 tools now require `ProfileAwareCurrentUser` in DI — tested via _(from `2026-09-08-mcp-schema-and-profile-userid-fixes`)_
- Auto-migration v9 добавляет unique index на `(idempotency_key, user_id)` where not null _(from `2026-09-07-write-tools-in-koog-registry`)_
- Dogfooding-профиль "AI Agent" (🤖) изолирует агентские задачи от пользовательских _(from `2026-09-07-dogfooding-mcp-server`)_
- `ErrorMapper.kt` маппит `McpToolError` в `CallToolResult` или бросает `McpException` _(from `2026-09-07-mcp-tool-error-model`)_
- `./gradlew :mcp-server:test` now includes a regression test (`McpServerEndToEndTest.server_blocks_until_stdin_closes`) that asserts `process.isAlive` after 3s of empty stdin. If anyone removes the blocking primitive, this test fails. _(from `2026-09-07-mcp-stdio-blocking-lifecycle`)_
- `list_tasks`, `list_linked_tasks`, and `search_tasks` now return correct results _(from `2026-09-08-mcp-schema-and-profile-userid-fixes`)_
- MCP clients that validate `$schema` as a URI will no longer reject tool schemas. _(from `2026-09-08-mcp-schema-and-profile-userid-fixes`)_
- MCP client (ZCode CLI) now sees the `initialize` roundtrip succeed and can list/call tools. _(from `2026-09-07-mcp-stdio-blocking-lifecycle`)_
- `McpToolError.kt` в `mcp-server/src/main/kotlin/com/singularity/todo/mcp/errors/` _(from `2026-09-07-mcp-tool-error-model`)_
- One new e2e test in `mcp-server` (`McpToolRoundTripTest`). _(from `2026-09-08-mcp-server-health-audit`)_
- One new unit test file in `mcp-server` (`KoogJsonSchemaBuilderTest`). _(from `2026-09-08-mcp-server-health-audit`)_
- Process exit semantics change from "instant" to "on stdin EOF or session error". A passing test asserts the process stays alive ≥3s with empty stdin. _(from `2026-09-07-mcp-stdio-blocking-lifecycle`)_
- `Runtime.getRuntime().addShutdownHook { server.close() }` becomes redundant for normal EOF exits — `onClose → done.complete() → done.join() returns → runBlocking exits → JVM exits cleanly`. We keep the shutdown hook only as a backstop for SIGTERM. _(from `2026-09-07-mcp-stdio-blocking-lifecycle`)_

### `"mcp"`

- tag 'mcp-ux'/'ui-subtask'/'ai-tooling'/'mcp-policy'/'refactor' — 5 persistent categories для фильтрации. _(from `2026-09-08-mcp-dogfooding-round-2`)_

### `mcp`

- `TaskEntity` получает `@ColumnInfo("idempotency_key") val idempotencyKey: String?` _(from `2026-09-07-write-tools-in-koog-registry`)_
- `TaskRepository` получает `findByIdempotencyKey(key, userId)` метод _(from `2026-09-07-write-tools-in-koog-registry`)_
- The downstream `ToolRegistrar` and tools still run inside `runBlocking { koogTool.execute(args) }` per call — coroutine scope inside the request handler, no change. _(from `2026-09-07-mcp-stdio-blocking-lifecycle`)_
- Three new unit test files in `shared/commonTest` for the read tools. _(from `2026-09-08-mcp-server-health-audit`)_
- `ToolFactories.kt` gets the profile-aware default applied (small diff, _(from `2026-09-08-mcp-server-health-audit`)_
- `ToolRegistrar` catches `McpToolError` first (small diff). _(from `2026-09-08-mcp-server-health-audit`)_
- ZCode подключается через `mcpServers.singularity-todo` в настройках _(from `2026-09-07-dogfooding-mcp-server`)_

### `"mcp"`

- В профиле ai-agent теперь 5 top-level plans × ~6 sub-tasks = ~30 новых rows. _(from `2026-09-08-mcp-dogfooding-round-2`)_

### `mcp`

- Все 17+ tools следуют этому контракту _(from `2026-09-07-write-tools-in-koog-registry`)_
- Все token usage пишется в `llm_usage` с `profile_id=ai-agent` _(from `2026-09-07-dogfooding-mcp-server`)_
- Все write-tools используют `Result<T>` + `mapCatching` для differentiation `Internal` от `Validation`/etc. _(from `2026-09-07-mcp-tool-error-model`)_

### `"mcp"`

- Каждый plan имеет parentTaskId = top-task; UI должен теперь уметь их показать (см. plan 'ui-subtask'). _(from `2026-09-08-mcp-dogfooding-round-2`)_

### `mcp`

- Новый Gradle-модуль `:mcp-server` с dependency на shared _(from `2026-09-07-dogfooding-mcp-server`)_

### `"mcp"`

- Один прогон драйвера = реальная multi-step демонстрация MCP. _(from `2026-09-08-mcp-plan-tracking-via-mcp`)_
- При недоступности LLM в драйвере зашит fallback sub-task'ов. _(from `2026-09-08-mcp-plan-tracking-via-mcp`)_

### `multi-profile`

- 4 ADR entries created + DIGEST.md refreshed _(from `2026-09-07-multi-profile-and-usage-tracking`)_
- AI Usage screen в Settings _(from `2026-09-07-multi-profile-and-usage-tracking`)_
- `ProfileAwareCurrentUser` инжектится во все write-tools _(from `2026-09-07-multi-profile-and-usage-tracking`)_
- Room schema v8 с `llm_usage` table + `profiles` table _(from `2026-09-07-multi-profile-and-usage-tracking`)_
- ZCode подключается с `--profile=ai-agent` → все операции в профиле ai-agent _(from `2026-09-07-multi-profile-and-usage-tracking`)_

### `nav3`

- 8 new files (nav package under projects feature) + 2 new ADR records. _(from `2026-09-15-projects-nested-nav3`)_
- **Adding a new route type on Android**: must still call `navSavedStateConfig(...)` with the new type's serializer in every NavGraph that can contain it. The `subclass(...)` registration requirement (per `2026-09-16-nav3-savedstate-serializers-required`) is unchanged on Android. _(from `2026-09-16-nav3-desktop-in-memory-no-savedstate`)_
- **Adding a new route type on Desktop**: no serializer registration needed; `rememberInMemoryNavBackStack(start)` is untyped and works for any `T : NavKey`. _(from `2026-09-16-nav3-desktop-in-memory-no-savedstate`)_
- Additional level of indirection for new developers: "where am I?" _(from `2026-09-15-projects-nested-nav3`)_
- All 3 projects screens use `LocalProjectsNavigator` — no callback parameters. _(from `2026-09-15-projects-nested-nav3`)_
- All Android NavGraph back stack declarations become `val backStack = rememberNavBackStackTyped(savedStateConfig, start)` — clean, typed, no suppression. _(from `2026-09-16-nav3-type-asymmetry-adr`)_
- All `@Preview` composables compile without composition-local crashes. _(from `2026-09-16-nav3-post-migration-fixes`)_
- All task feature screens (`TaskListScreen`, `TaskDetailViewScreen`, `TaskCreateScreen`) use `LocalTasksNavigator.current` for navigation — no callback parameters. _(from `2026-09-14-nav3-tasks-navigator`)_
- **Android build unchanged**: `assembleDebug` still compiles all Android-specific NavGraphs with full `SavedStateConfiguration` for process-death survival. _(from `2026-09-16-nav3-desktop-in-memory-no-savedstate`)_
- Android system back gesture is handled by `BackHandler` in `TasksNavGraph.android.kt`. JVM has no back handling. _(from `2026-09-14-nav3-tasks-navigator`)_
- Any future code that calls `backStack.last()` on Android must explicitly cast. _(from `2026-09-16-nav3-type-asymmetry-adr`)_
- `AppDestination.TaskDetail` and `TaskDetailCreate` remain `@Deprecated` — they can be deleted in a follow-up cleanup commit. _(from `2026-09-16-nav3-feature-graph-extensions`)_
- Cross-feature navigation between projects and tasks uses type-safe `AppDestination` hops. _(from `2026-09-15-projects-nested-nav3`)_
- **Desktop in-memory only**: Closing and reopening the Desktop window resets all nested back stacks. This was already the behavior before this change — `LocalSaveableStateRegistry` was always `null`. The new code makes this explicit. _(from `2026-09-16-nav3-desktop-in-memory-no-savedstate`)_
- `fabActionForNav3` is simpler and more correct. _(from `2026-09-16-nav3-post-migration-fixes`)_
- Feature isolation: `ProjectsNavGraph` is self-contained and could be ported to iOS or other shells. _(from `2026-09-15-projects-nested-nav3`)_
- `NavEntries.kt` wires `SettingsNavGraph(navCallbacks = nav)` and `SearchNavGraph(navCallbacks = nav)` instead of the raw screens. _(from `2026-09-16-nav3-settings-and-search-nested-graphs`)_
- Notes deep-links from Search now land on the correct note preview. _(from `2026-09-16-nav3-post-migration-fixes`)_
- Preview for `AccountSettingsScreen` uses a separate `AccountSettingsScreenPreviewContent` composable that takes an explicit callback, since `LocalSettingsNavigator` is only available inside the graph. _(from `2026-09-16-nav3-settings-and-search-nested-graphs`)_
- `ProjectDetailViewModel(projectId)` and `ProjectEditorViewModel(projectId)` now have correct per-entry VM scoping on Android. _(from `2026-09-15-projects-nested-nav3`)_
- `ProjectsNavGraph` in `NavEntries` now maps `ProjectsStartRoute.Editor` to `ProjectsRoute.Editor`. _(from `2026-09-16-nav3-feature-graph-extensions`)_
- Risk of `ClassCastException` if the type parameter is misused. _(from `2026-09-16-nav3-type-asymmetry-adr`)_
- Screens that need `@Preview` use `TasksPreviewWrapper { ... }` which provides a `PreviewTasksNavigator` via `LocalTasksNavigator`. _(from `2026-09-14-nav3-tasks-navigator`)_
- `SettingsScreen` no longer accepts `onNavigateToProfileSwitcher` — `AccountSettingsScreen` navigates directly. _(from `2026-09-16-nav3-settings-and-search-nested-graphs`)_
- Smoke test: tap FAB on Inbox → verify CreateTask opens; tap FAB on Plans → verify CreateProject opens. _(from `2026-09-16-android-shell-fab-fix`)_
- `@Suppress("UNCHECKED_CAST")` removed from all 5 Android NavGraph files. _(from `2026-09-16-nav3-type-asymmetry-adr`)_
- `TaskDetailIntent` no longer has `NavigateToProject` / `NavigateToTask` routing intents — those are now navigator methods. _(from `2026-09-14-nav3-tasks-navigator`)_
- `TasksNavGraph` is the `@Composable` nav host — it sets up `LocalTasksNavigator`, `LocalNavBackStack`, and the `BackHandler`. _(from `2026-09-14-nav3-tasks-navigator`)_
- `TasksNavigator` is the only class that mutates `NavBackStack<TasksRoute>`. _(from `2026-09-14-nav3-tasks-navigator`)_
- `TasksRoute` is the sealed interface defining all routes within the tasks graph (Inbox, Today, ByProject, Detail, Create). _(from `2026-09-14-nav3-tasks-navigator`)_
- The Android no-arg overload `rememberNavBackStack(vararg elements)` (reflection path) is **not used** in this project anymore — every call goes through the configuration overload so Android and JVM share one contract. _(from `2026-09-16-nav3-savedstate-serializers-required`)_
- The cast remains in all Android NavGraphs. _(from `2026-09-16-nav3-type-asymmetry-adr`)_
- The inline wrapper is `internal` to the Android source set — no API surface change. _(from `2026-09-16-nav3-type-asymmetry-adr`)_
- Users can now create projects directly from Plans via the FAB. _(from `2026-09-16-android-shell-fab-fix`)_
- Users can now create tasks directly from Inbox/Today via the FAB. _(from `2026-09-16-android-shell-fab-fix`)_
- When adding a new `data object` or `data class` to `AppDestination` (or any sealed route hierarchy that backs a `rememberNavBackStack`), **always** add the matching `subclass(...)` line in every relevant `serializersModule` — the compiler does not enforce this. _(from `2026-09-16-nav3-savedstate-serializers-required`)_

### `navigation`

- ~18 файлов переработано, +5 новых, -2 удалено. _(from `2026-09-14-tasks-feature-nested-nav3`)_
- 8 new files (nav package under projects feature) + 2 new ADR records. _(from `2026-09-15-projects-nested-nav3`)_
- **Adding a new route type on Android**: must still call `navSavedStateConfig(...)` with the new type's serializer in every NavGraph that can contain it. The `subclass(...)` registration requirement (per `2026-09-16-nav3-savedstate-serializers-required`) is unchanged on Android. _(from `2026-09-16-nav3-desktop-in-memory-no-savedstate`)_
- **Adding a new route type on Desktop**: no serializer registration needed; `rememberInMemoryNavBackStack(start)` is untyped and works for any `T : NavKey`. _(from `2026-09-16-nav3-desktop-in-memory-no-savedstate`)_
- Additional level of indirection for new developers: "where am I?" _(from `2026-09-15-projects-nested-nav3`)_
- All 3 projects screens use `LocalProjectsNavigator` — no callback parameters. _(from `2026-09-15-projects-nested-nav3`)_
- All Android NavGraph back stack declarations become `val backStack = rememberNavBackStackTyped(savedStateConfig, start)` — clean, typed, no suppression. _(from `2026-09-16-nav3-type-asymmetry-adr`)_
- All `@Preview` composables compile without composition-local crashes. _(from `2026-09-16-nav3-post-migration-fixes`)_
- All task feature screens (`TaskListScreen`, `TaskDetailViewScreen`, `TaskCreateScreen`) use `LocalTasksNavigator.current` for navigation — no callback parameters. _(from `2026-09-14-nav3-tasks-navigator`)_
- **Android build unchanged**: `assembleDebug` still compiles all Android-specific NavGraphs with full `SavedStateConfiguration` for process-death survival. _(from `2026-09-16-nav3-desktop-in-memory-no-savedstate`)_
- Android system back gesture is handled by `BackHandler` in `TasksNavGraph.android.kt`. JVM has no back handling. _(from `2026-09-14-nav3-tasks-navigator`)_
- Any future code that calls `backStack.last()` on Android must explicitly cast. _(from `2026-09-16-nav3-type-asymmetry-adr`)_
- `AppDestination.TaskDetail` and `TaskDetailCreate` remain `@Deprecated` — they can be deleted in a follow-up cleanup commit. _(from `2026-09-16-nav3-feature-graph-extensions`)_
- `AppDestination` пополнился `Notes` (уже был), логика FAB его задействует. _(from `2026-09-07-fab-chrome-level`)_
- `AppShell` — minor change: добавлен `FabAction` parameter. _(from `2026-09-07-fab-chrome-level`)_
- Backlinks are now shown and functional _(from `2026-09-09-notes-view-edit-split`)_
- **BottomBar taps** now have a single source of truth: `navigator.navigateTopLevel(dest)` — no `selectedIndex` to keep in sync. _(from `2026-09-05-android-bottom-nav`)_
- Clear UX: notes list → tap note → read → optionally edit _(from `2026-09-09-notes-view-edit-split`)_
- Cross-feature navigation between projects and tasks uses type-safe `AppDestination` hops. _(from `2026-09-15-projects-nested-nav3`)_
- Delete confirmation is handled in `NotePreview`, not buried in editor overflow menu _(from `2026-09-09-notes-view-edit-split`)_
- Desktop chrome is a 240 dp left rail, VSCode/JetBrains-style. Width is explicit, not derived from drawer measurements. _(from `2026-09-06-desktop-sidebar-replaces-permanent-drawer`)_
- **Desktop chrome** is unchanged from the user's perspective — the drawer still works exactly as before. _(from `2026-09-05-android-bottom-nav`)_
- **Desktop in-memory only**: Closing and reopening the Desktop window resets all nested back stacks. This was already the behavior before this change — `LocalSaveableStateRegistry` was always `null`. The new code makes this explicit. _(from `2026-09-16-nav3-desktop-in-memory-no-savedstate`)_
- Diff больше, чем чисто миграция tasks — затрагивает общий `Nav3State`. _(from `2026-09-14-nav3-vm-store-decorator-fix`)_
- Every `NavDestination` entry has an `icon` field. When adding a new entry, pick an icon from `androidx.compose.material.icons.Filled` or `Icons.AutoMirrored.Filled`. _(from `2026-09-06-desktop-sidebar-replaces-permanent-drawer`)_
- `fabActionForNav3` is simpler and more correct. _(from `2026-09-16-nav3-post-migration-fixes`)_
- Feature isolation: `ProjectsNavGraph` is self-contained and could be ported to iOS or other shells. _(from `2026-09-15-projects-nested-nav3`)_
- Instrumented/integration тесты (`CreateTaskFlowInstrumentedTest`) _(from `2026-09-14-nav3-vm-store-decorator-fix`)_
- Lifecycle VM становится привязан к lifetime entry — VM очищается _(from `2026-09-14-nav3-vm-store-decorator-fix`)_
- **`LocalNavBackStack` как публичный API** — позволяет экранам _(from `2026-09-14-tasks-feature-nested-nav3`)_
- **Menu sheet visibility** is `rememberSaveable` state in `AndroidShell` — survives config changes, not part of the back stack. _(from `2026-09-05-android-bottom-nav`)_
- `ModalShell` + `DrawerStyle.Modal` remain in `AppShell.kt`. They are not wired to any platform but are preserved for future modal drawer needs. _(from `2026-09-06-desktop-sidebar-replaces-permanent-drawer`)_
- **`NavDestination` (drawer enum)** remains for the desktop drawer's grouping by `NavGroup` — not removed, just no longer wired to mobile. _(from `2026-09-05-android-bottom-nav`)_
- `NavEntries.kt` wires `SettingsNavGraph(navCallbacks = nav)` and `SearchNavGraph(navCallbacks = nav)` instead of the raw screens. _(from `2026-09-16-nav3-settings-and-search-nested-graphs`)_
- Navigation now has one more route: `NoteView` ↔ `NoteEditor` ↔ `NotesScreen` _(from `2026-09-09-notes-view-edit-split`)_
- Navigation между Detail и подзадачами/проектами становится _(from `2026-09-14-tasks-feature-nested-nav3`)_
- Note metadata (word count, last updated) is visible without entering edit mode _(from `2026-09-09-notes-view-edit-split`)_
- `NotePreview` must observe the note via `repo.watchNote()` — requires a Flow subscription _(from `2026-09-09-notes-view-edit-split`)_
- Notes deep-links from Search now land on the correct note preview. _(from `2026-09-16-nav3-post-migration-fixes`)_
- **Per-tab backstacks** work as expected: open TaskDetail on Today, switch to Plans, switch back to Today → TaskDetail is restored. _(from `2026-09-05-android-bottom-nav`)_
- Preview for `AccountSettingsScreen` uses a separate `AccountSettingsScreenPreviewContent` composable that takes an explicit callback, since `LocalSettingsNavigator` is only available inside the graph. _(from `2026-09-16-nav3-settings-and-search-nested-graphs`)_
- `ProjectDetailViewModel(projectId)` and `ProjectEditorViewModel(projectId)` now have correct per-entry VM scoping on Android. _(from `2026-09-15-projects-nested-nav3`)_
- `ProjectDetailViewModel(projectId)` — Project X → back → Project Y _(from `2026-09-14-nav3-vm-store-decorator-fix`)_
- `ProjectsNavGraph` in `NavEntries` now maps `ProjectsStartRoute.Editor` to `ProjectsRoute.Editor`. _(from `2026-09-16-nav3-feature-graph-extensions`)_
- Risk of `ClassCastException` if the type parameter is misused. _(from `2026-09-16-nav3-type-asymmetry-adr`)_
- Screens that need `@Preview` use `TasksPreviewWrapper { ... }` which provides a `PreviewTasksNavigator` via `LocalTasksNavigator`. _(from `2026-09-14-nav3-tasks-navigator`)_
- `SettingsScreen` no longer accepts `onNavigateToProfileSwitcher` — `AccountSettingsScreen` navigates directly. _(from `2026-09-16-nav3-settings-and-search-nested-graphs`)_
- `singularity-todo-shared-ui-components` skill governs decomposition: desktop-only chrome stays in `feature/nav/`, shared widgets go to `core/ui/components/`. _(from `2026-09-06-desktop-sidebar-replaces-permanent-drawer`)_
- Smoke test: tap FAB on Inbox → verify CreateTask opens; tap FAB on Plans → verify CreateProject opens. _(from `2026-09-16-android-shell-fab-fix`)_
- **`String`-encoded `initialDueDate`** — заменён на _(from `2026-09-14-tasks-feature-nested-nav3`)_
- `@Suppress("UNCHECKED_CAST")` removed from all 5 Android NavGraph files. _(from `2026-09-16-nav3-type-asymmetry-adr`)_
- `TagsScreen` больше не принимает callback — экран не подключён к навигации (menu destination `Tags` отсутствует в `AppDestination`). _(from `2026-09-07-fab-chrome-level`)_
- `TaskCreateViewModel(initialDueDate)` — два последовательных _(from `2026-09-14-nav3-vm-store-decorator-fix`)_
- `TaskDetailIntent` no longer has `NavigateToProject` / `NavigateToTask` routing intents — those are now navigator methods. _(from `2026-09-14-nav3-tasks-navigator`)_
- `TaskDetailViewModel(taskId)` — Task A → back → Task B больше _(from `2026-09-14-nav3-vm-store-decorator-fix`)_
- `TasksNavGraph` is the `@Composable` nav host — it sets up `LocalTasksNavigator`, `LocalNavBackStack`, and the `BackHandler`. _(from `2026-09-14-nav3-tasks-navigator`)_
- `TasksNavigator` is the only class that mutates `NavBackStack<TasksRoute>`. _(from `2026-09-14-nav3-tasks-navigator`)_
- `TasksRoute` is the sealed interface defining all routes within the tasks graph (Inbox, Today, ByProject, Detail, Create). _(from `2026-09-14-nav3-tasks-navigator`)_
- **`TasksRoute.Pop` как sentinel** — race condition (см. review rev. 1, _(from `2026-09-14-tasks-feature-nested-nav3`)_
- **`TasksScreen`** unchanged — it already takes `onNavigateToTask` / `onNavigateToCreateTask` callbacks; the per-tab sub-navigation state now lives in `TasksRoute` inside `AppNavHost` via `rememberSaveable`. _(from `2026-09-05-android-bottom-nav`)_
- The Android no-arg overload `rememberNavBackStack(vararg elements)` (reflection path) is **not used** in this project anymore — every call goes through the configuration overload so Android and JVM share one contract. _(from `2026-09-16-nav3-savedstate-serializers-required`)_
- The cast remains in all Android NavGraphs. _(from `2026-09-16-nav3-type-asymmetry-adr`)_
- The inline wrapper is `internal` to the Android source set — no API surface change. _(from `2026-09-16-nav3-type-asymmetry-adr`)_
- Unit-тесты навигации tasks требуют `Robolectric` или `composeRule` — _(from `2026-09-14-tasks-feature-nested-nav3`)_
- User must explicitly tap "Edit" to modify — one additional tap for casual reading _(from `2026-09-09-notes-view-edit-split`)_
- Users can now create projects directly from Plans via the FAB. _(from `2026-09-16-android-shell-fab-fix`)_
- Users can now create tasks directly from Inbox/Today via the FAB. _(from `2026-09-16-android-shell-fab-fix`)_
- When adding a new `data object` or `data class` to `AppDestination` (or any sealed route hierarchy that backs a `rememberNavBackStack`), **always** add the matching `subclass(...)` line in every relevant `serializersModule` — the compiler does not enforce this. _(from `2026-09-16-nav3-savedstate-serializers-required`)_
- В `JvmNav3State.kt` для `AppDestination.TasksGraph` / _(from `2026-09-14-tasks-feature-nested-nav3`)_
- В `TasksNavGraph.kt` (для nested `rememberNavBackStack`). _(from `2026-09-14-tasks-feature-nested-nav3`)_
- Все остальные параметризованные VM (~20 callsites). _(from `2026-09-14-nav3-vm-store-decorator-fix`)_
- Дополнительный уровень индирекции для новых разработчиков: «где я?». _(from `2026-09-14-tasks-feature-nested-nav3`)_
- Если какой-то VM был неявно расчитан на per-Activity scope _(from `2026-09-14-nav3-vm-store-decorator-fix`)_
- Необходимо зарегистрировать `TasksRoute` в двух `SerializersModule`: _(from `2026-09-14-tasks-feature-nested-nav3`)_
- **Один плоский AppDestination без nested graph** — не даёт feature _(from `2026-09-14-tasks-feature-nested-nav3`)_
- Первая фича с nested graph — другие фичи (notes/projects/auth/settings) _(from `2026-09-14-tasks-feature-nested-nav3`)_
- Рассмотреть переход на `LocalResultEventBus` + `ResultEffect<T>` для _(from `2026-09-14-nav3-vm-store-decorator-fix`)_
- Сигнатуры экранов tasks упрощаются до 1-2 аргументов. _(from `2026-09-14-tasks-feature-nested-nav3`)_
- Существующие unit-тесты для VM не затрагиваются (тестируют VM _(from `2026-09-14-nav3-vm-store-decorator-fix`)_
- Чинится латентный bug для всех `koinViewModel { parametersOf(...) }` _(from `2026-09-14-nav3-vm-store-decorator-fix`)_
- **Чинится латентный VM scoping bug** для `TaskDetailViewModel`, _(from `2026-09-14-tasks-feature-nested-nav3`)_

### `notes`

- `AppDestination.TaskDetail` and `TaskDetailCreate` remain `@Deprecated` — they can be deleted in a follow-up cleanup commit. _(from `2026-09-16-nav3-feature-graph-extensions`)_
- Backlinks are now shown and functional _(from `2026-09-09-notes-view-edit-split`)_
- Caller must provide `MutableStateFlow<String>` and inject `InternalLinkRepository` and `ProfileAwareCurrentUser` — slightly more boilerplate at call site _(from `2026-09-09-internal-link-picker-generic`)_
- Clear UX: notes list → tap note → read → optionally edit _(from `2026-09-09-notes-view-edit-split`)_
- `core/ui/components/` is now free of feature-domain imports _(from `2026-09-09-internal-link-picker-generic`)_
- Cross-screen state (e.g. "did the user just save a note") must flow through navigation callbacks, not shared VM state _(from `2026-09-09-notes-vm-split`)_
- Delete confirmation is handled in `NotePreview`, not buried in editor overflow menu _(from `2026-09-09-notes-view-edit-split`)_
- Each VM is small enough to understand fully (~60-150 lines) _(from `2026-09-09-notes-vm-split`)_
- Editor session state is released when user navigates away _(from `2026-09-09-notes-vm-split`)_
- `FakeNotesRepository` и `FakeNoteDao` обновлены同步. _(from `2026-09-07-note-editor-body-load`)_
- `getBacklinkNotes` now returns real results — backlinks in `NotePreview` and `InternalLinkPickerSheet` will work _(from `2026-09-09-notes-outgoing-links-extraction`)_
- Icon per `LinkKind` makes the list scannable _(from `2026-09-09-internal-link-picker-generic`)_
- Navigation now has one more route: `NoteView` ↔ `NoteEditor` ↔ `NotesScreen` _(from `2026-09-09-notes-view-edit-split`)_
- No loading screen — the text field is always visible in the list _(from `2026-09-09-notes-quick-add`)_
- No new dependencies _(from `2026-09-09-notes-outgoing-links-extraction`)_
- No schema migration needed _(from `2026-09-09-notes-outgoing-links-extraction`)_
- `NoteDao.updateContent` сигнатура изменилась: добавлен параметр `html: String`. _(from `2026-09-07-note-editor-body-load`)_

### `"notes"`

- `NoteEditor` now requires `InternalLinkRepository` in its constructor — updated `NotesDiModule` accordingly. _(from `2026-09-15-noteeditor-udf-link-search`)_

### `notes`

- Note metadata (word count, last updated) is visible without entering edit mode _(from `2026-09-09-notes-view-edit-split`)_
- `NotePreview` must observe the note via `repo.watchNote()` — requires a Flow subscription _(from `2026-09-09-notes-view-edit-split`)_
- `NotesListViewModel` now requires `IdGenerator` as a third constructor parameter _(from `2026-09-09-notes-quick-add`)_
- `NotesRepository.createWithContent` и `updateContent` сигнатуры изменились: добавлен параметр `bodyHtml: String`. _(from `2026-09-07-note-editor-body-load`)_
- `NotesRoute` now injects `NotesListViewModel` via `koinViewModel()`, `NoteEditor` and `NotePreview` are injected via their respective screen composables _(from `2026-09-09-notes-vm-split`)_
- One tap fewer than before for the common "capture a thought" workflow _(from `2026-09-09-notes-quick-add`)_
- Previews for each screen can use `koinViewModel { parametersOf(...) }` without circular dependency _(from `2026-09-09-notes-vm-split`)_

### `"notes"`

- Previews that don't use Koin continue to work since `searchNotesForLink`/`searchTasksForLink` are nullable. _(from `2026-09-15-noteeditor-udf-link-search`)_

### `notes`

- `ProjectsNavGraph` in `NavEntries` now maps `ProjectsStartRoute.Editor` to `ProjectsRoute.Editor`. _(from `2026-09-16-nav3-feature-graph-extensions`)_
- Regex over HTML is less elegant than walking the paragraph tree, but the paragraph tree is internal _(from `2026-09-09-notes-outgoing-links-extraction`)_
- Search debouncing (300ms) is now the caller's responsibility (implemented inside the sheet via `LaunchedEffect`) _(from `2026-09-09-internal-link-picker-generic`)_
- Sheet is reusable by any feature that needs internal linking (e.g. TaskEditor) _(from `2026-09-09-internal-link-picker-generic`)_
- Single search + merged results = better UX (one tap instead of tab switching) _(from `2026-09-09-internal-link-picker-generic`)_
- Slight visual complexity added to the list screen _(from `2026-09-09-notes-quick-add`)_
- The `outgoing_links` column is populated on every save, keeping backlinks current _(from `2026-09-09-notes-outgoing-links-extraction`)_
- Three Koin registrations instead of one _(from `2026-09-09-notes-vm-split`)_
- Title pre-saved to DB before navigating to editor (no lost titles on crash) _(from `2026-09-09-notes-quick-add`)_
- User must explicitly tap "Edit" to modify — one additional tap for casual reading _(from `2026-09-09-notes-view-edit-split`)_
- VMs are independently testable with focused test suites _(from `2026-09-09-notes-vm-split`)_
- Все существующие тесты проходят — никаких изменений в тестовых вызовах не потребовалось (jvmTest зелёный). _(from `2026-09-07-note-editor-body-load`)_
- При первом открытии старой заметки (без `bodyHtml`) — форматирование может отличаться от исходного (round-trip через markdown). Это accepted trade-off для legacy data. _(from `2026-09-07-note-editor-body-load`)_

### `observability`

- 4 ADR entries created + DIGEST.md refreshed _(from `2026-09-07-multi-profile-and-usage-tracking`)_
- AI Usage screen в Settings _(from `2026-09-07-multi-profile-and-usage-tracking`)_
- `ProfileAwareCurrentUser` инжектится во все write-tools _(from `2026-09-07-multi-profile-and-usage-tracking`)_
- Room schema v8 с `llm_usage` table + `profiles` table _(from `2026-09-07-multi-profile-and-usage-tracking`)_
- ZCode подключается с `--profile=ai-agent` → все операции в профиле ai-agent _(from `2026-09-07-multi-profile-and-usage-tracking`)_

### `picker`

- `ParentOption` is a `@JvmInline value class` candidate if it grows beyond 3 fields (currently 3 — plain data class is fine) _(from `2026-09-09-parent-picker-contract`)_
- Parent options are reactive (`StateFlow`) — picker updates automatically when projects change _(from `2026-09-09-parent-picker-contract`)_
- `ParentPickerSheet` signature: `options: List<ParentOption>`, NOT `currentParentId: ProjectId?` _(from `2026-09-09-parent-picker-contract`)_
- The "None (root)" option is rendered as a `TextButton` above the `LazyColumn`, not as part of `options` _(from `2026-09-09-parent-picker-contract`)_

### `"plan-tracking"`

- ADR пишется в `docs/decisions/{YYYY-MM-DD}-{slug}.md` (server-side date). _(from `2026-09-08-mcp-plan-tracking-via-mcp`)_
- Один прогон драйвера = реальная multi-step демонстрация MCP. _(from `2026-09-08-mcp-plan-tracking-via-mcp`)_
- При недоступности LLM в драйвере зашит fallback sub-task'ов. _(from `2026-09-08-mcp-plan-tracking-via-mcp`)_

### `platform-module`

- `BackupRepository` resolves correctly in all environments (JVM desktop, Android). _(from `2026-09-07-backup-directory-via-koin-string`)_
- Settings → Backup tab no longer crashes during composition. _(from `2026-09-07-backup-directory-via-koin-string`)_
- The `desktopApp/build.gradle.kts` change (adding `implementation(project(":shared"))` with kotlinJvmTask) was also part of the desktop build fix. _(from `2026-09-07-backup-directory-via-koin-string`)_

### `preview`

- All new screens MUST follow the `PublicScreen` / `PrivateContent` naming pattern _(from `2026-09-09-preview-with-koin-helper`)_
- All `@Preview` composables use `ProjectDetailContent(vm, ...)` with `FakeRepositories` — no preview crashes _(from `2026-09-09-project-detail-rework-15-fixes`)_
- Architecture: screens own routing state (`sheetState`), VMs own domain logic, navigation callbacks are passed as parameters _(from `2026-09-09-project-detail-rework-15-fixes`)_
- `Clock.System.now()` must not appear in preview code — use _(from `2026-09-06-compose-previews`)_
- Do NOT introduce `koinViewModel()` inside any `@Preview` — CI/preview harness does not start Koin _(from `2026-09-09-preview-with-koin-helper`)_
- FakeRepositories live in `commonMain/test/fakes/` (not `commonTest`) so `commonMain` previews can access them _(from `2026-09-09-preview-with-koin-helper`)_
- `@Preview` annotation is `@androidx.compose.ui.tooling.preview.Preview` — _(from `2026-09-06-compose-previews`)_
- `@Preview` composables are always `private` and call the `*Content` variant with manually constructed VMs _(from `2026-09-09-preview-with-koin-helper`)_
- Preview functions are `private` and placed at the end of the source file, _(from `2026-09-06-compose-previews`)_
- `PreviewParameterProvider` is avoided — individual preview functions used instead _(from `2026-09-06-compose-previews`)_
- `ProjectDetailScreen` is fully functional: quick-add creates tasks, parent picker works, Remind/Attach/DueDate/Children sheets open, task click navigates to `TaskDetailScreen` _(from `2026-09-09-project-detail-rework-15-fixes`)_
- `ProjectPickerSheet` is reactive — newly created projects appear without reopening the sheet _(from `2026-09-09-project-detail-rework-15-fixes`)_
- `useSurface = false` when the preview root already contains a `Scaffold` _(from `2026-09-06-compose-previews`)_

### `profiles`

- All 3 tools now require `ProfileAwareCurrentUser` in DI — tested via _(from `2026-09-08-mcp-schema-and-profile-userid-fixes`)_
- `list_tasks`, `list_linked_tasks`, and `search_tasks` now return correct results _(from `2026-09-08-mcp-schema-and-profile-userid-fixes`)_
- MCP clients that validate `$schema` as a URI will no longer reject tool schemas. _(from `2026-09-08-mcp-schema-and-profile-userid-fixes`)_

### `project-detail`

- **`createTask`** must go through `CreateTaskUseCase`, not direct `taskRepo.create`. _(from `2026-09-09-projectdetail-write-through-fix`)_
- Screen owns `activeSheet` routing state; VM only receives routing intents. _(from `2026-09-09-projectdetail-write-through-fix`)_

### `projects`

- 8 new files (nav package under projects feature) + 2 new ADR records. _(from `2026-09-15-projects-nested-nav3`)_
- Additional level of indirection for new developers: "where am I?" _(from `2026-09-15-projects-nested-nav3`)_
- All 3 projects screens use `LocalProjectsNavigator` — no callback parameters. _(from `2026-09-15-projects-nested-nav3`)_
- All `@Preview` composables use `ProjectDetailContent(vm, ...)` with `FakeRepositories` — no preview crashes _(from `2026-09-09-project-detail-rework-15-fixes`)_
- Architecture: screens own routing state (`sheetState`), VMs own domain logic, navigation callbacks are passed as parameters _(from `2026-09-09-project-detail-rework-15-fixes`)_
- Cross-feature navigation between projects and tasks uses type-safe `AppDestination` hops. _(from `2026-09-15-projects-nested-nav3`)_
- Feature isolation: `ProjectsNavGraph` is self-contained and could be ported to iOS or other shells. _(from `2026-09-15-projects-nested-nav3`)_
- `ParentOption` is a `@JvmInline value class` candidate if it grows beyond 3 fields (currently 3 — plain data class is fine) _(from `2026-09-09-parent-picker-contract`)_
- Parent options are reactive (`StateFlow`) — picker updates automatically when projects change _(from `2026-09-09-parent-picker-contract`)_
- `ParentPickerSheet` signature: `options: List<ParentOption>`, NOT `currentParentId: ProjectId?` _(from `2026-09-09-parent-picker-contract`)_
- `ProjectDetailScreen` is fully functional: quick-add creates tasks, parent picker works, Remind/Attach/DueDate/Children sheets open, task click navigates to `TaskDetailScreen` _(from `2026-09-09-project-detail-rework-15-fixes`)_
- `ProjectDetailViewModel(projectId)` and `ProjectEditorViewModel(projectId)` now have correct per-entry VM scoping on Android. _(from `2026-09-15-projects-nested-nav3`)_
- `ProjectPickerSheet` is reactive — newly created projects appear without reopening the sheet _(from `2026-09-09-project-detail-rework-15-fixes`)_
- The "None (root)" option is rendered as a `TextButton` above the `LazyColumn`, not as part of `options` _(from `2026-09-09-parent-picker-contract`)_

### `"quality"`

- 3 preview functions per component (default, empty, edge case) — consistent with `2026-09-06-compose-previews` skill. _(from `2026-09-08-roboazzi-snapshot-tests`)_
- Baseline images stored in `shared/src/commonTest/resources/roborazzi/`. _(from `2026-09-08-roboazzi-snapshot-tests`)_

### `quality`

- **Configuration cache**: detekt 1.23.x and kover 0.9.9 are both CC-compatible. Verified by running `./gradlew --configuration-cache :shared:detekt`. _(from `2026-09-15-detekt-ktlint-kover-setup`)_
- `:desktopApp:detekt` / `:desktopApp:detektFormat` / `:desktopApp:detektBaseline` _(from `2026-09-15-detekt-ktlint-kover-setup`)_
- `:desktopApp:koverXmlReport` / `:desktopApp:koverHtmlReport` _(from `2026-09-15-detekt-ktlint-kover-setup`)_
- **detekt 2.0.0-alpha.3 vs Kotlin 2.3.21**: this version was chosen because stable 1.23.8 was compiled against Kotlin 2.0.21 and throws "detekt was compiled with Kotlin 2.0.21 but is currently running with 2.3.21". Upgrade to stable 2.x once released. _(from `2026-09-15-detekt-ktlint-kover-setup`)_
- **`.editorconfig` may rewrap existing code** on first `detektFormat` run. Expect a large diff; consider a separate "format" commit before merging. _(from `2026-09-15-detekt-ktlint-kover-setup`)_

### `"quality"`

- Every future PR touching UI components must run snapshot tests and update baselines when changes are intentional. _(from `2026-09-08-roboazzi-snapshot-tests`)_

### `quality`

- **`ignoreFailures = true`** means violations are reported but never block builds. To enforce violations: set `ignoreFailures = false` in both `shared/build.gradle.kts` and `desktopApp/build.gradle.kts` once baselines are settled. **TODO: tracked in issue tracker — promote after baselines are clean (est. post-format PR).** _(from `2026-09-15-detekt-ktlint-kover-setup`)_
- **New Gradle tasks added**: _(from `2026-09-15-detekt-ktlint-kover-setup`)_
- `:shared:detekt` / `:shared:detektFormat` / `:shared:detektBaseline` _(from `2026-09-15-detekt-ktlint-kover-setup`)_
- `:shared:koverXmlReport` / `:shared:koverHtmlReport` _(from `2026-09-15-detekt-ktlint-kover-setup`)_

### `quick-add`

- No loading screen — the text field is always visible in the list _(from `2026-09-09-notes-quick-add`)_
- `NotesListViewModel` now requires `IdGenerator` as a third constructor parameter _(from `2026-09-09-notes-quick-add`)_
- One tap fewer than before for the common "capture a thought" workflow _(from `2026-09-09-notes-quick-add`)_
- Slight visual complexity added to the list screen _(from `2026-09-09-notes-quick-add`)_
- Title pre-saved to DB before navigating to editor (no lost titles on crash) _(from `2026-09-09-notes-quick-add`)_

### `reactive`

- All `@Preview` composables use `ProjectDetailContent(vm, ...)` with `FakeRepositories` — no preview crashes _(from `2026-09-09-project-detail-rework-15-fixes`)_
- Architecture: screens own routing state (`sheetState`), VMs own domain logic, navigation callbacks are passed as parameters _(from `2026-09-09-project-detail-rework-15-fixes`)_
- `ProjectDetailScreen` is fully functional: quick-add creates tasks, parent picker works, Remind/Attach/DueDate/Children sheets open, task click navigates to `TaskDetailScreen` _(from `2026-09-09-project-detail-rework-15-fixes`)_
- `ProjectPickerSheet` is reactive — newly created projects appear without reopening the sheet _(from `2026-09-09-project-detail-rework-15-fixes`)_

### `refactor`

- 8 new files (nav package under projects feature) + 2 new ADR records. _(from `2026-09-15-projects-nested-nav3`)_
- Additional level of indirection for new developers: "where am I?" _(from `2026-09-15-projects-nested-nav3`)_
- All 3 projects screens use `LocalProjectsNavigator` — no callback parameters. _(from `2026-09-15-projects-nested-nav3`)_
- All task feature screens (`TaskListScreen`, `TaskDetailViewScreen`, `TaskCreateScreen`) use `LocalTasksNavigator.current` for navigation — no callback parameters. _(from `2026-09-14-nav3-tasks-navigator`)_
- Android system back gesture is handled by `BackHandler` in `TasksNavGraph.android.kt`. JVM has no back handling. _(from `2026-09-14-nav3-tasks-navigator`)_
- **`createTask` and `moveTaskToProject`** remain in VM (require repository writes) _(from `2026-09-09-project-detail-intent-refactor`)_
- Cross-feature navigation between projects and tasks uses type-safe `AppDestination` hops. _(from `2026-09-15-projects-nested-nav3`)_
- Feature isolation: `ProjectsNavGraph` is self-contained and could be ported to iOS or other shells. _(from `2026-09-15-projects-nested-nav3`)_
- **`NavigateToTasks`** is no longer a VM event — screen handles it as routing _(from `2026-09-09-project-detail-intent-refactor`)_
- **No pure reducer needed** — `ProjectDetailViewModel` is write-through like `TaskDetailViewModel` _(from `2026-09-09-project-detail-intent-refactor`)_
- One new e2e test in `mcp-server` (`McpToolRoundTripTest`). _(from `2026-09-08-mcp-server-health-audit`)_
- One new unit test file in `mcp-server` (`KoogJsonSchemaBuilderTest`). _(from `2026-09-08-mcp-server-health-audit`)_
- **`ProjectDetailIntent`** is the canonical list of all project mutations — adding a new field mutation = one `Domain` case _(from `2026-09-09-project-detail-intent-refactor`)_
- **`ProjectDetailUiEvent`** now has only 2 cases: `NavigateBack` (post-delete) and `ShowError` _(from `2026-09-09-project-detail-intent-refactor`)_
- `ProjectDetailViewModel(projectId)` and `ProjectEditorViewModel(projectId)` now have correct per-entry VM scoping on Android. _(from `2026-09-15-projects-nested-nav3`)_
- Screens that need `@Preview` use `TasksPreviewWrapper { ... }` which provides a `PreviewTasksNavigator` via `LocalTasksNavigator`. _(from `2026-09-14-nav3-tasks-navigator`)_
- `TaskDetailIntent` no longer has `NavigateToProject` / `NavigateToTask` routing intents — those are now navigator methods. _(from `2026-09-14-nav3-tasks-navigator`)_
- `TasksNavGraph` is the `@Composable` nav host — it sets up `LocalTasksNavigator`, `LocalNavBackStack`, and the `BackHandler`. _(from `2026-09-14-nav3-tasks-navigator`)_
- `TasksNavigator` is the only class that mutates `NavBackStack<TasksRoute>`. _(from `2026-09-14-nav3-tasks-navigator`)_
- `TasksRoute` is the sealed interface defining all routes within the tasks graph (Inbox, Today, ByProject, Detail, Create). _(from `2026-09-14-nav3-tasks-navigator`)_
- Three new unit test files in `shared/commonTest` for the read tools. _(from `2026-09-08-mcp-server-health-audit`)_
- **`toggleArchive`** no longer emits `Saved` — `lastEditedAt` drives "Saved X ago" UI via the `mutate{}` helper _(from `2026-09-09-project-detail-intent-refactor`)_
- `ToolFactories.kt` gets the profile-aware default applied (small diff, _(from `2026-09-08-mcp-server-health-audit`)_
- `ToolRegistrar` catches `McpToolError` first (small diff). _(from `2026-09-08-mcp-server-health-audit`)_

### `regression`

- **`createTask`** must go through `CreateTaskUseCase`, not direct `taskRepo.create`. _(from `2026-09-09-projectdetail-write-through-fix`)_
- Screen owns `activeSheet` routing state; VM only receives routing intents. _(from `2026-09-09-projectdetail-write-through-fix`)_

### `"repository"`

- Archive and Delete have distinct storage semantics — future "Trash" filter can distinguish intentional archive from accidental delete. _(from `2026-09-08-task-archive-restore-contract`)_
- `_recentlyDeleted` in TaskDetailViewModel holds the full task before delete/archive for undo. _(from `2026-09-08-task-archive-restore-contract`)_
- `restore()` is idempotent — calling restore on a non-archived task is a no-op (or returns Result.success if the row simply re-inserted). _(from `2026-09-08-task-archive-restore-contract`)_

### `rich-editor`

- Backlinks are now shown and functional _(from `2026-09-09-notes-view-edit-split`)_
- Clear UX: notes list → tap note → read → optionally edit _(from `2026-09-09-notes-view-edit-split`)_
- Delete confirmation is handled in `NotePreview`, not buried in editor overflow menu _(from `2026-09-09-notes-view-edit-split`)_
- `FakeNotesRepository` и `FakeNoteDao` обновлены同步. _(from `2026-09-07-note-editor-body-load`)_
- `getBacklinkNotes` now returns real results — backlinks in `NotePreview` and `InternalLinkPickerSheet` will work _(from `2026-09-09-notes-outgoing-links-extraction`)_
- Navigation now has one more route: `NoteView` ↔ `NoteEditor` ↔ `NotesScreen` _(from `2026-09-09-notes-view-edit-split`)_
- No new dependencies _(from `2026-09-09-notes-outgoing-links-extraction`)_
- No schema migration needed _(from `2026-09-09-notes-outgoing-links-extraction`)_
- `NoteDao.updateContent` сигнатура изменилась: добавлен параметр `html: String`. _(from `2026-09-07-note-editor-body-load`)_
- Note metadata (word count, last updated) is visible without entering edit mode _(from `2026-09-09-notes-view-edit-split`)_
- `NotePreview` must observe the note via `repo.watchNote()` — requires a Flow subscription _(from `2026-09-09-notes-view-edit-split`)_
- `NotesRepository.createWithContent` и `updateContent` сигнатуры изменились: добавлен параметр `bodyHtml: String`. _(from `2026-09-07-note-editor-body-load`)_
- Regex over HTML is less elegant than walking the paragraph tree, but the paragraph tree is internal _(from `2026-09-09-notes-outgoing-links-extraction`)_
- The `outgoing_links` column is populated on every save, keeping backlinks current _(from `2026-09-09-notes-outgoing-links-extraction`)_
- User must explicitly tap "Edit" to modify — one additional tap for casual reading _(from `2026-09-09-notes-view-edit-split`)_
- Все существующие тесты проходят — никаких изменений в тестовых вызовах не потребовалось (jvmTest зелёный). _(from `2026-09-07-note-editor-body-load`)_
- При первом открытии старой заметки (без `bodyHtml`) — форматирование может отличаться от исходного (round-trip через markdown). Это accepted trade-off для legacy data. _(from `2026-09-07-note-editor-body-load`)_

### `robolectric`

- **`Clock` must be passed to `CreateTaskUseCase` / `UpdateTaskUseCase`** — use the singleton `Clock` from `core.platform`. _(from `2026-09-05-robolectric-widget-tests`)_
- **Fake repo returns empty by default** — widget tests that check `LazyColumn` with `testTag` will fail when repo is empty (state = `Empty`). Test the `EmptyState` text instead, or seed data via `fakeNotesRepo.seed(note)`. _(from `2026-09-05-robolectric-widget-tests`)_
- **JVM args for JDK 21+** — add `--add-opens=java.base/jdk.internal.access=ALL-UNNAMED` to `gradle.properties` (`org.gradle.jvmargs`) AND to `shared/build.gradle.kts` via `afterEvaluate` + `tasks.withType<Test>()` for the test worker process. _(from `2026-09-05-robolectric-widget-tests`)_
- **Robolectric 4.17-beta-4** — `4.16` maxes at SDK 36; `compileSdk=37` requires the beta. The beta is already cached. _(from `2026-09-05-robolectric-widget-tests`)_
- **`Session.Anonymous()` requires `UserId`** — always pass `UserId.anonymous` or `UserId.fromString("...")`. _(from `2026-09-05-robolectric-widget-tests`)_
- **Use `UserId` from `feature.tasks`** — it's defined in `Ids.kt` there, imported explicitly. _(from `2026-09-05-robolectric-widget-tests`)_
- **`waitForIdle()` is a method, not a function** — do NOT import it. Call `composeRule.waitForIdle()` directly. _(from `2026-09-05-robolectric-widget-tests`)_

### `"roborazzi"`

- 3 preview functions per component (default, empty, edge case) — consistent with `2026-09-06-compose-previews` skill. _(from `2026-09-08-roboazzi-snapshot-tests`)_
- Baseline images stored in `shared/src/commonTest/resources/roborazzi/`. _(from `2026-09-08-roboazzi-snapshot-tests`)_
- Every future PR touching UI components must run snapshot tests and update baselines when changes are intentional. _(from `2026-09-08-roboazzi-snapshot-tests`)_

### `room`

- `FakeNotesRepository` и `FakeNoteDao` обновлены同步. _(from `2026-09-07-note-editor-body-load`)_
- `getBacklinkNotes` now returns real results — backlinks in `NotePreview` and `InternalLinkPickerSheet` will work _(from `2026-09-09-notes-outgoing-links-extraction`)_
- No new dependencies _(from `2026-09-09-notes-outgoing-links-extraction`)_
- No schema migration needed _(from `2026-09-09-notes-outgoing-links-extraction`)_
- `NoteDao.updateContent` сигнатура изменилась: добавлен параметр `html: String`. _(from `2026-09-07-note-editor-body-load`)_
- `NotesRepository.createWithContent` и `updateContent` сигнатуры изменились: добавлен параметр `bodyHtml: String`. _(from `2026-09-07-note-editor-body-load`)_
- Regex over HTML is less elegant than walking the paragraph tree, but the paragraph tree is internal _(from `2026-09-09-notes-outgoing-links-extraction`)_
- The `outgoing_links` column is populated on every save, keeping backlinks current _(from `2026-09-09-notes-outgoing-links-extraction`)_
- Все существующие тесты проходят — никаких изменений в тестовых вызовах не потребовалось (jvmTest зелёный). _(from `2026-09-07-note-editor-body-load`)_
- При первом открытии старой заметки (без `bodyHtml`) — форматирование может отличаться от исходного (round-trip через markdown). Это accepted trade-off для legacy data. _(from `2026-09-07-note-editor-body-load`)_

### `"round-2"`

- tag 'mcp-ux'/'ui-subtask'/'ai-tooling'/'mcp-policy'/'refactor' — 5 persistent categories для фильтрации. _(from `2026-09-08-mcp-dogfooding-round-2`)_
- В профиле ai-agent теперь 5 top-level plans × ~6 sub-tasks = ~30 новых rows. _(from `2026-09-08-mcp-dogfooding-round-2`)_
- Каждый plan имеет parentTaskId = top-task; UI должен теперь уметь их показать (см. plan 'ui-subtask'). _(from `2026-09-08-mcp-dogfooding-round-2`)_

### `schema`

- All 3 tools now require `ProfileAwareCurrentUser` in DI — tested via _(from `2026-09-08-mcp-schema-and-profile-userid-fixes`)_
- `list_tasks`, `list_linked_tasks`, and `search_tasks` now return correct results _(from `2026-09-08-mcp-schema-and-profile-userid-fixes`)_
- MCP clients that validate `$schema` as a URI will no longer reject tool schemas. _(from `2026-09-08-mcp-schema-and-profile-userid-fixes`)_

### `screen-architecture`

- All `@Preview` composables use `ProjectDetailContent(vm, ...)` with `FakeRepositories` — no preview crashes _(from `2026-09-09-project-detail-rework-15-fixes`)_
- Architecture: screens own routing state (`sheetState`), VMs own domain logic, navigation callbacks are passed as parameters _(from `2026-09-09-project-detail-rework-15-fixes`)_
- `ProjectDetailScreen` is fully functional: quick-add creates tasks, parent picker works, Remind/Attach/DueDate/Children sheets open, task click navigates to `TaskDetailScreen` _(from `2026-09-09-project-detail-rework-15-fixes`)_
- `ProjectPickerSheet` is reactive — newly created projects appear without reopening the sheet _(from `2026-09-09-project-detail-rework-15-fixes`)_

### `search`

- `NavEntries.kt` wires `SettingsNavGraph(navCallbacks = nav)` and `SearchNavGraph(navCallbacks = nav)` instead of the raw screens. _(from `2026-09-16-nav3-settings-and-search-nested-graphs`)_
- Preview for `AccountSettingsScreen` uses a separate `AccountSettingsScreenPreviewContent` composable that takes an explicit callback, since `LocalSettingsNavigator` is only available inside the graph. _(from `2026-09-16-nav3-settings-and-search-nested-graphs`)_
- `SettingsScreen` no longer accepts `onNavigateToProfileSwitcher` — `AccountSettingsScreen` navigates directly. _(from `2026-09-16-nav3-settings-and-search-nested-graphs`)_

### `secure-storage`

- Adding a new secret (e.g. another provider's API key) **always** follows the same pattern: new `KEY_*` constant, new config object, migration on first DataStore access, no DataStore copy. _(from `2026-09-05-secret-storage-split`)_
- `AiApiKeyMigration` is wired through `koinBridge { ... }` inside the DataStore factory's `.also { ds -> ... }` block. See `koin-suspend-bridge` decision. _(from `2026-09-05-secret-storage-split`)_

### `security`

- Adding a new secret (e.g. another provider's API key) **always** follows the same pattern: new `KEY_*` constant, new config object, migration on first DataStore access, no DataStore copy. _(from `2026-09-05-secret-storage-split`)_
- `AiApiKeyMigration` is wired through `koinBridge { ... }` inside the DataStore factory's `.also { ds -> ... }` block. See `koin-suspend-bridge` decision. _(from `2026-09-05-secret-storage-split`)_

### `serialization`

- The Android no-arg overload `rememberNavBackStack(vararg elements)` (reflection path) is **not used** in this project anymore — every call goes through the configuration overload so Android and JVM share one contract. _(from `2026-09-16-nav3-savedstate-serializers-required`)_
- When adding a new `data object` or `data class` to `AppDestination` (or any sealed route hierarchy that backs a `rememberNavBackStack`), **always** add the matching `subclass(...)` line in every relevant `serializersModule` — the compiler does not enforce this. _(from `2026-09-16-nav3-savedstate-serializers-required`)_

### `settings`

- Adding a new secret (e.g. another provider's API key) **always** follows the same pattern: new `KEY_*` constant, new config object, migration on first DataStore access, no DataStore copy. _(from `2026-09-05-secret-storage-split`)_
- `AiApiKeyMigration` is wired through `koinBridge { ... }` inside the DataStore factory's `.also { ds -> ... }` block. See `koin-suspend-bridge` decision. _(from `2026-09-05-secret-storage-split`)_
- All changes are additive; no existing behavior is removed. _(from `2026-09-07-settings-ux-improvements`)_
- `App.kt` инжектит `SettingsRepository` через Koin — это нормально, Koin доступен в Common startup. _(from `2026-09-07-settings-fixes`)_
- Backup confirm dialogs prevent accidental data loss. _(from `2026-09-07-settings-ux-improvements`)_
- Debounce reduces SecureStorage/DataStore writes by ~90% during text input. _(from `2026-09-07-settings-ux-improvements`)_
- `NavEntries.kt` wires `SettingsNavGraph(navCallbacks = nav)` and `SearchNavGraph(navCallbacks = nav)` instead of the raw screens. _(from `2026-09-16-nav3-settings-and-search-nested-graphs`)_
- Preview for `AccountSettingsScreen` uses a separate `AccountSettingsScreenPreviewContent` composable that takes an explicit callback, since `LocalSettingsNavigator` is only available inside the graph. _(from `2026-09-16-nav3-settings-and-search-nested-graphs`)_
- `SettingsNavRail` Column теперь содержит Box с CircleShape — Layout инлайн, не refactor. _(from `2026-09-07-settings-fixes`)_
- `SettingsScreen` no longer accepts `onNavigateToProfileSwitcher` — `AccountSettingsScreen` navigates directly. _(from `2026-09-16-nav3-settings-and-search-nested-graphs`)_
- `SettingsViewModel.testConnection()` **always** short-circuits with `Error("API key not configured")` when no key, **without** calling `textGen`. Tests assert this with `FakeTextGen(trackGenerateCalls = true)` and `assertEquals(emptyList(), textGen.generateCalls)`. _(from `2026-09-05-llm-provider-settings`)_
- Test suite (`SettingsViewModelTest`) updated to work with debounce bypass in test mode. _(from `2026-09-07-settings-ux-improvements`)_
- `TextGenPort.listModels` — добавлен в интерфейс, реализация в `KoogAgentService` и `FakeTextGen`. _(from `2026-09-07-settings-fixes`)_
- The Test connection "probe" prompt is hard-coded: `"Reply with the single word: pong."` — change together with the system prompt if needed. _(from `2026-09-05-llm-provider-settings`)_
- Все 6 sub-screens имеют `verticalScroll` — контент больше не обрезается. _(from `2026-09-07-settings-fixes`)_

### `shell`

- **BottomBar taps** now have a single source of truth: `navigator.navigateTopLevel(dest)` — no `selectedIndex` to keep in sync. _(from `2026-09-05-android-bottom-nav`)_
- **Desktop chrome** is unchanged from the user's perspective — the drawer still works exactly as before. _(from `2026-09-05-android-bottom-nav`)_
- **Menu sheet visibility** is `rememberSaveable` state in `AndroidShell` — survives config changes, not part of the back stack. _(from `2026-09-05-android-bottom-nav`)_
- **`NavDestination` (drawer enum)** remains for the desktop drawer's grouping by `NavGroup` — not removed, just no longer wired to mobile. _(from `2026-09-05-android-bottom-nav`)_
- **Per-tab backstacks** work as expected: open TaskDetail on Today, switch to Plans, switch back to Today → TaskDetail is restored. _(from `2026-09-05-android-bottom-nav`)_
- **`TasksScreen`** unchanged — it already takes `onNavigateToTask` / `onNavigateToCreateTask` callbacks; the per-tab sub-navigation state now lives in `TasksRoute` inside `AppNavHost` via `rememberSaveable`. _(from `2026-09-05-android-bottom-nav`)_

### `"snapshot"`

- 3 preview functions per component (default, empty, edge case) — consistent with `2026-09-06-compose-previews` skill. _(from `2026-09-08-roboazzi-snapshot-tests`)_
- Baseline images stored in `shared/src/commonTest/resources/roborazzi/`. _(from `2026-09-08-roboazzi-snapshot-tests`)_
- Every future PR touching UI components must run snapshot tests and update baselines when changes are intentional. _(from `2026-09-08-roboazzi-snapshot-tests`)_

### `state-restoration`

- `core/draft/DataStoreDraftStore.kt` _(from `2026-09-15-task-editor-unification`)_
- `core/draft/DraftStore.kt` _(from `2026-09-15-task-editor-unification`)_
- `core/draft/FakeDraftStore.kt` _(from `2026-09-15-task-editor-unification`)_
- `core/serialization/StableJson.kt` _(from `2026-09-15-task-editor-unification`)_
- `single<DraftStore> { DataStoreDraftStore(get()) }` in `CoreDiModule` _(from `2026-09-15-task-editor-unification`)_
- `TaskCreateContent.kt` _(from `2026-09-15-task-editor-unification`)_
- `TaskCreateDeps` expanded with `draftStore: DraftStore, autosaveScheduler: AutosaveScheduler` _(from `2026-09-15-task-editor-unification`)_
- `TaskCreationTopBar.kt` _(from `2026-09-15-task-editor-unification`)_
- `TaskDetailViewContent.kt` _(from `2026-09-15-task-editor-unification`)_

### `stdio`

- `./gradlew :mcp-server:test` now includes a regression test (`McpServerEndToEndTest.server_blocks_until_stdin_closes`) that asserts `process.isAlive` after 3s of empty stdin. If anyone removes the blocking primitive, this test fails. _(from `2026-09-07-mcp-stdio-blocking-lifecycle`)_
- MCP client (ZCode CLI) now sees the `initialize` roundtrip succeed and can list/call tools. _(from `2026-09-07-mcp-stdio-blocking-lifecycle`)_
- Process exit semantics change from "instant" to "on stdin EOF or session error". A passing test asserts the process stays alive ≥3s with empty stdin. _(from `2026-09-07-mcp-stdio-blocking-lifecycle`)_
- `Runtime.getRuntime().addShutdownHook { server.close() }` becomes redundant for normal EOF exits — `onClose → done.complete() → done.join() returns → runBlocking exits → JVM exits cleanly`. We keep the shutdown hook only as a backstop for SIGTERM. _(from `2026-09-07-mcp-stdio-blocking-lifecycle`)_
- The downstream `ToolRegistrar` and tools still run inside `runBlocking { koogTool.execute(args) }` per call — coroutine scope inside the request handler, no change. _(from `2026-09-07-mcp-stdio-blocking-lifecycle`)_

### `"subtasks"`

- Checklist items can be promoted to sub-tasks via "Convert to task" overflow action. _(from `2026-09-08-task-1-level-subtasks`)_
- Sub-task count is denormalized via `TaskFilter.ByParent` query — no need for recursive count. _(from `2026-09-08-task-1-level-subtasks`)_

### `"task-detail"`

- Archive and Delete have distinct storage semantics — future "Trash" filter can distinguish intentional archive from accidental delete. _(from `2026-09-08-task-archive-restore-contract`)_
- Checklist items can be promoted to sub-tasks via "Convert to task" overflow action. _(from `2026-09-08-task-1-level-subtasks`)_
- `_recentlyDeleted` in TaskDetailViewModel holds the full task before delete/archive for undo. _(from `2026-09-08-task-archive-restore-contract`)_
- `_recentlyDeleted` must be cleared in `onCleared()` to avoid leaking task data on configuration change. _(from `2026-09-08-task-restore-undo`)_
- `restore()` is idempotent — calling restore on a non-archived task is a no-op (or returns Result.success if the row simply re-inserted). _(from `2026-09-08-task-archive-restore-contract`)_
- `restore()` re-uses the original `id` — idempotent by design. _(from `2026-09-08-task-restore-undo`)_
- Sub-task count is denormalized via `TaskFilter.ByParent` query — no need for recursive count. _(from `2026-09-08-task-1-level-subtasks`)_

### `tasks`

- `ActiveSheet.kt`: 35 → ~15 lines (`toActiveSheet()` removed). _(from `2026-09-09-task-detail-intent-refactor`)_
- `AppDestination.TaskDetail` and `TaskDetailCreate` remain `@Deprecated` — they can be deleted in a follow-up cleanup commit. _(from `2026-09-16-nav3-feature-graph-extensions`)_
- **Known limitation**: 10 constructor parameters remain; next candidate for `TaskDetailDeps` by analogy with `TaskEditorDeps`. _(from `2026-09-09-task-detail-intent-refactor`)_
- New file `TaskDetailIntent.kt` (~120 lines). _(from `2026-09-09-task-detail-intent-refactor`)_

### `"tasks"`

- Preview functions in `TaskDetailViewScreen` updated to pass `emptyFlow()` for `recentlyDeleted`. _(from `2026-09-15-task-detail-drafts-undo-fix`)_

### `tasks`

- `ProjectsNavGraph` in `NavEntries` now maps `ProjectsStartRoute.Editor` to `ProjectsRoute.Editor`. _(from `2026-09-16-nav3-feature-graph-extensions`)_
- `TaskDetailContent` is now `internal` (stateless, previewable without Koin). _(from `2026-09-09-task-detail-intent-refactor`)_
- `TaskDetailScreen.kt`: `when (action)` on 27 branches → `when (intent)` on 6 branches. Routing now uniform (all `activeSheet = …`). _(from `2026-09-09-task-detail-intent-refactor`)_
- `TaskDetailUiEvent.kt`: 34 → ~18 lines (10 sheet-triggers removed). _(from `2026-09-09-task-detail-intent-refactor`)_

### `"tasks"`

- `TaskDetailViewContent` now takes a `recentlyDeleted: Flow<Task?>` parameter — passed from `TaskDetailViewScreen`. _(from `2026-09-15-task-detail-drafts-undo-fix`)_

### `tasks`

- `TaskDetailViewModel.kt`: 450 → ~270 lines, 37 public methods → 3 (`start`, `onTitleChange`, `onIntent`). _(from `2026-09-09-task-detail-intent-refactor`)_
- `TaskDetailViewModelTest`: updated 5 tests to call `vm.onIntent(Domain.X)` instead of `vm.setX(task, value)`. _(from `2026-09-09-task-detail-intent-refactor`)_

### `"tasks"`

- `TasksDiModule` removed now-unused `ProjectsRepository` import. _(from `2026-09-15-task-detail-drafts-undo-fix`)_

### `tasks`

- `TasksFormatters.kt`: added `dueChipColors` formatter and `parseDueTime` utility. _(from `2026-09-09-task-detail-intent-refactor`)_

### `technical-debt`

- All Android NavGraph back stack declarations become `val backStack = rememberNavBackStackTyped(savedStateConfig, start)` — clean, typed, no suppression. _(from `2026-09-16-nav3-type-asymmetry-adr`)_
- Any future code that calls `backStack.last()` on Android must explicitly cast. _(from `2026-09-16-nav3-type-asymmetry-adr`)_
- Risk of `ClassCastException` if the type parameter is misused. _(from `2026-09-16-nav3-type-asymmetry-adr`)_
- `@Suppress("UNCHECKED_CAST")` removed from all 5 Android NavGraph files. _(from `2026-09-16-nav3-type-asymmetry-adr`)_
- The cast remains in all Android NavGraphs. _(from `2026-09-16-nav3-type-asymmetry-adr`)_
- The inline wrapper is `internal` to the Android source set — no API surface change. _(from `2026-09-16-nav3-type-asymmetry-adr`)_

### `"testing"`

- 3 preview functions per component (default, empty, edge case) — consistent with `2026-09-06-compose-previews` skill. _(from `2026-09-08-roboazzi-snapshot-tests`)_
- Baseline images stored in `shared/src/commonTest/resources/roborazzi/`. _(from `2026-09-08-roboazzi-snapshot-tests`)_

### `testing`

- **`Clock` must be passed to `CreateTaskUseCase` / `UpdateTaskUseCase`** — use the singleton `Clock` from `core.platform`. _(from `2026-09-05-robolectric-widget-tests`)_
- `compose-ui-test:1.12.0` added to `libs.versions.toml` as `composeUiTest` _(from `2026-09-06-desktop-smoke-test-with-koin`)_

### `"testing"`

- Every future PR touching UI components must run snapshot tests and update baselines when changes are intentional. _(from `2026-09-08-roboazzi-snapshot-tests`)_

### `testing`

- **Fake repo returns empty by default** — widget tests that check `LazyColumn` with `testTag` will fail when repo is empty (state = `Empty`). Test the `EmptyState` text instead, or seed data via `fakeNotesRepo.seed(note)`. _(from `2026-09-05-robolectric-widget-tests`)_
- `JvmAiDiGraphTest` keeps its `LLModel` override as a safety belt — if someone reintroduces `OpenAIModels.*`, this test fails at graph-build time. _(from `2026-09-05-koog-test-workarounds`)_
- **JVM args for JDK 21+** — add `--add-opens=java.base/jdk.internal.access=ALL-UNNAMED` to `gradle.properties` (`org.gradle.jvmargs`) AND to `shared/build.gradle.kts` via `afterEvaluate` + `tasks.withType<Test>()` for the test worker process. _(from `2026-09-05-robolectric-widget-tests`)_
- Navigation interaction tests (click-to-navigate) are out of scope for this smoke test — they require handling NavBackStackEntry lifecycle in `runDesktopComposeUiTest` _(from `2026-09-06-desktop-smoke-test-with-koin`)_
- **Robolectric 4.17-beta-4** — `4.16` maxes at SDK 36; `compileSdk=37` requires the beta. The beta is already cached. _(from `2026-09-05-robolectric-widget-tests`)_
- **`Session.Anonymous()` requires `UserId`** — always pass `UserId.anonymous` or `UserId.fromString("...")`. _(from `2026-09-05-robolectric-widget-tests`)_
- Smoke test now passes: `./gradlew :desktopApp:test` → BUILD SUCCESSFUL _(from `2026-09-06-desktop-smoke-test-with-koin`)_
- `sourceSets { test { java.srcDirs("src/jvmTest") ... } }` added to `desktopApp/build.gradle.kts` to wire the `jvmTest` source set to the `test` task _(from `2026-09-06-desktop-smoke-test-with-koin`)_
- **Use `UserId` from `feature.tasks`** — it's defined in `Ids.kt` there, imported explicitly. _(from `2026-09-05-robolectric-widget-tests`)_
- **`waitForIdle()` is a method, not a function** — do NOT import it. Call `composeRule.waitForIdle()` directly. _(from `2026-09-05-robolectric-widget-tests`)_
- When adding a new AI tool, **always** bind its use case with **explicit `get<ConcreteTool>()`** if the use case's parameter is `SimpleTool<T>`: _(from `2026-09-05-koog-test-workarounds`)_

### `tests`

- One new e2e test in `mcp-server` (`McpToolRoundTripTest`). _(from `2026-09-08-mcp-server-health-audit`)_
- One new unit test file in `mcp-server` (`KoogJsonSchemaBuilderTest`). _(from `2026-09-08-mcp-server-health-audit`)_
- Three new unit test files in `shared/commonTest` for the read tools. _(from `2026-09-08-mcp-server-health-audit`)_
- `ToolFactories.kt` gets the profile-aware default applied (small diff, _(from `2026-09-08-mcp-server-health-audit`)_
- `ToolRegistrar` catches `McpToolError` first (small diff). _(from `2026-09-08-mcp-server-health-audit`)_

### `toctou`

- **`createTask`** must go through `CreateTaskUseCase`, not direct `taskRepo.create`. _(from `2026-09-09-projectdetail-write-through-fix`)_
- Screen owns `activeSheet` routing state; VM only receives routing intents. _(from `2026-09-09-projectdetail-write-through-fix`)_

### `tools`

- Auto-migration v9 добавляет unique index на `(idempotency_key, user_id)` where not null _(from `2026-09-07-write-tools-in-koog-registry`)_
- `TaskEntity` получает `@ColumnInfo("idempotency_key") val idempotencyKey: String?` _(from `2026-09-07-write-tools-in-koog-registry`)_
- `TaskRepository` получает `findByIdempotencyKey(key, userId)` метод _(from `2026-09-07-write-tools-in-koog-registry`)_
- Все 17+ tools следуют этому контракту _(from `2026-09-07-write-tools-in-koog-registry`)_

### `"udf"`

- **+100% testability** — all business logic is in pure Kotlin, testable without Compose. _(from `2026-09-15-viewmodel-state-ownership`)_
- **−100% UDF violations** in this category — the rule is now written and enforced via skill. _(from `2026-09-15-viewmodel-state-ownership`)_
- **+~20% lines in ViewModels** — state that was implicit in Composables must be made explicit in VMs. _(from `2026-09-15-viewmodel-state-ownership`)_
- 4 new files: `AccountSettingsViewModel.kt`, `TagPickerViewModel.kt`, plus DI registrations. _(from `2026-09-15-projects-settings-profile-udf-fixes`)_
- 6 modified files: `ProjectDetailViewModel.kt`, `ProjectDetailScreen.kt`, `ProjectPickerSheet.kt`, `AccountSettingsScreen.kt`, `SettingsScreen.kt`, `Modules.kt`. _(from `2026-09-15-projects-settings-profile-udf-fixes`)_
- `collectAsState` replaced with `collectAsStateWithLifecycle` in previews. _(from `2026-09-15-projects-settings-profile-udf-fixes`)_
- **Migration cost** — 7 violations across 5 PRs. See the implementation plan for the sequence. _(from `2026-09-15-viewmodel-state-ownership`)_
- `NoteEditor` now requires `InternalLinkRepository` in its constructor — updated `NotesDiModule` accordingly. _(from `2026-09-15-noteeditor-udf-link-search`)_
- Preview functions in `TaskDetailViewScreen` updated to pass `emptyFlow()` for `recentlyDeleted`. _(from `2026-09-15-task-detail-drafts-undo-fix`)_
- Previews that don't use Koin continue to work since `searchNotesForLink`/`searchTasksForLink` are nullable. _(from `2026-09-15-noteeditor-udf-link-search`)_
- Previews updated: `AccountSettingsScreenLightPreview` / `DarkPreview` now construct `AccountSettingsViewModel(FakeProfileRepository())`; `SettingsScreen` preview updated similarly. _(from `2026-09-15-projects-settings-profile-udf-fixes`)_
- `TaskDetailViewContent` now takes a `recentlyDeleted: Flow<Task?>` parameter — passed from `TaskDetailViewScreen`. _(from `2026-09-15-task-detail-drafts-undo-fix`)_
- `TasksDiModule` removed now-unused `ProjectsRepository` import. _(from `2026-09-15-task-detail-drafts-undo-fix`)_

### `ui`

- **8 экранов мигрируют одновременно** — невозможно сделать постепенную миграцию из-за смены типа `_events` _(from `2026-09-05-ui-event-per-feature`)_
- `AppDestination` пополнился `Notes` (уже был), логика FAB его задействует. _(from `2026-09-07-fab-chrome-level`)_
- `App.kt` инжектит `SettingsRepository` через Koin — это нормально, Koin доступен в Common startup. _(from `2026-09-07-settings-fixes`)_
- `AppShell` — minor change: добавлен `FabAction` parameter. _(from `2026-09-07-fab-chrome-level`)_
- **BottomBar taps** now have a single source of truth: `navigator.navigateTopLevel(dest)` — no `selectedIndex` to keep in sync. _(from `2026-09-05-android-bottom-nav`)_
- **`Clock` must be passed to `CreateTaskUseCase` / `UpdateTaskUseCase`** — use the singleton `Clock` from `core.platform`. _(from `2026-09-05-robolectric-widget-tests`)_
- `Clock.System.now()` must not appear in preview code — use _(from `2026-09-06-compose-previews`)_
- **CollectEvents** в виджетах принимает `Flow<T : UiEvent>` — generic call site остаётся тем же _(from `2026-09-05-ui-event-per-feature`)_

### `ui-components`

- Caller must provide `MutableStateFlow<String>` and inject `InternalLinkRepository` and `ProfileAwareCurrentUser` — slightly more boilerplate at call site _(from `2026-09-09-internal-link-picker-generic`)_
- `core/ui/components/` is now free of feature-domain imports _(from `2026-09-09-internal-link-picker-generic`)_
- Icon per `LinkKind` makes the list scannable _(from `2026-09-09-internal-link-picker-generic`)_
- Search debouncing (300ms) is now the caller's responsibility (implemented inside the sheet via `LaunchedEffect`) _(from `2026-09-09-internal-link-picker-generic`)_
- Sheet is reusable by any feature that needs internal linking (e.g. TaskEditor) _(from `2026-09-09-internal-link-picker-generic`)_
- Single search + merged results = better UX (one tap instead of tab switching) _(from `2026-09-09-internal-link-picker-generic`)_

### `ui`

- Consistent API across all shared components _(from `2026-09-09-content-slot-pattern`)_

### `ui-contract`

- `ParentOption` is a `@JvmInline value class` candidate if it grows beyond 3 fields (currently 3 — plain data class is fine) _(from `2026-09-09-parent-picker-contract`)_
- Parent options are reactive (`StateFlow`) — picker updates automatically when projects change _(from `2026-09-09-parent-picker-contract`)_
- `ParentPickerSheet` signature: `options: List<ParentOption>`, NOT `currentParentId: ProjectId?` _(from `2026-09-09-parent-picker-contract`)_
- The "None (root)" option is rendered as a `TextButton` above the `LazyColumn`, not as part of `options` _(from `2026-09-09-parent-picker-contract`)_

### `ui`

- `core/draft/DataStoreDraftStore.kt` _(from `2026-09-15-task-editor-unification`)_
- `core/draft/DraftStore.kt` _(from `2026-09-15-task-editor-unification`)_
- `core/draft/FakeDraftStore.kt` _(from `2026-09-15-task-editor-unification`)_
- `core/serialization/StableJson.kt` _(from `2026-09-15-task-editor-unification`)_
- Desktop chrome is a 240 dp left rail, VSCode/JetBrains-style. Width is explicit, not derived from drawer measurements. _(from `2026-09-06-desktop-sidebar-replaces-permanent-drawer`)_
- **Desktop chrome** is unchanged from the user's perspective — the drawer still works exactly as before. _(from `2026-09-05-android-bottom-nav`)_
- Easier to extend cards and editors without breaking call sites _(from `2026-09-09-content-slot-pattern`)_
- Every `NavDestination` entry has an `icon` field. When adding a new entry, pick an icon from `androidx.compose.material.icons.Filled` or `Icons.AutoMirrored.Filled`. _(from `2026-09-06-desktop-sidebar-replaces-permanent-drawer`)_
- **Fake repo returns empty by default** — widget tests that check `LazyColumn` with `testTag` will fail when repo is empty (state = `Empty`). Test the `EmptyState` text instead, or seed data via `fakeNotesRepo.seed(note)`. _(from `2026-09-05-robolectric-widget-tests`)_
- **JVM args for JDK 21+** — add `--add-opens=java.base/jdk.internal.access=ALL-UNNAMED` to `gradle.properties` (`org.gradle.jvmargs`) AND to `shared/build.gradle.kts` via `afterEvaluate` + `tasks.withType<Test>()` for the test worker process. _(from `2026-09-05-robolectric-widget-tests`)_
- **Menu sheet visibility** is `rememberSaveable` state in `AndroidShell` — survives config changes, not part of the back stack. _(from `2026-09-05-android-bottom-nav`)_
- Migration from plain lambdas requires updating call sites _(from `2026-09-09-content-slot-pattern`)_
- `ModalShell` + `DrawerStyle.Modal` remain in `AppShell.kt`. They are not wired to any platform but are preserved for future modal drawer needs. _(from `2026-09-06-desktop-sidebar-replaces-permanent-drawer`)_
- **`NavDestination` (drawer enum)** remains for the desktop drawer's grouping by `NavGroup` — not removed, just no longer wired to mobile. _(from `2026-09-05-android-bottom-nav`)_
- **NotificationHost** — финальный widget для всех экранов, заменяет ~64 строк ручного glue кода _(from `2026-09-05-ui-event-per-feature`)_
- **Per-tab backstacks** work as expected: open TaskDetail on Today, switch to Plans, switch back to Today → TaskDetail is restored. _(from `2026-09-05-android-bottom-nav`)_
- `@Preview` annotation is `@androidx.compose.ui.tooling.preview.Preview` — _(from `2026-09-06-compose-previews`)_
- Preview functions are `private` and placed at the end of the source file, _(from `2026-09-06-compose-previews`)_
- `PreviewParameterProvider` is avoided — individual preview functions used instead _(from `2026-09-06-compose-previews`)_
- **Robolectric 4.17-beta-4** — `4.16` maxes at SDK 36; `compileSdk=37` requires the beta. The beta is already cached. _(from `2026-09-05-robolectric-widget-tests`)_
- **`Session.Anonymous()` requires `UserId`** — always pass `UserId.anonymous` or `UserId.fromString("...")`. _(from `2026-09-05-robolectric-widget-tests`)_
- `SettingsNavRail` Column теперь содержит Box с CircleShape — Layout инлайн, не refactor. _(from `2026-09-07-settings-fixes`)_
- `SettingsViewModel.testConnection()` **always** short-circuits with `Error("API key not configured")` when no key, **without** calling `textGen`. Tests assert this with `FakeTextGen(trackGenerateCalls = true)` and `assertEquals(emptyList(), textGen.generateCalls)`. _(from `2026-09-05-llm-provider-settings`)_
- `single<DraftStore> { DataStoreDraftStore(get()) }` in `CoreDiModule` _(from `2026-09-15-task-editor-unification`)_
- `singularity-todo-shared-ui-components` skill governs decomposition: desktop-only chrome stays in `feature/nav/`, shared widgets go to `core/ui/components/`. _(from `2026-09-06-desktop-sidebar-replaces-permanent-drawer`)_
- `TagsScreen` больше не принимает callback — экран не подключён к навигации (menu destination `Tags` отсутствует в `AppDestination`). _(from `2026-09-07-fab-chrome-level`)_
- `TaskCreateContent.kt` _(from `2026-09-15-task-editor-unification`)_
- `TaskCreateDeps` expanded with `draftStore: DraftStore, autosaveScheduler: AutosaveScheduler` _(from `2026-09-15-task-editor-unification`)_
- `TaskCreationTopBar.kt` _(from `2026-09-15-task-editor-unification`)_
- `TaskDetailViewContent.kt` _(from `2026-09-15-task-editor-unification`)_
- **`TasksScreen`** unchanged — it already takes `onNavigateToTask` / `onNavigateToCreateTask` callbacks; the per-tab sub-navigation state now lives in `TasksRoute` inside `AppNavHost` via `rememberSaveable`. _(from `2026-09-05-android-bottom-nav`)_

### `ui-test`

- `compose-ui-test:1.12.0` added to `libs.versions.toml` as `composeUiTest` _(from `2026-09-06-desktop-smoke-test-with-koin`)_
- Navigation interaction tests (click-to-navigate) are out of scope for this smoke test — they require handling NavBackStackEntry lifecycle in `runDesktopComposeUiTest` _(from `2026-09-06-desktop-smoke-test-with-koin`)_
- Smoke test now passes: `./gradlew :desktopApp:test` → BUILD SUCCESSFUL _(from `2026-09-06-desktop-smoke-test-with-koin`)_
- `sourceSets { test { java.srcDirs("src/jvmTest") ... } }` added to `desktopApp/build.gradle.kts` to wire the `jvmTest` source set to the `test` task _(from `2026-09-06-desktop-smoke-test-with-koin`)_

### `ui`

- `TextGenPort.listModels` — добавлен в интерфейс, реализация в `KoogAgentService` и `FakeTextGen`. _(from `2026-09-07-settings-fixes`)_
- The Test connection "probe" prompt is hard-coded: `"Reply with the single word: pong."` — change together with the system prompt if needed. _(from `2026-09-05-llm-provider-settings`)_
- Type-safe actions via `sealed class Action` with exhaustive `when` _(from `2026-09-09-content-slot-pattern`)_
- **UiEvent marker** — `ShowDialog/ShowError/NavigateBack` больше не определены глобально _(from `2026-09-05-ui-event-per-feature`)_
- `useSurface = false` when the preview root already contains a `Scaffold` _(from `2026-09-06-compose-previews`)_
- **Use `UserId` from `feature.tasks`** — it's defined in `Ids.kt` there, imported explicitly. _(from `2026-09-05-robolectric-widget-tests`)_
- `value class XxxActions` indirection — harder to read at first glance _(from `2026-09-09-content-slot-pattern`)_
- **`waitForIdle()` is a method, not a function** — do NOT import it. Call `composeRule.waitForIdle()` directly. _(from `2026-09-05-robolectric-widget-tests`)_
- Все 6 sub-screens имеют `verticalScroll` — контент больше не обрезается. _(from `2026-09-07-settings-fixes`)_
- **Существующие тесты** использующие `TasksViewModel`, `NotesViewModel` и т.д. — `_events.emit(UiEvent.ShowDialog(...))` нужно обновить на `TasksUiEvent.AiResult(...)` _(from `2026-09-05-ui-event-per-feature`)_

### `"undo"`

- Preview functions in `TaskDetailViewScreen` updated to pass `emptyFlow()` for `recentlyDeleted`. _(from `2026-09-15-task-detail-drafts-undo-fix`)_
- `_recentlyDeleted` must be cleared in `onCleared()` to avoid leaking task data on configuration change. _(from `2026-09-08-task-restore-undo`)_
- `restore()` re-uses the original `id` — idempotent by design. _(from `2026-09-08-task-restore-undo`)_
- `TaskDetailViewContent` now takes a `recentlyDeleted: Flow<Task?>` parameter — passed from `TaskDetailViewScreen`. _(from `2026-09-15-task-detail-drafts-undo-fix`)_
- `TasksDiModule` removed now-unused `ProjectsRepository` import. _(from `2026-09-15-task-detail-drafts-undo-fix`)_

### `_untagged_`

- **~14 изменённых файлов**: Screen.kt + testTag, VM constructors, DI module _(from `2026-09-05-ui-tests-ultron`)_
- **~25 новых файлов**: 4 порта, 7 Page Objects, test infrastructure, integration tests _(from `2026-09-05-ui-tests-ultron`)_
- 8 экранов мигрированы: Tasks, Notes, TaskDetail, TaskEditor, Projects, ProjectEditor, Chat, Archive _(from `2026-09-05-ui-decomposition`)_
- AGENTS.md remains unchanged — its inline `adb`/`sqlite3` commands are still valid escape hatches. _(from `2026-09-06-modular-justfile`)_
- `AiSettingsContributor` remains as the sole `SettingsContributor` implementation — used only for AI test/fetch ephemeral state. _(from `2026-09-10-simplified-settings-vm`)_
- All notes screens now navigationally self-contained _(from `2026-09-15-nav3-notes-navigator`)_
- `AppDestination.Habits` → `AppDestination.Pomodoro`, `AppDestination.Calendar` → `AppDestination.Statistics` _(from `2026-09-11-nav3-kmp-migration`)_
- `AppDestination.TaskEditor` serialisation is backward compatible (extra field _(from `2026-09-05-task-editor-refactor`)_
- `appearanceModule()` was removed (no `AppearanceContributor` needed — `SettingsViewModel` handles appearance intents directly). _(from `2026-09-10-simplified-settings-vm`)_
- `AppNavHost.kt`, `AppNavigator.kt`, `DesktopShell.kt` (old Nav2 files) are deleted _(from `2026-09-11-nav3-kmp-migration`)_
- Archive доступен с любого TaskDetailScreen через ⋮ menu _(from `2026-09-07-task-detail-archive-overflow`)_
- Autosave вынесен из `delay()` в VM в отдельный port — теперь тестируем без `advanceTimeBy` _(from `2026-09-05-ui-decomposition`)_
- Backlinks queryable via SQL without HTML parsing _(from `2026-09-07-notes-internal-links-backlinks`)_
- Both Android and Desktop now use the same Nav3 architecture (multi-back-stack, `Navigator`, `NavDisplay`) _(from `2026-09-11-nav3-kmp-migration`)_
- Bulk-операции fail-fast при отсутствующих ID _(from `2026-09-05-refactoring-summary`)_
- CI may later call `just tests::check` instead of `./check.sh` — the behavior is identical. _(from `2026-09-06-modular-justfile`)_
- **CI требует adb-устройство** для instrumentation — `SKIP_ADB=1` для пропуска _(from `2026-09-05-ui-tests-ultron`)_
- `Clock.now()` should migrate to `kotlinx.datetime.Clock.System.now()` in a future PR. _(from `2026-09-08-instant-migration`)_
- `ContentStateMapper` — добавлен object с двумя методами _(from `2026-09-05-refactoring-summary`)_
- Dead Nav2 code removed from Android _(from `2026-09-11-nav3-kmp-migration`)_
- `DeleteProjectUseCase` конструктор теперь `(projectRepo: ProjectsRepository, taskRepo: TaskRepository)` — DI модуль обновлён соответственно. _(from `2026-09-15-projects-clean-architecture`)_
- Deprecation warnings in `StatisticsScreen.kt` and `Clock.jvm.kt` remain until migration is completed. _(from `2026-09-08-instant-migration`)_
- Developers should prefer `kotlinx.datetime.Instant` in new code. _(from `2026-09-08-instant-migration`)_
- DI-граф упрощён: 5 factory → 1 _(from `2026-09-05-refactoring-summary`)_
- FAB работает на desktop для всех табов (Tasks, Projects, Notes) _(from `2026-09-07-task-detail-archive-overflow`)_
- Internal links survive HTML round-trip (stored as `note://` / `task://` href) _(from `2026-09-07-notes-internal-links-backlinks`)_
- `io.github.nickid:roborazzi:1.25.0` added to `libs.versions.toml`. _(from `2026-09-08-roborazzi-snapshot-tests`)_
- `just` must be installed (`just 1.57.0` is present in this environment). _(from `2026-09-06-modular-justfile`)_
- **`koinInject()` в Screen** требует Koin контекст — widget тесты обходят это через Robolectric + `createComposeRule` без Koin _(from `2026-09-05-ui-tests-ultron`)_
- Link tap detection requires cursor placement (no visual link highlight tap) — acceptable tradeoff given library limitation _(from `2026-09-07-notes-internal-links-backlinks`)_
- `NoteEditorScreen` still accepts `onNavigateToNote` and `onNavigateToTask` for _(from `2026-09-15-nav3-notes-navigator`)_
- `NotesNavGraph(navCallbacks)` is the single integration point with the outer graph _(from `2026-09-15-nav3-notes-navigator`)_
- `NotificationHost` заменил ~64 строки ручного glue кода на 8 экранах _(from `2026-09-05-ui-decomposition`)_
- Per-feature events устранили конфликты имён (до: `ShowDialog` everywhere; после: `TasksUiEvent.AiResult`, `NotesUiEvent.SaveFailed`) _(from `2026-09-05-ui-decomposition`)_
- **`performTextClear`** не доступен в Robolectric — используется `performTextInput` напрямую _(from `2026-09-05-ui-tests-ultron`)_
- Picker sheets визуально согласованы с остальными sheets (drag-handle, chrome) _(from `2026-09-07-task-detail-archive-overflow`)_
- Pre-existing test failures (`RussianDateFormatterTest`, `TaskCreateViewModelTest`, _(from `2026-09-15-nav3-notes-navigator`)_
- `ProjectsDiModule.kt` подключён через `domainModule` в `Modules.kt`. _(from `2026-09-15-projects-clean-architecture`)_
- Recipe names with `::` sub-namespacing (e.g. `android::db::schema`) do not work in `just 1.57.0` — flat names are used instead (e.g. `android::db-schema`). _(from `2026-09-06-modular-justfile`)_
- Robolectric widget tests в `androidHostTest` также **удалены** — все 5 классов _(from `2026-09-05-uiautomator-compose-discovery`)_
- `roborazzi` dependency added to `androidHostTest` in `shared/build.gradle.kts`. _(from `2026-09-08-roborazzi-snapshot-tests`)_
- Schema v7 requires `fallbackToDestructiveMigration` during development (dev strategy per skill) _(from `2026-09-07-notes-internal-links-backlinks`)_
- `scopeOverride` добавлен в `ProjectsViewModel` _(from `2026-09-05-ui-decomposition`)_
- Settings UI is NOT reactive to external changes (other VMs writing to `SettingsRepository`). Acceptable because the settings screen is typically visited once, changed, and closed. _(from `2026-09-10-simplified-settings-vm`)_
- `TaskDetailScreen` stays as a read-only viewer until a future PR consolidates _(from `2026-09-05-task-editor-refactor`)_
- `TaskEditorReducerTest` must add test cases for new intents. _(from `2026-09-05-task-editor-refactor`)_
- `TaskEditorViewModel` constructor signature unchanged; DI registration unchanged. _(from `2026-09-05-task-editor-refactor`)_
- `TaskEditorViewModelTest` and `TaskEditorIntegrationTest` must add edit-mode scenarios. _(from `2026-09-05-task-editor-refactor`)_
- `TaskMutationsUseCase` — новый класс, но он по сущиности — grouping, не новая логика _(from `2026-09-05-refactoring-summary`)_
- Tests are ignored until the plugin resolution issue in the development environment is resolved. _(from `2026-09-08-roborazzi-snapshot-tests`)_
- Two new top-level entries added: `justfile` and `.just/`. _(from `2026-09-06-modular-justfile`)_
- UI Automator тесты **удалены** (`UIAutomatorTest.kt`). _(from `2026-09-05-uiautomator-compose-discovery`)_
- Все fake-репозитории теперь имеют консистентное поведение seed()/add()/clear() _(from `2026-09-05-refactoring-summary`)_
- Все ViewModel'ы с `scopeOverride` — консистентны в тестах _(from `2026-09-05-refactoring-summary`)_
- Все импорты в 30+ файлах обновлены на новые FQN (`.domain.model`, `.domain.port`, `.domain.usecase`, `.data`, `.presentation.state`, `.presentation.viewmodel`). _(from `2026-09-15-projects-clean-architecture`)_
- Для UI-тестов на реальном устройстве: Kaspresso или `contentDescription` + `By.desc()`. _(from `2026-09-05-uiautomator-compose-discovery`)_
- Оставшиеся `androidHostTest`: только `AppNavigatorTest` (nav contract, без Espresso), _(from `2026-09-05-uiautomator-compose-discovery`)_

### `ux`

- All changes are additive; no existing behavior is removed. _(from `2026-09-07-settings-ux-improvements`)_
- Backlinks are now shown and functional _(from `2026-09-09-notes-view-edit-split`)_
- Backup confirm dialogs prevent accidental data loss. _(from `2026-09-07-settings-ux-improvements`)_
- Clear UX: notes list → tap note → read → optionally edit _(from `2026-09-09-notes-view-edit-split`)_
- Debounce reduces SecureStorage/DataStore writes by ~90% during text input. _(from `2026-09-07-settings-ux-improvements`)_
- Delete confirmation is handled in `NotePreview`, not buried in editor overflow menu _(from `2026-09-09-notes-view-edit-split`)_
- Navigation now has one more route: `NoteView` ↔ `NoteEditor` ↔ `NotesScreen` _(from `2026-09-09-notes-view-edit-split`)_
- No loading screen — the text field is always visible in the list _(from `2026-09-09-notes-quick-add`)_
- Note metadata (word count, last updated) is visible without entering edit mode _(from `2026-09-09-notes-view-edit-split`)_
- `NotePreview` must observe the note via `repo.watchNote()` — requires a Flow subscription _(from `2026-09-09-notes-view-edit-split`)_
- `NotesListViewModel` now requires `IdGenerator` as a third constructor parameter _(from `2026-09-09-notes-quick-add`)_
- One tap fewer than before for the common "capture a thought" workflow _(from `2026-09-09-notes-quick-add`)_

### `"ux"`

- `_recentlyDeleted` must be cleared in `onCleared()` to avoid leaking task data on configuration change. _(from `2026-09-08-task-restore-undo`)_
- `restore()` re-uses the original `id` — idempotent by design. _(from `2026-09-08-task-restore-undo`)_

### `ux`

- Slight visual complexity added to the list screen _(from `2026-09-09-notes-quick-add`)_
- Test suite (`SettingsViewModelTest`) updated to work with debounce bypass in test mode. _(from `2026-09-07-settings-ux-improvements`)_
- Title pre-saved to DB before navigating to editor (no lost titles on crash) _(from `2026-09-09-notes-quick-add`)_
- User must explicitly tap "Edit" to modify — one additional tap for casual reading _(from `2026-09-09-notes-view-edit-split`)_

### `viewmodel`

- ~18 файлов переработано, +5 новых, -2 удалено. _(from `2026-09-14-tasks-feature-nested-nav3`)_
- `ActiveSheet.kt`: 35 → ~15 lines (`toActiveSheet()` removed). _(from `2026-09-09-task-detail-intent-refactor`)_
- Cross-screen state (e.g. "did the user just save a note") must flow through navigation callbacks, not shared VM state _(from `2026-09-09-notes-vm-split`)_
- Diff больше, чем чисто миграция tasks — затрагивает общий `Nav3State`. _(from `2026-09-14-nav3-vm-store-decorator-fix`)_
- Each VM is small enough to understand fully (~60-150 lines) _(from `2026-09-09-notes-vm-split`)_
- Editor session state is released when user navigates away _(from `2026-09-09-notes-vm-split`)_
- Instrumented/integration тесты (`CreateTaskFlowInstrumentedTest`) _(from `2026-09-14-nav3-vm-store-decorator-fix`)_
- **Known limitation**: 10 constructor parameters remain; next candidate for `TaskDetailDeps` by analogy with `TaskEditorDeps`. _(from `2026-09-09-task-detail-intent-refactor`)_
- Lifecycle VM становится привязан к lifetime entry — VM очищается _(from `2026-09-14-nav3-vm-store-decorator-fix`)_
- **`LocalNavBackStack` как публичный API** — позволяет экранам _(from `2026-09-14-tasks-feature-nested-nav3`)_
- Navigation между Detail и подзадачами/проектами становится _(from `2026-09-14-tasks-feature-nested-nav3`)_
- New file `TaskDetailIntent.kt` (~120 lines). _(from `2026-09-09-task-detail-intent-refactor`)_
- `NotesRoute` now injects `NotesListViewModel` via `koinViewModel()`, `NoteEditor` and `NotePreview` are injected via their respective screen composables _(from `2026-09-09-notes-vm-split`)_
- Previews for each screen can use `koinViewModel { parametersOf(...) }` without circular dependency _(from `2026-09-09-notes-vm-split`)_
- `ProjectDetailViewModel(projectId)` — Project X → back → Project Y _(from `2026-09-14-nav3-vm-store-decorator-fix`)_
- **`String`-encoded `initialDueDate`** — заменён на _(from `2026-09-14-tasks-feature-nested-nav3`)_
- `TaskCreateViewModel(initialDueDate)` — два последовательных _(from `2026-09-14-nav3-vm-store-decorator-fix`)_
- `TaskDetailContent` is now `internal` (stateless, previewable without Koin). _(from `2026-09-09-task-detail-intent-refactor`)_
- `TaskDetailScreen.kt`: `when (action)` on 27 branches → `when (intent)` on 6 branches. Routing now uniform (all `activeSheet = …`). _(from `2026-09-09-task-detail-intent-refactor`)_
- `TaskDetailUiEvent.kt`: 34 → ~18 lines (10 sheet-triggers removed). _(from `2026-09-09-task-detail-intent-refactor`)_
- `TaskDetailViewModel.kt`: 450 → ~270 lines, 37 public methods → 3 (`start`, `onTitleChange`, `onIntent`). _(from `2026-09-09-task-detail-intent-refactor`)_
- `TaskDetailViewModel(taskId)` — Task A → back → Task B больше _(from `2026-09-14-nav3-vm-store-decorator-fix`)_
- `TaskDetailViewModelTest`: updated 5 tests to call `vm.onIntent(Domain.X)` instead of `vm.setX(task, value)`. _(from `2026-09-09-task-detail-intent-refactor`)_
- `TasksFormatters.kt`: added `dueChipColors` formatter and `parseDueTime` utility. _(from `2026-09-09-task-detail-intent-refactor`)_
- **`TasksRoute.Pop` как sentinel** — race condition (см. review rev. 1, _(from `2026-09-14-tasks-feature-nested-nav3`)_
- Three Koin registrations instead of one _(from `2026-09-09-notes-vm-split`)_
- Unit-тесты навигации tasks требуют `Robolectric` или `composeRule` — _(from `2026-09-14-tasks-feature-nested-nav3`)_
- VMs are independently testable with focused test suites _(from `2026-09-09-notes-vm-split`)_
- В `JvmNav3State.kt` для `AppDestination.TasksGraph` / _(from `2026-09-14-tasks-feature-nested-nav3`)_
- В `TasksNavGraph.kt` (для nested `rememberNavBackStack`). _(from `2026-09-14-tasks-feature-nested-nav3`)_
- Все остальные параметризованные VM (~20 callsites). _(from `2026-09-14-nav3-vm-store-decorator-fix`)_
- Дополнительный уровень индирекции для новых разработчиков: «где я?». _(from `2026-09-14-tasks-feature-nested-nav3`)_
- Если какой-то VM был неявно расчитан на per-Activity scope _(from `2026-09-14-nav3-vm-store-decorator-fix`)_
- Необходимо зарегистрировать `TasksRoute` в двух `SerializersModule`: _(from `2026-09-14-tasks-feature-nested-nav3`)_
- **Один плоский AppDestination без nested graph** — не даёт feature _(from `2026-09-14-tasks-feature-nested-nav3`)_
- Первая фича с nested graph — другие фичи (notes/projects/auth/settings) _(from `2026-09-14-tasks-feature-nested-nav3`)_
- Рассмотреть переход на `LocalResultEventBus` + `ResultEffect<T>` для _(from `2026-09-14-nav3-vm-store-decorator-fix`)_
- Сигнатуры экранов tasks упрощаются до 1-2 аргументов. _(from `2026-09-14-tasks-feature-nested-nav3`)_
- Существующие unit-тесты для VM не затрагиваются (тестируют VM _(from `2026-09-14-nav3-vm-store-decorator-fix`)_
- Чинится латентный bug для всех `koinViewModel { parametersOf(...) }` _(from `2026-09-14-nav3-vm-store-decorator-fix`)_
- **Чинится латентный VM scoping bug** для `TaskDetailViewModel`, _(from `2026-09-14-tasks-feature-nested-nav3`)_

### `vm`

- **`createTask` and `moveTaskToProject`** remain in VM (require repository writes) _(from `2026-09-09-project-detail-intent-refactor`)_
- **`createTask`** must go through `CreateTaskUseCase`, not direct `taskRepo.create`. _(from `2026-09-09-projectdetail-write-through-fix`)_
- **`koinInject()` для репозиториев/сервисов остаётся** — не VM _(from `2026-09-06-koin-vm-viewmodelof-koinviewmodel`)_
- **`koinViewModel()` для VM в Composable** — `koinInject()` для VM антипаттерн _(from `2026-09-06-koin-vm-viewmodelof-koinviewmodel`)_
- **`NavigateToTasks`** is no longer a VM event — screen handles it as routing _(from `2026-09-09-project-detail-intent-refactor`)_
- **No pure reducer needed** — `ProjectDetailViewModel` is write-through like `TaskDetailViewModel` _(from `2026-09-09-project-detail-intent-refactor`)_
- **@Preview и widget-тесты не затрагиваются** — все preview используют `*Content` helpers (stateless) _(from `2026-09-06-koin-vm-viewmodelof-koinviewmodel`)_
- **`ProjectDetailIntent`** is the canonical list of all project mutations — adding a new field mutation = one `Domain` case _(from `2026-09-09-project-detail-intent-refactor`)_
- **`ProjectDetailUiEvent`** now has only 2 cases: `NavigateBack` (post-delete) and `ShowError` _(from `2026-09-09-project-detail-intent-refactor`)_
- Screen owns `activeSheet` routing state; VM only receives routing intents. _(from `2026-09-09-projectdetail-write-through-fix`)_
- **`singularity-todo-vm-koin-scoping` skill** — создан как single source of truth _(from `2026-09-06-koin-vm-viewmodelof-koinviewmodel`)_

### `"vm-state"`

- **+100% testability** — all business logic is in pure Kotlin, testable without Compose. _(from `2026-09-15-viewmodel-state-ownership`)_
- **−100% UDF violations** in this category — the rule is now written and enforced via skill. _(from `2026-09-15-viewmodel-state-ownership`)_
- **+~20% lines in ViewModels** — state that was implicit in Composables must be made explicit in VMs. _(from `2026-09-15-viewmodel-state-ownership`)_
- **Migration cost** — 7 violations across 5 PRs. See the implementation plan for the sequence. _(from `2026-09-15-viewmodel-state-ownership`)_

### `vm`

- **`TaskEditorViewModel` special case** — `viewModel { (initialDueDate) -> ... }` + `koinViewModel { parametersOf(initialDueDate) }` _(from `2026-09-06-koin-vm-viewmodelof-koinviewmodel`)_
- **`toggleArchive`** no longer emits `Saved` — `lastEditedAt` drives "Saved X ago" UI via the `mutate{}` helper _(from `2026-09-09-project-detail-intent-refactor`)_
- **`viewModelOf(::VM)` для VM без nullable dep** — предпочтительный паттерн _(from `2026-09-06-koin-vm-viewmodelof-koinviewmodel`)_
- **`viewModel { Vm(get(), get(), ...) }`** — для VM с nullable dep + getOrNull() (TasksViewModel, ProjectsViewModel) _(from `2026-09-06-koin-vm-viewmodelof-koinviewmodel`)_

### `wikilinks`

- `getBacklinkNotes` now returns real results — backlinks in `NotePreview` and `InternalLinkPickerSheet` will work _(from `2026-09-09-notes-outgoing-links-extraction`)_
- No new dependencies _(from `2026-09-09-notes-outgoing-links-extraction`)_
- No schema migration needed _(from `2026-09-09-notes-outgoing-links-extraction`)_
- Regex over HTML is less elegant than walking the paragraph tree, but the paragraph tree is internal _(from `2026-09-09-notes-outgoing-links-extraction`)_
- The `outgoing_links` column is populated on every save, keeping backlinks current _(from `2026-09-09-notes-outgoing-links-extraction`)_

### `write-through`

- **`createTask`** must go through `CreateTaskUseCase`, not direct `taskRepo.create`. _(from `2026-09-09-projectdetail-write-through-fix`)_
- Screen owns `activeSheet` routing state; VM only receives routing intents. _(from `2026-09-09-projectdetail-write-through-fix`)_


## Index (slug → tags)

- `2026-09-05-android-bottom-nav` — navigation  compose  shell  ui
- `2026-09-05-android-bottom-nav-followups` — navigation  followups  refactoring  bugs
- `2026-09-05-koin-suspend-bridge` — koin  di  coroutines
- `2026-09-05-koog-both-platforms` — koog  kmp  ai
- `2026-09-05-koog-test-workarounds` — koog  koin  testing
- `2026-09-05-llm-provider-settings` — ai  settings  ui
- `2026-09-05-robolectric-widget-tests` — testing  robolectric  koin  ui
- `2026-09-05-secret-storage-split` — security  secure-storage  settings
- `2026-09-05-ui-event-per-feature` — ui  architecture  events  koin
- `2026-09-06-compose-multiplatform-1.12.0-bump` — compose  gradle  build
- `2026-09-06-compose-previews` — compose  preview  ui
- `2026-09-06-desktop-sidebar-replaces-permanent-drawer` — desktop  compose  ui  navigation
- `2026-09-06-desktop-smoke-test-with-koin` — desktop  testing  compose  koin  ui-test
- `2026-09-06-di-module-split` — di  koin  architecture
- `2026-09-06-kermit-logging-setup` — logging  koin  kermit  debugging
- `2026-09-06-koin-bridge-audit` — koin  di  coroutines
- `2026-09-06-koin-vm-viewmodelof-koinviewmodel` — koin  di  vm
- `2026-09-07-backup-directory-via-koin-string` — koin  di  backup  platform-module
- `2026-09-07-dogfooding-followups` — dogfooding  followups  technical-debt
- `2026-09-07-dogfooding-mcp-server` — mcp  dogfooding  koog  agent
- `2026-09-07-fab-chrome-level` — ui  navigation  architecture
- `2026-09-07-mcp-stdio-blocking-lifecycle` — mcp  kotlin-sdk  stdio  coroutines  lifecycle
- `2026-09-07-mcp-tool-error-model` — mcp  error-handling  json-rpc
- `2026-09-07-multi-profile-and-usage-tracking` — multi-profile  llm-usage  observability  dogfooding
- `2026-09-07-note-editor-body-load` — notes  room  rich-editor  di-graph
- `2026-09-07-settings-fixes` — settings  ui  di-graph
- `2026-09-07-settings-ux-improvements` — settings  ux  compose  koin
- `2026-09-07-write-tools-in-koog-registry` — mcp  tools  koog  idempotency
- `2026-09-08-mcp-dogfooding-round-2` — "mcp"  "dogfooding"  "round-2"  "followups"
- `2026-09-08-mcp-plan-tracking-via-mcp` — "mcp"  "dogfooding"  "plan-tracking"
- `2026-09-08-mcp-schema-and-profile-userid-fixes` — mcp  koog  schema  profiles  bugfix
- `2026-09-08-mcp-server-health-audit` — mcp  audit  refactor  tests  dead-code
- `2026-09-08-roboazzi-snapshot-tests` — "testing"  "snapshot"  "roborazzi"  "quality"
- `2026-09-08-task-1-level-subtasks` — "task-detail"  "subtasks"  "architecture"
- `2026-09-08-task-archive-restore-contract` — "task-detail"  "archive"  "repository"
- `2026-09-08-task-detail-critical-fixes` — "task-detail"  "critical-fix"  "ux"
- `2026-09-08-task-restore-undo` — "task-detail"  "undo"  "ux"
- `2026-09-09-content-slot-pattern` — architecture  compose  ui
- `2026-09-09-di-factory-viewmodel-fix` — koin  di  bugfix
- `2026-09-09-feature-tasks-clean-architecture` — architecture  clean-architecture  feature-tasks  kotlin-multiplatform
- `2026-09-09-internal-link-picker-generic` — notes  ui-components  linking  architecture
- `2026-09-09-notes-outgoing-links-extraction` — notes  wikilinks  rich-editor  room
- `2026-09-09-notes-quick-add` — notes  ux  quick-add
- `2026-09-09-notes-view-edit-split` — notes  navigation  rich-editor  ux
- `2026-09-09-notes-vm-split` — notes  architecture  viewmodel  di
- `2026-09-09-parent-picker-contract` — ui-contract  projects  picker  architecture
- `2026-09-09-preview-with-koin-helper` — preview  compose  koin  architecture
- `2026-09-09-project-detail-intent-refactor` — vm  intent  refactor  koin
- `2026-09-09-project-detail-rework-15-fixes` — projects  screen-architecture  preview  koin  reactive
- `2026-09-09-projectdetail-write-through-fix` — project-detail  toctou  write-through  vm  regression
- `2026-09-09-task-detail-intent-refactor` — architecture  viewmodel  compose  tasks
- `2026-09-14-nav3-tasks-navigator` — nav3  navigation  koin  refactor
- `2026-09-14-nav3-vm-store-decorator-fix` — architecture  navigation  koin  viewmodel  bug
- `2026-09-14-tasks-feature-nested-nav3` — architecture  navigation  koin  viewmodel
- `2026-09-15-detekt-ktlint-kover-setup` — detekt  ktlint  kover  lint  coverage  quality
- `2026-09-15-noteeditor-udf-link-search` — "architecture"  "udf"  "notes"  "di"
- `2026-09-15-projects-nested-nav3` — nav3  navigation  koin  refactor  projects
- `2026-09-15-projects-settings-profile-udf-fixes` — "architecture"  "udf"  "compose"  "di"
- `2026-09-15-task-detail-drafts-undo-fix` — "architecture"  "compose"  "udf"  "tasks"  "drafts"  "undo"
- `2026-09-15-task-editor-unification` — architecture  compose  ui  drafts  state-restoration
- `2026-09-15-viewmodel-state-ownership` — "architecture"  "compose"  "udf"  "vm-state"
- `2026-09-16-android-shell-fab-fix` — navigation  nav3  android  fab
- `2026-09-16-nav3-desktop-in-memory-no-savedstate` — navigation  nav3  jvm  desktop  android
- `2026-09-16-nav3-feature-graph-extensions` — navigation  nav3  tasks  notes
- `2026-09-16-nav3-post-migration-fixes` — navigation  nav3
- `2026-09-16-nav3-savedstate-serializers-required` — navigation  nav3  serialization  jvm  android
- `2026-09-16-nav3-settings-and-search-nested-graphs` — navigation  nav3  settings  search
- `2026-09-16-nav3-type-asymmetry-adr` — navigation  nav3  android  jvm  technical-debt

## Active entries

- `2026-09-05-android-bottom-nav-followups` — Android Bottom Navigation — known issues and refactoring backlog
- `2026-09-05-android-bottom-nav` — Android bottom navigation bar via AppShell + NavHost (no separate ViewModels)
- `2026-09-05-koin-suspend-bridge` — Bridge suspend code into Koin factories via koinBridge { ... }
- `2026-09-05-koog-both-platforms` — Wire Koog AI agent for both JVM desktop and Android
- `2026-09-05-koog-test-workarounds` — Avoid OpenAIModels.Chat.* — use KnownModels; explicit get<>() for SimpleTool<T>
- `2026-09-05-llm-provider-settings` — LLM provider settings: pure-Kotlin config object + sealed test result
- `2026-09-05-refactoring-summary` — _(no title)
- `2026-09-05-robolectric-widget-tests` — Widget tests via Robolectric androidHostTest — no Koin, direct ViewModel construction
- `2026-09-05-secret-storage-split` — API key lives in SecureStorage only — never in DataStore, never in UI state
- `2026-09-05-task-editor-refactor` — _(no title)
- `2026-09-05-uiautomator-compose-discovery` — _(no title)
- `2026-09-05-ui-decomposition` — _(no title)
- `2026-09-05-ui-event-per-feature` — Per-feature UiEvent — маршрутизация событий без глобальной утечки типов
- `2026-09-05-ui-tests-ultron` — UI testing strategy with Ultron + minimal DI seams
- `2026-09-06-compose-multiplatform-1.12.0-bump` — Bump Compose Multiplatform plugin and libs to 1.12.0
- `2026-09-06-compose-previews` — Add @Preview to all screens and widgets via shared PreviewSamples
- `2026-09-06-desktop-sidebar-replaces-permanent-drawer` — Desktop: replace PermanentNavigationDrawer with explicit Row+Sidebar rail
- `2026-09-06-desktop-smoke-test-with-koin` — Desktop smoke test: Koin initialization pattern for Compose Multiplatform UI tests
- `2026-09-06-di-module-split` — DI module split: one monolith → 7 feature modules
- `2026-09-06-kermit-logging-setup` — Kermit logging: Koin-injected Logger, per-class tags, ANSI colors on JVM
- `2026-09-06-koin-bridge-audit` — Koin bridge audit: all usages correct, no raw runBlocking in module blocks
- `2026-09-06-koin-vm-viewmodelof-koinviewmodel` — ViewModel DI: viewModelOf + koinViewModel() instead of factory + koinInject()
- `2026-09-06-modular-justfile` — Modular justfile with .just/ submodules
- `2026-09-07-backup-directory-via-koin-string` — Delete throwing backupDirectoryPath; resolve backup directory via Koin get<String>()
- `2026-09-07-dogfooding-followups` — Dogfooding follow-ups — observed during implementation
- `2026-09-07-dogfooding-mcp-server` — Dogfooding MCP Server — ZCode Agent управляет задачами через stdio
- `2026-09-07-fab-chrome-level` — FAB at chrome level — single source of truth in shells
- `2026-09-07-mcp-stdio-blocking-lifecycle` — MCP stdio server blocking lifecycle — session.onClose + Job.join
- `2026-09-07-mcp-tool-error-model` — MCP Tool Error Model — two-tier, JSON-RPC compatible
- `2026-09-07-multi-profile-and-usage-tracking` — Multi-Profile and LLM Usage Tracking
- `2026-09-07-note-editor-body-load` — NoteEditor body load — store HTML directly, fix RichTextState init
- `2026-09-07-notes-internal-links-backlinks` — _(no title)
- `2026-09-07-settings-fixes` — Settings layout fixes, reactive dark theme, LLM providers
- `2026-09-07-settings-ux-improvements` — Settings UX improvements: swatches, time picker, connection badge, debounce, confirm dialogs
- `2026-09-07-task-detail-archive-overflow` — _(no title)
- `2026-09-07-task-detail-document-style` — _(no title)
- `2026-09-07-write-tools-in-koog-registry` — Write Tools — idempotent контракт, dryRun, error model
- `2026-09-08-instant-migration` — Instant Type Migration: kotlin.time.Instant → kotlinx.datetime.Instant
- `2026-09-08-mcp-dogfooding-round-2` — MCP dogfooding — round 2 plan index
- `2026-09-08-mcp-plan-tracking-via-mcp` — MCP plan tracking end-to-end
- `2026-09-08-mcp-schema-and-profile-userid-fixes` — MCP schema dialect bug + profile-aware userId defaults
- `2026-09-08-mcp-server-health-audit` — MCP server health audit — dead code, missing tests, contract hazards
- `2026-09-08-projects-ux-rework` — _(no title)
- `2026-09-08-roboazzi-snapshot-tests` — Snapshot tests via Roborazzi for all detail screen sections
- `2026-09-08-roborazzi-snapshot-tests` — Roborazzi Snapshot Tests for Task Detail Sections
- `2026-09-08-roborazzi-snapshot-tests-superseded` — Superseded: Roborazzi Snapshot Tests for Task Detail Sections
- `2026-09-08-task-1-level-subtasks` — Sub-task 1-level hierarchy (like projects)
- `2026-09-08-task-archive-restore-contract` — Task archive vs delete: separate contracts via archiveAt
- `2026-09-08-task-detail-critical-fixes` — TaskDetail critical fixes: TOCTOU race, Saved-spam, dead condition
- `2026-09-08-task-restore-undo` — TaskRepository.restore + UndoDelete via SnackbarHost
- `2026-09-09-content-slot-pattern` — Content slot API design rules
- `2026-09-09-di-factory-viewmodel-fix` — DI bugfix: factory → viewModel for 4 VM registrations with runtime parameters
- `2026-09-09-feature-tasks-clean-architecture` — Feature/tasks Clean Architecture: domain/data/presentation layers
- `2026-09-09-internal-link-picker-generic` — Notes — generic InternalLinkPickerSheet with merged Notes+Tasks results
- `2026-09-09-notes-outgoing-links-extraction` — Notes — wikilink extraction via HTML parsing + setOutgoingLinks wired to persist()
- `2026-09-09-notes-quick-add` — Notes — quick-add inline input on the notes list screen
- `2026-09-09-notes-view-edit-split` — Notes — split NoteDetail into NoteView (read-only) and NoteEditor (edit)
- `2026-09-09-notes-vm-split` — Notes — split god-class NotesViewModel into 3 focused ViewModels
- `2026-09-09-parent-picker-contract` — ParentPickerSheet receives ParentOption DTO, not Project entity
- `2026-09-09-preview-with-koin-helper` — Preview with VM-as-parameter, not Koin-in-preview
- `2026-09-09-project-detail-intent-refactor` — ProjectDetailViewModel/Screen: sealed Intent + onIntent dispatcher pattern
- `2026-09-09-project-detail-rework-15-fixes` — ProjectDetailScreen — 15-fixes rework (2026-09-09)
- `2026-09-09-projectdetail-write-through-fix` — ProjectDetailViewModel: write-through + _latestProject TOCTOU guard
- `2026-09-09-task-detail-intent-refactor` — TaskDetailViewModel: sealed Intent + single onIntent dispatcher
- `2026-09-10-simplified-settings-vm` — _(no title)
- `2026-09-11-nav3-kmp-migration` — _(no title)
- `2026-09-14-nav3-tasks-navigator` — Nav3: TasksNavigator replaces callback-passing in task screens
- `2026-09-14-nav3-vm-store-decorator-fix` — _(no title)
- `2026-09-14-tasks-feature-nested-nav3` — _(no title)
- `2026-09-15-detekt-ktlint-kover-setup` — Integrate detekt, ktlint, and kotlinx-kover for code quality and coverage
- `2026-09-15-nav3-notes-navigator` — _(no title)
- `2026-09-15-noteeditor-udf-link-search` — NoteEditor UDF fix — delegate link search to ViewModel
- `2026-09-15-projects-clean-architecture` — _(no title)
- `2026-09-15-projects-nested-nav3` — Projects feature: nested Nav3 graph with ProjectsNavigator
- `2026-09-15-projects-settings-profile-udf-fixes` — PR 5 UDF fixes — ProjectDetail, ProjectPicker, AccountSettings, TagPicker
- `2026-09-15-task-detail-drafts-undo-fix` — TaskDetail drafts seed-from-task; TaskListScreen koinViewModel; undo snackbar wired
- `2026-09-15-task-editor-unification` — Task Editor State Restoration + UI Unification
- `2026-09-15-viewmodel-state-ownership` — ViewModel owns all domain state; Composable owns only routing and animation
- `2026-09-16-android-shell-fab-fix` — AndroidShellNav3 FAB — wire to real navigation
- `2026-09-16-nav3-desktop-in-memory-no-savedstate` — Nav3 Desktop uses in-memory NavBackStack; SavedStateConfiguration is Android-only
- `2026-09-16-nav3-feature-graph-extensions` — NotesNavGraph start parameter, TasksStartRoute.Detail, AppDestination additions
- `2026-09-16-nav3-post-migration-fixes` — Nav3 post-migration fixes — NotesNavGraph start, preview wrappers, FAB cleanup
- `2026-09-16-nav3-savedstate-serializers-required` — Nav3 SavedStateConfiguration must register all NavKey subtypes polymorphically
- `2026-09-16-nav3-settings-and-search-nested-graphs` — SettingsNavGraph and SearchNavGraph — single-route nested graphs
- `2026-09-16-nav3-type-asymmetry-adr` — Nav3 type asymmetry: rememberInMemoryNavBackStack returns NavBackStack<T>, Android rememberNavBackStack returns NavBackStack<NavKey>
