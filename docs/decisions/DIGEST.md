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
- **Always** use `GenericUserScopedRepository<E, ID>` as the base for any new _(from `2026-09-21-generic-user-scoped-repository`)_
- **Never** add `ForCurrentUser` suffix to new method names — the type guarantees user-scope. _(from `2026-09-21-generic-user-scoped-repository`)_
- **Never** return `Result<Unit>` from `create` / `update` — return `Result<E>`. _(from `2026-09-21-generic-user-scoped-repository`)_
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

- **ADR `2026-09-16-agenda-engine.md` mandate completed** — TasksViewModel
- **All 5 `SyncViewModelTest` cases pass** under `:shared:jvmTest`. The pre-existing `DiGraphTest` failure (DataStore multi-instance on the same file) is unrelated to this PR.
- **Breaking change** for `NoteEditor`, `NotePreview`, and their tests — the `userId` argument is removed from `linkRepo.searchNotes(...)`, `linkRepo.searchTasks(...)`, and `linkRepo.getBacklinkNotes(...)` calls.
- **CI требует adb-устройство** для instrumentation — `SKIP_ADB=1` для пропуска
- **Detekt clean**: 14 false-positive warnings gone; baseline shrinks.
- **Five commits land together** because they all touch the same orbit
- **HlcFactory must be `open`**: The actual JVM class is final, preventing test subclassing. Changed to `open class`.
- **JVM target**: `SyncEngine` still exists, but `SyncWorkScheduler` is `NoopSyncWorkScheduler` (no-op). No background sync on desktop.
- **Negative**: Google Calendar API rate limits apply (handled by WorkManager back-off)
- **Negative**: `WRITE_CALENDAR` is a dangerous permission; users may be hesitant
- **Neutral:** `SyncRepositoryImpl` now requires a `CoroutineScope` injection for the follow-up launch. DI binding in `CoreDiModule` passes `AutoCloseableCoroutineScope(createBackgroundScope().coroutineContext)`.
- **No new auth-safety risk**: each tool still stamps the user-provided
- **Per-screen wiring is more verbose** — `TaskCardActions(onPin = { ... })`
- **Positive**: JVM tests cover all domain logic (mappers, diff, generation) via `FakeCalendarProvider`
- **Positive**: Users get free calendar notifications for tasks; multi-profile isolates calendar accounts
- **Positive:** Multiple rapid triggers (network + lifecycle + UI) now produce at most 2 sync runs (one immediate, one follow-up). No concurrent overlapping syncs.
- **Positive:** `FakeSyncRepository.syncOnce()` mirrors the same `isRunning()` guard — tests accurately reflect real behavior.
- **Positive:** `SyncOutcome.Skipped` is explicit — callers (ViewModel, AutoSync) can distinguish "sync didn't run" from "sync failed".
- **Pull `lastSuccessfulSyncAt`** is now updated, so the "Last synced" UI field stays accurate after a successful pull.
- **Smaller public surface**: `-880 / +120` lines net; 5 files deleted;
- **State-embedded error pattern is now consistent** across `Settings`, `Sync`, and (already) `Backup` (the latter uses a typed `SharedFlow<UiEvent>` for navigation).
- **SyncOutboxWorker is Android-only**: JVM has no `SyncOutboxWorker`.
- **Test fakes** (`FakeSyncRepository`, `FakeSyncApiClient`) gained the new methods to satisfy the interfaces.
- **Tests removed**: `SyncWorkSchedulerTest` was removed due to `advanceUntilIdle()` flakiness with `StateFlow` + `runTest`. The `FakeSyncWorkScheduler` and `FakeHlcFactory` utilities remain as compilable test doubles.
- **The pull DELETED handler now propagates sync server deletes** to local repositories. Soft-delete is honored when the entity supports it (Tag, Project); hard-delete repos ignore the soft semantics.
- **Tool APIs lose their `currentUser: ProfileAwareCurrentUser` parameter** — any
- **Two-tier snackbar pattern**: state-embedded `errorMessage` for transient errors, typed `SharedFlow<UiEvent>` for navigation. This is intentional — see `2026-09-21-state-embedded-errors.md`.
- **Type-safe UX expectations**: each screen's `TaskCardActions(...)`
- **UX honesty**: rendered buttons do what they advertise. No more
- **Unit tests gain an `init { ProfileAwareCurrentUser.setInstance(fake) }` setup
- **`AutoCloseableCoroutineScope.job` property** is now exposed; `testScope()` creates a child Job so test cleanup does not cancel the parent TestScope root.
- **`ProjectDetailActions` still uses the value-class + block pattern** —
- **`SyncViewModel` constructor exposes `vmScope`** for tests; cancelling `vmScope.job` is the documented way to stop infinite collectors in test scope cleanup (child Job, does not cancel test body).
- **`TaskCardActions` API is a breaking change** for any external consumer
- **`koinInject()` в Screen** требует Koin контекст — widget тесты обходят это через Robolectric + `createComposeRule` без Koin
- **`performTextClear`** не доступен в Robolectric — используется `performTextInput` напрямую
- **`testConnection()` requires `authRepository` + `api`** in `SyncRepositoryImpl`. `CoreDiModule` updated to pass both.
- **~14 изменённых файлов**: Screen.kt + testTag, VM constructors, DI module
- **~25 новых файлов**: 4 порта, 7 Page Objects, test infrastructure, integration tests
- 1 orphan VM deleted
- 2 UI state classes simplified (`data object` instead of `data class` with dead field)
- 2 screen preview functions updated
- 23 Tier-1 VMs lose their `onCleared()` override — the scope is now auto-cancelled via `addCloseable(scope)`.
- 25 VMs migrated across 3 MRs (MR-0 PoC + MR-1 simple VMs + MR-2 editor VMs + MR-3 complex VMs)
- 4 VMs no longer inject `ProfileAwareCurrentUser`
- 4 detekt rules promoted from warn to error in MR-4
- 4 test files updated (removed `fakeCurrentUser` args where no longer needed)
- 8 экранов мигрированы: Tasks, Notes, TaskDetail, TaskEditor, Projects, ProjectEditor, Chat, Archive
- AGENTS.md remains unchanged — its inline `adb`/`sqlite3` commands are still valid escape hatches.
- AI actions do **not** appear in `TaskEditorMenuBuilder` menu — they remain accessible only from `TaskAiBottomSheet` (accessed via FAB icon on `TaskDetail`).
- AI tools (11 Koog `SimpleTool` implementations) drop `currentUser` from
- Agenda always shows correct bucket labels across midnight.
- All 13 migrated VMs are now testable with `backgroundScope` injection
- All 593 existing tests continue to pass.
- All 6 repositories now extend `GenericUserScopedRepository`: Tasks, Notes, Projects, Tags, SavedAgendaViews, Profile.
- All 7 actions require AI to be configured — if no AI is available, `isActionAvailable()` returns false and `runAiAction()` is a no-op.
- All `FakeRepositories` updated to match
- All existing `NoteEntity` construction sites (`createWithContent`, `createNoteWithTitle`) updated to pass explicit `kind = NoteKind.Plain`.
- All four entity types can be synced (previously only `Task` had `SyncableEntity`)
- All migrations use `scope: AutoCloseableCoroutineScope` as last constructor parameter with secondary no-arg Koin constructor
- All notes screens now navigationally self-contained
- All skills now reference verified Koin 4.x API surface (jar inspection as the ground truth).
- Archive доступен с любого TaskDetailScreen через ⋮ menu
- Autosave вынесен из `delay()` в VM в отдельный port — теперь тестируем без `advanceTimeBy`
- Backlink display: `LinkedBacklinksCard` composable rendered via `extraSections` in `TaskEditorContent` model-based overload
- Backlinks queryable via SQL without HTML parsing
- Backup/restore roundtrip must include new fields (done via `TaskDto` update)
- Before using `singleOf`/`factoryOf`, deduplicate existing `single<X> { ... }` bindings for the same type — Koin throws `BeanOverrideException` on duplicates.
- Both Android and Desktop now use the same Nav3 architecture (multi-back-stack, `Navigator`, `NavDisplay`)
- Bulk-операции fail-fast при отсутствующих ID
- CI may later call `just tests::check` instead of `./check.sh` — the behavior is identical.
- Cannot filter by `name` in SQL without parsing JSON — acceptable; user-facing
- Code migration to Koin Annotations is explicitly **deferred** — see ADR `2026-09-22-koin-annotations-4x-skill-correction` for the analysis.
- Compose UI for setting these new fields is not yet built — that's MR-3's scope.
- Coverage target: 100% for `StatefulViewModel`, `MviViewModel`, `EventBus`, `StateStrategy`, `DraftState`
- DI bindings for canonical types: `singleOf(::Class)` for simple ctors (≤3 args, singleton scope), `factoryOf(::Class)` for per-injection scope. No `bind<Interface>()`.
- DI-граф упрощён: 5 factory → 1
- Dead Nav2 code removed from Android
- Dead dependency removed from `CalendarDeps` — DI graph is now consistent
- Deadline indicator rendering in `UpcomingBadges`.
- Deprecation warnings in `StatisticsScreen.kt` and `Clock.jvm.kt` remain until migration is completed.
- Detekt `ParameterNaming` rule suppressed in two places (`TagsRepository.kt:54,59`) because `create(item: Tag)` vs `create(item: E)` parameter naming follows the domain convention — not a bug.
- Developers should prefer `kotlinx.datetime.Instant` in new code.
- Domain models gain `serverVersion` and `hlc` fields — existing call sites unaffected (defaults)
- Domain/repo/data layers are fully isolated.
- Enqueue is best-effort — local changes are never rolled back due to sync failures
- Every `_events.emit(x)` in VM code becomes `_events.trySend(x).isSuccess` (fire-and-forget) or `_events.send(x)` (back-pressure when needed).
- Existing `AgendaDeps` binding must add `clock: Clock` parameter (no breaking change
- Existing `viewModelOf` calls in DI modules updated to `viewModel { Vm(...) }` form
- Existing tests for note features verified passing with the new schema.
- Expand-day-list (tap day in month view to show all tasks).
- Exposed `events: Flow<UiEvent>` becomes `_events.receiveAsFlow()`.
- FAB работает на desktop для всех табов (Tasks, Projects, Notes)
- Full filter panel with Project / Tags / Priority / Status.
- Future agents reading these skills will not waste time on `koin-annotations-compiler` setup that doesn't exist.
- Future developers understand which fields are stubbed vs. populated
- GenUI is purely client-side rendering; the LLM controls content. No server-side validation of the payload happens — trust comes from the authenticated Supabase session.
- Horizontal swipe between dates.
- If a Tier 1 interface gains a 2nd implementation, restore the interface — never compromise final-by-default by adding `open` to the existing concrete class.
- Internal links survive HTML round-trip (stored as `note://` / `task://` href)
- Keep `Stub` prefix for honest no-op documentation; drop only when the real implementation arrives.
- Link tap detection requires cursor placement (no visual link highlight tap) — acceptable tradeoff given library limitation
- Locale-aware `firstDayOfWeek` (hardcoded to Monday for MVP).
- Locale-aware first day of week.
- MR-2b (UI) will wire these fields into task create/edit screens
- Minor UX polish (loading placeholder, TTL) can be added opportunistically when the screen is touched.
- Month-grid cells are still hand-rolled (no kizitonwose `MonthView`). Week/Day remain unchanged.
- Nested nav3 graph keeps task-click navigation encapsulated.
- New component kinds require a new `UiNode` subtype + new renderer + `@SerialName` annotation + update to `BasicCatalog.systemPromptAppendix`. No schema migration needed.
- No migration needed for this fix.
- No more write storms from rapid task edits
- No new repository or DAO methods — `ByDateRange` filter reuses existing `watchTasks`.
- No repository contract overloads are needed for this interface (it has no non-Koin callers).
- No server-driven static content (Layer 4 from the article) — deferred until a concrete surface exists (e.g., in-app FAQ)
- None
- Per-collection 3-way merge (needs attachments/tags bidirectional)
- Per-feature events устранили конфликты имён (до: `ShowDialog` everywhere; после: `TasksUiEvent.AiResult`, `NotesUiEvent.SaveFailed`)
- Performance: one extra `StateFlow.distinctUntilChanged().flatMapLatest()` per
- Phase 8 (test rewrites) and Phase 9 (verification) follow from this migration
- Picker sheets визуально согласованы с остальными sheets (drag-handle, chrome)
- Play In-App Updates are Android-only; Desktop uses the `AppVersionGate` hard block plus manual download links
- Pre-existing test failures (`RussianDateFormatterTest`, `TaskCreateViewModelTest`,
- Profile migration via `duplicateForProfile` is explicit and testable.
- Pull events are now actually applied to the local database (not stub)
- Pull handler for `DELETED` events is a stub — entities are not soft-deleted from remote events yet
- Pure `UpcomingTaskUiMapper` and `UpcomingFirstDayOfWeek` are unit-testable
- Pure date arithmetic fully unit-tested with no Compose or Koin dependencies.
- Recipe names with `::` sub-namespacing (e.g. `android::db::schema`) do not work in `just 1.57.0` — flat names are used instead (e.g. `android::db-schema`).
- Robolectric widget tests в `androidHostTest` также **удалены** — все 5 классов
- Room schema unchanged (tables `task_tags` and `task_dependencies` already existed).
- RuStore / Galaxy Store support requires ~1 day of work when distribution to those stores is planned.
- Schema v7 requires `fallbackToDestructiveMigration` during development (dev strategy per skill)
- Self-loop dependency is rejected at `setDependencies()` call site; cycle detection (A→B→C→A) is deferred.
- Settings UI is NOT reactive to external changes (other VMs writing to `SettingsRepository`). Acceptable because the settings screen is typically visited once, changed, and closed.
- Settings screen can show specific recovery actions per failure type
- Simple schema, no migration complexity beyond bumping SCHEMA_VERSION.
- Single narrow Room query (`watchByDate`) reused for the new use case.
- Single-impl interface with no test fake is YAGNI — inline the concrete class as canonical.
- Slot-API (`CalendarContent` separate from `CalendarScreen`) enables preview without Koin.
- StableJson round-trip test verifies no data loss.
- Stale KDoc references `[OldInterface]` are dangling after inlining — always grep the whole repo and replace with `[CanonicalType]`.
- Test classes updated: `createVm()` now takes `scope = backgroundScope` via `TestScope.createVm()`
- Test factories for those VMs use `testScope(backgroundScope)` (or `testScope(this)` in `runTest`).
- Tests that construct `TaskEntity` directly must include all 6 new nullable parameters
- The 2 side-effects-in-combine anti-patterns remain in `TaskDetailViewModel`
- The 4 untested VMs (`TaskCreateViewModel`, `ProjectEditorViewModel`,
- The GenUI `whatsNewPayload` is entirely server-controlled content rendered via LLM; it is **not** validated against a schema beyond `A2uiParser` parsing. Trust comes from the authenticated Supabase session.
- The `WhatsNew` screen is the first production surface using GenUI, rendered at startup when a new `RemoteConfigSnapshot.whatsNewPayload` is present.
- The `koin-gradle-plugin` is already wired in `shared/build.gradle.kts` (commit `1eb272a`) but no annotations are in use. If a future agent wants to adopt annotations, they can reapply the pattern shown in commit `1eb272a`'s setup; the plugin doesn't break anything.
- The `pageCount = 240` is fixed at compile time. Users navigating beyond ±10 years from today
- The `scopeOverride` getter anti-pattern remains in 10 VMs (the canonical
- The default `viewModelScope` is still created by the ViewModel but is unused in Tier-1 VMs (negligible memory cost: one empty `SupervisorJob`).
- The four layers of the OTA strategy (gate, in-app update, flags, GenUI) are production-ready for Google Play distribution.
- The ⟳ icon on calendar task chips will now work once MR-3b (Click-to-create)
- Theme switching now correctly recomposes the calendar palette
- Throttling prevents SQLite spam from polling.
- Tier-2 VMs are unaffected.
- Two new top-level entries added: `justfile` and `.just/`.
- UI Automator тесты **удалены** (`UIAutomatorTest.kt`).
- UI can show "Syncing…" indicator during `Manual`/`ConfigChanged` syncs
- UI switching (MR3) requires adding `definition: AgendaDefinition` to `AgendaViewModel`
- User switch cancels in-flight evaluations cleanly.
- VM tests using `turbine` on `_events` need migration to `flow.test {}` from `kotlinx-coroutines-test`.
- ViewModels become thin read-through: `tasks = taskRepo.observeByFilter(filter)
- VtodoCache local-edit guard (needs two-way sync)
- Week navigation via swipe on `DaySwitcherRow`.
- Week-start locale handling is isolated and can be made configurable later.
- When converting a strategy class (`BackupFileNamer`-like), prefer `class(c: (T) -> R)` lambda strategy over `open class`. Composition beats inheritance for testability.
- `AgendaViewModel` and `ChatViewModel` excluded — use `combine + stateIn(WhileSubscribed)` pattern already validated; detekt skip by name
- `AgendaViewModel` binding is unchanged — does not consume saved views.
- `AiSettingsContributor` remains as the sole `SettingsContributor` implementation — used only for AI test/fetch ephemeral state.
- `AppDestination.Habits` → `AppDestination.Pomodoro`, `AppDestination.Calendar` → `AppDestination.Statistics`
- `AppDestination.TaskEditor` serialisation is backward compatible (extra field
- `AppNavHost.kt`, `AppNavigator.kt`, `DesktopShell.kt` (old Nav2 files) are deleted
- `AutoCloseable` ContentResolver cleanup
- `ByDateBucket` requires `today` in SQL query dispatch — the filter is not purely
- `CalendarDeps` is constructed in `CalendarDiModule` via `get<ReminderRepository>()`.
- `CalendarDeps` matches the `AgendaDeps` pattern (project convention)
- `CalendarNavigator` gets two `onExitGraph` callers: `openTask` and `openCreateTask`.
- `CalendarViewModel` now has an additional dependency — tests must inject
- `Clock.now()` should migrate to `kotlinx.datetime.Clock.System.now()` in a future PR.
- `Clock` injectable for deterministic tests via `runTest { advanceTimeBy(...) }`.
- `ConflictResolver` is a fun interface — can be injected separately if needed later
- `ContentStateMapper` — добавлен object с двумя методами
- `CreateTaskFromDraftUseCase` now takes a dependency on `DueDateOption` resolution
- `DeleteProjectUseCase` конструктор теперь `(projectRepo: ProjectsRepository, taskRepo: TaskRepository)` — DI модуль обновлён соответственно.
- `DependencyValidatorImplTest` (13 cases) covers self-loop, linear chains, branching chains, branching with merges, deep chains, missing nodes.
- `Dispatchers.Main.immediate` in secondary constructors causes `IllegalStateException` on JVM — tests must use the primary constructor with `backgroundScope`
- `ExtractActions` output is only displayed as formatted text in the event notification — actual task creation from extracted actions (pre-filling `TaskCreateSheet`) is deferred to a follow-up that integrates with `CreateTaskFromDraftUseCase`.
- `FakeAppDatabase` fakes updated for both new DAO methods
- `FakeNotesRepository` and `FakeNoteDao` updated with all 6 new methods for test coverage.
- `FakeProfileRepository` implements both new generic methods and deprecated legacy overloads for test compatibility.
- `FakeReminderDao` implements `watchRecurringTaskIds` for `FakeAppDatabase`.
- `FakeReminderRepository` implements `observeRecurringTaskIds` using in-memory filtering.
- `FakeRepositories.InMemoryTaskDao.listAllDependenciesForUser` stub implemented for tests.
- `InternalLinkRepositoryImpl` now fully owns the user resolution — consistent with `TagsRepository`, `TaskRepository`, etc.
- `LocalCalendarPalette` isolates calendar theming without breaking `MaterialTheme`.
- `NoteDao.getNotesLinkingToTask` — same pattern for `task://` scheme in notes
- `NoteEditorScreen` still accepts `onNavigateToNote` and `onNavigateToTask` for
- `NoteEditor` now has two AI entry points: `improveNote()` (legacy) and `runAiAction()` (new).
- `NotesNavGraph(navCallbacks)` is the single integration point with the outer graph
- `NotificationHost` заменил ~64 строки ручного glue кода на 8 экранах
- `ProfileAwareCurrentUser` moves **inside** repositories; the DI graph registers
- `ProjectEditorViewModel`, `TaskCreateViewModel`, `NotesListViewModel` in MR-2
- `ProjectsDiModule.kt` подключён через `domainModule` в `Modules.kt`.
- `ReminderRepository` is now a dependency of `CalendarViewModel` — tested via `FakeReminderRepository` in `CalendarViewModelTest`.
- `RemoteConfigPort` schema version must increment if `updatePriority` or any new field is added — existing clients silently fall back to defaults
- `RoomReminderRepository.upsert` now stamps ambient on insert — no more stale/missing userId.
- `RoomSavedAgendaViewsRepository.upsert` now stamps ambient on insert — consistent with other repos.
- `SavedAgendaViewModel` (via `SavedAgendaDeps`) no longer injects `ProfileAwareCurrentUser`.
- `ShowError` event removed from `CalendarUiEvent` (no longer needed after previous refactors).
- `SyncBootstrapper` remains `internal` — no feature code can bypass `SyncRepository`
- `SyncEngine` and `SyncRunner` remain `internal` — feature modules never touch them directly
- `SyncRepository` becomes a required dependency of all four repositories — circular DI risk monitored
- `SyncViewModel` is `ViewModel` (extends AndroidX `ViewModel`) — standard Koin `viewModel {}` DSL applies
- `SyncableEntity.toJson()` uses `StableJson` — no new serialization surface
- `TagsViewModel`, `AccountSettingsViewModel`, `StatisticsViewModel`, `ArchiveViewModel`, `AttachmentsViewModel`, `AiUsageViewModel` in MR-1
- `Task.tags` and `Task.dependsOn` are now correctly populated in all list views (`observeAll`, `observeByFilter`, `observeByDate`, `observeSubtasks`).
- `TaskDao.getBacklinkTasks` — LIKE query on the JSON column: `outgoing_links LIKE '%task://' || :taskId || '%'`
- `TaskDetail.RunAiAction` is a one-shot action returning via `_events` SharedFlow.
- `TaskDetailDeps.linkRepo: InternalLinkRepository? = null` — nullable so existing tests pass without a fake link repo
- `TaskDetailScreen` stays as a read-only viewer until a future PR consolidates
- `TaskDetailViewModelTest` removed 5 broken `StubRefineTaskUseCase` etc. class definitions — tests use nullable defaults instead.
- `TaskDetailViewModel` no longer injects `ProfileAwareCurrentUser`.
- `TaskDetailViewModel`, `SavedAgendaViewModel`, `CalendarViewModel`, `SearchViewModel`, `ProfileSwitcherViewModel`, `AuthViewModel`, `BackupViewModel` in MR-3
- `TaskDraft` serialization format changes — old drafts opened after upgrade will
- `TaskEditorDeps.clock` is also dead (the file's own KDoc flags it for deletion alongside `TaskEditorViewModel`)
- `TaskEditorReducerTest` must add test cases for new intents.
- `TaskEditorViewModelTest` and `TaskEditorIntegrationTest` must add edit-mode scenarios.
- `TaskEditorViewModel` constructor signature unchanged; DI registration unchanged.
- `TaskEntity` has new `outgoingLinks: String = "[]"` field with default
- `TaskEntity` is now 6 columns wider — acceptable storage cost
- `TaskFilter` remains untouched — Search feature is unaffected.
- `TaskMutationsUseCase` — новый класс, но он по сущиности — grouping, не новая логика
- `TaskRepository.delete()` now calls `taskDao.softDelete()` directly instead of delegating to `softDelete()`
- `TasksStartRoute.Create` now accepts `initialDueDate` — backward compatible since it's nullable.
- `TreeVisitor` remains unchanged for other use cases (non-cycle-detection tree traversal).
- `Upcoming` tab position (3rd) shifts the bottom bar order — snapshot tests
- `appearanceModule()` was removed (no `AppearanceContributor` needed — `SettingsViewModel` handles appearance intents directly).
- `applyRoute` in `TasksViewModel` is dead code — zero callers confirmed; deleted.
- `core/ui/state/StateFlowExt.kt::updateState` removed after all migrations complete (MR-4)
- `deadlineDate` badge is rendered as a red flag + date for tasks due on the selected date.
- `deadlineDate` badge rendering in month grid.
- `delay(until-midnight)` means the flow never completes — collectors must be scoped
- `endTime` / `accentColor` — blocked on Room migration for `startAt`/`endAt`/`accentColor` fields in `Task`
- `expect object Clock` rename to `PlatformClock` — deferred until a broader cleanup window
- `flatMapLatest` re-evaluates all tasks on every date change (necessary trade-off;
- `getOrThrow()` removed from 5 VM sites; replaced with `fireAndForget` + channel emit.
- `isActive` is a behavioral change from previous inline logic — tested thoroughly.
- `isBlocked` badge will appear on task cards when dependencies are unfinished.
- `isRecurring` is always `false` in `CalendarTaskUi` — requires per-task
- `just` must be installed (`just 1.57.0` is present in this environment).
- `observeByFilter` now contains the filter-logic inline (was delegated to `watchTasks`)
- `scopeOverride` добавлен в `ProjectsViewModel`
- `serverVersion`/`hlc` survive the full round-trip: domain → entity → DAO → DB → entity → domain
- `single<Interface>(::Impl)` does NOT work — Koin can't resolve `Impl`'s constructor params from DI when called through `single<T>(::Impl)`. Use `single { Impl(get(), ...) }` for interface bindings.
- `singleOf` fails for classes with function-type constructor parameters (Koin tries to resolve `Function1` from DI) — use explicit lambda in those cases.
- `startAt`/`endAt`/`allDay` fields don't exist in the `Task` domain model
- `startAt`/`endAt`/`allDay`/`recurrence` in `Task` (Room migration).
- `weight` modifier requires careful structuring inside `Row { Column(weight) }`.
- cTag/ETag two-way diff (needs CalDAV server)
- detekt: 0 new findings | jvmTest: green
- detekt: 60 warnings (pre-existing, non-blocking) | jvmTest: green.
- kizitonwose remains available for future exploration if AndroidX/JB compatibility is resolved.
- ~12 MRs total, ~6–9 weeks.
- Все ViewModel'ы с `scopeOverride` — консистентны в тестах
- Все fake-репозитории теперь имеют консистентное поведение seed()/add()/clear()
- Все импорты в 30+ файлах обновлены на новые FQN (`.domain.model`, `.domain.port`, `.domain.usecase`, `.data`, `.presentation.state`, `.presentation.viewmodel`).
- Для UI-тестов на реальном устройстве: Kaspresso или `contentDescription` + `By.desc()`.
- Оставшиеся `androidHostTest`: только `AppNavigatorTest` (nav contract, без Espresso),
- ✅ Multi-profile isolation
- ✅ No `SCHEDULE_EXACT_ALARM` permission
- ✅ Phase transitions гарантированы даже после process death
- ✅ Reminders работают после reboot (catch-up)
- ✅ Smooth countdown UI через 1 Hz ticker
- ✅ Stale-text fix: fresh task title в notifications
- ✅ `phaseStartedAtEpochMs` в State (single source of truth) для точного recompute
- ❌ Catch-up capped at 20 to avoid notification storm
- ❌ Requires `RECEIVE_BOOT_COMPLETED` permission
- ❌ Нужен catch-up логики (recompute from `Clock.now()`)

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

### `analytics`

- Crash reporting (`CrashReporting` interface from Tasks.org) is intentionally separate — will be addressed in a dedicated ADR when Sentry/Crashlytics is evaluated.
- No real analytics SDK is connected. `logEvent` calls in the codebase are safe no-ops.
- When a real SDK is added: create `RealAnalytics : Analytics` (wrapping Amplitude/Mixpanel/PostHog), change Koin binding from `NoopAnalytics` to `RealAnalytics`, remove this ADR's "no-op" status.
- `ProfileAwareAnalytics` decorator (adding `profile_id` to every event) will be added when the real SDK is connected — at that point we know whether the SDK handles profile identity natively.

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

### `auth`

- `IdToken` parses but does **not** validate signatures. Signature verification requires the JWKS endpoint and is provider-specific — out of scope for this ADR.
- `RedirectState.encode` is useful for OAuth2 authorization code flow with state parameter — the nonce prevents CSRF. The redirect state encoding (Base64URL of JSON) matches the OIDC `state` parameter convention.
- `SecureStoragePort` (already in the codebase) is the right place to store `OAuthTokenData.serialize()` — `SecureStoragePort.write(KEY_OAUTH_TOKEN, tokenData.serialize())`. This is noted for the follow-up ADR.
- `core/auth/SupabaseAuthRepository` remains a stub. A follow-up ADR will define the connection contract.

### `backup`

- **Negative**: Attachments are not deduplicated across backups — two backups with the same file will contain two copies
- **Negative**: No incremental backup — every export is a full snapshot
- **Positive**: Single portable file with integrity check (SHA-256)
- **Positive**: Version fields allow future migrations (FORMAT_VERSION / SCHEMA_VERSION)
- **Positive**: `ignoreUnknownKeys` provides graceful forward compatibility

### `billing`

- Real Google Play Billing integration will require: `GooglePlaySubscriptionProvider : SubscriptionProvider`, adding `com.android.billingclient:billing` dependency, and wiring `BillingClient` in `androidMain`. This is the next ADR in this area.
- `NoopSubscriptionProvider` is bound as `single<SubscriptionProvider>`. All purchase-related UI shows "Pro: false".
- `awaitVerification()` returning `true` by default (Noop) means the app assumes the user is verified when no payment provider is connected. When Google Play is connected, it will query Google Play's licensing API.
- `purchaseStateFor` is a factory function (not a class) — it recomputes on each call from the live `subscription` flow. If performance becomes an issue (excessive recomputation), it can be replaced with a `DerivedPurchaseState : PurchaseState` class that caches the result.

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

### `dao`

- Fake implementations in `FakeProjectDao` add `mutateForUser` that guards by `userId` before mutating, returning 0 if the entity belongs to a different user.
- Old non-`*ForUser` DAO methods remain in the interface for binary compatibility but are no longer called by production code.

### `datastore`

- Custom property delegates not introduced
- Navigation 3 changes deferred (article inaccessible)
- No changes to `SyncPrefs.kt` — handled separately in MR-1 (`refactor/syncprefs-suspend-api`)
- No new API or delegate layer introduced
- `NullableStringPref` and `EnumPref` intentionally left unchanged

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
- Both rules are in **warning mode** — they do not fail the build
- Promotion to error: after baseline is reduced in a follow-up PR
- `:desktopApp:detekt` / `:desktopApp:detektFormat` / `:desktopApp:detektBaseline`
- `:desktopApp:koverXmlReport` / `:desktopApp:koverHtmlReport`
- `:shared:detekt` / `:shared:detektFormat` / `:shared:detektBaseline`
- `:shared:koverXmlReport` / `:shared:koverHtmlReport`
- `NoRealDelayInTestRule` fires on all 44 pre-existing `delay(N>1)` occurrences
- `NoViewModelScopeInProductionRule` fires on 7 pre-existing `viewModelScope.launch` occurrences

### `di`

- **Breaking:** `coreDomainModule()` удалён; заменён на `domainModule()` (includes everything). Test files обновлены.
- **Existing tests:** `DiGraphTest`, `JvmAiDiGraphTest`, `AppSmokeTest` обновлены и проходят.
- **New file count:** 8 новых файлов (7 модулей + decision).

### `dsl`

- `ListPickerItem<T>.leading` slot already covers the `RowScope` customization need; no `trailing` slot added (not needed yet).
- `ListPickerScope<T>.header { }` and `footer { }` are the canonical way to add custom content above/below the item list.
- `T : Any?` means callers can use `null` as a key — filter at call site if needed.

### `git`

- Developers in worktrees get fast pre-commit feedback (compile only); full test suite runs in CI or via `just tcheck`
- Hooks work identically in main checkout and worktrees
- No duplicate hook scripts — one canonical copy in `.githooks/`
- `just setup-hooks` configures all worktrees in one command

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
- No redaction layer added. Access tokens and profile IDs are not written to logs today. When they are, a `RedactingLogWriter` decorator must be added before this layer.
- On Android, `logDirectory()` lazily resolves `Context` from Koin. The `Context` is available by the time the first log entry is written (after Koin starts), so this is safe.
- On JVM, `ColorizedWriter` uses `\u001B` ANSI escapes. Older Windows terminals (pre-10) will print escape sequences literally. `NO_COLOR` env var is respected.
- The `FileLogWriter` instance is **not** exposed via Koin — it is created inside `initLogging` and lives as a global. This is intentional: Kermit's `Logger` holds it, and we don't want DI to manage it.
- `BuildConfig.DEBUG` requires `buildConfig = true` in `androidApp/build.gradle.kts`. No BuildConfig is available in `shared` jvm target.
- `DebugInfo` uses `version: String` and `isDebug: Boolean` passed from the app entry point (Android: `BuildConfig`, JVM: Gradle property). No global `BuildConfig` in shared.
- `LogExporter` **is** a Koin singleton (`single<LogExporter>`) so screens can `koinInject<LogExporter>()` for a "Send logs" button.
- `RefineTaskTool.kt:34-38` has identical try and catch branches (copy-paste bug) — not fixed in this PR.
- `initLogging` must be called **before** `startKoin` (unchanged from previous behavior).

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
- The `created` field is not currently consumed by any caller — it is there for future observability / logging use cases.
- The downstream `ToolRegistrar` and tools still run inside `runBlocking { koogTool.execute(args) }` per call — coroutine scope inside the request handler, no change.
- Three new unit test files in `shared/commonTest` for the read tools.
- ZCode подключается через `mcpServers.singularity-todo` в настройках
- `./gradlew :mcp-server:test` now includes a regression test (`McpServerEndToEndTest.server_blocks_until_stdin_closes`) that asserts `process.isAlive` after 3s of empty stdin. If anyone removes the blocking primitive, this test fails.
- `ErrorMapper.kt` маппит `McpToolError` в `CallToolResult` или бросает `McpException`
- `McpToolError.kt` в `mcp-server/src/main/kotlin/com/singularity/todo/mcp/errors/`
- `ProfileBootstrapper.run()` now returns a value; all 3 call sites (`Main.kt`, any Android/Desktop bootstrappers) must handle the result or ignore it.
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

- All new helpers are `internal` except `NoteAiController` (used in DI) and `NoteContentMapper`
- Backlinks are now shown and functional
- Caller must provide `MutableStateFlow<String>` and inject `InternalLinkRepository` and `ProfileAwareCurrentUser` — slightly more boilerplate at call site
- Clear UX: notes list → tap note → read → optionally edit
- Cross-screen state (e.g. "did the user just save a note") must flow through navigation callbacks, not shared VM state
- DI in `NotesDiModule` uses explicit `viewModel { NoteEditor(...) }` lambda — never
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
- `FakeNotesRepository` is `final` — do not inherit from it in tests. Override individual methods
- `FakeNotesRepository` и `FakeNoteDao` обновлены同步.
- `NoteDao.updateContent` сигнатура изменилась: добавлен параметр `html: String`.
- `NoteEditorScreen` (UI) is unaffected — public API (`editorState`, `savedPulse`, `events`,
- `NotePreview` must observe the note via `repo.watchNote()` — requires a Flow subscription
- `NoteSaver.fail()` is the only error path — all save failures emit `NotesUiEvent.SaveFailed`
- `NotesListViewModel` now requires `IdGenerator` as a third constructor parameter
- `NotesRepository.createWithContent` и `updateContent` сигнатуры изменились: добавлен параметр `bodyHtml: String`.
- `NotesRoute` now injects `NotesListViewModel` via `koinViewModel()`, `NoteEditor` and `NotePreview` are injected via their respective screen composables
- `OutgoingLinksExtractor` regex now uses two separate `Regex` instances (one for `note://`,
- `SavedPulse` (a `SharedFlow<Unit>`) is the only pulse channel. Phase 1 placeholder;
- `core/ui/components/` is now free of feature-domain imports
- `getBacklinkNotes` now returns real results — backlinks in `NotePreview` and `InternalLinkPickerSheet` will work
- Все существующие тесты проходят — никаких изменений в тестовых вызовах не потребовалось (jvmTest зелёный).
- При первом открытии старой заметки (без `bodyHtml`) — форматирование может отличаться от исходного (round-trip через markdown). Это accepted trade-off для legacy data.

### `pomodoro`

- Tick-based tests (fake clock advancing real `delay()`) are unreliable in unit tests. All `AndroidPomodoroTimer` tests use `skip()` to drive phase transitions without depending on virtual time.
- `AlarmContract` is an `object` (no `Companion`). Static-style access (`AlarmContract.EXTRA_PHASE`) is direct, not via `.Companion`.
- `androidHostTest` (Robolectric) must be used for any tests that require Android runtime or Android-specific types. `jvmTest` cannot access `androidMain`.
- `factory { AndroidPomodoroTimer(...) }` in Koin is a **memory leak** for ViewModels — must use `factory<PomodoroTimer> { AndroidPomodoroTimer(...) }` or `viewModel { }` for actual ViewModels. `AndroidPomodoroTimer` is not a ViewModel, so `factory` is correct here.
- `kotlinx.datetime.Clock` is aliased as `com.singularity.todo.core.platform.Clock` (expect/actual). Use `kotlinx.datetime.Clock` in new code; the alias is deprecated.

### `preview`

- All new screens MUST follow the `PublicScreen` / `PrivateContent` naming pattern
- Do NOT introduce `koinViewModel()` inside any `@Preview` — CI/preview harness does not start Koin
- FakeRepositories live in `commonMain/test/fakes/` (not `commonTest`) so `commonMain` previews can access them
- `@Preview` composables are always `private` and call the `*Content` variant with manually constructed VMs

### `profile`

- **Breaking for any future external callers** that relied on the deprecated overloads — they must migrate to `create(Profile(...))`.
- **Negative**: Compound `scopedUserId` is a string manipulation — a proper `ScopedUserId` value class would be cleaner (future work)
- **Negative**: Profile deletion cascades to all that profile's data — no soft-delete for profiles
- **Positive**: Clean separation of auth (user) vs data namespace (profile)
- **Positive**: MCP server can route to any profile via `--profile=<id>`
- Any future code that needs `ProfileAwareCurrentUser` must receive it via constructor injection.
- The `FakeProfileAwareCurrentUser()` factory function in tests remains — it creates a real `ProfileAwareCurrentUser` instance using `FakeAuthRepository` + `FakeProfileRepository`.
- The `Profile` factory is verbose for tests; consider adding a test-specific builder or factory if the pattern repeats.
- `FakeProfileRepository` is now fully consistent with the real `ProfileRepository` interface.
- `FakeTaskRepository` retains a backward-compat `FakeProfileAwareCurrentUser()` fallback for its `currentUser` property when no explicit user is provided, so existing tests continue to compile.

### `project-detail`

- **`createTask`** must go through `CreateTaskUseCase`, not direct `taskRepo.create`.
- Screen owns `activeSheet` routing state; VM only receives routing intents.

### `projects`

- All `@Preview` composables use `ProjectDetailContent(vm, ...)` with `FakeRepositories` — no preview crashes
- Architecture: screens own routing state (`sheetState`), VMs own domain logic, navigation callbacks are passed as parameters
- `ProjectDetailScreen` is fully functional: quick-add creates tasks, parent picker works, Remind/Attach/DueDate/Children sheets open, task click navigates to `TaskDetailScreen`
- `ProjectPickerSheet` is reactive — newly created projects appear without reopening the sheet

### `recurring`

- MCP server `create_task`/`update_task` tools need schema updates (deferred to post-MR-10 issue).
- Migration 18→19 adds `recurrence_rule TEXT NOT NULL DEFAULT NULL`.
- `CompleteRecurringTaskUseCaseTest` should be added before final merge (MR-13).
- `FakeTaskRepository` could gain `tags`/`dependsOn` population from a fake extras query, but is not blocking.
- `Task.recurrence: RecurrenceSpec?` — must be propagated through `CreateTaskInput`, `TaskDomain.createInput`, `TaskDomain.buildTask`, `CreateTaskUseCase`, `CreateTaskFromDraftUseCase`.
- `TaskDetailViewModel` now depends on `CompleteRecurringTaskUseCase` in `TaskDetailDeps`.
- `lastDayOfMonth` refactor is optional cleanup.

### `refactor`

- Routing intents can originate from sheets (not just from the screen). Pattern: `NavigateToChild` routing intent → `ProjectDetailActions.onNavigateToChild` → `nav.openDetail()`.
- `CurrentProjectContent` is the correct pattern for bundling 16+ nullable callbacks for sheet hosts — keep as-is until >20 fields.
- `showSheet` should never be added back to `*Callbacks` data classes when the content composable owns the sheet state internally.

### `reminders`

- **Neutral:** Pre-existing recurring reminders without `lastFiredAt` will fire immediately on next poll after upgrade (no worse than before).
- **Neutral:** Requires bump from schema v14 → v15 (`AppDatabase.version = 15`, `Migration14To15` registered).
- **Neutral:** Requires bump from schema v14 → v15 (`last_fired_at` column).
- **Neutral:** `NotificationPort.scheduleAt` returns `Unit` — no programmatic success detection; failure is only detectable via thrown exception (caught as of fix #4).
- **No new test coverage** for the concurrent-mutex, suspend-stop, or loop try/catch paths (documented as coverage gap).
- **No new test coverage** for the guard logic (Tier 3c is documented as a coverage gap per Round 1).
- **Positive:** Concurrent polls are serialized — no race conditions between test-triggered and background polls.
- **Positive:** Correct user scoping — reminders are always attributed to the signed-in user.
- **Positive:** Duplicate recurring reminder fires are eliminated on app restart or after device wake.
- **Positive:** Duplicate recurring reminder fires are prevented on device restart (via `lastFiredAt` guard).
- **Positive:** Graceful shutdown via `stop()` — tests can now stop the scheduler cleanly.
- **Positive:** No more leaked coroutine scopes — the scheduler now respects lifecycle boundaries.
- **Positive:** The `last_fired_at` column is available for future analytics (e.g., "last reminded at").
- **Positive:** `scheduleAt` failures are gracefully handled — a single failed notification does not crash the loop.

### `repository`

- **PR 3** (VM cleanup) is unblocked: all repository `create` methods now stamp ambient `userId`, so VMs no longer need to pass it. `currentUser` can be dropped from remaining VMs (`TaskDetailViewModel`, `NotePreview`, `NoteEditor`, `ProjectsViewModel`, `NotesListViewModel`, `ProjectEditorViewModel`, `AttachmentsViewModel`, `SavedAgendaViewModel`, `ProjectDetailViewModel`).
- **When** a second entity acquires free-text search — extract `Searchable<E>` mixin
- **When** adding a cross-cutting repository helper (batch op, transactional wrap) —
- AI tools (`CreateTaskTool`, `CreateProjectTool`) still pass `userId` in their input classes — those are separate from this PR's scope (the AI tool MCP adapter work).
- Fakes in `test/fakes/FakeRepositories.kt` simplify: one constructor parameter
- If a future use-case requires a true `SourceOfTruth` abstraction (e.g., migrating part of the data to a KV-store or SqlDelight), the decision to adopt Store or a custom `LocalStore<T>` interface can be revisited.
- The old `UserScopedRepository<T, ID>` typealias is removed in the cleanup commit
- Write pipeline is now formalised in `GenericUserScopedRepository` KDoc.
- `AttachmentRepository.addUrlAttachment` and `saveFileAttachment` already resolved ambient `userId` internally — no change needed.
- `ChecklistEditorViewModel` is constructed with `taskId` via Koin `parametersOf`. Any existing call site that used `bindToTask()` is broken by design — that method no longer exists. Verify no production call site calls `bindToTask()` before merging.
- `ChecklistRepository` is the single source of truth for checklist mutations. All consumers (VMs, AI tools) must use `addItem` / `toggleItem` / `upsert` / `delete` on the repository.
- `GenerateChecklistUseCase` (AI feature, `feature/ai/use_cases/`) is a separate class and is not affected by this deletion.
- `InternalLinkRepositoryImpl` methods (`searchNotes`, `searchTasks`, `getBacklinkNotes`) already resolved ambient internally — no change needed.
- `PomodoroRepository` has 0 production call sites. It is a candidate for deletion
- `ProfileAwareCurrentUser` remains in `feature/profile/` and is still injected into repositories (`TaskRepositoryImpl`, `RoomNotesRepository`, `AttachmentRepository`, `ReminderRepository`, `ProjectsRepositoryImpl`, `InternalLinkRepositoryImpl`, `RoomSavedAgendaViewsRepository`). It is **not** injected into presentation-layer VMs except where actually read.
- `RoomNotesRepository.createWithContent` and `createNoteWithTitle` already resolved ambient `userId` internally — no change needed.
- `RoomReminderRepository.deleteByTask` already resolved ambient internally — no change needed.
- `RoomSavedAgendaViewsRepository.upsert` delegates to `create`/`update`; since `SavedAgendaView` is constructed by the VM with `userId` already on it (from the domain model), stamping happens inside the repository. The Create branch was already handled by the existing `userId` on the entity — no structural change needed.
- `SyncEngine` and `SyncOutbox` are unaffected — they remain the canonical sync pipeline.
- `TaskRepositoryImpl.create` and `ProjectsRepositoryImpl.create` now enforce user scoping. Any caller passing a mismatched `userId` will get a loud `IllegalStateException`.
- `TaskRepositoryImpl` does **not** yet stamp `userId` on `create` — that is PR 2 (Repository infrastructure). Until that lands, callers must still pass `userId`-stamped entities to `TaskRepository.create`.
- `assertCanWrite` is the single entry point for cross-user write guards across all user-scoped repositories.

### `search`

- Query AST lives in `feature/search/query/` — `Condition.kt`, `Query.kt`, `QueryInterval.kt`, `QueryTokenizer.kt`, `QueryParser.kt`, `SingularityQueryParser.kt`, `ResolvedSearchQuery.kt`, `SearchQueryResolver.kt`, `SimpleFilter.kt`, `SimpleFilterBuilder.kt`, `SimpleFilterMapper.kt`.
- Room schema version increments by 1 per feature migration; `Migration15To16` is the current head.
- `Not(Condition)` is the only negation representation — never add a `not: Boolean` flag to any condition data class.
- `SavedSearch.queryString` is the raw user input — never try to normalize/format it on save.
- `SearchQueryResolver.addPostFilter` must check `negationDepth > 0` to determine polarity — never call `negationDepth--` without a matching `negationDepth++`.
- `SearchUseCase` accepts both `Query` (structured) and `String` (raw, parsed internally) — the string overload is for backwards compatibility only; new code should pass `Query`.
- `SearchViewModel` always uses the 7-arg constructor for production; the 6-arg secondary constructor creates its own `AutoCloseableCoroutineScope`.
- `SimpleFilter.states` is `null` for "no filter"; never default to `setOf(Active)` in new code.
- `SimpleFilterMapper.toQuery` produces `condition = null` (not `HasText("")`) for empty filters.

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

- 5 repositories (`Notifications`, `WorkSchedule`, `Greeting`, `DefaultAgendaView`, appearance) shrank by ~35% each (~90 → ~55 lines).
- 6 marker interfaces added: `AppearanceContributor`, `AiContributor`,
- All changes are additive; no existing behavior is removed.
- Backup confirm dialogs prevent accidental data loss.
- Compile-time safety: renaming `AiSettingsContributor` to `AiSettingsContributorImpl` now
- Debounce reduces SecureStorage/DataStore writes by ~90% during text input.
- No `simpleName` strings anywhere in the ViewModel — eliminated ~30 lines of accessor code
- No changes to the public repository interface — `Flow<T>` and `suspend fun set` signatures are identical.
- Test suite (`SettingsViewModelTest`) updated to work with debounce bypass in test mode.
- The `aiEphemeral` field in `SettingsUiState.Content` is kept for future migrations; do not rely on it as the primary read path for AI ephemeral state today.
- When adding new AI-related state, add it to `SettingsSection.Ai` directly; do not introduce a parallel `EphemeralState.Ai` field.
- `AiSettingsContributor` stays as a 1-argument class — `observe()` returns `Flow<SettingsSection.Ai>` (no `stateIn` wrapper) to avoid `CoroutineScope` requirements that break `DiGraphTest`.
- `AiSettingsStore.observe()` is an 8-flow `combine`: 4 persisted flows + 4 ephemeral `MutableStateFlow`s.
- `AiSettingsStore` не нуждается в рефакторинге — AI setters на месте.
- `App.kt` инжектит `SettingsRepository` через Koin — это нормально, Koin доступен в Common startup.
- `IntPref` range support (e.g., `intPref(..., range = 0..23)`) enforces min/max at write time, consistent with `coerceIn` in `Flow.map`.
- `PrefSpec` as internal holder avoids Kotlin inline class boxing — the inline class wrapper is zero-cost at call sites.
- `ProfileAwareCurrentUser` не нуждается в рефакторинге — `userId` на месте.
- `SettingsDataStoreMigration` продолжает работать — companion object не тронут.
- `SettingsNavRail` Column теперь содержит Box с CircleShape — Layout инлайн, не refactor.
- `SettingsSection.Ai` always contains all AI state (persisted + ephemeral) — never split.
- `SettingsViewModel.reloadAiSection()` always updates **both** the `ai.*` fields on the `SettingsSection.Ai` object **and** the top-level flat fields (`aiTestResult`, `aiModels`, `isFetchingAiModels`, `fetchAiModelsError`) in `SettingsUiState.Content`.
- `SurfaceController.apply(event)` is **not** changed — separate scope, separate task.
- `TextGenPort.listModels` — добавлен в интерфейс, реализация в `KoogAgentService` и `FakeTextGen`.
- `filterIsInstance<XxxContributor>()` on a `Set<SettingsContributor<*, *>>` works because the
- `process(intent)` is the canonical name for contributor intent dispatch.
- Все 6 sub-screens имеют `verticalScroll` — контент больше не обрезается.
- Мёртвый код убран: ни один внешний звонок не сломался.

### `sync`

- **Deferred**: Whether to add `AppError.Auth(code: Int, body: String?)` — use string interpolation for now.
- **Negative**: New `SyncRepository` interface adds an indirection. Mitigated by `FakeSyncRepository` for VM tests.
- **Negative**: No server-side push; conflict resolution is last-write-wins with checksum fast-reject (not full CRDT)
- **Negative**: Schema migration 15→16 required. AutoMigration handles it automatically.
- **Negative**: `SyncEngine` constructor grows from 7 to 8 parameters. Mitigated by Koin named parameters at call site.
- **Negative**: `SyncEngine` now has two responsibilities (push/pull logic + `syncOnce()` composition) — mitigated by `SyncRunner` extracting the orchestration.
- **Negative**: `pull()` is not yet implemented — remote changes do not appear on the device
- **Positive**: Persistent `lastLsn` enables incremental pull — server sends only new events.
- **Positive**: Simple, predictable push model; HLC provides causal ordering; outbox is durable (Room)
- **Positive**: Supabase credentials never touch Room — `SecureStoragePort` is hardware-backed on both platforms.
- **Positive**: UI can now observe sync state; `SyncRepository` gives a clean module boundary; `Result<T>` matches project conventions; Orgzly UX patterns adopted.
- **Positive**: `DataStoreSyncPrefs` follows the exact same pattern as `DataStoreSessionStore` — consistent with project.
- **Positive**: `autoSyncEnabled` and `scheduledInterval` survive app restarts.
- **Positive**: `enqueue()` wiring in repositories becomes testable via `FakeSyncRepository`.

### `tags`

- Filter UI (MR-11) can filter by tag group (future): `TaskFilter.ByTagGroups(Set<TagGroupId>)`.
- Tag group deletion is a write operation that cascades to untag member tags — requires `TagDao.bulkUpdateGroupId()` (future improvement, currently a TODO in delete handler).
- `TagsRepository.observeAll()` now returns tags with `groupId` populated — UI can display group badges.
- `Task.tags` in list views now needs `EffectiveTagsResolver` to show inherited tags — `TaskExtras` helper (MR-0) loads tags efficiently in batch.

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

### `tech-debt`

- All three changes are additive-renames only
- MR-2.2 note: `expect object Clock` remains for backward compatibility; production code should use `kotlinx.datetime.Clock` directly
- No breaking changes to public API

### `technical-debt`

- Fixed: `RussianDateFormatter.kt`, `MiniCalendarPanel.kt`, `TimeGridView.kt`, `MonthGridView.kt`, `CalendarEventMapper.kt`
- Not fixed (requires `Int` → `Month` migration in DI): `CalendarScreen.kt:29` — `anchorDate.monthNumber` passed as `Int` to `parametersOf(year, monthNumber, mode)`. `CalendarDiModule` accepts `Int`, not `Month`. Fix requires changing DI parameter type from `Int` to `Month` and updating all call sites.
- Not fixed: `CalendarScreen.kt` — same DI issue as above.
- Partially fixed: `CalendarEventMapper.kt` `nextDay()` function now uses `monthNumber` (local variable, not property) to avoid ambiguity.
- `ChatViewModelTest` — 3 tests
- `NoteEditorTest` — 7 tests
- `SavedAgendaViewModelTest` — 11 tests

### `testing`

- **Fake repo returns empty by default** — widget tests that check `LazyColumn` with `testTag` will fail when repo is empty (state = `Empty`). Test the `EmptyState` text instead, or seed data via `fakeNotesRepo.seed(note)`.
- **JVM args for JDK 21+** — add `--add-opens=java.base/jdk.internal.access=ALL-UNNAMED` to `gradle.properties` (`org.gradle.jvmargs`) AND to `shared/build.gradle.kts` via `afterEvaluate` + `tasks.withType<Test>()` for the test worker process.
- **Robolectric 4.17-beta-4** — `4.16` maxes at SDK 36; `compileSdk=37` requires the beta. The beta is already cached.
- **Use `UserId` from `feature.tasks`** — it's defined in `Ids.kt` there, imported explicitly.
- **`Clock` must be passed to `CreateTaskUseCase` / `UpdateTaskUseCase`** — use the singleton `Clock` from `core.platform`.
- **`Session.Anonymous()` requires `UserId`** — always pass `UserId.anonymous` or `UserId.fromString("...")`.
- **`waitForIdle()` is a method, not a function** — do NOT import it. Call `composeRule.waitForIdle()` directly.
- 3 preview functions per component (default, empty, edge case) — consistent with `2026-09-06-compose-previews` skill.
- All 593 existing tests continue to pass
- All future tests that boot a platform (Robolectric, Android instrumented, screenshot) must be
- All link-related string literals in the notes feature must use `LinkSchemes.NOTE_PREFIX` / `LinkSchemes.TASK_PREFIX`. No raw `"note://"` in `feature/notes/`.
- All new tests that need to verify failure paths use `XxxOverride = Result.failure(...)` on the appropriate fake.
- All unit tests follow AAA structure, use `sut` naming, and use fakes for state assertions
- Baseline images stored in `shared/src/commonTest/resources/roborazzi/`.
- Do not use Turbine `awaitItem()` for VM state testing; use `MutableStateFlow.value` assertions
- Every future PR touching UI components must run snapshot tests and update baselines when changes are intentional.
- Forked test JVMs now get 2 GB heap instead of ~512 MB default
- Heap dumps will appear in `<module>/build/test-heap-dumps/` after an OOM
- If OOM recurs on CI, the trade-off to consider is reducing
- MockK is used **only** for verifying outgoing command interactions (DB writes, analytics, network).
- No breaking change — these methods were never called externally.
- No test flakiness observed in 10× repeated fast test runs
- Parallel execution is dynamic — Jupiter adjusts thread pool based on CPU cores
- Peak RSS on a 4-worker CI run: ~8 GB (acceptable on 7 GB runner with swap)
- Pre-existing failures (9 tests) remain unchanged
- SharedFlow emission tests in this project always use `launch { flow.take(1).collect { ... } }` on `this@runTest`, not `backgroundScope`, with `runCurrent()` before the suspending call that emits.
- Test parallelization: Jupiter method-level concurrency enabled
- `:shared:jvmTest` fast tests now run in ~7s (was ~90s with `delay`)
- `:shared:jvmTest` fast tests: ~7s wall-clock (was ~90s sequential with real `delay`)
- `AndroidPomodoroTimerTest` is excluded from the default suite, reducing fast-suite heap pressure
- `Clock` import may become unused in `FakeRepositories.kt` if not used elsewhere.
- `FakeTaskRepository` is now ~30 lines shorter.
- `McpServerEndToEndTest` runs on default `./gradlew :mcp-server:test`
- `SCHEME_FACTORIES` is the extension point for new link kinds in `OutgoingLinksExtractor` — add one entry, not one regex + one branch.

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

### `versioning`

- Kill switches (`modelFlags`, `mcpToolFlags`) are **compile-time safe**: `KnownModels` and `McpToolRegistry` accept `RemoteConfigSnapshot` as a parameter, never `RemoteConfigPort` directly. This enables testing with `FakeRemoteConfigSnapshot`.
- Schema versions (`protocolVersion`, `schemaVersion`) are **int**, not string. `"v0.9"` in GenUI prompts is descriptive only; it is never parsed or compared.
- `RemoteConfigPort.snapshot()` is the **only** write path to the local Room cache. No other code writes `remote_config_cache` directly.
- `RemoteConfigPort` is a stub in MR-2: `SyncApiClient.getRemoteConfig()` returns null, so `RemoteConfigRepositoryImpl` always falls back to defaults. Full backend implementation is deferred.
- `RemoteConfigSnapshot.validate()` is called **every time** a snapshot is deserialized from cache or network. Never skip validation.
- `SyncBootstrapper`, `A2uiParser`, and `BackupDomain` use **consistent** error-severity: `warn` for version mismatch, `error` for schema parse failure. This is reflected in log output and sync status.
- `appVersion()` is the **only** place that reads the running app's version. All other code — logging, backup manifest, About dialog — uses it. Version literals `"0.1.0"` must not be added anywhere else.
- `core/sync/RemoteConfig` (Supabase credentials) is never to be confused with `RemoteConfigPort` (runtime policy). The former is a repository; the latter is a remote-gateway port.

### `viewmodel`

- No cast needed — `scope` is `AutoCloseableCoroutineScope` at both call site and definition
- Tests use `testScope(backgroundScope)` to wrap the test dispatcher
- `AutoCloseableCoroutineScope` companion factory creates a scope backed by `createBackgroundScope()`
- `appearanceContributor = null` is explicit — the default is intentional, not accidental

### `vm`

- **No pure reducer needed** — `ProjectDetailViewModel` is write-through like `TaskDetailViewModel`
- **`NavigateToTasks`** is no longer a VM event — screen handles it as routing
- **`ProjectDetailIntent`** is the canonical list of all project mutations — adding a new field mutation = one `Domain` case
- **`ProjectDetailUiEvent`** now has only 2 cases: `NavigateBack` (post-delete) and `ShowError`
- **`createTask` and `moveTaskToProject`** remain in VM (require repository writes)
- **`toggleArchive`** no longer emits `Saved` — `lastEditedAt` drives "Saved X ago" UI via the `mutate{}` helper
- 4 PRs instead of 1 (review overhead).
- All new VMs in this codebase should prefer `with(intent) { ... }` for data class intents with ≥2 properties.
- All other VMs use plain `MutableStateFlow`
- Dead code removed — `TaskDetailMode` and the `Attachment` intent branch would have required maintenance with zero benefit.
- Double-tap on Save creates exactly one entity (compareAndSet enforces single-writer).
- Internal note/task links now navigate correctly.
- Pre-work required 3-4 hours before any visible feature change.
- Recomposition skip — `@Stable` on 11 holders.
- Single-property intents may remain as `intent.X` for simplicity — the overhead is minimal.
- Testability — `NotePreviewTest`, `LoginFormStateTest`, `OverlayStateTest`,
- This pattern does NOT require a custom DSL marker or annotation; stdlib `with` is sufficient.
- Unified mental model for state holders.
- `AgendaViewModel`, `SavedAgendaListViewModel`, `ProjectsViewModel`, `CalendarViewModel` remain as pure read-through with `combine+stateIn` — this is intentional and permitted
- `Dispatchers.Default` fixes flaky VM tests.
- `NoteSaver` API contract is precise: it sends, never manages the channel lifecycle.
- `OverlayState` (Phase 1) is not yet saved across process death — acceptable
- `TaskDetailViewModel` typed combine is readable without `@Suppress` annotations.
- `scopeOverride` usage anywhere in a ViewModel signals an audit is needed

## Open / Deferred

_2 entries need attention._

- `2026-09-08-instant-migration` — **deferred** — Instant Type Migration: kotlin.time.Instant → kotlinx.datetime.Instant
- `2026-09-25-remaining-test-debt` — **open** — Remaining Test Debt — post JUnit/suite-acceleration audit

## Recently superseded

- `2026-09-26-production-readiness-findings` — Production Readiness Findings — 2026-09-26
- `2026-09-23-test-standards-enforcement` — Test Standards — Enforcement, Gap Filling, and Architecture Cleanup
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
- `2026-09-21-generic-user-scoped-repository` — repository, architecture, kotlin, kmp
- `2026-09-21-kotlin-auto-closeable-vs-java-closeable` — _untagged_
- `2026-09-21-out-of-scope-after-phase-5-5` — _untagged_
- `2026-09-21-profile-repository-migration` — _untagged_
- `2026-09-21-state-hoisting-audit` — vm, compose, state-hoisting, refactor
- `2026-09-21-state-hoisting-p3-dispatchers-overlay` — _untagged_
- `2026-09-21-tier1-interface-cleanup` — _untagged_
- `2026-09-21-user-scoped-repository` — _untagged_
- `2026-09-22-alarmmanager-reminders` — _untagged_
- `2026-09-22-bottomsheet-host-mr22` — ui-components, sheet-state, compose
- `2026-09-22-calendar-click-to-create` — _untagged_
- `2026-09-22-calendar-horizontal-pager` — _untagged_
- `2026-09-22-calendar-reminder-repo` — _untagged_
- `2026-09-22-calendar-sync-tasks-org-patterns` — _untagged_
- `2026-09-22-canonical-vm-scope-pattern` — viewmodel, architecture, coroutines, koin
- `2026-09-22-checklist-usecase-delete-and-dead-deps-cleanup` — repository, checklist, currentuser, koin, refactor
- `2026-09-22-contributor-process-rename-mr24` — settings, naming, kotlin-idioms
- `2026-09-22-dead-sheets-removal-mr23` — cleanup, dead-code
- `2026-09-22-explicit-overload-removal` — _untagged_
- `2026-09-22-fake-overrides-link-schemes-savedpulse-tests` — testing, architecture, notes
- `2026-09-22-flat-settings-api-removal` — settings, architecture, cleanup
- `2026-09-22-koin-annotations-4x-skill-correction` — _untagged_
- `2026-09-22-marker-contributor-interfaces` — settings, architecture, kotlin, type-system
- `2026-09-22-noteeditor-refactor` — notes, architecture, refactor
- `2026-09-22-outbox-workmanager-refactor` — _untagged_
- `2026-09-22-pomodoro-hybrid-timer` — _untagged_
- `2026-09-22-preference-wrappers` — settings, architecture, datastore, kotlin
- `2026-09-22-reminder-lastfiredat-schema` — reminders, database, scheduler
- `2026-09-22-reminder-scheduler-critical-fixes` — reminders, scheduler, concurrency, coroutines, di
- `2026-09-22-repository-user-stamping-and-usercase-currentuser-removal` — repository, currentuser, userid, draft-store, use-case, koin
- `2026-09-22-settings-section-ai-ephemeral-fields` — settings, architecture, state-management
- `2026-09-22-system-calendar-sync` — _untagged_
- `2026-09-22-task-rich-dates` — _untagged_
- `2026-09-22-task-ui-rich-dates` — _untagged_
- `2026-09-23-ai-tools-currentuser-singleton` — _untagged_
- `2026-09-23-analytics-port` — analytics, observability, gdpr
- `2026-09-23-billing-abstractions` — billing, subscriptions, monetization
- `2026-09-23-dead-currentuser-and-orphan-vm-cleanup` — _untagged_
- `2026-09-23-deprecation-tech-debt` — technical-debt, deprecation, tests
- `2026-09-23-file-logging-and-exporter` — logging, observability, android, jvm
- `2026-09-23-genui-server-driven-ui` — _untagged_
- `2026-09-23-ksp-missing-type-main-branch` — _untagged_
- `2026-09-23-mcp-bootstrap-result-pattern` — mcp, profile, concurrency, bootstrap
- `2026-09-23-oauth-pkce-refresh-helpers` — auth, oauth, security, pkce
- `2026-09-23-ota-deferred-items` — _untagged_
- `2026-09-23-ota-update-strategy` — _untagged_
- `2026-09-23-pomodoro-alarm-refactor` — pomodoro, alarms, architecture, testability, koin
- `2026-09-23-profile-deprecated-alias-removal` — profile, api, cleanup
- `2026-09-23-recurring-parser-review-notes` — recurring, tasks, review
- `2026-09-23-recurring-tasks-dsl` — recurring, tasks, dsl
- `2026-09-23-reminder-savedagenda-repo-stamping` — _untagged_
- `2026-09-23-search-query-language` — search, query-ast, room, viewmodel, dsl
- `2026-09-23-sync-pull-application` — _untagged_
- `2026-09-23-sync-pull-handlers-and-ui` — _untagged_
- `2026-09-23-sync-scheduling-abstraction` — sync, architecture, core, scheduling, remote-config, persistence
- `2026-09-23-sync-state-model` — sync, architecture, core, state, ui
- `2026-09-23-sync-tier3-fixes` — _untagged_
- `2026-09-23-tag-groups-inheritance` — tags, tag-groups, inheritance
- `2026-09-23-task-dependencies-completion` — _untagged_
- `2026-09-23-tech-debt-audit` — tech-debt, audit, vm, database, tests
- `2026-09-23-versioning-and-runtime-gates` — versioning, schema, sync, genui, backup, security, kmp
- `2026-09-23-vm-event-guard-cleanup` — vm, concurrency, cleanup
- `2026-09-24-combine-statein-policy` — vm, architecture, epic2, policy
- `2026-09-24-dao-userid-guards` — dao, auth, security, userid
- `2026-09-24-datastore-catch-fix-together` — datastore, resilience, error-handling
- `2026-09-24-deferred-backlog` — deferred, backlog, epic3
- `2026-09-24-pr1-tech-debt-audit-resolution` — tech-debt, audit, pr-1
- `2026-09-24-pre-existing-issues` — techdebt, testing, di, epic1
- `2026-09-24-profile-aware-current-user-di` — profile, di, koin, ai-tools
- `2026-09-24-sync-debouncer-and-tasks-comparison` — _untagged_
- `2026-09-24-taskeditor-refactor-remaining-debt` — refactor, taskeditor, projectdetail, sheets
- `2026-09-24-tech-debt-mini-prs` — tech-debt, deprecation, android
- `2026-09-25-ai-action-registry-design` — _untagged_
- `2026-09-25-cycle-detector-design` — _untagged_
- `2026-09-25-detekt-test-rules` — detekt, testing, lint, epic2
- `2026-09-25-fake-legacy-cleanup` — testing, fakes, cleanup
- `2026-09-25-git-hooks-worktree-isolation` — git, hooks, worktree, devx, epic2
- `2026-09-25-local-mvi-framework` — _untagged_
- `2026-09-25-no-store-library-local-first-pattern` — repository, local-first, sync, architecture
- `2026-09-25-note-ai-multi-op-design` — _(no title)_
- `2026-09-25-note-templates-daily-design` — _(no title)_
- `2026-09-25-remaining-test-debt` — Remaining Test Debt — post JUnit/suite-acceleration audit
- `2026-09-25-task-backlinks-design` — _(no title)_
- `2026-09-25-taskcard-slot-api-and-orphan-vm-cleanup` — _(no title)_
- `2026-09-25-test-jvm-heap-default` — Test JVM heap defaults and HeapDumpOnOutOfMemoryError
- `2026-09-25-test-parallelization` — Test Parallelization — Jupiter Concurrency + Thread Safety
- `2026-09-25-test-standards-comprehensive` — Test Standards Comprehensive — JUnit Jupiter, Virtual Time, Fast/Slow Split
- `2026-09-25-test-suite-tag-defaults` — Test suite tag defaults and Khorikov testing principles
- `2026-09-26-internal-link-repo-currentuser` — Drop userId from InternalLinkRepository

