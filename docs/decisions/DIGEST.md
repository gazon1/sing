# Decision Log Digest

Auto-generated from `docs/decisions/`. Run `./scripts/refresh-decisions-digest.sh` to rebuild.

## Critical

- **Always** keep the auto-fill rule in `OpenAiConfig.resolveBaseUrl(storedUrl, provider)` only. The UI delegates to it — changing both is a bug. _(from `2026-09-05-llm-provider-settings`)_
- `AiTestResult` is part of `SettingsUiState.Content.aiTestResult` with default `Idle`. **Never** make it a `UiEvent`. _(from `2026-09-05-llm-provider-settings`)_
- `FakeTextGen` is parametrised: `(success, failureMessage, trackGenerateCalls)`. **Always** use `trackGenerateCalls = true` in VM tests that assert the no-key short-circuit. _(from `2026-09-05-llm-provider-settings`)_
- **Always** extend `LAYER_ALLOWLIST` in `ArchitectureTest` together with a debt entry _(from `2026-09-26-konsist-architecture-tests`)_
- **Always** keep `Selector` a pure predicate; badge/transform logic belongs to `SelectorTransformer` attached to `AgendaDefinition`, not embedded in evaluator. _(from `2026-09-17-orgmode-architectural-lessons`)_
- **Always** keep new Koog imports inside the five sanctioned packages. _(from `2026-09-26-konsist-architecture-tests`)_
- **Always** make new sealed hierarchies for DSL predicates (filter, selector, transformer, predicate) simultaneously `@Serializable` AND pure predicate — no parallel DTOs. _(from `2026-09-17-orgmode-functional-patterns`)_
- **Always** return empty collection (not `Result.Left(Empty)`) for no-match cases in pure-domain pipelines like `AgendaEvaluator`. `Result.Left` is reserved for validation/business-rule failures only. _(from `2026-09-17-orgmode-functional-patterns`)_
- **Always** route derived predicates (`isOverdue`, `isReady`, `isBlocked`) through `feature/tasks/domain/logic/Computed.kt`. Never duplicate inline in `AgendaEvaluator`, `Selector`, or `TaskDomain.matchesFilter`. _(from `2026-09-17-orgmode-architectural-lessons`)_
- **Always** route derived values (`effectivePriority`, `effectiveColor`, `effectiveTags`) through `core/tree/Cascade.kt` `cascadeUp` — never as stored fields on entities. _(from `2026-09-17-orgmode-functional-patterns`)_
- **Always** use `core/tree/Cascade.kt` `cascadeUp` for inheritance queries; never walk ancestors ad-hoc with `find { it.parentId == ... }` chains. _(from `2026-09-17-orgmode-architectural-lessons`)_
- **Always** use `core/tree/TreeVisitor.kt` `traverseDepthFirst` for recursive tree operations; never write recursive `.filter { … }.map { … }` chains. _(from `2026-09-17-orgmode-architectural-lessons`)_
- **Always** use `data class.copy()` for Task/Project/Tag/AgendaDefinition mutations in pure-domain code — never add setters. Mutations go through `TaskRepository.update(...)`. _(from `2026-09-17-orgmode-functional-patterns`)_
- **Never** add `*Blocking` methods to `*Repository` interfaces. _(from `2026-09-26-konsist-architecture-tests`)_
- **Never** import `*RepositoryImpl` outside `core/di` — DI composition root only. _(from `2026-09-26-konsist-architecture-tests`)_
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
- **Never** rely on an exception escaping a `catchTo`/`emitError` block to _(from `2026-09-30-mvi-error-path-contract`)_
- **Always** mark every `NavKey` subtype that may appear in a stack as `@Serializable`. Without it, there is no `.serializer()` to pass to `subclass(...)`. _(from `2026-09-16-nav3-savedstate-serializers-required`)_
- **Always** provide a `serializersModule` that calls `polymorphic(NavKey::class) { subclass(...) }` for every concrete route type in the stack. _(from `2026-09-16-nav3-savedstate-serializers-required`)_
- **Never** write `SavedStateConfiguration { }` for any `rememberNavBackStack` call — the empty body silently falls back to `DEFAULT.serializersModule` and breaks the polymorphism contract. _(from `2026-09-16-nav3-savedstate-serializers-required`)_
- **Always** read entity state from the write-through `_latest<Entity>` cache, never from `state.value` snapshot in mutation methods. _(from `2026-09-09-projectdetail-write-through-fix`)_
- **Always** update `_latest<Entity>` before any async operation that reads it. _(from `2026-09-09-projectdetail-write-through-fix`)_
- **Never** emit `Saved` events for debounced inline edits — update `_lastEditedAt` only. _(from `2026-09-09-projectdetail-write-through-fix`)_
- **Always** use `GenericUserScopedRepository<E, ID>` as the base for any new _(from `2026-09-21-generic-user-scoped-repository`)_
- **Always** — DAO mutations carry `userId` and return the affected count; a `0` is a _(from `2026-09-27-write-layer-soundness`)_
- **Never** add `ForCurrentUser` suffix to new method names — the type guarantees user-scope. _(from `2026-09-21-generic-user-scoped-repository`)_
- **Never** return `Result<Unit>` from `create` / `update` — return `Result<E>`. _(from `2026-09-21-generic-user-scoped-repository`)_
- **Never** — leave an unscoped DAO mutation next to a scoped one; delete the old variant. _(from `2026-09-27-write-layer-soundness`)_
- **Never** — treat `assertCanWrite` as the sole ownership check for id-only methods. _(from `2026-09-27-write-layer-soundness`)_
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
- **Always** host a state-asserting ViewModel on the foreground `TestScope` _(from `2026-09-30-testscope-background-work-semantics`)_
- **Always** inject `CoroutineDispatcher` into fakes that own a `CoroutineScope`. Use `StandardTestDispatcher(testScheduler)` in tests. _(from `2026-09-25-testable-vm-dispatcher-clock`)_
- **Always** keep `FakeClock` and `FakeIdGenerator` in `commonMain/test/fakes/` _(from `2026-09-18-testing-best-practices`)_
- **Always** keep `TestVmContext` and `runAndWait` in `jvmTest/test/helpers/` _(from `2026-09-18-testing-best-practices`)_
- **Always** parameterize repeating tests with `@ParameterizedTest @EnumSource/@MethodSource` when the SUT is the same. _(from `2026-09-25-test-helper-stack`)_
- **Always** use `RecordingHttpClient` (or similar scripted-response pattern) for HTTP verification in tests without MockK. _(from `2026-09-25-testable-vm-dispatcher-clock`)_
- **Always** use `get<Clock>()` in DI modules and UI code instead of `Clock.System.now()`. _(from `2026-09-25-testable-vm-dispatcher-clock`)_
- **Always** use `kotest.assertions.shouldBe / shouldNotThrowAny / shouldContain` as matchers alongside `kotlin.test.*` assertions in new tests. _(from `2026-09-25-test-helper-stack`)_
- **Always** use `kotlinx.coroutines.test.runTest` for VM tests _(from `2026-09-18-testing-best-practices`)_
- **Always** use `testTask()`, `testNote()`, `testProject()` for fixtures _(from `2026-09-18-testing-best-practices`)_
- **Never** add Burst or kotlin-faker to the project. _(from `2026-09-25-test-helper-stack`)_
- **Never** expose public mutable properties on domain objects — use `private set` + mutation methods. _(from `2026-09-25-testable-vm-dispatcher-clock`)_
- **Never** hardcode `Dispatchers.Default` or `Dispatchers.Unconfined` in production ViewModels. _(from `2026-09-25-testable-vm-dispatcher-clock`)_
- **Never** trust a rejection test that has no passing sibling on the same _(from `2026-09-30-testscope-background-work-semantics`)_
- **Never** use `Clock.System.now()` — inject `Clock` and use `FakeClock` in tests _(from `2026-09-18-testing-best-practices`)_
- **Never** use `UUID.randomUUID()` or `nextId()` directly — inject `IdGenerator` and use `SequenceIdGenerator` in tests _(from `2026-09-18-testing-best-practices`)_
- **Never** use `assertTrue(true)` placeholders — delete or write real assertions _(from `2026-09-18-testing-best-practices`)_
- **Never** use `delay(N)` in tests — use `scope.advanceUntilIdle()` or `runAndWait { }` _(from `2026-09-18-testing-best-practices`)_
- **Never** use `java.io.File` in `commonMain` — use `FileSystem` port or pass paths as `String`. _(from `2026-09-25-testable-vm-dispatcher-clock`)_
- **Never** use `org.junit.*` — use `kotlin.test.*` _(from `2026-09-18-testing-best-practices`)_
- **Never** use `runBlocking` in production code — use `MutableStateFlow` + `scope.launch { }` _(from `2026-09-18-testing-best-practices`)_
- **Never** use `stateIn` in VMs — use `MutableStateFlow` for testability _(from `2026-09-18-testing-best-practices`)_
- **Never** use `viewModelScope` in VM code — inject `CoroutineScope` instead _(from `2026-09-18-testing-best-practices`)_
- **Never** use real `delay()` in test files — use `advanceUntilIdle()` after PR-3.1. _(from `2026-09-25-test-helper-stack`)_
- **Never** write inline test doubles — add to `test/fakes/` _(from `2026-09-18-testing-best-practices`)_
- **Never** migrate a sheet to `ListPickerSheet` if it uses `FilterChip`, `ListItem` with rich content, or custom item layouts. _(from `2026-09-18-picker-sheet-migration-mr13`)_
- **Never** use `mutableStateOf<X?>` for sheet/dialog state — always use `rememberDialogState()`. _(from `2026-09-18-dialog-state-migration-mr12`)_

## Per-tag

### `_untagged_`

- **ADR `2026-09-16-agenda-engine.md` mandate completed** — TasksViewModel
- **All 5 `SyncViewModelTest` cases pass** under `:shared:jvmTest`. The pre-existing `DiGraphTest` failure (DataStore multi-instance on the same file) is unrelated to this PR.
- **Breaking change** for `NoteEditor`, `NotePreview`, and their tests — the `userId` argument is removed from `linkRepo.searchNotes(...)`, `linkRepo.searchTasks(...)`, and `linkRepo.getBacklinkNotes(...)` calls.
- **CI не затронут**: `.github/workflows/ci.yml` эмулятор не поднимает.
- **CI требует adb-устройство** для instrumentation — `SKIP_ADB=1` для пропуска
- **Detekt clean**: 14 false-positive warnings gone; baseline shrinks.
- **Duplicate snackbar on settings export.** `exportSettingsSnapshot` emitted both
- **Every ViewModel in the project is now on `MviViewModel` or `DraftMviViewModel`.**
- **Five commits land together** because they all touch the same orbit
- **HlcFactory must be `open`**: The actual JVM class is final, preventing test subclassing. Changed to `open class`.
- _... and 350 more items_

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
- _... and 40 more items_

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
- _... and 65 more items_

### `auth`

- `IdToken` parses but does **not** validate signatures. Signature verification requires the JWKS endpoint and is provider-specific — out of scope for this ADR.
- `RedirectState.encode` is useful for OAuth2 authorization code flow with state parameter — the nonce prevents CSRF. The redirect state encoding (Base64URL of JSON) matches the OIDC `state` parameter convention.
- `SecureStoragePort` (already in the codebase) is the right place to store `OAuthTokenData.serialize()` — `SecureStoragePort.write(KEY_OAUTH_TOKEN, tokenData.serialize())`. This is noted for the follow-up ADR.
- `core/auth/SupabaseAuthRepository` remains a stub. A follow-up ADR will define the connection contract.

### `backup`

- **A no-op lambda is now ambiguous by construction.** A reader cannot tell a
- **Negative**: Attachments are not deduplicated across backups — two backups with the same file will contain two copies
- **Negative**: No incremental backup — every export is a full snapshot
- **Positive**: Single portable file with integrity check (SHA-256)
- **Positive**: Version fields allow future migrations (FORMAT_VERSION / SCHEMA_VERSION)
- **Positive**: `ignoreUnknownKeys` provides graceful forward compatibility
- `AppFilePicker` now has call sites, so the seam is exercised and can no longer
- `Archive` vs `Delete` are the same operation in this codebase —
- `SharePort` returns `false` on a platform that cannot share. No caller currently

### `billing`

- Real Google Play Billing integration will require: `GooglePlaySubscriptionProvider : SubscriptionProvider`, adding `com.android.billingclient:billing` dependency, and wiring `BillingClient` in `androidMain`. This is the next ADR in this area.
- `NoopSubscriptionProvider` is bound as `single<SubscriptionProvider>`. All purchase-related UI shows "Pro: false".
- `awaitVerification()` returning `true` by default (Noop) means the app assumes the user is verified when no payment provider is connected. When Google Play is connected, it will query Google Play's licensing API.
- `purchaseStateFor` is a factory function (not a class) — it recomputes on each call from the live `subscription` flow. If performance becomes an issue (excessive recomputation), it can be replaced with a `DerivedPurchaseState : PurchaseState` class that caches the result.

### `calendar`

- Dead dependency removed from `CalendarDeps` — DI graph is now consistent
- Expand-day-list (tap day in month view to show all tasks).
- Full filter panel with Project / Tags / Priority / Status.
- Future developers understand which fields are stubbed vs. populated
- Horizontal swipe between dates.
- If calendar filtering is built later, the affordances go back — as real controls
- Locale-aware first day of week.
- Nested nav3 graph keeps task-click navigation encapsulated.
- No new repository or DAO methods — `ByDateRange` filter reuses existing `watchTasks`.
- None
- _... and 17 more items_

### `cleanup`

- When a real use case appears (e.g. TaskDetailViewModel needs a project picker), implement it from scratch using `ListPickerSheet` + `DialogState` + caller-side state hoisting — not by resurrecting the deleted code.

### `clock`

- Anything still passing a bare `Clock` as a value will silently bind
- Injecting a `Clock` still gives full test control — `FakeClock` implements
- `Clock.jvm.kt` / `Clock.android.kt` shrink to the single `actual val
- `core/platform/Clock.kt` no longer declares anything `expect`/`actual`; the

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

- **Open: undated tasks do not render in the agenda.** A task written through the
- 23 of 28 context menu items are wired to `actions.onDismiss()` — future iterations wire the
- 30 tests across seven flow suites plus the boot checkpoint, all green.
- Agenda context menu: Pin, Delete, Expand, Complete are functional.
- Desktop chrome is a 240 dp left rail, VSCode/JetBrains-style. Width is explicit, not derived from drawer measurements.
- Every `NavDestination` entry has an `icon` field. When adding a new entry, pick an icon from `androidx.compose.material.icons.Filled` or `Icons.AutoMirrored.Filled`.
- Hover delay (300ms) on submenus via `LaunchedEffect(isHovered) { delay(300); onOpenSubMenu() }`.
- Menu bar appears in OS-native window chrome on all three desktop platforms.
- Not covered: Roborazzi snapshots on desktop, navigation lifecycle beyond what
- Right-click context menu works again on task rows in the agenda.
- _... and 12 more items_

### `detekt`

- **Configuration cache**: detekt 1.23.x and kover 0.9.9 are both CC-compatible. Verified by running `./gradlew --configuration-cache :shared:detekt`.
- **New Gradle tasks added**:
- **`.editorconfig` may rewrap existing code** on first `detektFormat` run. Expect a large diff; consider a separate "format" commit before merging.
- **`ignoreFailures = true`** means violations are reported but never block builds. To enforce violations: set `ignoreFailures = false` in both `shared/build.gradle.kts` and `desktopApp/build.gradle.kts` once baselines are settled. **TODO: tracked in issue tracker — promote after baselines are clean (est. post-format PR).**
- **detekt 2.0.0-alpha.3 vs Kotlin 2.3.21**: this version was chosen because stable 1.23.8 was compiled against Kotlin 2.0.21 and throws "detekt was compiled with Kotlin 2.0.21 but is currently running with 2.3.21". Upgrade to stable 2.x once released.
- A comment in `Clock.kt` and `CoreDiModule.kt` should reference `NoDirectClockSystemRule` so that developers moving code are warned.
- A future improvement: define `Clock.System` usage in a single `core/platform/Clock.kt` internal object and exempt only that object's direct references, rather than exempting the entire file.
- All 7 custom rule sets now produce findings when violations exist
- Both rules are in **warning mode** — they do not fail the build
- If `todayAt` or the DI binding moves to a different file, this rule must be updated alongside it. Treat it as a linked refactoring pair.
- _... and 13 more items_

### `di`

- **Breaking:** `coreDomainModule()` удалён; заменён на `domainModule()` (includes everything). Test files обновлены.
- **Existing tests:** `DiGraphTest`, `JvmAiDiGraphTest`, `AppSmokeTest` обновлены и проходят.
- **New file count:** 8 новых файлов (7 модулей + decision).

### `dsl`

- `ListPickerItem<T>.leading` slot already covers the `RowScope` customization need; no `trailing` slot added (not needed yet).
- `ListPickerScope<T>.header { }` and `footer { }` are the canonical way to add custom content above/below the item list.
- `T : Any?` means callers can use `null` as a key — filter at call site if needed.

### `emulator`

- After any crash: `pkill -f qemu-system-x86_64` and remove
- Aliveness is determined by the `/proc` exe scan and `adb devices`; `pgrep -f`
- Launch the emulator only via the recipe in `singularity-todo-emulator-launch`;
- The `android-emulator` MCP plugin's `android_start_emulator` times out on
- The `show_ime_with_hard_keyboard 0` mitigation is kept — it reduces crash rate
- The real fix is upstream: a corrected `TextureResize` in a future Android
- `SKIP_INSTALL=1` is honoured on recovery too, so a fast re-run stays fast; a
- `ensure-emulator.sh` is now the documented way to get a device; the
- `just tm` is no longer all-or-nothing across a device death; at most one flow

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
- _... and 18 more items_

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

- **Order of `REDACTION_PATTERNS` is load-bearing.** Adding a new pattern
- All new `catch` blocks in ViewModels, repositories, and use cases should inject `Logger` and call `log.e(e) { "..." }` or use `runCatchingLogged`.
- Android cannot flush on termination — `Application.onTerminate()` is never
- Existing silent `catch (_: Exception)` (e.g., in `ToolFactories.kt` lines 58, 126, 181) remain unfixed — these require separate investigation (some appear to be copy-paste bugs, not intentional suppression).
- Koin logs (`NoDefinitionFoundException`, etc.) now appear in Kermit's output via `KermitKoinLogger`.
- Log files are local-only, so **a bug report from a user still cannot come
- On JVM, `ColorizedWriter` uses `\u001B` ANSI escapes. Older Windows terminals (pre-10) will print escape sequences literally. `NO_COLOR` env var is respected.
- Redaction is best-effort, not a guarantee — a credential in an unrecognised
- Release builds write the same `Warn`-and-above stream as debug builds.
- The `log-writer` form is a heuristic, not a proof. A writer stored in a
- _... and 9 more items_

### `maestro`

- A future refactor could wire `LEGACY_RAW` into the loop to eliminate the duplication, but the ROI is near zero.
- A new `Maestro/helpers/seed-archived-task.yaml` helper is created in PR-2
- All `AlertDialog`-based buttons in `core/ui/components/` must eventually
- All `ModalBottomSheet` item rows should carry `sheet_item_<slug>`.
- If a future debug-seed API is added (approach 3), both seeder helpers become
- The arrays must be manually kept in sync with the skip-list if a new legacy ID is added. This is low risk: both are trivially grep-able.
- The helper is tagged `helpers` (never run standalone).
- The script's output message ("add to TestTags.kt or LEGACY_RAW") is slightly misleading — LEGACY_RAW is checked only by human review, not by the code. The message should be updated to say "add to TestTags.kt or the skip-list" if the arrays are kept as documentation-only and not wired in.
- When writing a new Maestro flow that hits a dialog/sheet without a testTag,
- `11-archive-restore-smoke.yaml` is updated to `runFlow:
- _... and 1 more items_

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
- _... and 24 more items_

### `mr`

- All `*RepositoryImpl` imports are now consistently either from `core/di` (Konsist enforced) or
- File names now match their class names (ktlint `Filename` rule satisfied)
- New Konsist rule will catch future unscoped reads at PR level
- Tests compile without suppressions for non-deprecated destinations
- `FabActionResolver` no longer carries a suppression for code that was never reachable
- `Room*`-prefixed classes are no longer used — the Konsist rule now catches all production repo impls
- `TaskRepositoryImpl` now correctly scopes dependency reads to the current user
- `getBlockingTaskIdsForUser` required a new DAO method (schema unchanged — Room migration not needed, the cross-ref table has no userId column)

### `multi-profile`

- 4 ADR entries created + DIGEST.md refreshed
- AI Usage screen в Settings
- Room schema v8 с `llm_usage` table + `profiles` table
- ZCode подключается с `--profile=ai-agent` → все операции в профиле ai-agent
- `ProfileAwareCurrentUser` инжектится во все write-tools

### `mvi`

- **Verify a rule exists before relying on it.** The skill table listed a rule that had never
- A `combine` transform must be pure. `NoCombineSideEffect` fails the build on `.value =`,
- A detail-screen ViewModel that owns more than one repository observation should be a
- A slot that is read-only and has no intent surface (the backlinks collector) is a plain
- Any subclass relying on `error` persisting across unrelated edits will see it
- Create now rejects what it used to accept. Names over 100 characters and
- Do not add `StateStrategy.Atomic` or a `stateStrategy` parameter until a VM demonstrates a
- Slot tests construct one slot and its own fakes. Share one test-dispatcher-backed
- Tag renaming is reachable from Settings → Tags: the pencil on a card opens
- The first `pushUiState()` is asynchronous, so `isSaveEnabled` / `isDirty` are
- _... and 11 more items_

### `nav3`

- **Deprecated `AppDestination` singletons** (`Inbox`, `Today`, `Upcoming`, `TasksByProject`,
- **`TaskDetailViewModelTest."TitleChanged debounce saves after delay"` fails with
- **`just setup-hooks` is broken in worktrees.** It sets `core.hooksPath` to
- **`rememberNavBackStackTyped<T>`** from `2026-09-16-nav3-type-asymmetry-adr.md` is still
- **`topLevelRoute` is not persisted**, so a cold launch always restores the start tab rather
- 8 new files (nav package under projects feature) + 2 new ADR records.
- A future change that re-shares the configuration will show up as
- A new route must extend `AppNavKey`, not `NavKey`. Declaring against `NavKey`
- Additional level of indirection for new developers: "where am I?"
- All 3 projects screens use `LocalProjectsNavigator` — no callback parameters.
- _... and 19 more items_

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
- _... and 23 more items_

### `notes`

- **A ViewModel that surfaces a created entity's id must surface the one the write used.**
- **A create that navigates should carry the navigation in the result path**, so a
- A flow that assumes a note can be deleted will hang rather than fail clearly.
- All new helpers are `internal` except `NoteAiController` (used in DI) and `NoteContentMapper`
- Backlinks are now shown and functional
- Caller must provide `MutableStateFlow<String>` and inject `InternalLinkRepository` and `ProfileAwareCurrentUser` — slightly more boilerplate at call site
- Clear UX: notes list → tap note → read → optionally edit
- Cross-screen state (e.g. "did the user just save a note") must flow through navigation callbacks, not shared VM state
- DI in `NotesDiModule` uses explicit `viewModel { NoteEditor(...) }` lambda — never
- Delete confirmation is handled in `NotePreview`, not buried in editor overflow menu
- _... and 40 more items_

### `pomodoro`

- Starting a Pomodoro no longer crashes with or without the permission; without
- The reminder scheduler shares this concern; a future reminder flow will want
- Tick-based tests (fake clock advancing real `delay()`) are unreliable in unit tests. All `AndroidPomodoroTimer` tests use `skip()` to drive phase transitions without depending on virtual time.
- `AlarmContract` is an `object` (no `Companion`). Static-style access (`AlarmContract.EXTRA_PHASE`) is direct, not via `.Companion`.
- `Maestro/flows/pomodoro/*.yaml` cover start, pause/resume, skip and stop. They
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
- _... and 11 more items_

### `repository`

- **PR 3** (VM cleanup) is unblocked: all repository `create` methods now stamp ambient `userId`, so VMs no longer need to pass it. `currentUser` can be dropped from remaining VMs (`TaskDetailViewModel`, `NotePreview`, `NoteEditor`, `ProjectsViewModel`, `NotesListViewModel`, `ProjectEditorViewModel`, `AttachmentsViewModel`, `SavedAgendaViewModel`, `ProjectDetailViewModel`).
- **When** a second entity acquires free-text search — extract `Searchable<E>` mixin
- **When** adding a cross-cutting repository helper (batch op, transactional wrap) —
- A Konsist rule and a detekt rule (added in the enforcement MR) fail the build on new
- AI tools (`CreateTaskTool`, `CreateProjectTool`) still pass `userId` in their input classes — those are separate from this PR's scope (the AI tool MCP adapter work).
- Fakes in `test/fakes/FakeRepositories.kt` simplify: one constructor parameter
- Fakes must reproduce production semantics — including ownership. A fake that cannot
- If a future use-case requires a true `SourceOfTruth` abstraction (e.g., migrating part of the data to a KV-store or SqlDelight), the decision to adopt Store or a custom `LocalStore<T>` interface can be revisited.
- The old `UserScopedRepository<T, ID>` typealias is removed in the cleanup commit
- Write pipeline is now formalised in `GenericUserScopedRepository` KDoc.
- _... and 17 more items_

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
- _... and 18 more items_

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
- _... and 8 more items_

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
- Deadline indicator rendering in `UpcomingBadges`.
- If card-level AI is wanted later, the prerequisite is a preview-and-confirm step —
- Locale-aware `firstDayOfWeek` (hardcoded to Monday for MVP).
- Pure `UpcomingTaskUiMapper` and `UpcomingFirstDayOfWeek` are unit-testable
- Self-dependency is validated in the MCP tool and silently ignored by the join-table upsert (PRIMARY KEY prevents the duplicate).
- Single narrow Room query (`watchByDate`) reused for the new use case.
- _... and 15 more items_

### `tech-debt`

- 0 active violations сверх baseline
- 0 raw production `runBlocking` outside suppressed boundaries; 2 fewer than
- All three changes are additive-renames only
- Both "god-VMs" are now honest coordinators; the audit's line-count smell is
- CI regression устранена: `NoDirectClockSystem = 0`
- Detekt now actually enforces runBlocking/vmScope bans in `:shared` (report-only
- Detekt reports 21 pre-existing `VmCloseable` findings + ~340 mostly-formatting
- Future feature work adds a section by declaring a contributor + one `bind`
- If Settings grows actual cross-section coupling later, the split decision can
- MR-2.2 note: `expect object Clock` remains for backward compatibility; production code should use `kotlinx.datetime.Clock` directly
- _... and 5 more items_

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
- A test failure in CI now produces a directory path in the suppressed exceptions
- A timeout on `awaitTag` names the missing tag and suggests nearby alternatives.
- All 593 existing tests continue to pass
- _... and 42 more items_

### `ui`

- **8 экранов мигрируют одновременно** — невозможно сделать постепенную миграцию из-за смены типа `_events`
- **CollectEvents** в виджетах принимает `Flow<T : UiEvent>` — generic call site остаётся тем же
- **NotificationHost** — финальный widget для всех экранов, заменяет ~64 строк ручного glue кода
- **UiEvent marker** — `ShowDialog/ShowError/NavigateBack` больше не определены глобально
- **Существующие тесты** использующие `TasksViewModel`, `NotesViewModel` и т.д. — `_events.emit(UiEvent.ShowDialog(...))` нужно обновить на `TasksUiEvent.AiResult(...)`
- A `null` `onClick` is meaningful: "use the row's own behaviour". An empty
- Archiving remains destructive from the user's point of view. Until restore
- Future picker sheets (ProjectPickerSheet, TagPickerSheet) should consider `ListPickerSheet` before implementing custom sheets.
- Sheet rows carry `sheet_item_<label>` tags (`TestTags.sheetItem`) so UI
- The Material3 date picker's day cells expose only a contentDescription
- _... and 19 more items_

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

- A detail ViewModel that observes more than one repository should be a coordinator plus slots,
- A slot's `onIntent` needs an `else` branch. It is unreachable through the coordinator's
- No cast needed — `scope` is `AutoCloseableCoroutineScope` at both call site and definition
- Slot tests pump with real `delay()`, not `advanceUntilIdle()`. The fakes' current user runs on
- Tests use `testScope(backgroundScope)` to wrap the test dispatcher
- The AI slot's five success paths are covered at the coordinator level rather than with five
- `AutoCloseableCoroutineScope` companion factory creates a scope backed by `createBackgroundScope()`
- `ProjectsFlowTest` больше не падает под нагрузкой: порядок объявления в
- `TaskAiState.isRunning` is now actually reachable; the old `_aiRunning` was write-only.
- `TaskDraftSlot.seed()` is public because seeding is a one-time initialisation, not a
- _... and 6 more items_

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
- _... and 14 more items_

## Open / Deferred

_21 entries need attention._

- `2026-09-08-instant-migration` — **deferred** — Instant Type Migration: kotlin.time.Instant → kotlinx.datetime.Instant
- `2026-09-25-remaining-test-debt` — **open** — Remaining Test Debt — post JUnit/suite-acceleration audit
- `2026-09-26-agenda-clean-architecture-r22` — **deferred** — R22: Agenda Clean Architecture — deferred
- `2026-09-26-deferred-r24-r30` — **deferred** — R24: Profile subsystem ADR — deferred
- `2026-09-26-deferred-r25-r30` — **deferred** — Deferred Backlog Items R25–R30
- `2026-09-26-notes-clean-architecture-r21` — **deferred** — R21: Notes Clean Architecture — deferred
- `2026-09-28-setup-hooks-broken-githooks-path` — **open** — just setup-hooks указывает на несуществующий .githooks/ — hooks молча отключаются
- `2026-09-29-check-tags-sh-allow-patterns-dead-code` — **open** — Context
- `2026-09-29-remaining-problem-areas-after-maestro-mr` — **deferred** — Оставшиеся проблемные места после MR про Maestro UI-тесты
- `2026-09-30-dispatcher-listviewmodel-cost` — **open** — MR-6 Architectural Polish
- `2026-09-30-god-vm-decomposition` — **open** — MR-5 God-VM Decomposition
- `2026-09-30-post-epic-critical-fixes-and-backlog` — **open** — Post-Epic Critical Fixes and Remaining Backlog
- `2026-09-30-post-mr-1-findings` — **open** — MR-1 Quick Wins — Post-MR-1 Findings
- `2026-09-30-post-mr-2-findings` — **open** — MR-2 Repository Read-Path Isolation — Post-MR-2 Findings
- `2026-09-30-post-mr-3-findings` — **open** — Post-MR-3 findings — Nav2 deprecation removal
- `2026-09-30-post-mr-4-findings` — **open** — Post-MR-4 findings — Repository naming and package convention
- `2026-09-30-post-mr-5-findings` — **open** — Post-MR-5 findings — God-VM Decomposition
- `2026-09-30-post-mr-6-final-triage` — **open** — Post-MR-6 Final Triage — All Open Findings
- `2026-09-30-remove-nav2-deprecations` — **open** — MR-3 Nav2 Deprecation Removal
- `2026-09-30-repository-naming-and-package-convention` — **open** — MR-4 Repository naming and package convention
- `2026-09-30-repository-read-isolation` — **open** — MR-2 Repository Read-Path Isolation

## Recently superseded

- `2026-09-26-production-readiness-findings` — Production Readiness Findings — 2026-09-25
- `2026-09-26-notes-clean-architecture-r21` — R21: Notes Clean Architecture — deferred
- `2026-09-23-test-standards-enforcement` — Test Standards — Enforcement, Gap Filling, and Architecture Cleanup
- `2026-09-23-file-logging-and-exporter` — FileLogWriter + LogExporter: persistent rolling logs and user-facing export
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
- `2026-09-16-calendar-feature` — calendar, feature, navigation
- `2026-09-16-calendar-post-merge-fixes` — calendar, code-review, compose
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
- `2026-09-16-tasks-upcoming-screen` — tasks, filter, repository
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
- `2026-09-25-mr-6a-audit-findings` — _untagged_
- `2026-09-25-mr-6b-findings` — _untagged_
- `2026-09-25-mvi-framework-post-mr-6c` — _untagged_
- `2026-09-25-mvi-framework-status` — _untagged_
- `2026-09-25-no-store-library-local-first-pattern` — repository, local-first, sync, architecture
- `2026-09-25-note-ai-multi-op-design` — _untagged_
- `2026-09-25-note-templates-daily-design` — _untagged_
- `2026-09-25-post-mr-7-audit` — _untagged_
- `2026-09-25-remaining-test-debt` — testing, junit, detekt, epic2
- `2026-09-25-repository-architecture-gaps` — repository, technical-debt, sync, type-safety
- `2026-09-25-task-backlinks-design` — _untagged_
- `2026-09-25-taskcard-slot-api-and-orphan-vm-cleanup` — _untagged_
- `2026-09-25-test-flaky-root-causes` — _untagged_
- `2026-09-25-test-helper-stack` — testing, quality, kotlin, junit5
- `2026-09-25-test-jvm-heap-default` — testing, gradle, kover, heap, koog
- `2026-09-25-test-parallelization` — testing, junit, jupiter, parallel, epic2
- `2026-09-25-test-standards-comprehensive` — testing, junit, jupiter, epic2
- `2026-09-25-test-suite-tag-defaults` — testing, junit, gradle, detekt
- `2026-09-25-testable-vm-dispatcher-clock` — testing, coroutines, viewmodel, koin, di
- `2026-09-26-adr-supersede-process` — _untagged_
- `2026-09-26-agenda-clean-architecture-r22` — _untagged_
- `2026-09-26-code-review-process` — _untagged_
- `2026-09-26-deferred-r24-r30` — _untagged_
- `2026-09-26-deferred-r25-r30` — _untagged_
- `2026-09-26-detekt-baseline-established` — _untagged_
- `2026-09-26-detekt-rules-activation-audit` — detekt, quality, kotlin
- `2026-09-26-docs-lifecycle` — _untagged_
- `2026-09-26-domain-glossary-policy` — _untagged_
- `2026-09-26-draft-mvi-bugfixes` — _untagged_
- `2026-09-26-epic-final-retro` — _untagged_
- `2026-09-26-epic2-retro-findings` — retro, tech-debt, epic2, detekt, testing
- `2026-09-26-epic2-roadmap` — tech-debt, roadmap, epic2, testing, vm
- `2026-09-26-epic3-retro-findings` — retro, tech-debt, epic3, detekt, serialization, sync
- `2026-09-26-four-phases-gate` — _untagged_
- `2026-09-26-genui-subsystem-applied-r23` — _untagged_
- `2026-09-26-internal-link-repo-currentuser` — _untagged_
- `2026-09-26-internationalization` — _untagged_
- `2026-09-26-kdoc-enforcement-rules` — _untagged_
- `2026-09-26-konsist-architecture-tests` — architecture, testing, konsist, ci
- `2026-09-26-observability-production` — _untagged_
- `2026-09-26-performance-profiling` — _untagged_
- `2026-09-26-post-p0-retro` — _untagged_
- `2026-09-26-post-pr-1.1-retro` — _untagged_
- `2026-09-26-post-pr-1.2-retro` — _untagged_
- `2026-09-26-post-pr-1.3-retro` — _untagged_
- `2026-09-26-post-pr-1.4-retro` — _untagged_
- `2026-09-26-post-pr-2.1-retro` — _untagged_
- `2026-09-26-post-pr-2.2-retro` — _untagged_
- `2026-09-26-pr-0-1-retro` — _untagged_
- `2026-09-26-pr-0-2-retro` — _untagged_
- `2026-09-26-pr-0-3-retro` — _untagged_
- `2026-09-26-pr-0-4-retro` — _untagged_
- `2026-09-26-pr24-rescope` — tech-debt, vm, settings, projects, epic2
- `2026-09-26-preflight-quick-wins` — tech-debt, detekt, sync, auth, preflight
- `2026-09-26-preflight-retro-findings` — retro, tech-debt, preflight, detekt, docs
- `2026-09-26-progress-journal-policy` — _untagged_
- `2026-09-26-security-review` — _untagged_
- `2026-09-26-skill-authoring-policy` — _untagged_
- `2026-09-26-ui-testing-deferred` — _untagged_
- `2026-09-26-writer-reviewer-pattern` — _untagged_
- `2026-09-27-di-module-aggregator-narrative` — koin, di, architecture, documentation
- `2026-09-27-doc-and-skills-sprint-findings` — sprint, hygiene, triage, documentation
- `2026-09-27-doc-and-skills-sprint-results` — sprint, hygiene, documentation, skills, tooling
- `2026-09-27-draft-mvi-single-state-source` — mvi, viewmodel, draft, coroutines, initialization-order
- `2026-09-27-feature-slot-pattern` — mvi, viewmodel, architecture, flow
- `2026-09-27-framework-drift-resolution` — mvi, framework, detekt, tech-debt
- `2026-09-27-mr1-retro-findings` — retro, tech-debt, tests, detekt
- `2026-09-27-mvi-single-state-entry-and-vm-sweep` — _untagged_
- `2026-09-27-nav3-startroute-invariant` — nav3, navigation, bug, koin, architecture
- `2026-09-27-no-op-update-state-reducer` — _untagged_
- `2026-09-27-remove-platform-clock-object` — clock, architecture, migration, kotlin-stdlib
- `2026-09-27-taskdetail-migration-and-debounce-write-loop` — _untagged_
- `2026-09-27-vm-koin-scoping-retired` — koin, viewmodel, skills, documentation
- `2026-09-27-write-layer-soundness` — repository, multi-profile, sync, architecture, security
- `2026-09-28-android-cold-start-nav3-serializer-crash` — _untagged_
- `2026-09-28-androidApp-smoke-tests-enabled` — _untagged_
- `2026-09-28-detekt-daemon-and-crashing-rule` — detekt, tooling, build, ci, retro
- `2026-09-28-detekt-duplicate-registration-guard` — detekt, tooling, ci, parallel-work, postmortem
- `2026-09-28-emulator-gfxstream-colorbuffer-segv` — _untagged_
- `2026-09-28-emulator-mesa-radeon-cs-rejected` — _untagged_
- `2026-09-28-mr1-test-virtualization-retro` — retro, tech-debt, tests, coroutines
- `2026-09-28-mr2-project-detail-retro` — retro, tech-debt, viewmodel, coroutines, deprecation
- `2026-09-28-mr2-retro-findings` — retro, tech-debt, tasks, tests
- `2026-09-28-mr3-repository-read-isolation` — retro, tech-debt, repository, multi-profile, security, detekt
- `2026-09-28-mr4-combine-soundness` — retro, tech-debt, detekt, coroutines, mvi
- `2026-09-28-mr5-verification` — retro, tech-debt, verification, coroutines, testing
- `2026-09-28-mr5-vm-hygiene` — retro, tech-debt, viewmodel, coroutines, notes
- `2026-09-28-mr6-verification` — retro, tech-debt, koin, di, konsist, verification
- `2026-09-28-mr7-mr8-verification` — retro, tech-debt, verification, settings, docs
- `2026-09-28-notes-create-navigation` — notes, viewmodel, navigation, mvi, bug
- `2026-09-28-roadmap-closeout` — retro, tech-debt, koin, konsist, docs
- `2026-09-28-roadmap-status` — retro, tech-debt, roadmap, status
- `2026-09-28-setup-hooks-broken-githooks-path` — _untagged_
- `2026-09-28-task-detail-slot-refactor` — viewmodel, mvi, tasks, refactor, bugfix
- `2026-09-29-archive-has-no-restore-ui` — ui, tasks, gap
- `2026-09-29-check-tags-legacy-raw-dead-code` — maestro, testing, tech-debt
- `2026-09-29-check-tags-sh-allow-patterns-dead-code` — _untagged_
- `2026-09-29-destroyed-but-not-deleted-callbacks` — backup, agenda, ui, gap, seam
- `2026-09-29-editor-row-onclick-noop-default` — ui, tasks, android
- `2026-09-29-emulator-crash-recovery-runner` — emulator, android, maestro, tooling
- `2026-09-29-emulator-launch-recipe` — emulator, android, tooling
- `2026-09-29-kotlinx-datetime-androidapp-missing` — _untagged_
- `2026-09-29-maestro-archive-seed-strategy` — maestro, testing
- `2026-09-29-maestro-date-js-host-clock` — maestro, test-infrastructure
- `2026-09-29-maestro-dialog-buttons-no-testtag` — maestro, testing, ui
- `2026-09-29-missing-koin-dao-bindings` — koin, di, crash
- `2026-09-29-no-direct-clock-system-exemptions` — detekt, architecture, tech-debt
- `2026-09-29-notes-and-calendar-unreachable-controls` — notes, calendar, ui, gap
- `2026-09-29-pomodoro-exact-alarm-crash` — pomodoro, android, crash, permissions
- `2026-09-29-remaining-problem-areas-after-maestro-mr` — _untagged_
- `2026-09-29-saved-state-config-must-be-per-graph` — nav3, android, regression, serialization
- `2026-09-29-settings-rail-not-scrollable` — ui, settings, android
- `2026-09-29-single-sealed-navkey-root` — nav3, serialization, android, crash
- `2026-09-29-sync-config-screen-has-no-host` — sync, ui, gap, maestro
- `2026-09-29-task-longpress-menu-and-archive-restore` — ui, tasks, agenda, android
- `2026-09-30-agenda-section-discard-missing` — agenda, domain-logic, agenda-presets, lazycolumn
- `2026-09-30-card-level-ai-actions-deferred` — tasks, ai, ux, gap
- `2026-09-30-dead-affordances-removed` — calendar, ui, gap, cleanup
- `2026-09-30-dead-code-deleted-and-oauth-kept` — logging, koin, kermit, debugging
- `2026-09-30-desktop-compose-ui-flow-tests` — desktop, testing, compose, koin, ui-test
- `2026-09-30-desktop-test-diagnostics` — testing, desktop-compose, koin, kermit, ui-tests
- `2026-09-30-dispatcher-listviewmodel-cost` — mr, coroutines, dispatchers, listviewmodel, cost-tracking
- `2026-09-30-draft-save-failure-and-testtag-honesty` — testing, ui, draft-mvi, testtags, debuggability
- `2026-09-30-file-logging-wired` — logging, observability, android, jvm
- `2026-09-30-god-vm-decomposition` — mr, vm, long-method, tech-debt
- `2026-09-30-log-redaction-pattern-ordering` — logging, koin, kermit, debugging
- `2026-09-30-mr-0-1-test-infra-ratchet-retro` — _untagged_
- `2026-09-30-mvi-error-path-contract` — mvi, testing
- `2026-09-30-nav3-need-viewmodelstore-decorator` — navigation, nav3, koin, viewmodel, desktop-compose
- `2026-09-30-nodate-root-cause` — agenda, testing, debugging, bisect
- `2026-09-30-post-epic-critical-fixes-and-backlog` — mr-review, tech-debt, cascade-delete, dead-ui, konsist
- `2026-09-30-post-mr-1-findings` — mr-review, tech-debt
- `2026-09-30-post-mr-2-findings` — mr-review, tech-debt
- `2026-09-30-post-mr-3-findings` — mr-post-review, nav2, tech-debt
- `2026-09-30-post-mr-4-findings` — mr-post-review, repository, tech-debt
- `2026-09-30-post-mr-5-findings` — mr-post-review, vm, long-method, tech-debt
- `2026-09-30-post-mr-6-final-triage` — mr-review, final-triage, tech-debt
- `2026-09-30-project-reminder-own-table` — reminders, projects, database, migration, room
- `2026-09-30-remove-nav2-deprecations` — mr, navigation, deprecation
- `2026-09-30-repository-naming-and-package-convention` — mr, repository, naming-convention
- `2026-09-30-repository-read-isolation` — mr, architecture, repository
- `2026-09-30-roborazzi-not-built-superseded` — testing, snapshot, roborazzi, doc-hygiene
- `2026-09-30-section-reorder-via-buttons` — agenda, ui, a11y, gap
- `2026-09-30-similar-defects-inventory` — testing, audit, detekt, logging, fakes
- `2026-09-30-tag-rename-and-validation` — mvi, compose, testing
- `2026-09-30-tech-debt-quick-wins` — tech-debt, mr-1, quick-wins, detekt, ktlint, kotlin
- `2026-09-30-test-infra-known-gaps` — testing, tech-debt, konsist, testtags, determinism
- `2026-09-30-testscope-background-work-semantics` — testing, vm
- `2026-09-30-vm-init-property-declaration-order` — viewmodel, coroutines, defect-class, production-bug
- `2026-10-01-agent-velocity-remaining-debt` — testing, tech-debt, agent-velocity, unwired-surface

## Active entries

- `2026-09-05-android-bottom-nav-followups` — Android Bottom Navigation — known issues and refactoring backlog
- `2026-09-05-android-bottom-nav` — Android bottom navigation bar via AppShell + NavHost (no separate ViewModels)
- `2026-09-05-koin-suspend-bridge` — Bridge suspend code into Koin factories via koinBridge { ... }
- `2026-09-05-koog-both-platforms` — Wire Koog AI agent for both JVM desktop and Android
- `2026-09-05-koog-test-workarounds` — Avoid OpenAIModels.Chat.* — use KnownModels; explicit get<>() for SimpleTool<T>
- `2026-09-05-llm-provider-settings` — LLM provider settings: pure-Kotlin config object + sealed test result
- `2026-09-05-refactoring-summary` — ADR: Рефакторинг — унификация, scopeOverride, TaskMutationsUseCase, ContentStateMapper
- `2026-09-05-robolectric-widget-tests` — Widget tests via Robolectric androidHostTest — no Koin, direct ViewModel construction
- `2026-09-05-secret-storage-split` — API key lives in SecureStorage only — never in DataStore, never in UI state
- `2026-09-05-task-editor-refactor` — Task Editor Refactor — TickTick-like single-screen editor
- `2026-09-05-ui-decomposition` — UI Decomposition — reusable widgets, per-feature events, use-case extraction
- `2026-09-05-ui-event-per-feature` — Per-feature UiEvent — маршрутизация событий без глобальной утечки типов
- `2026-09-05-ui-tests-ultron` — UI testing strategy with Ultron + minimal DI seams
- `2026-09-05-uiautomator-compose-discovery` — UI Automator + JetBrains Compose: несовместимость обнаружения элементов
- `2026-09-06-compose-multiplatform-1.12.0-bump` — Bump Compose Multiplatform plugin and libs to 1.12.0
- `2026-09-06-compose-previews` — Add @Preview to all screens and widgets via shared PreviewSamples
- `2026-09-06-desktop-sidebar-replaces-permanent-drawer` — Desktop: replace PermanentNavigationDrawer with explicit Row+Sidebar rail
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
- `2026-09-07-notes-internal-links-backlinks` — Notes Internal Links + Backlinks (Phase 2 extension)
- `2026-09-07-settings-fixes` — Settings layout fixes, reactive dark theme, LLM providers
- `2026-09-07-settings-ux-improvements` — Settings UX improvements: swatches, time picker, connection badge, debounce, confirm dialogs
- `2026-09-07-task-detail-archive-overflow` — Archive in Overflow menu + Picker sheet chrome
- `2026-09-07-task-detail-document-style` — Task Detail — Document-Style Migration
- `2026-09-07-write-tools-in-koog-registry` — Write Tools — idempotent контракт, dryRun, error model
- `2026-09-08-instant-migration` — Instant Type Migration: kotlin.time.Instant → kotlinx.datetime.Instant
- `2026-09-08-mcp-dogfooding-round-2` — MCP dogfooding — round 2 plan index
- `2026-09-08-mcp-plan-tracking-via-mcp` — MCP plan tracking end-to-end
- `2026-09-08-mcp-schema-and-profile-userid-fixes` — MCP schema dialect bug + profile-aware userId defaults
- `2026-09-08-mcp-server-health-audit` — MCP server health audit — dead code, missing tests, contract hazards
- `2026-09-08-projects-ux-rework` — Projects UX Rework — TickTick-level Parity
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
- `2026-09-10-simplified-settings-vm` — Simplified SettingsViewModel — no reactive collection
- `2026-09-11-nav3-kmp-migration` — Nav3 KMP Migration (Android + JVM Desktop)
- `2026-09-14-nav3-tasks-navigator` — Nav3: TasksNavigator replaces callback-passing in task screens
- `2026-09-14-nav3-vm-store-decorator-fix` — Nav3 ViewModelStore decorator fix
- `2026-09-14-tasks-feature-nested-nav3` — Tasks feature → nested navigation3 graph
- `2026-09-15-desktop-menus` — Desktop context menu + window MenuBar via generic MenuNode sealed class
- `2026-09-15-detekt-ktlint-kover-setup` — Integrate detekt, ktlint, and kotlinx-kover for code quality and coverage
- `2026-09-15-nav3-notes-navigator` — Nav3 Notes Navigator — Eliminate callback-passing in notes screens
- `2026-09-15-noteeditor-udf-link-search` — NoteEditor UDF fix — delegate link search to ViewModel
- `2026-09-15-projects-clean-architecture` — ADR: feature/projects — Clean Architecture рефакторинг
- `2026-09-15-projects-nested-nav3` — Projects feature: nested Nav3 graph with ProjectsNavigator
- `2026-09-15-projects-settings-profile-udf-fixes` — PR 5 UDF fixes — ProjectDetail, ProjectPicker, AccountSettings, TagPicker
- `2026-09-15-task-detail-drafts-undo-fix` — TaskDetail drafts seed-from-task; TaskListScreen koinViewModel; undo snackbar wired
- `2026-09-15-task-editor-unification` — Task Editor State Restoration + UI Unification
- `2026-09-15-viewmodel-state-ownership` — ViewModel owns all domain state; Composable owns only routing and animation
- `2026-09-16-agenda-engine` — AgendaEngine: единый DSL-движок для list-вью задач (org-agenda style)
- `2026-09-16-agenda-mr3-saved-views-ui` — AgendaEngine MR3 — Saved Views UI: routes, reducer, events, top-bar entry
- `2026-09-16-agenda-mr4-saved-views-create-reorder` — ADR: AgendaEngine MR4 — Saved Views: Create + Reorder + Polish
- `2026-09-16-agendaengine-post-mr1-nav-cleanup` — AgendaEngine MR1 post-cleanup: remove dead TasksRoute variants and deprecated AppDestination branches
- `2026-09-16-android-shell-fab-fix` — AndroidShellNav3 FAB — wire to real navigation
- `2026-09-16-calendar-feature` — Calendar feature — data layer, UI modes, view models
- `2026-09-16-calendar-post-merge-fixes` — Calendar post-merge fixes
- `2026-09-16-desktop-menus-bugfixes` — Desktop menus: MenuBar AWT, right-click fix, agenda wiring
- `2026-09-16-nav3-desktop-in-memory-no-savedstate` — Nav3 Desktop uses in-memory NavBackStack; SavedStateConfiguration is Android-only
- `2026-09-16-nav3-feature-graph-extensions` — NotesNavGraph start parameter, TasksStartRoute.Detail, AppDestination additions
- `2026-09-16-nav3-post-migration-fixes` — Nav3 post-migration fixes — NotesNavGraph start, preview wrappers, FAB cleanup
- `2026-09-16-nav3-savedstate-serializers-required` — Nav3 SavedStateConfiguration must register all NavKey subtypes polymorphically
- `2026-09-16-nav3-settings-and-search-nested-graphs` — SettingsNavGraph and SearchNavGraph — single-route nested graphs
- `2026-09-16-nav3-type-asymmetry-adr` — Nav3 type asymmetry: rememberInMemoryNavBackStack returns NavBackStack<T>, Android rememberNavBackStack returns NavBackStack<NavKey>
- `2026-09-16-reactive-today-flow` — ADR 2026-09-16 — Reactive `todayFlow` for AgendaEngine
- `2026-09-16-saved-agenda-views` — ADR 2026-09-16 — Saved Agenda Views Persistence
- `2026-09-16-task-filter-set-variants` — TaskFilter and Selector set variants: ByTags/ByPriorities/ByRegexp SQL-backed filters
- `2026-09-16-task-list-filter-to-task-status` — Rename TaskListFilter → TaskStatus: domain-level completion status enum
- `2026-09-16-tasks-upcoming-screen` — Tasks — Upcoming screen
- `2026-09-17-agenda-mr5-pure-infra-ux-polish` — Agenda MR5: Pure Infrastructure + Selector Cohesion + UX Polish
- `2026-09-17-orgmode-architectural-lessons` — Org-mode architectural lessons: cascade, visitor, computed, super-agenda
- `2026-09-17-orgmode-functional-patterns` — Org-mode functional patterns: pure composition extensions
- `2026-09-17-selector-serializer-plain-kserializer` — SelectorSerializer: plain KSerializer instead of JsonContentPolymorphicSerializer
- `2026-09-17-vm-testability-audit` — 2026-09-17 — VM Testability Audit (rolled back, root cause identified)
- `2026-09-18-agenda-nav-route-mapping` — MR7: AgendaNavContent shared route mapping
- `2026-09-18-agenda-selector-composer-dsl` — Agenda — selector composer DSL + universal section() overload
- `2026-09-18-agenda-ui-shared-adoption` — Agenda UI — shared BackTopAppBar, DiscardChangesDialog, SettingsRadioRow adoption
- `2026-09-18-backup-format` — Backup format: zip + MANIFEST + payload + SHA-256 checksum
- `2026-09-18-dialog-state-dsl` — MR8: DialogState<T> — state hoisting for dialog overlays
- `2026-09-18-dialog-state-migration-mr12` — TaskEditorSheet and ProjectDetailScreen migrate to DialogState<T>
- `2026-09-18-mcp-tool-catalog` — MCP tool catalog: 32 Koog SimpleTools registered via Koin
- `2026-09-18-mutation-result-handling` — Mutation-result handling in ViewModels
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
- `2026-09-18-vm-migration-scope-injection` — ADR: ViewModel scope injection — canonical 4-arg constructor pattern
- `2026-09-18-vm-scope-cancellation-oncleared` — ADR: ViewModel scope cancellation on `onCleared()` (SUPERSEDED)
- `2026-09-21-auto-closeable-coroutine-scope` — AutoCloseableCoroutineScope — ViewModel lifecycle scope pattern
- `2026-09-21-generic-user-scoped-repository` — GenericUserScopedRepository<E, ID> — unified CRUD base for all user-scoped repositories
- `2026-09-21-kotlin-auto-closeable-vs-java-closeable` — `kotlin.AutoCloseable` vs `java.io.Closeable` in KMP commonMain
- `2026-09-21-out-of-scope-after-phase-5-5` — Post-Phase-5.5 Out-of-Scope Decisions
- `2026-09-21-profile-repository-migration` — ProfileRepository Migration to GenericUserScopedRepository
- `2026-09-21-state-hoisting-audit` — State Hoisting Audit
- `2026-09-21-state-hoisting-p3-dispatchers-overlay` — state-hoisting-p3: Dispatchers, OverlayState, snackbar separation
- `2026-09-21-tier1-interface-cleanup` — Tier 1 interface cleanup — remove single-implementation contracts
- `2026-09-21-user-scoped-repository` — User-Scoped Repository Pattern
- `2026-09-22-alarmmanager-reminders` — AlarmManager + BootReceiver для reminders ( Orgzly pattern)
- `2026-09-22-bottomsheet-host-mr22` — BottomSheetHost centralises LaunchedEffect sheet state boilerplate
- `2026-09-22-calendar-click-to-create` — Calendar — click-to-create task on long-press + ReminderRepository enrichment
- `2026-09-22-calendar-horizontal-pager` — Calendar HorizontalPager (MR-1)
- `2026-09-22-calendar-reminder-repo` — Calendar + ReminderRepo Integration (MR-3a)
- `2026-09-22-calendar-sync-tasks-org-patterns` — Calendar sync — Tasks.org patterns adoption
- `2026-09-22-canonical-vm-scope-pattern` — Canonical ViewModel constructor: scope as AutoCloseableCoroutineScope
- `2026-09-22-checklist-usecase-delete-and-dead-deps-cleanup` — Delete ChecklistUseCase; drop unused ProfileAwareCurrentUser from AgendaDeps/CalendarDeps; inject taskId via ChecklistEditorViewModel constructor
- `2026-09-22-contributor-process-rename-mr24` — SettingsContributor.apply renamed to process — clarity win
- `2026-09-22-dead-sheets-removal-mr23` — Delete orphaned sheets and picker VMs — 700 lines dead code removed
- `2026-09-22-explicit-overload-removal` — Explicit userId overload removal
- `2026-09-22-fake-overrides-link-schemes-savedpulse-tests` — Fake repository override pattern, LinkSchemes helper, and SavedPulse emission tests
- `2026-09-22-flat-settings-api-removal` — SettingsRepository: remove dead flat API, keep AI and account
- `2026-09-22-koin-annotations-4x-skill-correction` — Koin Annotations 4.x skill correction — removed aspirational @IntoSet/@Single references
- `2026-09-22-marker-contributor-interfaces` — Settings contributors: marker interfaces to defeat type erasure
- `2026-09-22-noteeditor-refactor` — NoteEditor — extract state holders, save controller, AI controller
- `2026-09-22-outbox-workmanager-refactor` — Outbox polling → WorkManager
- `2026-09-22-pomodoro-hybrid-timer` — Hybrid Pomodoro Timer — in-app ticker + AlarmManager.setAlarmClock
- `2026-09-22-preference-wrappers` — DataStore preference wrappers: inline class + BaseSettingsRepository
- `2026-09-22-reminder-lastfiredat-schema` — Reminder `lastFiredAt` — schema migration + scheduler guard
- `2026-09-22-reminder-scheduler-critical-fixes` — ReminderScheduler — 9 critical fixes
- `2026-09-22-repository-user-stamping-and-usercase-currentuser-removal` — Repository stamps ambient userId on create; drop userId params from input classes and use cases
- `2026-09-22-settings-section-ai-ephemeral-fields` — Keep ephemeral state inside SettingsSection.Ai, not in EphemeralState
- `2026-09-22-system-calendar-sync` — System Calendar Provider sync (one-way)
- `2026-09-22-task-rich-dates` — Task Schema v16 — Rich Dates & Styling (MR-2a)
- `2026-09-22-task-ui-rich-dates` — Task UI + Domain Wiring for Rich Dates (MR-2b)
- `2026-09-23-ai-tools-currentuser-singleton` — AI tools: ProfileAwareCurrentUser as global singleton
- `2026-09-23-analytics-port` — Analytics port: interface + Noop + GDPR-compliant opt-in default
- `2026-09-23-billing-abstractions` — Billing abstractions: SubscriptionProvider port + Noop implementation
- `2026-09-23-dead-currentuser-and-orphan-vm-cleanup` — Dead currentUser and orphan VM cleanup
- `2026-09-23-deprecation-tech-debt` — Accumulated deprecation warnings and pre-existing test failures
- `2026-09-23-genui-server-driven-ui` — GenUI — Server-Driven UI via A2UI v0.9
- `2026-09-23-ksp-missing-type-main-branch` — ADR: Pre-existing KSP Error in Main Branch
- `2026-09-23-mcp-bootstrap-result-pattern` — ProfileBootstrapper returns an immutable result carrier — eliminates MCP race
- `2026-09-23-oauth-pkce-refresh-helpers` — OAuth building blocks: PKCE, OAuthTokenRefresh, IdToken (no-op SupabaseAuthRepository)
- `2026-09-23-ota-deferred-items` — OTA Deferred Items — post-MR follow-ups
- `2026-09-23-ota-update-strategy` — OTA Update Strategy — Play In-App Updates + Remote Config
- `2026-09-23-pomodoro-alarm-refactor` — Drop ViewModel in AndroidPomodoroTimer; extract PomodoroScheduler port; use kotlinx.datetime.Clock
- `2026-09-23-profile-deprecated-alias-removal` — Remove deprecated Profile convenience-alias overloads
- `2026-09-23-recurring-parser-review-notes` — Recurring tasks — post-review findings, no-blocker
- `2026-09-23-recurring-tasks-dsl` — Recurring tasks — Orgzly/Tasks.org DSL, rolling completion, CATCH_UP
- `2026-09-23-reminder-savedagenda-repo-stamping` — Reminder + SavedAgenda repository ambient stamping
- `2026-09-23-search-query-language` — Search query language: AST, SimpleFilter, SavedSearch, canonical SearchViewModel
- `2026-09-23-sync-pull-application` — ADR: Sync Pull Application & Consumer Wiring
- `2026-09-23-sync-pull-handlers-and-ui` — ADR: Sync Pull Handlers & Sync UI
- `2026-09-23-sync-scheduling-abstraction` — Sync scheduling abstraction: SyncScheduler + DataStoreSyncPrefs + RemoteConfig + SecureStorage
- `2026-09-23-sync-state-model` — Sync state model: public API, Result<T>, SyncRepository facade, AppError
- `2026-09-23-sync-tier3-fixes` — ADR: Sync Tier 3.5 Fixes & Non-Settings Consolidation
- `2026-09-23-tag-groups-inheritance` — Tag Groups — Orgzly :name: pattern, project inheritance, merge semantics
- `2026-09-23-task-dependencies-completion` — Task Dependencies (Blocked/Blocking)
- `2026-09-23-tech-debt-audit` — Tech debt audit — post vm-event-guard-cleanup
- `2026-09-23-versioning-and-runtime-gates` — Single source of truth for app version, typed schema versioning, and runtime version gates
- `2026-09-23-vm-event-guard-cleanup` — VM event/guard cleanup — compareAndSet, typed combine, SendChannel, dead code
- `2026-09-24-combine-statein-policy` — combine+stateIn Policy
- `2026-09-24-dao-userid-guards` — ProjectDao mutation methods require userId in WHERE clause
- `2026-09-24-datastore-catch-fix-together` — DataStore `.catch` fix-together
- `2026-09-24-deferred-backlog` — Deferred Backlog
- `2026-09-24-pr1-tech-debt-audit-resolution` — PR 1.1 resolution — Tech Debt Audit findings
- `2026-09-24-pre-existing-issues` — Pre-existing Issues Found During Tech Debt Audit
- `2026-09-24-profile-aware-current-user-di` — ProfileAwareCurrentUser — pure DI, no static singleton
- `2026-09-24-sync-debouncer-and-tasks-comparison` — ADR: Sync Coalescing + Tasks KMP Architecture Survey
- `2026-09-24-taskeditor-refactor-remaining-debt` — TaskEditor + ProjectDetail refactor remaining debt
- `2026-09-24-tech-debt-mini-prs` — Tech Debt Mini-PRs — September 2024
- `2026-09-25-ai-action-registry-design` — Task AI Action Registry Design
- `2026-09-25-cycle-detector-design` — CycleDetector Design
- `2026-09-25-detekt-test-rules` — Detekt Rules for Tests — NoRealDelay, NoViewModelScope
- `2026-09-25-fake-legacy-cleanup` — Remove FakeTaskRepository legacy observation methods
- `2026-09-25-git-hooks-worktree-isolation` — Git Hooks — Worktree Isolation + Shared Hooks Path
- `2026-09-25-local-mvi-framework` — Local MVI Framework — StatefulViewModel + MviViewModel + EventBus
- `2026-09-25-mr-6a-audit-findings` — MR-6a Audit Findings
- `2026-09-25-mr-6b-findings` — MR-6b Findings (No-Code MR)
- `2026-09-25-mvi-framework-post-mr-6c` — Post-MR-6d Audit: MVI Framework Migration — Final Status
- `2026-09-25-mvi-framework-status` — MVI Framework Audit Summary (Post MR-6a/6b/7)
- `2026-09-25-no-store-library-local-first-pattern` — Do not adopt MobileNativeFoundation/Store — local-first repository pattern
- `2026-09-25-note-ai-multi-op-design` — Note AI Multi-Op Design
- `2026-09-25-note-templates-daily-design` — Note Templates and Daily Log Design
- `2026-09-25-post-mr-7-audit` — Post-MR-7 Audit (MR-6a + MR-7 completed)
- `2026-09-25-remaining-test-debt` — Remaining Test Debt — post JUnit/suite-acceleration audit
- `2026-09-25-repository-architecture-gaps` — Repository architecture gaps — Tag userId types, dead ConflictResolver.merge, empty-string sentinels
- `2026-09-25-task-backlinks-design` — Task Backlinks — Design
- `2026-09-25-taskcard-slot-api-and-orphan-vm-cleanup` — TaskCard slot API refactor + TasksViewModel final cleanup
- `2026-09-25-test-flaky-root-causes` — Test Flaky Root Causes — Findings from Test Suite Audit
- `2026-09-25-test-helper-stack` — Test helper stack — kotest assertions, @ParameterizedTest, hand-rolled fakes
- `2026-09-25-test-jvm-heap-default` — OOM in TaskOutgoingLinksTest — Kover instrumentation + Koog-heavy classpath
- `2026-09-25-test-parallelization` — Test Parallelization — Jupiter Concurrency + Thread Safety
- `2026-09-25-test-standards-comprehensive` — Test Standards Comprehensive — JUnit Jupiter, Virtual Time, Fast/Slow Split
- `2026-09-25-test-suite-tag-defaults` — Test suite tag defaults and Khorikov testing principles
- `2026-09-25-testable-vm-dispatcher-clock` — Testable VMs — CoroutineDispatcher injection, Clock in DI, RecordingHttpClient
- `2026-09-26-adr-supersede-process` — ADR Supersede Process
- `2026-09-26-agenda-clean-architecture-r22` — R22: Agenda Clean Architecture — deferred
- `2026-09-26-code-review-process` — Code Review Process
- `2026-09-26-deferred-r24-r30` — R24: Profile subsystem ADR — deferred
- `2026-09-26-deferred-r25-r30` — Deferred Backlog Items R25–R30
- `2026-09-26-detekt-baseline-established` — detekt baseline established
- `2026-09-26-detekt-rules-activation-audit` — Detekt custom rules — activate unregistered rule sets and clean up orphan rules
- `2026-09-26-docs-lifecycle` — Docs Lifecycle
- `2026-09-26-domain-glossary-policy` — Domain Glossary Policy
- `2026-09-26-draft-mvi-bugfixes` — Bugfixes in DraftMviViewModel and NoteEditor (post-MR-4 audit)
- `2026-09-26-epic-final-retro` — Epic Final Retro — docs-and-skills-hygiene
- `2026-09-26-epic2-retro-findings` — Epic 2 retro findings — architecture phase retrospective
- `2026-09-26-epic2-roadmap` — Epic 2 roadmap — architecture phase of the tech-debt sprint
- `2026-09-26-epic3-retro-findings` — Epic 3 retro findings + sprint close-out — quality phase retrospective
- `2026-09-26-four-phases-gate` — Four Phases Gate
- `2026-09-26-genui-subsystem-applied-r23` — R23: GenUI subsystem — applied
- `2026-09-26-internal-link-repo-currentuser` — Drop userId from InternalLinkRepository
- `2026-09-26-internationalization` — Internationalization
- `2026-09-26-kdoc-enforcement-rules` — KDoc enforcement rules
- `2026-09-26-konsist-architecture-tests` — Konsist architecture tests — the first hard CI gate for layer boundaries
- `2026-09-26-observability-production` — Observability in Production
- `2026-09-26-performance-profiling` — Performance Profiling
- `2026-09-26-post-p0-retro` — Post-P0 retro: all P0 PRs complete
- `2026-09-26-post-pr-1.1-retro` — PR-1.1 Retro — docs-infrastructure ADRs
- `2026-09-26-post-pr-1.2-retro` — PR-1.2 Retro — docs-infrastructure FILES
- `2026-09-26-post-pr-1.3-retro` — PR-1.3 Retro — process ADRs
- `2026-09-26-post-pr-1.4-retro` — PR-1.4 Retro — Skills Reorg
- `2026-09-26-post-pr-2.1-retro` — PR-2.1 Retro — observability + security
- `2026-09-26-post-pr-2.2-retro` — PR-2.2 Retro — performance, UX/a11y, i18n
- `2026-09-26-pr-0-1-retro` — PR-0.1 retro: detekt-baseline
- `2026-09-26-pr-0-2-retro` — PR-0.2 retro: KDoc enforcement rules
- `2026-09-26-pr-0-3-retro` — PR-0.3 retro: KDoc fix batch + ADR supersede
- `2026-09-26-pr-0-4-retro` — PR-0.4 retro: production BAN fixes
- `2026-09-26-pr24-rescope` — PR 2.4a/2.4b re-scoped — settings boilerplate collapse, project detail single-observer
- `2026-09-26-preflight-quick-wins` — Pre-flight Quick Wins — techdebt roadmap phase 0
- `2026-09-26-preflight-retro-findings` — Pre-flight retro findings — phase 0 retrospective
- `2026-09-26-progress-journal-policy` — Progress Journal Policy
- `2026-09-26-security-review` — Security Review Process
- `2026-09-26-skill-authoring-policy` — Skill Authoring Policy
- `2026-09-26-ui-testing-deferred` — UI testing deferred — androidHostTest + UiAutomator postponed
- `2026-09-26-writer-reviewer-pattern` — Writer-Reviewer Pattern
- `2026-09-27-di-module-aggregator-narrative` — DI: `Modules.kt` is a facade, not the source of truth
- `2026-09-27-doc-and-skills-sprint-findings` — Triage findings from the doc-and-skills hygiene sprint
- `2026-09-27-doc-and-skills-sprint-results` — Doc & skills hygiene sprint — results
- `2026-09-27-draft-mvi-single-state-source` — DraftMviViewModel — one state source, no open-member calls from a constructor
- `2026-09-27-feature-slot-pattern` — FeatureSlot — split a god ViewModel into a coordinator plus focused slots
- `2026-09-27-framework-drift-resolution` — MVI framework drift — StateStrategy.Atomic deferred, NoCombineSideEffectRule written
- `2026-09-27-mr1-retro-findings` — MR-1 retro — pre-existing red test, a rule that never existed, a deprecated TOCTOU API
- `2026-09-27-mvi-single-state-entry-and-vm-sweep` — MVI Base — Single State-Update Entry + ViewModel Sweep
- `2026-09-27-nav3-startroute-invariant` — Nav3 startRoute must be a top-level route — enforce with an invariant, centralize serializers
- `2026-09-27-no-op-update-state-reducer` — No-Op `updateState` Reducer Silently Discards Collected State
- `2026-09-27-remove-platform-clock-object` — Remove `core.platform.Clock` — use `kotlin.time.Clock` everywhere
- `2026-09-27-taskdetail-migration-and-debounce-write-loop` — TaskDetailViewModel Migration + Debounce Write-Loop Fix
- `2026-09-27-vm-koin-scoping-retired` — Retire `singularity-todo-vm-koin-scoping`
- `2026-09-27-write-layer-soundness` — Write-layer soundness — ownership-scoped DAO mutations and the two-layer guard model
- `2026-09-28-android-cold-start-nav3-serializer-crash` — Android cold start крашится: SerializerAlreadyRegisteredException в navSavedStateConfig
- `2026-09-28-androidApp-smoke-tests-enabled` — Enable Android UI smoke tests + document AGP KMP Robolectric limitation
- `2026-09-28-detekt-daemon-and-crashing-rule` — A detekt rule that aborted the run, and a rule change the daemon never saw
- `2026-09-28-detekt-duplicate-registration-guard` — Guard against duplicate detekt rule registration
- `2026-09-28-emulator-gfxstream-colorbuffer-segv` — Эмулятор падает с SIGSEGV в gfxstream при создании ColorBuffer (триггер — soft IME)
- `2026-09-28-emulator-mesa-radeon-cs-rejected` — Emulator crash on Renoir — Mesa 25.3.6 + kernel 6.17 regression
- `2026-09-28-mr1-test-virtualization-retro` — MR-1 retro — three ADRs recorded a test constraint that had already been fixed
- `2026-09-28-mr2-project-detail-retro` — MR-2 retro — the god-VM split was rejected once already, and the real defect was a subscription
- `2026-09-28-mr2-retro-findings` — MR-2 retro — a subtask bug the slot tests exposed, and what the split did not fix
- `2026-09-28-mr3-repository-read-isolation` — MR-3 retro — the write-layer sweep left two read leaks, and the note split does not clear its baseline
- `2026-09-28-mr4-combine-soundness` — MR-4 retro — a lint guard that passes its test and misses the real file
- `2026-09-28-mr5-verification` — MR-5 verification — two of four items do not survive, and a wrongly-closed finding is open again
- `2026-09-28-mr5-vm-hygiene` — MR-5 retro — R7 was closed twice on a grep, and a phantom note id was hiding in plain sight
- `2026-09-28-mr6-verification` — MR-6 verification — the DI cleanup is real but cosmetic, the facade rule has nothing to guard yet, the repo move is backwards
- `2026-09-28-mr7-mr8-verification` — MR-7 and MR-8 verification — the sealed hierarchy is already migrated, and three of MR-8's items are mis-scoped
- `2026-09-28-notes-create-navigation` — A created note is opened on an id the repository never used
- `2026-09-28-roadmap-closeout` — Roadmap close-out — what the verified items actually delivered
- `2026-09-28-roadmap-status` — Tech-debt roadmap v3 — what three MRs closed, and what is left
- `2026-09-28-setup-hooks-broken-githooks-path` — just setup-hooks указывает на несуществующий .githooks/ — hooks молча отключаются
- `2026-09-28-task-detail-slot-refactor` — TaskDetailViewModel — split into a coordinator and seven slots
- `2026-09-29-archive-has-no-restore-ui` — Archiving is a one-way door — no restore UI exists
- `2026-09-29-check-tags-legacy-raw-dead-code` — check-tags.sh LEGACY_RAW and ALLOW_PATTERNS are documentation-only
- `2026-09-29-check-tags-sh-allow-patterns-dead-code` — Context
- `2026-09-29-destroyed-but-not-deleted-callbacks` — A control wired to a no-op reads as working; three of them shipped
- `2026-09-29-editor-row-onclick-noop-default` — Editor rows did nothing — onClick defaulted to a no-op lambda
- `2026-09-29-emulator-crash-recovery-runner` — Emulator gfxstream crash — the IME mitigation is insufficient, recover instead of prevent
- `2026-09-29-emulator-launch-recipe` — Emulator launch recipe — windowed, hardware GPU, camera and audio off
- `2026-09-29-kotlinx-datetime-androidapp-missing` — Context
- `2026-09-29-maestro-archive-seed-strategy` — Archive seed strategy — session coupling in archive-restore flow
- `2026-09-29-maestro-date-js-host-clock` — maestro-date-js-host-clock
- `2026-09-29-maestro-dialog-buttons-no-testtag` — AlertDialog buttons use visible text instead of testTag
- `2026-09-29-missing-koin-dao-bindings` — Three Room DAOs were never bound in Koin
- `2026-09-29-no-direct-clock-system-exemptions` — NoDirectClockSystemRule exemptions are fragile string comparisons
- `2026-09-29-notes-and-calendar-unreachable-controls` — Notes row actions and Calendar header controls are unreachable from the UI
- `2026-09-29-pomodoro-exact-alarm-crash` — Starting a Pomodoro crashed the app — exact-alarm permission was neither declared nor guarded
- `2026-09-29-remaining-problem-areas-after-maestro-mr` — Оставшиеся проблемные места после MR про Maestro UI-тесты
- `2026-09-29-saved-state-config-must-be-per-graph` — One SavedStateConfiguration per graph — a shared one silently dropped nested screens
- `2026-09-29-settings-rail-not-scrollable` — Settings nav rail was not scrollable — Backup and Account were unreachable
- `2026-09-29-single-sealed-navkey-root` — One sealed NavKey root — Settings and Search crashed the app on open
- `2026-09-29-sync-config-screen-has-no-host` — SyncConfigScreen is never rendered — the planned sync flows have nothing to drive
- `2026-09-29-task-longpress-menu-and-archive-restore` — Long-press task menu on Android, and restoring from the archive
- `2026-09-30-agenda-section-discard-missing` — AgendaPresets: every narrow bucket section needs discard=true
- `2026-09-30-card-level-ai-actions-deferred` — Card-level AI actions are deferred: they mutate without preview or undo
- `2026-09-30-dead-affordances-removed` — Nine calendar affordances were removed: they promised a feature that does not exist
- `2026-09-30-dead-code-deleted-and-oauth-kept` — MR-4 dead-code sweep: what was deleted, and three things that look deletable but are not
- `2026-09-30-desktop-compose-ui-flow-tests` — Desktop Compose UI tests mount the real App() with an in-memory platform module
- `2026-09-30-desktop-test-diagnostics` — Desktop test diagnostics: per-test Kermit ring, FailureBundle, awaitTag explainer
- `2026-09-30-dispatcher-listviewmodel-cost` — MR-6 Architectural Polish
- `2026-09-30-draft-save-failure-and-testtag-honesty` — A save that throws must be visible, and a declared testTag must be applied
- `2026-09-30-file-logging-wired` — File logging is wired into both apps; export deferred
- `2026-09-30-god-vm-decomposition` — MR-5 God-VM Decomposition
- `2026-09-30-log-redaction-pattern-ordering` — Redaction patterns are order-dependent — specific before generic
- `2026-09-30-mr-0-1-test-infra-ratchet-retro` — Retro MR-0 + MR-1: Test Infrastructure Ratchet
- `2026-09-30-mvi-error-path-contract` — MVI error paths: a thrown exception is an error event, never a crashed coroutine
- `2026-09-30-nav3-need-viewmodelstore-decorator` — Nav3State: every tab needs rememberViewModelStoreNavEntryDecorator
- `2026-09-30-nodate-root-cause` — NoDate bisect — the domain is sound; the break is above AgendaEvaluator
- `2026-09-30-post-epic-critical-fixes-and-backlog` — Post-Epic Critical Fixes and Remaining Backlog
- `2026-09-30-post-mr-1-findings` — MR-1 Quick Wins — Post-MR-1 Findings
- `2026-09-30-post-mr-2-findings` — MR-2 Repository Read-Path Isolation — Post-MR-2 Findings
- `2026-09-30-post-mr-3-findings` — Post-MR-3 findings — Nav2 deprecation removal
- `2026-09-30-post-mr-4-findings` — Post-MR-4 findings — Repository naming and package convention
- `2026-09-30-post-mr-5-findings` — Post-MR-5 findings — God-VM Decomposition
- `2026-09-30-post-mr-6-final-triage` — Post-MR-6 Final Triage — All Open Findings
- `2026-09-30-project-reminder-own-table` — Project reminders get their own table rather than a nullable task_id
- `2026-09-30-remove-nav2-deprecations` — MR-3 Nav2 Deprecation Removal
- `2026-09-30-repository-naming-and-package-convention` — MR-4 Repository naming and package convention
- `2026-09-30-repository-read-isolation` — MR-2 Repository Read-Path Isolation
- `2026-09-30-roborazzi-not-built-superseded` — Roborazzi snapshot tests — не реализовано, решение закрыто
- `2026-09-30-section-reorder-via-buttons` — Section reordering ships as buttons, not the drag handle that was drawn
- `2026-09-30-similar-defects-inventory` — Inventory of the 'declared but inert' defect class, after verification
- `2026-09-30-tag-rename-and-validation` — Tag rename, and validation that create and update share
- `2026-09-30-tech-debt-quick-wins` — MR-1: Quick Wins — механический техдолг batch
- `2026-09-30-test-infra-known-gaps` — Test-infra ratchet: оставшиеся долги после MR-5
- `2026-09-30-testscope-background-work-semantics` — runTest background work: advanceUntilIdle does not pump an idle foreground
- `2026-09-30-vm-init-property-declaration-order` — VM init: property declared after the init block that uses it
- `2026-10-01-agent-velocity-remaining-debt` — Ревизия после MR-7: что осталось и что поможет агенту

