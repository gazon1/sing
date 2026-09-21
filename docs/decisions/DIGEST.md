# Decision Log Digest

Auto-generated from `docs/decisions/`. Run `./scripts/refresh-decisions-digest.sh` to rebuild.

## Critical

- **Always** keep the auto-fill rule in `OpenAiConfig.resolveBaseUrl(storedUrl, provider)` only. The UI delegates to it — changing both is a bug. _(from `2026-09-05-llm-provider-settings`)_
- `AiTestResult` is part of `SettingsUiState.Content.aiTestResult` with default `Idle`. **Never** make it a `UiEvent`. _(from `2026-09-05-llm-provider-settings`)_
- `FakeTextGen` is parametrised: `(success, failureMessage, trackGenerateCalls)`. **Always** use `trackGenerateCalls = true` in VM tests that assert the no-key short-circuit. _(from `2026-09-05-llm-provider-settings`)_
- **Always** keep `Selector` a pure predicate; badge/transform logic belongs to `SelectorTransformer` attached to `AgendaDefinition`, not embedded in evaluator. _(from `2026-09-17-orgmode-architectural-lessons`)_
- **Always** make new sealed hierarchies for DSL predicates (filter, selector, transformer, predicate) simultaneously `@Serializable` AND pure predicate — no parallel DTOs. _(from `2026-09-17-orgmode-functional-patterns`)_
- **Always** return empty collection (not `Result.Left(Empty)`) for no-match cases in pure-domain pipelines like `AgendaEvaluator`. `Result.Left` is reserved for validation/business-rule failures only. _(from `2026-09-17-orgmode-functional-patterns`)_
- **Always** route derived predicates (`isOverdue`, `isReady`, `isBlocked`) through `feature/tasks/domain/logic/Computed.kt`. Never duplicate inline in `AgendaEvaluator`, `Selector`, or `TaskDomain.matchesFilter`. _(from `2026-09-17-orgmode-architectural-lessons`)_
- **Always** route derived values (`effectivePriority`, `effectiveColor`, `effectiveTags`) through `core/tree/Cascade.kt` `cascadeUp` — never as stored fields on entities. _(from `2026-09-17-orgmode-functional-patterns`)_
- **Always** use `core/tree/Cascade.kt` `cascadeUp` for inheritance queries; never walk ancestors ad-hoc with `find { it.parentId == ... }` chains. _(from `2026-09-17-orgmode-architectural-lessons`)_
- **Always** use `core/tree/TreeVisitor.kt` `traverseDepthFirst` for recursive tree operations; never write recursive `.filter { … }.map { … }` chains. _(from `2026-09-17-orgmode-architectural-lessons`)_
- **Always** use `data class.copy()` for Task/Project/Tag/AgendaDefinition mutations in pure-domain code — never add setters. Mutations go through `TaskRepository.update(...)`. _(from `2026-09-17-orgmode-functional-patterns`)_
- **Never** introduce `Map<String, Any>` plist-style containers in Kotlin domain code — use `data class` instead. _(from `2026-09-17-orgmode-functional-patterns`)_
- **Never** migrate to plain-text file storage for tasks. Room remains the source of truth; markdown export (if added later) is a read-only projection. _(from `2026-09-17-orgmode-architectural-lessons`)_
- **Never** relax `assertNoNesting` without a separate ADR. N-level outline requires Room `AutoMigration` (skill `singularity-todo-room-migration`). _(from `2026-09-17-orgmode-architectural-lessons`)_
- **Never** store derived predicates in Room (`isOverdue`, `isReady`). They are computed on read via extension properties. _(from `2026-09-17-orgmode-architectural-lessons`)_
- **Never** store memoization caches inside pure-domain functions — memoization is an outer wrapper (e.g. `rememberSaveable`, KDataStore, VM-side `StateFlow`). _(from `2026-09-17-orgmode-functional-patterns`)_
- **Never** pre-build UI components without a known caller. _(from `2026-09-22-dead-sheets-removal-mr23`)_
- **Never** register a VM in DI without at least one concrete consumer. _(from `2026-09-22-dead-sheets-removal-mr23`)_
- **Never** add `dismissOnConfirm` or `onItemsConfirmed` parameters — multi-select batch-confirm is the caller's responsibility. _(from `2026-09-18-picker-dsl-slots-mr11`)_
- **Always** use `koinBridge { ... }` in any Koin factory that calls a `suspend` function. _(from `2026-09-05-koin-suspend-bridge`)_
- **Never** use `GlobalScope.launch { ... }` inside factories — non-deterministic. _(from `2026-09-05-koin-suspend-bridge`)_
- **Never** use raw `runBlocking { ... }` inside `module { ... }` blocks. _(from `2026-09-05-koin-suspend-bridge`)_
- **Always** bind both `single<PromptExecutorPort>` and `single<PromptExecutor>`; `PromptExecutorPort` is for the streaming executor inside `KoogAgentService`, `PromptExecutor` is for the AI tool factories. _(from `2026-09-05-koog-both-platforms`)_
- **Always** check `grep -rn "OpenAIModels" shared/src/commonMain shared/src/jvmMain shared/src/androidMain --include="*.kt"` returns only comments in `KnownModels.kt`. Anything else is a regression. _(from `2026-09-05-koog-test-workarounds`)_
- **Always** declare `ai.koog:http-client-okhttp` in **both** `androidMain.dependencies` and `jvmMain.dependencies`. _(from `2026-09-05-koog-both-platforms`)_
- **Never** add capabilities to `KnownModels` unless a feature needs them — the simple form avoids the static init entirely. _(from `2026-09-05-koog-test-workarounds`)_
- **Always** mark every `NavKey` subtype that may appear in a stack as `@Serializable`. Without it, there is no `.serializer()` to pass to `subclass(...)`. _(from `2026-09-16-nav3-savedstate-serializers-required`)_
- **Always** provide a `serializersModule` that calls `polymorphic(NavKey::class) { subclass(...) }` for every concrete route type in the stack. _(from `2026-09-16-nav3-savedstate-serializers-required`)_
- **Never** write `SavedStateConfiguration { }` for any `rememberNavBackStack` call — the empty body silently falls back to `DEFAULT.serializersModule` and breaks the polymorphism contract. _(from `2026-09-16-nav3-savedstate-serializers-required`)_
- **Always** read entity state from the write-through `_latest<Entity>` cache, never from `state.value` snapshot in mutation methods. _(from `2026-09-09-projectdetail-write-through-fix`)_
- **Always** update `_latest<Entity>` before any async operation that reads it. _(from `2026-09-09-projectdetail-write-through-fix`)_
- **Never** emit `Saved` events for debounced inline edits — update `_lastEditedAt` only. _(from `2026-09-09-projectdetail-write-through-fix`)_
- **Never** add an `aiApiKey` (or any secret) field back to `SettingsRepository`. Adding one is a regression. _(from `2026-09-05-secret-storage-split`)_
- **Never** add an `aiApiKey` field to `SettingsUiState.Content`. _(from `2026-09-05-secret-storage-split`)_
- The password field on `AiProviderSettingsScreen` is a local `mutableStateOf`. **Never** lift it to the VM. _(from `2026-09-05-secret-storage-split`)_
- `SettingsViewModel.processIntent(UpdateAiKey)` **always** writes only to `secureStorage`. **Never** call `settings.setAiKey(...)`. _(from `2026-09-05-secret-storage-split`)_
- **Always** build `JsonObject` with `_type` manually in `serialize()` for sealed interface serializers — generated serializers for concrete subtypes omit the discriminator. _(from `2026-09-17-selector-serializer-plain-kserializer`)_
- **Never** use `serializer<Selector>().descriptor` inside a custom `SelectorSerializer` — it returns the custom serializer itself, causing infinite recursion. _(from `2026-09-17-selector-serializer-plain-kserializer`)_
- **Always** `combine` the debounced draft flow with the entity's source `StateFlow` — **never** use `.first()` re-fetch inside a debounced collector. _(from `2026-09-08-task-detail-critical-fixes`)_
- **Always** silent saves for inline edits — `Saved` event is reserved for explicit user actions only. _(from `2026-09-08-task-detail-critical-fixes`)_
- **Always** use `SnackbarHost` + `SnackbarHostState` for undo, not `AlertDialog`. _(from `2026-09-08-task-restore-undo`)_
- **Always** validate `parentTaskId` in `CreateTaskUseCase` and `UpdateTaskUseCase` via `assertNoNesting`. _(from `2026-09-08-task-1-level-subtasks`)_
- **Never** allow a task with `parentTaskId != null` to become a parent — enforce in domain, not just UI. _(from `2026-09-08-task-1-level-subtasks`)_
- **Never** leave `|| true` or other tautological conditions in UI conditionals. _(from `2026-09-08-task-detail-critical-fixes`)_
- **Never** store more than one recently-deleted task in memory — the most recent overwrite. _(from `2026-09-08-task-restore-undo`)_
- **Always** keep `FakeClock` and `FakeIdGenerator` in `commonMain/test/fakes/` _(from `2026-09-18-testing-best-practices`)_
- **Always** keep `TestVmContext` and `runAndWait` in `jvmTest/test/helpers/` _(from `2026-09-18-testing-best-practices`)_
- **Always** use `kotlinx.coroutines.test.runTest` for VM tests _(from `2026-09-18-testing-best-practices`)_
- **Always** use `testTask()`, `testNote()`, `testProject()` for fixtures _(from `2026-09-18-testing-best-practices`)_
- **Never** use `Clock.System.now()` — inject `Clock` and use `FakeClock` in tests _(from `2026-09-18-testing-best-practices`)_
- **Never** use `UUID.randomUUID()` or `nextId()` directly — inject `IdGenerator` and use `SequenceIdGenerator` in tests _(from `2026-09-18-testing-best-practices`)_
- **Never** use `assertTrue(true)` placeholders — delete or write real assertions _(from `2026-09-18-testing-best-practices`)_
- **Never** use `delay(N)` in tests — use `scope.advanceUntilIdle()` or `runAndWait { }` _(from `2026-09-18-testing-best-practices`)_
- **Never** use `org.junit.*` — use `kotlin.test.*` _(from `2026-09-18-testing-best-practices`)_
- **Never** use `runBlocking` in production code — use `MutableStateFlow` + `scope.launch { }` _(from `2026-09-18-testing-best-practices`)_
- **Never** use `stateIn` in VMs — use `MutableStateFlow` for testability _(from `2026-09-18-testing-best-practices`)_
- **Never** use `viewModelScope` in VM code — inject `CoroutineScope` instead _(from `2026-09-18-testing-best-practices`)_
- **Never** write inline test doubles — add to `test/fakes/` _(from `2026-09-18-testing-best-practices`)_
- **Never** migrate a sheet to `ListPickerSheet` if it uses `FilterChip`, `ListItem` with rich content, or custom item layouts. _(from `2026-09-18-picker-sheet-migration-mr13`)_
- **Never** use `mutableStateOf<X?>` for sheet/dialog state — always use `rememberDialogState()`. _(from `2026-09-18-dialog-state-migration-mr12`)_

## Per-tag

### `_untagged_`

- **CI требует adb-устройство** для instrumentation — `SKIP_ADB=1` для пропуска
- **No new auth-safety risk**: each tool still stamps the user-provided
- **Tool APIs lose their `currentUser: ProfileAwareCurrentUser` parameter** — any
- **Unit tests gain an `init { ProfileAwareCurrentUser.setInstance(fake) }` setup
- **`koinInject()` в Screen** требует Koin контекст — widget тесты обходят это через Robolectric + `createComposeRule` без Koin
- **`performTextClear`** не доступен в Robolectric — используется `performTextInput` напрямую
- **~14 изменённых файлов**: Screen.kt + testTag, VM constructors, DI module
- **~25 новых файлов**: 4 порта, 7 Page Objects, test infrastructure, integration tests
- 23 Tier-1 VMs lose their `onCleared()` override — the scope is now auto-cancelled via `addCloseable(scope)`.
- 8 экранов мигрированы: Tasks, Notes, TaskDetail, TaskEditor, Projects, ProjectEditor, Chat, Archive
- AGENTS.md remains unchanged — its inline `adb`/`sqlite3` commands are still valid escape hatches.
- AI tools (11 Koog `SimpleTool` implementations) drop `currentUser` from
- Agenda always shows correct bucket labels across midnight.
- All 13 migrated VMs are now testable with `backgroundScope` injection
- All 593 existing tests continue to pass.
- All notes screens now navigationally self-contained
- Archive доступен с любого TaskDetailScreen через ⋮ menu
- Autosave вынесен из `delay()` в VM в отдельный port — теперь тестируем без `advanceTimeBy`
- Backlinks queryable via SQL without HTML parsing
- Both Android and Desktop now use the same Nav3 architecture (multi-back-stack, `Navigator`, `NavDisplay`)
- Bulk-операции fail-fast при отсутствующих ID
- CI may later call `just tests::check` instead of `./check.sh` — the behavior is identical.
- Cannot filter by `name` in SQL without parsing JSON — acceptable; user-facing
- DI-граф упрощён: 5 factory → 1
- Dead Nav2 code removed from Android
- Dead dependency removed from `CalendarDeps` — DI graph is now consistent
- Deadline indicator rendering in `UpcomingBadges`.
- Deprecation warnings in `StatisticsScreen.kt` and `Clock.jvm.kt` remain until migration is completed.
- Developers should prefer `kotlinx.datetime.Instant` in new code.
- Domain/repo/data layers are fully isolated.
- Every `_events.emit(x)` in VM code becomes `_events.trySend(x).isSuccess` (fire-and-forget) or `_events.send(x)` (back-pressure when needed).
- Existing `AgendaDeps` binding must add `clock: Clock` parameter (no breaking change
- Existing `viewModelOf` calls in DI modules updated to `viewModel { Vm(...) }` form
- Expand-day-list (tap day in month view to show all tasks).
- Exposed `events: Flow<UiEvent>` becomes `_events.receiveAsFlow()`.
- FAB работает на desktop для всех табов (Tasks, Projects, Notes)
- Full filter panel with Project / Tags / Priority / Status.
- Future developers understand which fields are stubbed vs. populated
- Horizontal swipe between dates.
- Internal links survive HTML round-trip (stored as `note://` / `task://` href)
- Link tap detection requires cursor placement (no visual link highlight tap) — acceptable tradeoff given library limitation
- Locale-aware `firstDayOfWeek` (hardcoded to Monday for MVP).
- Locale-aware first day of week.
- Nested nav3 graph keeps task-click navigation encapsulated.
- No new repository or DAO methods — `ByDateRange` filter reuses existing `watchTasks`.
- None
- Per-feature events устранили конфликты имён (до: `ShowDialog` everywhere; после: `TasksUiEvent.AiResult`, `NotesUiEvent.SaveFailed`)
- Performance: one extra `StateFlow.distinctUntilChanged().flatMapLatest()` per
- Phase 8 (test rewrites) and Phase 9 (verification) follow from this migration
- Picker sheets визуально согласованы с остальными sheets (drag-handle, chrome)
- Pre-existing test failures (`RussianDateFormatterTest`, `TaskCreateViewModelTest`,
- Pure `UpcomingTaskUiMapper` and `UpcomingFirstDayOfWeek` are unit-testable
- Pure date arithmetic fully unit-tested with no Compose or Koin dependencies.
- Recipe names with `::` sub-namespacing (e.g. `android::db::schema`) do not work in `just 1.57.0` — flat names are used instead (e.g. `android::db-schema`).
- Robolectric widget tests в `androidHostTest` также **удалены** — все 5 классов
- Schema v7 requires `fallbackToDestructiveMigration` during development (dev strategy per skill)
- Settings UI is NOT reactive to external changes (other VMs writing to `SettingsRepository`). Acceptable because the settings screen is typically visited once, changed, and closed.
- Simple schema, no migration complexity beyond bumping SCHEMA_VERSION.
- Single narrow Room query (`watchByDate`) reused for the new use case.
- Slot-API (`CalendarContent` separate from `CalendarScreen`) enables preview without Koin.
- StableJson round-trip test verifies no data loss.
- Test classes updated: `createVm()` now takes `scope = backgroundScope` via `TestScope.createVm()`
- Test factories for those VMs use `testScope(backgroundScope)` (or `testScope(this)` in `runTest`).
- The 2 side-effects-in-combine anti-patterns remain in `TaskDetailViewModel`
- The 4 untested VMs (`TaskCreateViewModel`, `ProjectEditorViewModel`,
- The `scopeOverride` getter anti-pattern remains in 10 VMs (the canonical
- The default `viewModelScope` is still created by the ViewModel but is unused in Tier-1 VMs (negligible memory cost: one empty `SupervisorJob`).
- Theme switching now correctly recomposes the calendar palette
- Throttling prevents SQLite spam from polling.
- Tier-2 VMs are unaffected.
- Two new top-level entries added: `justfile` and `.just/`.
- UI Automator тесты **удалены** (`UIAutomatorTest.kt`).
- UI switching (MR3) requires adding `definition: AgendaDefinition` to `AgendaViewModel`
- User switch cancels in-flight evaluations cleanly.
- VM tests using `turbine` on `_events` need migration to `flow.test {}` from `kotlinx-coroutines-test`.
- ViewModels become thin read-through: `tasks = taskRepo.observeByFilter(filter)
- Week navigation via swipe on `DaySwitcherRow`.
- Week-start locale handling is isolated and can be made configurable later.
- `AgendaViewModel` binding is unchanged — does not consume saved views.
- `AiSettingsContributor` remains as the sole `SettingsContributor` implementation — used only for AI test/fetch ephemeral state.
- `AppDestination.Habits` → `AppDestination.Pomodoro`, `AppDestination.Calendar` → `AppDestination.Statistics`
- `AppDestination.TaskEditor` serialisation is backward compatible (extra field
- `AppNavHost.kt`, `AppNavigator.kt`, `DesktopShell.kt` (old Nav2 files) are deleted
- `ByDateBucket` requires `today` in SQL query dispatch — the filter is not purely
- `CalendarDeps` matches the `AgendaDeps` pattern (project convention)
- `Clock.now()` should migrate to `kotlinx.datetime.Clock.System.now()` in a future PR.
- `Clock` injectable for deterministic tests via `runTest { advanceTimeBy(...) }`.
- `ContentStateMapper` — добавлен object с двумя методами
- `DeleteProjectUseCase` конструктор теперь `(projectRepo: ProjectsRepository, taskRepo: TaskRepository)` — DI модуль обновлён соответственно.
- `Dispatchers.Main.immediate` in secondary constructors causes `IllegalStateException` on JVM — tests must use the primary constructor with `backgroundScope`
- `LocalCalendarPalette` isolates calendar theming without breaking `MaterialTheme`.
- `NoteEditorScreen` still accepts `onNavigateToNote` and `onNavigateToTask` for
- `NotesNavGraph(navCallbacks)` is the single integration point with the outer graph
- `NotificationHost` заменил ~64 строки ручного glue кода на 8 экранах
- `ProfileAwareCurrentUser` moves **inside** repositories; the DI graph registers
- `ProjectsDiModule.kt` подключён через `domainModule` в `Modules.kt`.
- `TaskDetailScreen` stays as a read-only viewer until a future PR consolidates
- `TaskEditorDeps.clock` is also dead (the file's own KDoc flags it for deletion alongside `TaskEditorViewModel`)
- `TaskEditorReducerTest` must add test cases for new intents.
- `TaskEditorViewModelTest` and `TaskEditorIntegrationTest` must add edit-mode scenarios.
- `TaskEditorViewModel` constructor signature unchanged; DI registration unchanged.
- `TaskFilter` remains untouched — Search feature is unaffected.
- `TaskMutationsUseCase` — новый класс, но он по сущиности — grouping, не новая логика
- `Upcoming` tab position (3rd) shifts the bottom bar order — snapshot tests
- `appearanceModule()` was removed (no `AppearanceContributor` needed — `SettingsViewModel` handles appearance intents directly).
- `applyRoute` in `TasksViewModel` is dead code — zero callers confirmed; deleted.
- `deadlineDate` badge is rendered as a red flag + date for tasks due on the selected date.
- `deadlineDate` badge rendering in month grid.
- `delay(until-midnight)` means the flow never completes — collectors must be scoped
- `endTime` / `accentColor` — blocked on Room migration for `startAt`/`endAt`/`accentColor` fields in `Task`
- `expect object Clock` rename to `PlatformClock` — deferred until a broader cleanup window
- `flatMapLatest` re-evaluates all tasks on every date change (necessary trade-off;
- `getOrThrow()` removed from 5 VM sites; replaced with `fireAndForget` + channel emit.
- `isRecurring` is always `false` in `CalendarTaskUi` — requires per-task
- `just` must be installed (`just 1.57.0` is present in this environment).
- `scopeOverride` добавлен в `ProjectsViewModel`
- `startAt`/`endAt`/`allDay` fields don't exist in the `Task` domain model
- `startAt`/`endAt`/`allDay`/`recurrence` in `Task` (Room migration).
- `weight` modifier requires careful structuring inside `Row { Column(weight) }`.
- ~12 MRs total, ~6–9 weeks.
- Все ViewModel'ы с `scopeOverride` — консистентны в тестах
- Все fake-репозитории теперь имеют консистентное поведение seed()/add()/clear()
- Все импорты в 30+ файлах обновлены на новые FQN (`.domain.model`, `.domain.port`, `.domain.usecase`, `.data`, `.presentation.state`, `.presentation.viewmodel`).
- Для UI-тестов на реальном устройстве: Kaspresso или `contentDescription` + `By.desc()`.
- Оставшиеся `androidHostTest`: только `AppNavigatorTest` (nav contract, без Espresso),

### `agenda`

- **Fake reactive** (`MutableStateFlow<Map<K,V>>`) required for VM tests — `flowOf(snapshot)` not testable for transitions.
- **MR2**: `ByTags(set)`, `ByPriorities(set)`, `ByDateBucket` с SQL, `ByRegexp`, реактивный `todayFlow`, пользовательские saved views.
- **MR4 scope**: Create flow (FAB on list), deep-link guard for `SavedAgendaEdit`, section reorder.
- **Нет saved views в v1**: пользовательские пресеты не сохраняются. Встроенные — захардкожены в `AgendaPresets`.
- **Удаляются**: `UpcomingScreen`, `UpcomingViewModel`, `UpcomingUiState`, `TaskListScreen` (для Inbox/Today/ByProject), `TasksViewModel`, `TasksRoute.Inbox/Today/Upcoming/ByProject`, `AppDestination.Inbox/Today/Upcoming/TasksByProject`.
- **Экраны не под заменой**: `ProjectDetailScreen`, `NotesListScreen`, `SearchScreen`, `ArchiveScreen` — не agenda-вью.
- Adding a new Selector variant: add `@SerialName` annotation + one `put()` in the registry (2 changes).
- All 633 JVM tests pass after migration.
- All 7 presets now use the canonical public DSL path.
- All new pure functions are `internal` or `private` where possible.
- Composite selectors (`AllOf`, `AnyOf`, `Not`) encode their children via `registrySnapshot.getValue(child.typeTag).encode(child)` — works for any nesting depth.
- D5 (Settings tab + default view picker UI) and D7 (full notification→navigator deeplink wiring) are deferred — `SettingsRepository` storage is in place; UI wiring requires further settings-screen integration work.
- Detekt: 263 findings (pre-existing), 0 в изменённых файлах
- Process death during Create: seed lost, returns to list. Acceptable — Create is not critical path.
- Selector composition uses `selector { allOf(...); not(...) }` style instead of `Selector.AllOf(listOf(...))`.
- The `init` assertion catches missing entries at class load time with a clear message.
- `AgendaEngine MR1` полностью завершён
- `BackTopAppBar` now has `containerColor = surface` by default — all 6 existing callers benefit automatically.
- `DiscardChangesDialog` can be repurposed for any "are you sure?" confirmation (not just agenda) by passing custom text.
- `ProfilePickerSheet` depends on `ProfileRepository.all()` — screens requiring profile context must inject `ProfileRepository`.
- `ReorderableConfig` interface allows future swap to `sh.calvin.reorderable` without changing call sites.
- `SavedAgendaEditViewModel` → `SavedAgendaViewModel` rename propagates to all callers.
- `SavedAgendaListScreen` keeps its FAB by using `Scaffold` directly (not `BackTopAppBar` which lacks FAB support).
- `SavedAgendaSeedStore` is a global singleton — concurrent Create operations would race. Acceptable for current single-user model.
- `SavedAgendaViewModel` and `SavedAgendaListViewModel` are the only callers of `SavedAgendaView` construction.
- `SavedAgendaView` companion object has no factory functions; VMs use inline `copy()`.
- `SelectorBuilderTest` and `AgendaScopeSectionTest` added in `commonTest`.
- `SelectorSerializer` is now in its own file, improving build isolation.
- `TaskComputed.isOverdue` is the ONLY place `isOverdue` logic lives — `grep "dueDate < today"` returns 0 hits.
- `agendaEntryProvider()` on both platforms must register `SavedAgendaList` and `SavedAgendaEdit` entries.
- `cascadeUp` throws `IllegalStateException` on cycle — no silent infinite loops.
- `navSavedStateConfig` on Android must include `SavedAgendaList.serializer()` and `SavedAgendaEdit.serializer()`.
- `onSave()` success in Edit mode clears `isDirty` immediately — no stale "unsaved changes" state after save.
- `sealed interface ActiveDialog` enables exhaustive `when` on JVM.
- `section("X") { }` without a selector now throws `IllegalStateException("Section 'X' has no selector — pass as parameter or assign inside block")` instead of `UninitializedPropertyAccessException`.
- `sh.calvin.reorderable` dependency deferred; `ReorderableSectionList` is a `LazyColumn` stub.
- `when (selector)` appears only in `SelectorMatcher.matches` and `SelectorDescriptor.typeDescription` — compile-time enforcement of exhaustiveness for all 13 variants.
- Компиляция Android + JVM успешна, все тесты проходят

### `ai`

- All 32 tools are available to any AI agent via MCP stdio
- LLM tools go through the Koog prompt pipeline (`PromptExecutor`)
- The Test connection "probe" prompt is hard-coded: `"Reply with the single word: pong."` — change together with the system prompt if needed.
- Write tools use repositories directly (same layer as ViewModels)
- `SettingsViewModel.testConnection()` **always** short-circuits with `Error("API key not configured")` when no key, **without** calling `textGen`. Tests assert this with `FakeTextGen(trackGenerateCalls = true)` and `assertEquals(emptyList(), textGen.generateCalls)`.
- `llm_usage` table tracks input/output tokens and cost per call

### `architecture`

- **+100% testability** — all business logic is in pure Kotlin, testable without Compose.
- **+~20% lines in ViewModels** — state that was implicit in Composables must be made explicit in VMs.
- **All** new pure-domain code lives under `core/tree/` or `feature/<x>/domain/logic/` and must be pure (no `runBlocking`, no Compose imports, no Koin). _(from `2026-09-17-orgmode-architectural-lessons`)_
- **Known limitation**: 10 constructor parameters remain; next candidate for `TaskDetailDeps` by analogy with `TaskEditorDeps`.
- **Migration cost** — 7 violations across 5 PRs. See the implementation plan for the sequence.
- **Negative**: 40+ files had import paths updated; test files also required path corrections
- **Negative**: Deep `domain/model/` import chains if not careful (mitigated by `package com.singularity.todo.feature.tasks.domain.model.*`)
- **Positive**: Cross-feature imports are now compile-time errors if they bypass domain
- **Positive**: Strict layer boundaries enforced by package structure; pure domain logic testable without Android instrumentation
- **Positive**: `TaskDetailUiState.reduce()` is a pure function — covered by unit tests without mocks
- **`LocalNavBackStack` как публичный API** — позволяет экранам
- **`String`-encoded `initialDueDate`** — заменён на
- **`TasksRoute.Pop` как sentinel** — race condition (см. review rev. 1,
- **Один плоский AppDestination без nested graph** — не даёт feature
- **Чинится латентный VM scoping bug** для `TaskDetailViewModel`,
- **−100% UDF violations** in this category — the rule is now written and enforced via skill.
- 4 new files: `AccountSettingsViewModel.kt`, `TagPickerViewModel.kt`, plus DI registrations.
- 6 modified files: `ProjectDetailViewModel.kt`, `ProjectDetailScreen.kt`, `ProjectPickerSheet.kt`, `AccountSettingsScreen.kt`, `SettingsScreen.kt`, `Modules.kt`.
- Consistent API across all shared components
- Diff больше, чем чисто миграция tasks — затрагивает общий `Nav3State`.
- Each pure-infrastructure module ships with at least one `commonTest` covering empty list, single element, deep nesting, and cycle detection. _(from `2026-09-17-orgmode-architectural-lessons`)_
- Easier to extend cards and editors without breaking call sites
- Instrumented/integration тесты (`CreateTaskFlowInstrumentedTest`)
- Lifecycle VM становится привязан к lifetime entry — VM очищается
- Migration from plain lambdas requires updating call sites
- Navigation между Detail и подзадачами/проектами становится
- New file `TaskDetailIntent.kt` (~120 lines).
- Preview functions in `TaskDetailViewScreen` updated to pass `emptyFlow()` for `recentlyDeleted`.
- Previews that don't use Koin continue to work since `searchNotesForLink`/`searchTasksForLink` are nullable.
- Previews updated: `AccountSettingsScreenLightPreview` / `DarkPreview` now construct `AccountSettingsViewModel(FakeProfileRepository())`; `SettingsScreen` preview updated similarly.
- This ADR layers on top of `2026-09-17-orgmode-architectural-lessons.md` and supersedes nothing. Both ADRs are read together at sprint planning time. _(from `2026-09-17-orgmode-functional-patterns`)_
- This ADR supersedes nothing; it layers new pure infrastructure over `2026-09-16-agenda-engine.md` and `2026-09-08-task-1-level-subtasks.md`. _(from `2026-09-17-orgmode-architectural-lessons`)_
- Type-safe actions via `sealed class Action` with exhaustive `when`
- Unit-тесты навигации tasks требуют `Robolectric` или `composeRule` —
- `ActiveSheet.kt`: 35 → ~15 lines (`toActiveSheet()` removed).
- `NoteEditor` now requires `InternalLinkRepository` in its constructor — updated `NotesDiModule` accordingly.
- `ProjectDetailViewModel(projectId)` — Project X → back → Project Y
- `TaskCreateContent.kt`
- `TaskCreateDeps` expanded with `draftStore: DraftStore, autosaveScheduler: AutosaveScheduler`
- `TaskCreateViewModel(initialDueDate)` — два последовательных
- `TaskCreationTopBar.kt`
- `TaskDetailContent` is now `internal` (stateless, previewable without Koin).
- `TaskDetailScreen.kt`: `when (action)` on 27 branches → `when (intent)` on 6 branches. Routing now uniform (all `activeSheet = …`).
- `TaskDetailUiEvent.kt`: 34 → ~18 lines (10 sheet-triggers removed).
- `TaskDetailViewContent.kt`
- `TaskDetailViewContent` now takes a `recentlyDeleted: Flow<Task?>` parameter — passed from `TaskDetailViewScreen`.
- `TaskDetailViewModel(taskId)` — Task A → back → Task B больше
- `TaskDetailViewModel.kt`: 450 → ~270 lines, 37 public methods → 3 (`start`, `onTitleChange`, `onIntent`).
- `TaskDetailViewModelTest`: updated 5 tests to call `vm.onIntent(Domain.X)` instead of `vm.setX(task, value)`.
- `TasksDiModule` removed now-unused `ProjectsRepository` import.
- `TasksFormatters.kt`: added `dueChipColors` formatter and `parseDueTime` utility.
- `collectAsState` replaced with `collectAsStateWithLifecycle` in previews.
- `core/draft/DataStoreDraftStore.kt`
- `core/draft/DraftStore.kt`
- `core/draft/FakeDraftStore.kt`
- `core/serialization/StableJson.kt`
- `core/tree/Ancestors.kt`, `feature/tasks/domain/logic/Bulk.kt`, `core/pure-formatters/Attr.kt` are optional add-ons — they may be added in any sprint A+/C+ order or skipped entirely if not yet needed. _(from `2026-09-17-orgmode-functional-patterns`)_
- `single<DraftStore> { DataStoreDraftStore(get()) }` in `CoreDiModule`
- `value class XxxActions` indirection — harder to read at first glance
- ~18 файлов переработано, +5 новых, -2 удалено.
- В `JvmNav3State.kt` для `AppDestination.TasksGraph` /
- В `TasksNavGraph.kt` (для nested `rememberNavBackStack`).
- Все остальные параметризованные VM (~20 callsites).
- Дополнительный уровень индирекции для новых разработчиков: «где я?».
- Если какой-то VM был неявно расчитан на per-Activity scope
- Необходимо зарегистрировать `TasksRoute` в двух `SerializersModule`:
- Первая фича с nested graph — другие фичи (notes/projects/auth/settings)
- Рассмотреть переход на `LocalResultEventBus` + `ResultEffect<T>` для
- Сигнатуры экранов tasks упрощаются до 1-2 аргументов.
- Существующие unit-тесты для VM не затрагиваются (тестируют VM
- Чинится латентный bug для всех `koinViewModel { parametersOf(...) }`

### `backup`

- **Negative**: Attachments are not deduplicated across backups — two backups with the same file will contain two copies
- **Negative**: No incremental backup — every export is a full snapshot
- **Positive**: Single portable file with integrity check (SHA-256)
- **Positive**: Version fields allow future migrations (FORMAT_VERSION / SCHEMA_VERSION)
- **Positive**: `ignoreUnknownKeys` provides graceful forward compatibility

### `cleanup`

- When a real use case appears (e.g. TaskDetailViewModel needs a project picker), implement it from scratch using `ListPickerSheet` + `DialogState` + caller-side state hoisting — not by resurrecting the deleted code.

### `compose`

- All JetBrains compose library versions MUST track `version.ref = "composeMultiplatform"`. Split-version declarations are forbidden unless the artifact is an AndroidX (not JetBrains) group.
- Preview functions are `private` and placed at the end of the source file,
- The `checkJvmMainComposeLibrariesCompatibility` task must pass silently on every PR.
- When adding a new third-party Compose dependency, verify its JetBrains compose `requires:` constraint in the Gradle module metadata (`.module` file in cache) before adding — if it demands a version newer than the current pin, either bump or find an alternative.
- `@Preview` annotation is `@androidx.compose.ui.tooling.preview.Preview` —
- `Clock.System.now()` must not appear in preview code — use
- `PreviewParameterProvider` is avoided — individual preview functions used instead
- `useSurface = false` when the preview root already contains a `Scaffold`

### `desktop`

- 23 of 28 context menu items are wired to `actions.onDismiss()` — future iterations wire the
- Agenda context menu: Pin, Delete, Expand, Complete are functional.
- Desktop chrome is a 240 dp left rail, VSCode/JetBrains-style. Width is explicit, not derived from drawer measurements.
- Every `NavDestination` entry has an `icon` field. When adding a new entry, pick an icon from `androidx.compose.material.icons.Filled` or `Icons.AutoMirrored.Filled`.
- Hover delay (300ms) on submenus via `LaunchedEffect(isHovered) { delay(300); onOpenSubMenu() }`.
- Menu bar appears in OS-native window chrome on all three desktop platforms.
- Navigation interaction tests (click-to-navigate) are out of scope for this smoke test — they require handling NavBackStackEntry lifecycle in `runDesktopComposeUiTest`
- Right-click context menu works again on task rows in the agenda.
- Smoke test now passes: `./gradlew :desktopApp:test` → BUILD SUCCESSFUL
- `AgendaDeps` extension for AI actions is the next step for AI menu items.
- `ContextMenuOpenState` data class in `jvmMain/core/ui/menu/` holds the screen `DpOffset`.
- `DesktopShellNav3.kt` owns `showAbout` state and `menuEntries` — natural location since
- `MenuBarHost` is a stub (Material 2 not available in current Compose version).
- `ModalShell` + `DrawerStyle.Modal` remain in `AppShell.kt`. They are not wired to any platform but are preserved for future modal drawer needs.
- `compose-material:material = 1.12.0` added to `libs.versions.toml` and `desktopApp/build.gradle.kts`
- `compose-ui-desktop` is now a required `jvmMain` dependency for `shared`.
- `compose-ui-test:1.12.0` added to `libs.versions.toml` as `composeUiTest`
- `onSecondaryClick` is a no-op on Android; touch long-press is handled separately by the caller.
- `openGitHub()` uses `java.awt.Desktop.browse(URI(...))`; `exitProcess(0)` for quit.
- `singularity-todo-shared-ui-components` skill governs decomposition: desktop-only chrome stays in `feature/nav/`, shared widgets go to `core/ui/components/`.
- `sourceSets { test { java.srcDirs("src/jvmTest") ... } }` added to `desktopApp/build.gradle.kts` to wire the `jvmTest` source set to the `test` task

### `detekt`

- **Configuration cache**: detekt 1.23.x and kover 0.9.9 are both CC-compatible. Verified by running `./gradlew --configuration-cache :shared:detekt`.
- **New Gradle tasks added**:
- **`.editorconfig` may rewrap existing code** on first `detektFormat` run. Expect a large diff; consider a separate "format" commit before merging.
- **`ignoreFailures = true`** means violations are reported but never block builds. To enforce violations: set `ignoreFailures = false` in both `shared/build.gradle.kts` and `desktopApp/build.gradle.kts` once baselines are settled. **TODO: tracked in issue tracker — promote after baselines are clean (est. post-format PR).**
- **detekt 2.0.0-alpha.3 vs Kotlin 2.3.21**: this version was chosen because stable 1.23.8 was compiled against Kotlin 2.0.21 and throws "detekt was compiled with Kotlin 2.0.21 but is currently running with 2.3.21". Upgrade to stable 2.x once released.
- `:desktopApp:detekt` / `:desktopApp:detektFormat` / `:desktopApp:detektBaseline`
- `:desktopApp:koverXmlReport` / `:desktopApp:koverHtmlReport`
- `:shared:detekt` / `:shared:detektFormat` / `:shared:detektBaseline`
- `:shared:koverXmlReport` / `:shared:koverHtmlReport`

### `di`

- **Breaking:** `coreDomainModule()` удалён; заменён на `domainModule()` (includes everything). Test files обновлены.
- **Existing tests:** `DiGraphTest`, `JvmAiDiGraphTest`, `AppSmokeTest` обновлены и проходят.
- **New file count:** 8 новых файлов (7 модулей + decision).

### `dsl`

- `ListPickerItem<T>.leading` slot already covers the `RowScope` customization need; no `trailing` slot added (not needed yet).
- `ListPickerScope<T>.header { }` and `footer { }` are the canonical way to add custom content above/below the item list.
- `T : Any?` means callers can use `null` as a key — filter at call site if needed.

### `gradle`

- **Catalog accessor shadowing (Gradle 9.x):** Library keys that start with a prefix that matches a version key (e.g., `jvm-test` when version key is `kotlin`, or `kotlinSerialization` when version key is `kotlin-serialization`) generate nested accessor classes that shadow the version accessor. Workaround: version alignment constants are defined in `gradle.properties` (`version.kotlin`, `version.kotlinSerialization`, `version.kotlinxCollectionsImmutable`) and used in `resolutionStrategy` via `project.property()` — this avoids the catalog entirely for version strings.
- All TOML keys follow `kebab-case` naming convention. New entries must use kebab-case.
- Android SDK versions use `sdk-compile` / `sdk-min` / `sdk-target` keys (accessor: `libs.versions.sdk.compile` etc.). Keys starting with `android` are avoided because library aliases like `androidx-android-*` shadow the version accessor.
- Before adding a new dependency, check if the library entry already exists in `libs.versions.toml`. Hardcoded `group:artifact:version` strings in `build.gradle.kts` are a code smell.
- Gradle deprecation warnings are now visible (`warning.mode=summary`). Warnings from AGP 9.x, Kotlin 2.3.x, and KMP 1.12.x should be reviewed periodically.
- When adding a bundle, confirm all members are used together in every relevant source set. A bundle that partially applies is worse than no bundle.
- `android.useAndroidX=true` removed from `gradle.properties` — it has been the default since AGP 4.x.
- `resolutionStrategy` additions go in `build.gradle.kts` (root) only. Never add a second `configurations.all { resolutionStrategy }` in a module.

### `koin`

- **4 VM registrations** (`TaskEditorViewModel`, `TasksByProjectViewModel`, `ProjectEditorViewModel`, `ProjectDetailViewModel`) now use `viewModel { (p) → ... }` instead of `factory { (p) → ... }`
- **@Preview и widget-тесты не затрагиваются** — все preview используют `*Content` helpers (stateless)
- **No call-site changes** — `koinViewModel { parametersOf(...) }` works with both forms
- **Raw `runBlocking` в модулях** — не допускается, `koinBridge` как единая точка входа
- **State survives configuration change** on Android — rotation no longer resets these screens
- **Test impact** — tests that relied on a fresh VM instance per `get()` may need updating; prefer stateful testing over instance-fresh guarantees
- **`TaskEditorViewModel` special case** — `viewModel { (initialDueDate) -> ... }` + `koinViewModel { parametersOf(initialDueDate) }`
- **`koinInject()` для репозиториев/сервисов остаётся** — не VM
- **`koinViewModel()` для VM в Composable** — `koinInject()` для VM антипаттерн
- **`singleOf` для репозиториев** — architectural limitation; сложные конструкторы не поддерживают constructor-reference форму
- **`singularity-todo-vm-koin-scoping` skill** — создан как single source of truth
- **`viewModel { Vm(get(), get(), ...) }`** — для VM с nullable dep + getOrNull() (TasksViewModel, ProjectsViewModel)
- **`viewModelOf(::VM)` для VM без nullable dep** — предпочтительный паттерн
- **Правило подтверждено:** `koinBridge` только для one-shot startup suspend reads
- Settings → Backup tab no longer crashes during composition.
- The `desktopApp/build.gradle.kts` change (adding `implementation(project(":shared"))` with kotlinJvmTask) was also part of the desktop build fix.
- When the script's grep is broken (a stray `runBlocking` appears), fix it immediately; the helper exists specifically so this is detectable.
- `BackupRepository` resolves correctly in all environments (JVM desktop, Android).
- `koinBridge` is for one-shot startup reads only — **not for** hot-path code, **not for** long-running operations.

### `koog`

- A passing `:androidApp:assembleDebug` is the cross-platform smoke test (it would have failed under the old stub).
- The old `JvmPromptExecutorPort` and `AndroidPromptExecutorPort` files are deleted.
- The unified `KoogPromptExecutorPort` lives in `commonMain` and exposes `val executor: PromptExecutor` publicly for the platform `single<PromptExecutor>` binding.
- When adding a new AI tool, **always** bind its use case with **explicit `get<ConcreteTool>()`** if the use case's parameter is `SimpleTool<T>`:
- `JvmAiDiGraphTest` keeps its `LLModel` override as a safety belt — if someone reintroduces `OpenAIModels.*`, this test fails at graph-build time.

### `kotlin`

- SettingsViewModel is the last VM in the codebase with enough multi-property intents to benefit; other 7 VMs have single-property intents where the pattern yields no gain.
- `apply(intent)` branches must stay separate — they rely on receiver being the contributor, not the intent.
- `with(intent) { }` is the canonical pattern for sealed-interface dispatch when branches share multi-property access patterns.

### `logging`

- All new `catch` blocks in ViewModels, repositories, and use cases should inject `Logger` and call `log.e(e) { "..." }` or use `runCatchingLogged`.
- Existing silent `catch (_: Exception)` (e.g., in `ToolFactories.kt` lines 58, 126, 181) remain unfixed — these require separate investigation (some appear to be copy-paste bugs, not intentional suppression).
- Koin logs (`NoDefinitionFoundException`, etc.) now appear in Kermit's output via `KermitKoinLogger`.
- On JVM, `ColorizedWriter` uses `\u001B` ANSI escapes. Older Windows terminals (pre-10) will print escape sequences literally. `NO_COLOR` env var is respected.
- `BuildConfig.DEBUG` requires `buildConfig = true` in `androidApp/build.gradle.kts`. No BuildConfig is available in `shared` jvm target.
- `RefineTaskTool.kt:34-38` has identical try and catch branches (copy-paste bug) — not fixed in this PR.

### `mcp`

- ADR пишется в `docs/decisions/{YYYY-MM-DD}-{slug}.md` (server-side date).
- AI-агент парсит `isError: true` из `result` для business errors и ловит `-32603` из `error` для internal
- AI-агенты получают нативный доступ к данным без UI
- All 3 tools now require `ProfileAwareCurrentUser` in DI — tested via
- Auto-migration v9 добавляет unique index на `(idempotency_key, user_id)` where not null
- Dogfooding-профиль "AI Agent" (🤖) изолирует агентские задачи от пользовательских
- MCP client (ZCode CLI) now sees the `initialize` roundtrip succeed and can list/call tools.
- MCP clients that validate `$schema` as a URI will no longer reject tool schemas.
- One new e2e test in `mcp-server` (`McpToolRoundTripTest`).
- One new unit test file in `mcp-server` (`KoogJsonSchemaBuilderTest`).
- Process exit semantics change from "instant" to "on stdin EOF or session error". A passing test asserts the process stays alive ≥3s with empty stdin.
- The downstream `ToolRegistrar` and tools still run inside `runBlocking { koogTool.execute(args) }` per call — coroutine scope inside the request handler, no change.
- Three new unit test files in `shared/commonTest` for the read tools.
- ZCode подключается через `mcpServers.singularity-todo` в настройках
- `./gradlew :mcp-server:test` now includes a regression test (`McpServerEndToEndTest.server_blocks_until_stdin_closes`) that asserts `process.isAlive` after 3s of empty stdin. If anyone removes the blocking primitive, this test fails.
- `ErrorMapper.kt` маппит `McpToolError` в `CallToolResult` или бросает `McpException`
- `McpToolError.kt` в `mcp-server/src/main/kotlin/com/singularity/todo/mcp/errors/`
- `Runtime.getRuntime().addShutdownHook { server.close() }` becomes redundant for normal EOF exits — `onClose → done.complete() → done.join() returns → runBlocking exits → JVM exits cleanly`. We keep the shutdown hook only as a backstop for SIGTERM.
- `TaskEntity` получает `@ColumnInfo("idempotency_key") val idempotencyKey: String?`
- `TaskRepository` получает `findByIdempotencyKey(key, userId)` метод
- `ToolFactories.kt` gets the profile-aware default applied (small diff,
- `ToolRegistrar` catches `McpToolError` first (small diff).
- `list_tasks`, `list_linked_tasks`, and `search_tasks` now return correct results
- tag 'mcp-ux'/'ui-subtask'/'ai-tooling'/'mcp-policy'/'refactor' — 5 persistent categories для фильтрации.
- В профиле ai-agent теперь 5 top-level plans × ~6 sub-tasks = ~30 новых rows.
- Все 17+ tools следуют этому контракту
- Все token usage пишется в `llm_usage` с `profile_id=ai-agent`
- Все write-tools используют `Result<T>` + `mapCatching` для differentiation `Internal` от `Validation`/etc.
- Каждый plan имеет parentTaskId = top-task; UI должен теперь уметь их показать (см. plan 'ui-subtask').
- Новый Gradle-модуль `:mcp-server` с dependency на shared
- Один прогон драйвера = реальная multi-step демонстрация MCP.
- При недоступности LLM в драйвере зашит fallback sub-task'ов.

### `multi-profile`

- 4 ADR entries created + DIGEST.md refreshed
- AI Usage screen в Settings
- Room schema v8 с `llm_usage` table + `profiles` table
- ZCode подключается с `--profile=ai-agent` → все операции в профиле ai-agent
- `ProfileAwareCurrentUser` инжектится во все write-tools

### `nav3`

- 8 new files (nav package under projects feature) + 2 new ADR records.
- Additional level of indirection for new developers: "where am I?"
- All 3 projects screens use `LocalProjectsNavigator` — no callback parameters.
- All task feature screens (`TaskListScreen`, `TaskDetailViewScreen`, `TaskCreateScreen`) use `LocalTasksNavigator.current` for navigation — no callback parameters.
- Android system back gesture is handled by `BackHandler` in `TasksNavGraph.android.kt`. JVM has no back handling.
- Cross-feature navigation between projects and tasks uses type-safe `AppDestination` hops.
- Feature isolation: `ProjectsNavGraph` is self-contained and could be ported to iOS or other shells.
- Screens that need `@Preview` use `TasksPreviewWrapper { ... }` which provides a `PreviewTasksNavigator` via `LocalTasksNavigator`.
- `ProjectDetailViewModel(projectId)` and `ProjectEditorViewModel(projectId)` now have correct per-entry VM scoping on Android.
- `TaskDetailIntent` no longer has `NavigateToProject` / `NavigateToTask` routing intents — those are now navigator methods.
- `TasksNavGraph` is the `@Composable` nav host — it sets up `LocalTasksNavigator`, `LocalNavBackStack`, and the `BackHandler`.
- `TasksNavigator` is the only class that mutates `NavBackStack<TasksRoute>`.
- `TasksRoute` is the sealed interface defining all routes within the tasks graph (Inbox, Today, ByProject, Detail, Create).

### `navigation`

- **Adding a new route type on Android**: must still call `navSavedStateConfig(...)` with the new type's serializer in every NavGraph that can contain it. The `subclass(...)` registration requirement (per `2026-09-16-nav3-savedstate-serializers-required`) is unchanged on Android.
- **Adding a new route type on Desktop**: no serializer registration needed; `rememberInMemoryNavBackStack(start)` is untyped and works for any `T : NavKey`.
- **Android build unchanged**: `assembleDebug` still compiles all Android-specific NavGraphs with full `SavedStateConfiguration` for process-death survival.
- **BottomBar taps** now have a single source of truth: `navigator.navigateTopLevel(dest)` — no `selectedIndex` to keep in sync.
- **Desktop chrome** is unchanged from the user's perspective — the drawer still works exactly as before.
- **Desktop in-memory only**: Closing and reopening the Desktop window resets all nested back stacks. This was already the behavior before this change — `LocalSaveableStateRegistry` was always `null`. The new code makes this explicit.
- **Menu sheet visibility** is `rememberSaveable` state in `AndroidShell` — survives config changes, not part of the back stack.
- **Per-tab backstacks** work as expected: open TaskDetail on Today, switch to Plans, switch back to Today → TaskDetail is restored.
- **`NavDestination` (drawer enum)** remains for the desktop drawer's grouping by `NavGroup` — not removed, just no longer wired to mobile.
- **`TasksScreen`** unchanged — it already takes `onNavigateToTask` / `onNavigateToCreateTask` callbacks; the per-tab sub-navigation state now lives in `TasksRoute` inside `AppNavHost` via `rememberSaveable`.
- All Android NavGraph back stack declarations become `val backStack = rememberNavBackStackTyped(savedStateConfig, start)` — clean, typed, no suppression.
- All `@Preview` composables compile without composition-local crashes.
- All `AgendaStartRoute` variants are now handled in one place.
- JVM path is unchanged.
- Notes deep-links from Search now land on the correct note preview.
- Preview for `AccountSettingsScreen` uses a separate `AccountSettingsScreenPreviewContent` composable that takes an explicit callback, since `LocalSettingsNavigator` is only available inside the graph.
- Smoke test: tap FAB on Inbox → verify CreateTask opens; tap FAB on Plans → verify CreateProject opens.
- The Android no-arg overload `rememberNavBackStack(vararg elements)` (reflection path) is **not used** in this project anymore — every call goes through the configuration overload so Android and JVM share one contract.
- The inline wrapper is `internal` to the Android source set — no API surface change.
- Users can now create projects directly from Plans via the FAB.
- Users can now create tasks directly from Inbox/Today via the FAB.
- When adding a new `AgendaStartRoute` variant, add it to `AgendaStartRoute.kt`, then add a branch to `AgendaNavContent.when`.
- When adding a new `data object` or `data class` to `AppDestination` (or any sealed route hierarchy that backs a `rememberNavBackStack`), **always** add the matching `subclass(...)` line in every relevant `serializersModule` — the compiler does not enforce this.
- `@Suppress("UNCHECKED_CAST")` removed from all 5 Android NavGraph files.
- `AgendaNavGraph.android.kt` and `AgendaNavGraph.jvm.kt` still have platform-specific setup (SavedState, in-memory backstack, desktop context menu) — those remain appropriately separated.
- `AppDestination.TaskDetail` and `TaskDetailCreate` remain `@Deprecated` — they can be deleted in a follow-up cleanup commit.
- `NavEntries.kt` wires `SettingsNavGraph(navCallbacks = nav)` and `SearchNavGraph(navCallbacks = nav)` instead of the raw screens.
- `ProjectsNavGraph` in `NavEntries` now maps `ProjectsStartRoute.Editor` to `ProjectsRoute.Editor`.
- `SettingsScreen` no longer accepts `onNavigateToProfileSwitcher` — `AccountSettingsScreen` navigates directly.
- `fabActionForNav3` is simpler and more correct.

### `notes`

- Backlinks are now shown and functional
- Caller must provide `MutableStateFlow<String>` and inject `InternalLinkRepository` and `ProfileAwareCurrentUser` — slightly more boilerplate at call site
- Clear UX: notes list → tap note → read → optionally edit
- Cross-screen state (e.g. "did the user just save a note") must flow through navigation callbacks, not shared VM state
- Delete confirmation is handled in `NotePreview`, not buried in editor overflow menu
- Each VM is small enough to understand fully (~60-150 lines)
- Editor session state is released when user navigates away
- Icon per `LinkKind` makes the list scannable
- Navigation now has one more route: `NoteView` ↔ `NoteEditor` ↔ `NotesScreen`
- No loading screen — the text field is always visible in the list
- No new dependencies
- No schema migration needed
- Note metadata (word count, last updated) is visible without entering edit mode
- One tap fewer than before for the common "capture a thought" workflow
- Previews for each screen can use `koinViewModel { parametersOf(...) }` without circular dependency
- Regex over HTML is less elegant than walking the paragraph tree, but the paragraph tree is internal
- Search debouncing (300ms) is now the caller's responsibility (implemented inside the sheet via `LaunchedEffect`)
- Sheet is reusable by any feature that needs internal linking (e.g. TaskEditor)
- Single search + merged results = better UX (one tap instead of tab switching)
- Slight visual complexity added to the list screen
- The `outgoing_links` column is populated on every save, keeping backlinks current
- Three Koin registrations instead of one
- Title pre-saved to DB before navigating to editor (no lost titles on crash)
- User must explicitly tap "Edit" to modify — one additional tap for casual reading
- VMs are independently testable with focused test suites
- `FakeNotesRepository` и `FakeNoteDao` обновлены同步.
- `NoteDao.updateContent` сигнатура изменилась: добавлен параметр `html: String`.
- `NotePreview` must observe the note via `repo.watchNote()` — requires a Flow subscription
- `NotesListViewModel` now requires `IdGenerator` as a third constructor parameter
- `NotesRepository.createWithContent` и `updateContent` сигнатуры изменились: добавлен параметр `bodyHtml: String`.
- `NotesRoute` now injects `NotesListViewModel` via `koinViewModel()`, `NoteEditor` and `NotePreview` are injected via their respective screen composables
- `core/ui/components/` is now free of feature-domain imports
- `getBacklinkNotes` now returns real results — backlinks in `NotePreview` and `InternalLinkPickerSheet` will work
- Все существующие тесты проходят — никаких изменений в тестовых вызовах не потребовалось (jvmTest зелёный).
- При первом открытии старой заметки (без `bodyHtml`) — форматирование может отличаться от исходного (round-trip через markdown). Это accepted trade-off для legacy data.

### `preview`

- All new screens MUST follow the `PublicScreen` / `PrivateContent` naming pattern
- Do NOT introduce `koinViewModel()` inside any `@Preview` — CI/preview harness does not start Koin
- FakeRepositories live in `commonMain/test/fakes/` (not `commonTest`) so `commonMain` previews can access them
- `@Preview` composables are always `private` and call the `*Content` variant with manually constructed VMs

### `profile`

- **Negative**: Compound `scopedUserId` is a string manipulation — a proper `ScopedUserId` value class would be cleaner (future work)
- **Negative**: Profile deletion cascades to all that profile's data — no soft-delete for profiles
- **Positive**: Clean separation of auth (user) vs data namespace (profile)
- **Positive**: MCP server can route to any profile via `--profile=<id>`

### `project-detail`

- **`createTask`** must go through `CreateTaskUseCase`, not direct `taskRepo.create`.
- Screen owns `activeSheet` routing state; VM only receives routing intents.

### `projects`

- All `@Preview` composables use `ProjectDetailContent(vm, ...)` with `FakeRepositories` — no preview crashes
- Architecture: screens own routing state (`sheetState`), VMs own domain logic, navigation callbacks are passed as parameters
- `ProjectDetailScreen` is fully functional: quick-add creates tasks, parent picker works, Remind/Attach/DueDate/Children sheets open, task click navigates to `TaskDetailScreen`
- `ProjectPickerSheet` is reactive — newly created projects appear without reopening the sheet

### `security`

- Adding a new secret (e.g. another provider's API key) **always** follows the same pattern: new `KEY_*` constant, new config object, migration on first DataStore access, no DataStore copy.
- `AiApiKeyMigration` is wired through `koinBridge { ... }` inside the DataStore factory's `.also { ds -> ... }` block. See `koin-suspend-bridge` decision.

### `serialization`

- Adding a new config option is a one-line change
- MR1 JSON `{"_type":"Tag","id":"..."}` and MR2 JSON `{"_type":"Tags","ids":[...],"matchAll":false}` both round-trip correctly.
- The `@Serializable(with = ...)` annotation on the sealed interface activates the custom serializer for ALL paths including nested occurrences (e.g. `AllOf.children: List<Selector>`).
- `StableJson` replaces 3+ local copies
- `encodeDefaults = true` increases JSON size marginally — acceptable for backup/sync payloads
- `ignoreUnknownKeys = true` silently drops unknown fields on deserialization — intentional for graceful migration

### `settings`

- All changes are additive; no existing behavior is removed.
- Backup confirm dialogs prevent accidental data loss.
- Debounce reduces SecureStorage/DataStore writes by ~90% during text input.
- Test suite (`SettingsViewModelTest`) updated to work with debounce bypass in test mode.
- `App.kt` инжектит `SettingsRepository` через Koin — это нормально, Koin доступен в Common startup.
- `SettingsNavRail` Column теперь содержит Box с CircleShape — Layout инлайн, не refactor.
- `SurfaceController.apply(event)` is **not** changed — separate scope, separate task.
- `TextGenPort.listModels` — добавлен в интерфейс, реализация в `KoogAgentService` и `FakeTextGen`.
- `process(intent)` is the canonical name for contributor intent dispatch.
- Все 6 sub-screens имеют `verticalScroll` — контент больше не обрезается.

### `sync`

- **Negative**: No server-side push; conflict resolution is last-write-wins with checksum fast-reject (not full CRDT)
- **Negative**: `pull()` is not yet implemented — remote changes do not appear on the device
- **Positive**: Simple, predictable push model; HLC provides causal ordering; outbox is durable (Room)

### `task-detail`

- Archive and Delete have distinct storage semantics — future "Trash" filter can distinguish intentional archive from accidental delete.
- Checklist items can be promoted to sub-tasks via "Convert to task" overflow action.
- Sub-task count is denormalized via `TaskFilter.ByParent` query — no need for recursive count.
- `_recentlyDeleted` in TaskDetailViewModel holds the full task before delete/archive for undo.
- `_recentlyDeleted` must be cleared in `onCleared()` to avoid leaking task data on configuration change.
- `restore()` is idempotent — calling restore on a non-archived task is a no-op (or returns Result.success if the row simply re-inserted).
- `restore()` re-uses the original `id` — idempotent by design.

### `tasks`

- **Breaking**: MCP tool producer-side обновляется
- **Breaking**: `Selector.Tag` rename — `AgendaPresetsTest` JSON snapshots обновляются
- A task may have zero, one, or many dependencies.
- Cycle detection is deferred — cycles are rare and the cost of a DFS on every `setDependencies` call is non-trivial for large task graphs.
- Self-dependency is validated in the MCP tool and silently ignored by the join-table upsert (PRIMARY KEY prevents the duplicate).
- `@Serializable` на `TaskStatus` — нужен для kotlinx.serialization AgendaDefinition (saved views в будущем).
- `AgendaEvaluator.matches` обновлён для `Selector.Tags` (список tags → `task.tags.any { it in ids }`)
- `FakeTaskDao` и `FakeTaskRepository` mirror для всех 4 новых queries
- `TaskListFilter` удалён — поиск по коду вернёт 0 результатов (если кто-то добавил вручную после этого коммита — это регресс).
- `TaskRepositoryImpl.watchTasks` получает 4 новые dispatch branches
- `TaskStatus` в domain/model доступен для AgendaEngine DSL без добавления cross-layer импорта.
- `dependsOn` is **not** enforced at the data layer — completion is always allowed. UI consumers (`TaskList`, `AgendaEvaluator`) display `isBlocked` to inform users.
- `isBlocking` (reverse direction) is not in MR-1 — a separate follow-up can add `watchBlockingBy` to `TaskUi` if needed.

### `testing`

- **Fake repo returns empty by default** — widget tests that check `LazyColumn` with `testTag` will fail when repo is empty (state = `Empty`). Test the `EmptyState` text instead, or seed data via `fakeNotesRepo.seed(note)`.
- **JVM args for JDK 21+** — add `--add-opens=java.base/jdk.internal.access=ALL-UNNAMED` to `gradle.properties` (`org.gradle.jvmargs`) AND to `shared/build.gradle.kts` via `afterEvaluate` + `tasks.withType<Test>()` for the test worker process.
- **Robolectric 4.17-beta-4** — `4.16` maxes at SDK 36; `compileSdk=37` requires the beta. The beta is already cached.
- **Use `UserId` from `feature.tasks`** — it's defined in `Ids.kt` there, imported explicitly.
- **`Clock` must be passed to `CreateTaskUseCase` / `UpdateTaskUseCase`** — use the singleton `Clock` from `core.platform`.
- **`Session.Anonymous()` requires `UserId`** — always pass `UserId.anonymous` or `UserId.fromString("...")`.
- **`waitForIdle()` is a method, not a function** — do NOT import it. Call `composeRule.waitForIdle()` directly.
- 3 preview functions per component (default, empty, edge case) — consistent with `2026-09-06-compose-previews` skill.
- Baseline images stored in `shared/src/commonTest/resources/roborazzi/`.
- Every future PR touching UI components must run snapshot tests and update baselines when changes are intentional.

### `ui`

- **8 экранов мигрируют одновременно** — невозможно сделать постепенную миграцию из-за смены типа `_events`
- **CollectEvents** в виджетах принимает `Flow<T : UiEvent>` — generic call site остаётся тем же
- **NotificationHost** — финальный widget для всех экранов, заменяет ~64 строк ручного glue кода
- **UiEvent marker** — `ShowDialog/ShowError/NavigateBack` больше не определены глобально
- **Существующие тесты** использующие `TasksViewModel`, `NotesViewModel` и т.д. — `_events.emit(UiEvent.ShowDialog(...))` нужно обновить на `TasksUiEvent.AiResult(...)`
- Future picker sheets (ProjectPickerSheet, TagPickerSheet) should consider `ListPickerSheet` before implementing custom sheets.
- `AppDestination` пополнился `Notes` (уже был), логика FAB его задействует.
- `AppShell` — minor change: добавлен `FabAction` parameter.
- `ConfirmActionDialog` replaces inline `AlertDialog` in any future confirm-dialog use case.
- `DialogState` is the canonical state holder for single-dialog overlays. Use directly with `if (dialogs.active == X) { ... }`.
- `DragHandleRow` is the canonical home for any read-only row that has a drag handle. If a future use case needs click-to-edit or checkable rows, create a separate component.
- `Icon`, `Column`, `Row`, `Arrangement` imports removed from `ReorderableSectionList.kt` since `SectionRow` no longer uses them directly.
- `ListPickerSheet` is the canonical bottom-sheet picker in this codebase. For simple static lists, use the DSL form. For dynamic lists (from a repository), construct `ListPickerItem` objects and pass to the data-class overload.
- `ProjectDetailScreen` (10 dialogs) remains a future migration candidate — its data-class variants (`PickParent(current: ProjectId?)`) require additional consideration for smart-cast ergonomics.
- `SavedAgendaScreen` now uses `dialogs.show(X)` and `dialogs.dismiss()` instead of `activeDialog = X` and `activeDialog = null`.
- `SectionEditorCard` now uses `DragHandleRow` internally, keeping the Card wrapper for elevation and background.
- `SectionTemplate` data class and `SectionTemplates` list removed from `SavedAgendaScreen`. If templates need to be reused elsewhere, promote them to a shared location.
- `TagsScreen` больше не принимает callback — экран не подключён к навигации (menu destination `Tags` отсутствует в `AppDestination`).

### `ui-components`

- All 13 sheet-holder screens in the codebase should migrate; remaining are ProjectPickerSheet, TagPickerSheet, ParentPickerSheet (deferred to MR14.b — require VM create-flow rework).
- Every new bottom sheet should use `BottomSheetHost`, not raw `ModalBottomSheet` + `rememberBottomSheetState` + `LaunchedEffect`.
- `BacklinksSheet` needs richer item rendering support (custom item composable slot) before migration is viable.
- `DialogState<T>` is the **only** approved pattern for bottom-sheet/dialog state in composables. `mutableStateOf<T?>` for sheet state is now deprecated.
- `KindSheet` can be migrated once a row-variant or chip-variant of `ListPickerSheet` exists.
- `LaunchedEffect { sheetState.show() }` must **never** appear in leaf sheet code.
- `ListPickerSheet` is appropriate for: enum pickers, ID/name pairs, flat lists with optional subtitle.
- `Show` extension on `DialogState` is **not used** — prefer `if (dialogs.active == X)` pattern for conditional rendering.

### `ui-contract`

- Parent options are reactive (`StateFlow`) — picker updates automatically when projects change
- The "None (root)" option is rendered as a `TextButton` above the `LazyColumn`, not as part of `options`
- `ParentOption` is a `@JvmInline value class` candidate if it grows beyond 3 fields (currently 3 — plain data class is fine)
- `ParentPickerSheet` signature: `options: List<ParentOption>`, NOT `currentParentId: ProjectId?`

### `usecase`

- **CI gate** (future): add `.github/workflows/ci.yml` with `just tcheck` as required status check
- **False positives** can be suppressed per-function with `@Suppress("PassThroughUseCase")`
- **`ChecklistEditorViewModel(checklistUseCase, checklistRepository, scope)`** — two deps
- **`TaskDetailDeps`** gains `checklistRepository: ChecklistRepository` field
- **`factory { ChecklistUseCase(get()) }`** in `TasksDiModule.kt` — Clock removed
- **`just lint`** now includes `PassThroughUseCase` checks for `:shared` and `:desktopApp`

### `vm`

- **No pure reducer needed** — `ProjectDetailViewModel` is write-through like `TaskDetailViewModel`
- **`NavigateToTasks`** is no longer a VM event — screen handles it as routing
- **`ProjectDetailIntent`** is the canonical list of all project mutations — adding a new field mutation = one `Domain` case
- **`ProjectDetailUiEvent`** now has only 2 cases: `NavigateBack` (post-delete) and `ShowError`
- **`createTask` and `moveTaskToProject`** remain in VM (require repository writes)
- **`toggleArchive`** no longer emits `Saved` — `lastEditedAt` drives "Saved X ago" UI via the `mutate{}` helper
- 4 PRs instead of 1 (review overhead).
- All new VMs in this codebase should prefer `with(intent) { ... }` for data class intents with ≥2 properties.
- Internal note/task links now navigate correctly.
- Pre-work required 3-4 hours before any visible feature change.
- Recomposition skip — `@Stable` on 11 holders.
- Single-property intents may remain as `intent.X` for simplicity — the overhead is minimal.
- Testability — `NotePreviewTest`, `LoginFormStateTest`, `OverlayStateTest`,
- This pattern does NOT require a custom DSL marker or annotation; stdlib `with` is sufficient.
- Unified mental model for state holders.
- `Dispatchers.Default` fixes flaky VM tests.
- `OverlayState` (Phase 1) is not yet saved across process death — acceptable

## Open / Deferred

_1 entries need attention._

- `2026-09-08-instant-migration` — **deferred** — Instant Type Migration: kotlin.time.Instant → kotlinx.datetime.Instant

## Recently superseded

- `2026-09-16-nav3-shared-state-factory-and-local-app-navigator` — LocalAppNavigator + shared rememberNav3State factory

## Index (slug -> tags)

- `2026-09-05-android-bottom-nav` — navigation, compose, shell, ui
- `2026-09-05-android-bottom-nav-followups` — navigation, followups, refactoring, bugs
- `2026-09-05-koin-suspend-bridge` — koin, di, coroutines
- `2026-09-05-koog-both-platforms` — koog, kmp, ai
- `2026-09-05-koog-test-workarounds` — koog, koin, testing
- `2026-09-05-llm-provider-settings` — ai, settings, ui
- `2026-09-05-refactoring-summary` — _untagged_
- `2026-09-05-robolectric-widget-tests` — testing, robolectric, koin, ui
- `2026-09-05-secret-storage-split` — security, secure-storage, settings
- `2026-09-05-task-editor-refactor` — _untagged_
- `2026-09-05-ui-decomposition` — _untagged_
- `2026-09-05-ui-event-per-feature` — ui, architecture, events, koin
- `2026-09-05-ui-tests-ultron` — _untagged_
- `2026-09-05-uiautomator-compose-discovery` — _untagged_
- `2026-09-06-compose-multiplatform-1.12.0-bump` — compose, gradle, build
- `2026-09-06-compose-previews` — compose, preview, ui
- `2026-09-06-desktop-sidebar-replaces-permanent-drawer` — desktop, compose, ui, navigation
- `2026-09-06-desktop-smoke-test-with-koin` — desktop, testing, compose, koin, ui-test
- `2026-09-06-di-module-split` — di, koin, architecture
- `2026-09-06-kermit-logging-setup` — logging, koin, kermit, debugging
- `2026-09-06-koin-bridge-audit` — koin, di, coroutines
- `2026-09-06-koin-vm-viewmodelof-koinviewmodel` — koin, di, vm
- `2026-09-06-modular-justfile` — _untagged_
- `2026-09-07-backup-directory-via-koin-string` — koin, di, backup, platform-module
- `2026-09-07-dogfooding-followups` — dogfooding, followups, technical-debt
- `2026-09-07-dogfooding-mcp-server` — mcp, dogfooding, koog, agent
- `2026-09-07-fab-chrome-level` — ui, navigation, architecture
- `2026-09-07-mcp-stdio-blocking-lifecycle` — mcp, kotlin-sdk, stdio, coroutines, lifecycle
- `2026-09-07-mcp-tool-error-model` — mcp, error-handling, json-rpc
- `2026-09-07-multi-profile-and-usage-tracking` — multi-profile, llm-usage, observability, dogfooding
- `2026-09-07-note-editor-body-load` — notes, room, rich-editor, di-graph
- `2026-09-07-notes-internal-links-backlinks` — _untagged_
- `2026-09-07-settings-fixes` — settings, ui, di-graph
- `2026-09-07-settings-ux-improvements` — settings, ux, compose, koin
- `2026-09-07-task-detail-archive-overflow` — _untagged_
- `2026-09-07-task-detail-document-style` — _untagged_
- `2026-09-07-write-tools-in-koog-registry` — mcp, tools, koog, idempotency
- `2026-09-08-instant-migration` — _untagged_
- `2026-09-08-mcp-dogfooding-round-2` — mcp, dogfooding, round-2, followups
- `2026-09-08-mcp-plan-tracking-via-mcp` — mcp, dogfooding, plan-tracking
- `2026-09-08-mcp-schema-and-profile-userid-fixes` — mcp, koog, schema, profiles, bugfix
- `2026-09-08-mcp-server-health-audit` — mcp, audit, refactor, tests, dead-code
- `2026-09-08-projects-ux-rework` — _untagged_
- `2026-09-08-roboazzi-snapshot-tests` — testing, snapshot, roborazzi, quality
- `2026-09-08-task-1-level-subtasks` — task-detail, subtasks, architecture
- `2026-09-08-task-archive-restore-contract` — task-detail, archive, repository
- `2026-09-08-task-detail-critical-fixes` — task-detail, critical-fix, ux
- `2026-09-08-task-restore-undo` — task-detail, undo, ux
- `2026-09-09-content-slot-pattern` — architecture, compose, ui
- `2026-09-09-di-factory-viewmodel-fix` — koin, di, bugfix
- `2026-09-09-feature-tasks-clean-architecture` — architecture, clean-architecture, feature-tasks, kotlin-multiplatform
- `2026-09-09-internal-link-picker-generic` — notes, ui-components, linking, architecture
- `2026-09-09-notes-outgoing-links-extraction` — notes, wikilinks, rich-editor, room
- `2026-09-09-notes-quick-add` — notes, ux, quick-add
- `2026-09-09-notes-view-edit-split` — notes, navigation, rich-editor, ux
- `2026-09-09-notes-vm-split` — notes, architecture, viewmodel, di
- `2026-09-09-parent-picker-contract` — ui-contract, projects, picker, architecture
- `2026-09-09-preview-with-koin-helper` — preview, compose, koin, architecture
- `2026-09-09-project-detail-intent-refactor` — vm, intent, refactor, koin
- `2026-09-09-project-detail-rework-15-fixes` — projects, screen-architecture, preview, koin, reactive
- `2026-09-09-projectdetail-write-through-fix` — project-detail, toctou, write-through, vm, regression
- `2026-09-09-task-detail-intent-refactor` — architecture, viewmodel, compose, tasks
- `2026-09-10-simplified-settings-vm` — _untagged_
- `2026-09-11-nav3-kmp-migration` — _untagged_
- `2026-09-14-nav3-tasks-navigator` — nav3, navigation, koin, refactor
- `2026-09-14-nav3-vm-store-decorator-fix` — architecture, navigation, koin, viewmodel, bug
- `2026-09-14-tasks-feature-nested-nav3` — architecture, navigation, koin, viewmodel
- `2026-09-15-desktop-menus` — desktop, ui, menu, navigation
- `2026-09-15-detekt-ktlint-kover-setup` — detekt, ktlint, kover, lint, coverage, quality
- `2026-09-15-nav3-notes-navigator` — _untagged_
- `2026-09-15-noteeditor-udf-link-search` — architecture, udf, notes, di
- `2026-09-15-projects-clean-architecture` — _untagged_
- `2026-09-15-projects-nested-nav3` — nav3, navigation, koin, refactor, projects
- `2026-09-15-projects-settings-profile-udf-fixes` — architecture, udf, compose, di
- `2026-09-15-task-detail-drafts-undo-fix` — architecture, compose, udf, tasks, drafts, undo
- `2026-09-15-task-editor-unification` — architecture, compose, ui, drafts, state-restoration
- `2026-09-15-viewmodel-state-ownership` — architecture, compose, udf, vm-state
- `2026-09-16-agenda-engine` — agenda, tasks, dsl, architecture
- `2026-09-16-agenda-mr3-saved-views-ui` — agenda, navigation3, reducers, events, koin
- `2026-09-16-agenda-mr4-saved-views-create-reorder` — agenda, saved-views, di, navigation3
- `2026-09-16-agendaengine-post-mr1-nav-cleanup` — agenda, navigation, cleanup, deprecated
- `2026-09-16-android-shell-fab-fix` — navigation, nav3, android, fab
- `2026-09-16-calendar-feature` — _untagged_
- `2026-09-16-calendar-post-merge-fixes` — _untagged_
- `2026-09-16-desktop-menus-bugfixes` — desktop, jvm, menu, bugfix
- `2026-09-16-nav3-desktop-in-memory-no-savedstate` — navigation, nav3, jvm, desktop, android
- `2026-09-16-nav3-feature-graph-extensions` — navigation, nav3, tasks, notes
- `2026-09-16-nav3-post-migration-fixes` — navigation, nav3
- `2026-09-16-nav3-savedstate-serializers-required` — navigation, nav3, serialization, jvm, android
- `2026-09-16-nav3-settings-and-search-nested-graphs` — navigation, nav3, settings, search
- `2026-09-16-nav3-type-asymmetry-adr` — navigation, nav3, android, jvm, technical-debt
- `2026-09-16-reactive-today-flow` — _untagged_
- `2026-09-16-saved-agenda-views` — _untagged_
- `2026-09-16-task-filter-set-variants` — tasks, domain-model, sql, selector, mr2a
- `2026-09-16-task-list-filter-to-task-status` — tasks, domain-model, rename
- `2026-09-16-tasks-upcoming-screen` — _untagged_
- `2026-09-17-agenda-mr5-pure-infra-ux-polish` — agenda, infrastructure, selectors, ux
- `2026-09-17-orgmode-architectural-lessons` — architecture
- `2026-09-17-orgmode-functional-patterns` — architecture
- `2026-09-17-selector-serializer-plain-kserializer` — serialization, agenda, selector
- `2026-09-17-vm-testability-audit` — _untagged_
- `2026-09-18-agenda-nav-route-mapping` — navigation, refactor
- `2026-09-18-agenda-selector-composer-dsl` — agenda, dsl, selector
- `2026-09-18-agenda-ui-shared-adoption` — agenda, ui, shared-components
- `2026-09-18-backup-format` — backup, architecture, format, core
- `2026-09-18-dialog-state-dsl` — ui, state-hoisting, refactor
- `2026-09-18-dialog-state-migration-mr12` — ui-components, state-hoisting, dialogs
- `2026-09-18-mcp-tool-catalog` — ai, mcp, koog, tools, architecture
- `2026-09-18-mutation-result-handling` — _untagged_
- `2026-09-18-no-pass-through-usecases` — usecase, detekt, architecture, lint
- `2026-09-18-picker-dsl-slots-mr11` — dsl, ui-components
- `2026-09-18-picker-sheet-dsl` — ui, dsl, refactor
- `2026-09-18-picker-sheet-migration-mr13` — ui-components, picker, migration
- `2026-09-18-profile-subsystem` — profile, architecture, multi-profile, core
- `2026-09-18-saved-view-factory` — agenda, viewmodel, draft
- `2026-09-18-selector-serializer-registry` — agenda, serialization, dsl
- `2026-09-18-settings-intent-with-mr10` — kotlin, viewmodel, settings
- `2026-09-18-shared-ui-adoption-mr5` — ui, refactor, dsl
- `2026-09-18-stable-json-config` — serialization, architecture, core, kotlinx-serialization
- `2026-09-18-sync-engine-architecture` — sync, architecture, core, hlc, conflict-resolution
- `2026-09-18-task-dependencies` — tasks, schema, ui, mcp, dependencies
- `2026-09-18-testing-best-practices` — testing, vm, kotlin-test, coroutines
- `2026-09-18-version-catalog-cleanup` — gradle, version-catalog, build-config
- `2026-09-18-vm-intent-with-receiver` — vm, refactor, kotlin
- `2026-09-18-vm-migration-scope-injection` — _untagged_
- `2026-09-18-vm-scope-cancellation-oncleared` — _untagged_
- `2026-09-21-auto-closeable-coroutine-scope` — _untagged_
- `2026-09-21-kotlin-auto-closeable-vs-java-closeable` — _untagged_
- `2026-09-21-out-of-scope-after-phase-5-5` — _untagged_
- `2026-09-21-state-hoisting-audit` — vm, compose, state-hoisting, refactor
- `2026-09-21-user-scoped-repository` — _untagged_
- `2026-09-22-bottomsheet-host-mr22` — ui-components, sheet-state, compose
- `2026-09-22-contributor-process-rename-mr24` — settings, naming, kotlin-idioms
- `2026-09-22-dead-sheets-removal-mr23` — cleanup, dead-code
- `2026-09-23-ai-tools-currentuser-singleton` — _untagged_

## Active entries

- `2026-09-05-android-bottom-nav-followups` — Android Bottom Navigation — known issues and refactoring backlog
- `2026-09-05-android-bottom-nav` — Android bottom navigation bar via AppShell + NavHost (no separate ViewModels)
- `2026-09-05-koin-suspend-bridge` — Bridge suspend code into Koin factories via koinBridge { ... }
- `2026-09-05-koog-both-platforms` — Wire Koog AI agent for both JVM desktop and Android
- `2026-09-05-koog-test-workarounds` — Avoid OpenAIModels.Chat.* — use KnownModels; explicit get<>() for SimpleTool<T>
- `2026-09-05-llm-provider-settings` — LLM provider settings: pure-Kotlin config object + sealed test result
- `2026-09-05-refactoring-summary` — _(no title)_
- `2026-09-05-robolectric-widget-tests` — Widget tests via Robolectric androidHostTest — no Koin, direct ViewModel construction
- `2026-09-05-secret-storage-split` — API key lives in SecureStorage only — never in DataStore, never in UI state
- `2026-09-05-task-editor-refactor` — _(no title)_
- `2026-09-05-ui-decomposition` — _(no title)_
- `2026-09-05-ui-event-per-feature` — Per-feature UiEvent — маршрутизация событий без глобальной утечки типов
- `2026-09-05-ui-tests-ultron` — UI testing strategy with Ultron + minimal DI seams
- `2026-09-05-uiautomator-compose-discovery` — _(no title)_
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
- `2026-09-07-notes-internal-links-backlinks` — _(no title)_
- `2026-09-07-settings-fixes` — Settings layout fixes, reactive dark theme, LLM providers
- `2026-09-07-settings-ux-improvements` — Settings UX improvements: swatches, time picker, connection badge, debounce, confirm dialogs
- `2026-09-07-task-detail-archive-overflow` — _(no title)_
- `2026-09-07-task-detail-document-style` — _(no title)_
- `2026-09-07-write-tools-in-koog-registry` — Write Tools — idempotent контракт, dryRun, error model
- `2026-09-08-instant-migration` — Instant Type Migration: kotlin.time.Instant → kotlinx.datetime.Instant
- `2026-09-08-mcp-dogfooding-round-2` — MCP dogfooding — round 2 plan index
- `2026-09-08-mcp-plan-tracking-via-mcp` — MCP plan tracking end-to-end
- `2026-09-08-mcp-schema-and-profile-userid-fixes` — MCP schema dialect bug + profile-aware userId defaults
- `2026-09-08-mcp-server-health-audit` — MCP server health audit — dead code, missing tests, contract hazards
- `2026-09-08-projects-ux-rework` — _(no title)_
- `2026-09-08-roboazzi-snapshot-tests` — Snapshot tests via Roborazzi for all detail screen sections
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
- `2026-09-10-simplified-settings-vm` — _(no title)_
- `2026-09-11-nav3-kmp-migration` — _(no title)_
- `2026-09-14-nav3-tasks-navigator` — Nav3: TasksNavigator replaces callback-passing in task screens
- `2026-09-14-nav3-vm-store-decorator-fix` — _(no title)_
- `2026-09-14-tasks-feature-nested-nav3` — _(no title)_
- `2026-09-15-desktop-menus` — Desktop context menu + window MenuBar via generic MenuNode sealed class
- `2026-09-15-detekt-ktlint-kover-setup` — Integrate detekt, ktlint, and kotlinx-kover for code quality and coverage
- `2026-09-15-nav3-notes-navigator` — _(no title)_
- `2026-09-15-noteeditor-udf-link-search` — NoteEditor UDF fix — delegate link search to ViewModel
- `2026-09-15-projects-clean-architecture` — _(no title)_
- `2026-09-15-projects-nested-nav3` — Projects feature: nested Nav3 graph with ProjectsNavigator
- `2026-09-15-projects-settings-profile-udf-fixes` — PR 5 UDF fixes — ProjectDetail, ProjectPicker, AccountSettings, TagPicker
- `2026-09-15-task-detail-drafts-undo-fix` — TaskDetail drafts seed-from-task; TaskListScreen koinViewModel; undo snackbar wired
- `2026-09-15-task-editor-unification` — Task Editor State Restoration + UI Unification
- `2026-09-15-viewmodel-state-ownership` — ViewModel owns all domain state; Composable owns only routing and animation
- `2026-09-16-agenda-engine` — AgendaEngine: единый DSL-движок для list-вью задач (org-agenda style)
- `2026-09-16-agenda-mr3-saved-views-ui` — AgendaEngine MR3 — Saved Views UI: routes, reducer, events, top-bar entry
- `2026-09-16-agenda-mr4-saved-views-create-reorder` — _(no title)_
- `2026-09-16-agendaengine-post-mr1-nav-cleanup` — AgendaEngine MR1 post-cleanup: remove dead TasksRoute variants and deprecated AppDestination branches
- `2026-09-16-android-shell-fab-fix` — AndroidShellNav3 FAB — wire to real navigation
- `2026-09-16-calendar-feature` — _(no title)_
- `2026-09-16-calendar-post-merge-fixes` — _(no title)_
- `2026-09-16-desktop-menus-bugfixes` — Desktop menus: MenuBar AWT, right-click fix, agenda wiring
- `2026-09-16-nav3-desktop-in-memory-no-savedstate` — Nav3 Desktop uses in-memory NavBackStack; SavedStateConfiguration is Android-only
- `2026-09-16-nav3-feature-graph-extensions` — NotesNavGraph start parameter, TasksStartRoute.Detail, AppDestination additions
- `2026-09-16-nav3-post-migration-fixes` — Nav3 post-migration fixes — NotesNavGraph start, preview wrappers, FAB cleanup
- `2026-09-16-nav3-savedstate-serializers-required` — Nav3 SavedStateConfiguration must register all NavKey subtypes polymorphically
- `2026-09-16-nav3-settings-and-search-nested-graphs` — SettingsNavGraph and SearchNavGraph — single-route nested graphs
- `2026-09-16-nav3-type-asymmetry-adr` — Nav3 type asymmetry: rememberInMemoryNavBackStack returns NavBackStack<T>, Android rememberNavBackStack returns NavBackStack<NavKey>
- `2026-09-16-reactive-today-flow` — _(no title)_
- `2026-09-16-saved-agenda-views` — _(no title)_
- `2026-09-16-task-filter-set-variants` — TaskFilter and Selector set variants: ByTags/ByPriorities/ByRegexp SQL-backed filters
- `2026-09-16-task-list-filter-to-task-status` — Rename TaskListFilter → TaskStatus: domain-level completion status enum
- `2026-09-16-tasks-upcoming-screen` — _(no title)_
- `2026-09-17-agenda-mr5-pure-infra-ux-polish` — Agenda MR5: Pure Infrastructure + Selector Cohesion + UX Polish
- `2026-09-17-orgmode-architectural-lessons` — Org-mode architectural lessons: cascade, visitor, computed, super-agenda
- `2026-09-17-orgmode-functional-patterns` — Org-mode functional patterns: pure composition extensions
- `2026-09-17-selector-serializer-plain-kserializer` — SelectorSerializer: plain KSerializer instead of JsonContentPolymorphicSerializer
- `2026-09-17-vm-testability-audit` — _(no title)_
- `2026-09-18-agenda-nav-route-mapping` — MR7: AgendaNavContent shared route mapping
- `2026-09-18-agenda-selector-composer-dsl` — Agenda — selector composer DSL + universal section() overload
- `2026-09-18-agenda-ui-shared-adoption` — Agenda UI — shared BackTopAppBar, DiscardChangesDialog, SettingsRadioRow adoption
- `2026-09-18-backup-format` — Backup format: zip + MANIFEST + payload + SHA-256 checksum
- `2026-09-18-dialog-state-dsl` — MR8: DialogState<T> — state hoisting for dialog overlays
- `2026-09-18-dialog-state-migration-mr12` — TaskEditorSheet and ProjectDetailScreen migrate to DialogState<T>
- `2026-09-18-mcp-tool-catalog` — MCP tool catalog: 32 Koog SimpleTools registered via Koin
- `2026-09-18-mutation-result-handling` — _(no title)_
- `2026-09-18-no-pass-through-usecases` — Eliminate pass-through UseCases + machine enforcement via custom detekt rule
- `2026-09-18-picker-dsl-slots-mr11` — ListPickerDsl gains header/footer slots; T bound relaxed to Any?
- `2026-09-18-picker-sheet-dsl` — MR6: ListPickerSheet<T> + DSL for agenda pickers
- `2026-09-18-picker-sheet-migration-mr13` — TaskAiBottomSheet migrates to ListPickerSheet; KindSheet and BacklinksSheet deferred
- `2026-09-18-profile-subsystem` — Profile subsystem: isolation, ProfileRepository, ProfileAwareCurrentUser
- `2026-09-18-saved-view-factory` — SavedAgendaView — DraftState.markSaved(), inline copy() in VMs
- `2026-09-18-selector-serializer-registry` — SelectorSerializer — Map-based registry + typeTag extension
- `2026-09-18-settings-intent-with-mr10` — SettingsViewModel processIntent uses with(intent) stdlib receiver
- `2026-09-18-shared-ui-adoption-mr5` — MR5: ConfirmActionDialog + DragHandleRow shared components
- `2026-09-18-stable-json-config` — StableJson: centralized Kotlinx Serialization Json config
- `2026-09-18-sync-engine-architecture` — Sync engine: HLC + SyncEngine + ConflictResolver
- `2026-09-18-task-dependencies` — Task Dependencies (MR-1): schema, domain, UI badge, MCP tool
- `2026-09-18-testing-best-practices` — Testing best practices — Tier 1 infrastructure, canonical VM pattern, Fake over mocks
- `2026-09-18-version-catalog-cleanup` — Version catalog cleanup — kebab-case, bundles, single resolutionStrategy
- `2026-09-18-vm-intent-with-receiver` — MR9: with(intent) stdlib receiver pattern for VM intent dispatch
- `2026-09-18-vm-migration-scope-injection` — _(no title)_
- `2026-09-18-vm-scope-cancellation-oncleared` — _(no title)_
- `2026-09-21-auto-closeable-coroutine-scope` — _(no title)_
- `2026-09-21-kotlin-auto-closeable-vs-java-closeable` — _(no title)_
- `2026-09-21-out-of-scope-after-phase-5-5` — _(no title)_
- `2026-09-21-state-hoisting-audit` — _(no title)_
- `2026-09-21-user-scoped-repository` — _(no title)_
- `2026-09-22-bottomsheet-host-mr22` — BottomSheetHost centralises LaunchedEffect sheet state boilerplate
- `2026-09-22-contributor-process-rename-mr24` — SettingsContributor.apply renamed to process — clarity win
- `2026-09-22-dead-sheets-removal-mr23` — Delete orphaned sheets and picker VMs — 700 lines dead code removed
- `2026-09-23-ai-tools-currentuser-singleton` — _(no title)_

