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

## Per-tag

### `ai`

- A passing `:androidApp:assembleDebug` is the cross-platform smoke test (it would have failed under the old stub). _(from `2026-09-05-koog-both-platforms`)_
- `SettingsViewModel.testConnection()` **always** short-circuits with `Error("API key not configured")` when no key, **without** calling `textGen`. Tests assert this with `FakeTextGen(trackGenerateCalls = true)` and `assertEquals(emptyList(), textGen.generateCalls)`. _(from `2026-09-05-llm-provider-settings`)_
- The old `JvmPromptExecutorPort` and `AndroidPromptExecutorPort` files are deleted. _(from `2026-09-05-koog-both-platforms`)_
- The Test connection "probe" prompt is hard-coded: `"Reply with the single word: pong."` — change together with the system prompt if needed. _(from `2026-09-05-llm-provider-settings`)_
- The unified `KoogPromptExecutorPort` lives in `commonMain` and exposes `val executor: PromptExecutor` publicly for the platform `single<PromptExecutor>` binding. _(from `2026-09-05-koog-both-platforms`)_

### `architecture`

- **8 экранов мигрируют одновременно** — невозможно сделать постепенную миграцию из-за смены типа `_events` _(from `2026-09-05-ui-event-per-feature`)_
- `AppDestination` пополнился `Notes` (уже был), логика FAB его задействует. _(from `2026-09-07-fab-chrome-level`)_
- `AppShell` — minor change: добавлен `FabAction` parameter. _(from `2026-09-07-fab-chrome-level`)_
- **Breaking:** `coreDomainModule()` удалён; заменён на `domainModule()` (includes everything). Test files обновлены. _(from `2026-09-06-di-module-split`)_
- **CollectEvents** в виджетах принимает `Flow<T : UiEvent>` — generic call site остаётся тем же _(from `2026-09-05-ui-event-per-feature`)_
- **Existing tests:** `DiGraphTest`, `JvmAiDiGraphTest`, `AppSmokeTest` обновлены и проходят. _(from `2026-09-06-di-module-split`)_
- **New file count:** 8 новых файлов (7 модулей + decision). _(from `2026-09-06-di-module-split`)_
- **NotificationHost** — финальный widget для всех экранов, заменяет ~64 строк ручного glue кода _(from `2026-09-05-ui-event-per-feature`)_
- `TagsScreen` больше не принимает callback — экран не подключён к навигации (menu destination `Tags` отсутствует в `AppDestination`). _(from `2026-09-07-fab-chrome-level`)_
- **UiEvent marker** — `ShowDialog/ShowError/NavigateBack` больше не определены глобально _(from `2026-09-05-ui-event-per-feature`)_
- **Существующие тесты** использующие `TasksViewModel`, `NotesViewModel` и т.д. — `_events.emit(UiEvent.ShowDialog(...))` нужно обновить на `TasksUiEvent.AiResult(...)` _(from `2026-09-05-ui-event-per-feature`)_

### `build`

- All JetBrains compose library versions MUST track `version.ref = "composeMultiplatform"`. Split-version declarations are forbidden unless the artifact is an AndroidX (not JetBrains) group. _(from `2026-09-06-compose-multiplatform-1.12.0-bump`)_
- The `checkJvmMainComposeLibrariesCompatibility` task must pass silently on every PR. _(from `2026-09-06-compose-multiplatform-1.12.0-bump`)_
- When adding a new third-party Compose dependency, verify its JetBrains compose `requires:` constraint in the Gradle module metadata (`.module` file in cache) before adding — if it demands a version newer than the current pin, either bump or find an alternative. _(from `2026-09-06-compose-multiplatform-1.12.0-bump`)_

### `compose`

- All JetBrains compose library versions MUST track `version.ref = "composeMultiplatform"`. Split-version declarations are forbidden unless the artifact is an AndroidX (not JetBrains) group. _(from `2026-09-06-compose-multiplatform-1.12.0-bump`)_
- **BottomBar taps** now have a single source of truth: `navigator.navigateTopLevel(dest)` — no `selectedIndex` to keep in sync. _(from `2026-09-05-android-bottom-nav`)_
- `Clock.System.now()` must not appear in preview code — use _(from `2026-09-06-compose-previews`)_
- `compose-ui-test:1.12.0` added to `libs.versions.toml` as `composeUiTest` _(from `2026-09-06-desktop-smoke-test-with-koin`)_
- Desktop chrome is a 240 dp left rail, VSCode/JetBrains-style. Width is explicit, not derived from drawer measurements. _(from `2026-09-06-desktop-sidebar-replaces-permanent-drawer`)_
- **Desktop chrome** is unchanged from the user's perspective — the drawer still works exactly as before. _(from `2026-09-05-android-bottom-nav`)_
- Every `NavDestination` entry has an `icon` field. When adding a new entry, pick an icon from `androidx.compose.material.icons.Filled` or `Icons.AutoMirrored.Filled`. _(from `2026-09-06-desktop-sidebar-replaces-permanent-drawer`)_
- **Menu sheet visibility** is `rememberSaveable` state in `AndroidShell` — survives config changes, not part of the back stack. _(from `2026-09-05-android-bottom-nav`)_
- `ModalShell` + `DrawerStyle.Modal` remain in `AppShell.kt`. They are not wired to any platform but are preserved for future modal drawer needs. _(from `2026-09-06-desktop-sidebar-replaces-permanent-drawer`)_
- **`NavDestination` (drawer enum)** remains for the desktop drawer's grouping by `NavGroup` — not removed, just no longer wired to mobile. _(from `2026-09-05-android-bottom-nav`)_
- Navigation interaction tests (click-to-navigate) are out of scope for this smoke test — they require handling NavBackStackEntry lifecycle in `runDesktopComposeUiTest` _(from `2026-09-06-desktop-smoke-test-with-koin`)_
- **Per-tab backstacks** work as expected: open TaskDetail on Today, switch to Plans, switch back to Today → TaskDetail is restored. _(from `2026-09-05-android-bottom-nav`)_
- `@Preview` annotation is `@androidx.compose.ui.tooling.preview.Preview` — _(from `2026-09-06-compose-previews`)_
- Preview functions are `private` and placed at the end of the source file, _(from `2026-09-06-compose-previews`)_
- `PreviewParameterProvider` is avoided — individual preview functions used instead _(from `2026-09-06-compose-previews`)_
- `singularity-todo-shared-ui-components` skill governs decomposition: desktop-only chrome stays in `feature/nav/`, shared widgets go to `core/ui/components/`. _(from `2026-09-06-desktop-sidebar-replaces-permanent-drawer`)_
- Smoke test now passes: `./gradlew :desktopApp:test` → BUILD SUCCESSFUL _(from `2026-09-06-desktop-smoke-test-with-koin`)_
- `sourceSets { test { java.srcDirs("src/jvmTest") ... } }` added to `desktopApp/build.gradle.kts` to wire the `jvmTest` source set to the `test` task _(from `2026-09-06-desktop-smoke-test-with-koin`)_
- **`TasksScreen`** unchanged — it already takes `onNavigateToTask` / `onNavigateToCreateTask` callbacks; the per-tab sub-navigation state now lives in `TasksRoute` inside `AppNavHost` via `rememberSaveable`. _(from `2026-09-05-android-bottom-nav`)_
- The `checkJvmMainComposeLibrariesCompatibility` task must pass silently on every PR. _(from `2026-09-06-compose-multiplatform-1.12.0-bump`)_
- `useSurface = false` when the preview root already contains a `Scaffold` _(from `2026-09-06-compose-previews`)_
- When adding a new third-party Compose dependency, verify its JetBrains compose `requires:` constraint in the Gradle module metadata (`.module` file in cache) before adding — if it demands a version newer than the current pin, either bump or find an alternative. _(from `2026-09-06-compose-multiplatform-1.12.0-bump`)_

### `coroutines`

- `koinBridge` is for one-shot startup reads only — **not for** hot-path code, **not for** long-running operations. _(from `2026-09-05-koin-suspend-bridge`)_
- **Raw `runBlocking` в модулях** — не допускается, `koinBridge` как единая точка входа _(from `2026-09-06-koin-bridge-audit`)_
- **`singleOf` для репозиториев** — architectural limitation; сложные конструкторы не поддерживают constructor-reference форму _(from `2026-09-06-koin-bridge-audit`)_
- When the script's grep is broken (a stray `runBlocking` appears), fix it immediately; the helper exists specifically so this is detectable. _(from `2026-09-05-koin-suspend-bridge`)_
- **Правило подтверждено:** `koinBridge` только для one-shot startup suspend reads _(from `2026-09-06-koin-bridge-audit`)_

### `debugging`

- All new `catch` blocks in ViewModels, repositories, and use cases should inject `Logger` and call `log.e(e) { "..." }` or use `runCatchingLogged`. _(from `2026-09-06-kermit-logging-setup`)_
- `BuildConfig.DEBUG` requires `buildConfig = true` in `androidApp/build.gradle.kts`. No BuildConfig is available in `shared` jvm target. _(from `2026-09-06-kermit-logging-setup`)_
- Existing silent `catch (_: Exception)` (e.g., in `ToolFactories.kt` lines 58, 126, 181) remain unfixed — these require separate investigation (some appear to be copy-paste bugs, not intentional suppression). _(from `2026-09-06-kermit-logging-setup`)_
- Koin logs (`NoDefinitionFoundException`, etc.) now appear in Kermit's output via `KermitKoinLogger`. _(from `2026-09-06-kermit-logging-setup`)_
- On JVM, `ColorizedWriter` uses `\u001B` ANSI escapes. Older Windows terminals (pre-10) will print escape sequences literally. `NO_COLOR` env var is respected. _(from `2026-09-06-kermit-logging-setup`)_
- `RefineTaskTool.kt:34-38` has identical try and catch branches (copy-paste bug) — not fixed in this PR. _(from `2026-09-06-kermit-logging-setup`)_

### `desktop`

- `compose-ui-test:1.12.0` added to `libs.versions.toml` as `composeUiTest` _(from `2026-09-06-desktop-smoke-test-with-koin`)_
- Desktop chrome is a 240 dp left rail, VSCode/JetBrains-style. Width is explicit, not derived from drawer measurements. _(from `2026-09-06-desktop-sidebar-replaces-permanent-drawer`)_
- Every `NavDestination` entry has an `icon` field. When adding a new entry, pick an icon from `androidx.compose.material.icons.Filled` or `Icons.AutoMirrored.Filled`. _(from `2026-09-06-desktop-sidebar-replaces-permanent-drawer`)_
- `ModalShell` + `DrawerStyle.Modal` remain in `AppShell.kt`. They are not wired to any platform but are preserved for future modal drawer needs. _(from `2026-09-06-desktop-sidebar-replaces-permanent-drawer`)_
- Navigation interaction tests (click-to-navigate) are out of scope for this smoke test — they require handling NavBackStackEntry lifecycle in `runDesktopComposeUiTest` _(from `2026-09-06-desktop-smoke-test-with-koin`)_
- `singularity-todo-shared-ui-components` skill governs decomposition: desktop-only chrome stays in `feature/nav/`, shared widgets go to `core/ui/components/`. _(from `2026-09-06-desktop-sidebar-replaces-permanent-drawer`)_
- Smoke test now passes: `./gradlew :desktopApp:test` → BUILD SUCCESSFUL _(from `2026-09-06-desktop-smoke-test-with-koin`)_
- `sourceSets { test { java.srcDirs("src/jvmTest") ... } }` added to `desktopApp/build.gradle.kts` to wire the `jvmTest` source set to the `test` task _(from `2026-09-06-desktop-smoke-test-with-koin`)_

### `di`

- **Breaking:** `coreDomainModule()` удалён; заменён на `domainModule()` (includes everything). Test files обновлены. _(from `2026-09-06-di-module-split`)_
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
- **@Preview и widget-тесты не затрагиваются** — все preview используют `*Content` helpers (stateless) _(from `2026-09-06-koin-vm-viewmodelof-koinviewmodel`)_
- **Raw `runBlocking` в модулях** — не допускается, `koinBridge` как единая точка входа _(from `2026-09-06-koin-bridge-audit`)_
- **`singleOf` для репозиториев** — architectural limitation; сложные конструкторы не поддерживают constructor-reference форму _(from `2026-09-06-koin-bridge-audit`)_
- **`singularity-todo-vm-koin-scoping` skill** — создан как single source of truth _(from `2026-09-06-koin-vm-viewmodelof-koinviewmodel`)_
- **`TaskEditorViewModel` special case** — `viewModel { (initialDueDate) -> ... }` + `koinViewModel { parametersOf(initialDueDate) }` _(from `2026-09-06-koin-vm-viewmodelof-koinviewmodel`)_
- **`viewModelOf(::VM)` для VM без nullable dep** — предпочтительный паттерн _(from `2026-09-06-koin-vm-viewmodelof-koinviewmodel`)_
- **`viewModel { Vm(get(), get(), ...) }`** — для VM с nullable dep + getOrNull() (TasksViewModel, ProjectsViewModel) _(from `2026-09-06-koin-vm-viewmodelof-koinviewmodel`)_
- When the script's grep is broken (a stray `runBlocking` appears), fix it immediately; the helper exists specifically so this is detectable. _(from `2026-09-05-koin-suspend-bridge`)_
- **Правило подтверждено:** `koinBridge` только для one-shot startup suspend reads _(from `2026-09-06-koin-bridge-audit`)_

### `events`

- **8 экранов мигрируют одновременно** — невозможно сделать постепенную миграцию из-за смены типа `_events` _(from `2026-09-05-ui-event-per-feature`)_
- **CollectEvents** в виджетах принимает `Flow<T : UiEvent>` — generic call site остаётся тем же _(from `2026-09-05-ui-event-per-feature`)_
- **NotificationHost** — финальный widget для всех экранов, заменяет ~64 строк ручного glue кода _(from `2026-09-05-ui-event-per-feature`)_
- **UiEvent marker** — `ShowDialog/ShowError/NavigateBack` больше не определены глобально _(from `2026-09-05-ui-event-per-feature`)_
- **Существующие тесты** использующие `TasksViewModel`, `NotesViewModel` и т.д. — `_events.emit(UiEvent.ShowDialog(...))` нужно обновить на `TasksUiEvent.AiResult(...)` _(from `2026-09-05-ui-event-per-feature`)_

### `gradle`

- All JetBrains compose library versions MUST track `version.ref = "composeMultiplatform"`. Split-version declarations are forbidden unless the artifact is an AndroidX (not JetBrains) group. _(from `2026-09-06-compose-multiplatform-1.12.0-bump`)_
- The `checkJvmMainComposeLibrariesCompatibility` task must pass silently on every PR. _(from `2026-09-06-compose-multiplatform-1.12.0-bump`)_
- When adding a new third-party Compose dependency, verify its JetBrains compose `requires:` constraint in the Gradle module metadata (`.module` file in cache) before adding — if it demands a version newer than the current pin, either bump or find an alternative. _(from `2026-09-06-compose-multiplatform-1.12.0-bump`)_

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

- **8 экранов мигрируют одновременно** — невозможно сделать постепенную миграцию из-за смены типа `_events` _(from `2026-09-05-ui-event-per-feature`)_
- All new `catch` blocks in ViewModels, repositories, and use cases should inject `Logger` and call `log.e(e) { "..." }` or use `runCatchingLogged`. _(from `2026-09-06-kermit-logging-setup`)_
- **Breaking:** `coreDomainModule()` удалён; заменён на `domainModule()` (includes everything). Test files обновлены. _(from `2026-09-06-di-module-split`)_
- `BuildConfig.DEBUG` requires `buildConfig = true` in `androidApp/build.gradle.kts`. No BuildConfig is available in `shared` jvm target. _(from `2026-09-06-kermit-logging-setup`)_
- **`Clock` must be passed to `CreateTaskUseCase` / `UpdateTaskUseCase`** — use the singleton `Clock` from `core.platform`. _(from `2026-09-05-robolectric-widget-tests`)_
- **CollectEvents** в виджетах принимает `Flow<T : UiEvent>` — generic call site остаётся тем же _(from `2026-09-05-ui-event-per-feature`)_
- `compose-ui-test:1.12.0` added to `libs.versions.toml` as `composeUiTest` _(from `2026-09-06-desktop-smoke-test-with-koin`)_
- Existing silent `catch (_: Exception)` (e.g., in `ToolFactories.kt` lines 58, 126, 181) remain unfixed — these require separate investigation (some appear to be copy-paste bugs, not intentional suppression). _(from `2026-09-06-kermit-logging-setup`)_
- **Existing tests:** `DiGraphTest`, `JvmAiDiGraphTest`, `AppSmokeTest` обновлены и проходят. _(from `2026-09-06-di-module-split`)_
- **Fake repo returns empty by default** — widget tests that check `LazyColumn` with `testTag` will fail when repo is empty (state = `Empty`). Test the `EmptyState` text instead, or seed data via `fakeNotesRepo.seed(note)`. _(from `2026-09-05-robolectric-widget-tests`)_
- `JvmAiDiGraphTest` keeps its `LLModel` override as a safety belt — if someone reintroduces `OpenAIModels.*`, this test fails at graph-build time. _(from `2026-09-05-koog-test-workarounds`)_
- **JVM args for JDK 21+** — add `--add-opens=java.base/jdk.internal.access=ALL-UNNAMED` to `gradle.properties` (`org.gradle.jvmargs`) AND to `shared/build.gradle.kts` via `afterEvaluate` + `tasks.withType<Test>()` for the test worker process. _(from `2026-09-05-robolectric-widget-tests`)_
- `koinBridge` is for one-shot startup reads only — **not for** hot-path code, **not for** long-running operations. _(from `2026-09-05-koin-suspend-bridge`)_
- **`koinInject()` для репозиториев/сервисов остаётся** — не VM _(from `2026-09-06-koin-vm-viewmodelof-koinviewmodel`)_
- Koin logs (`NoDefinitionFoundException`, etc.) now appear in Kermit's output via `KermitKoinLogger`. _(from `2026-09-06-kermit-logging-setup`)_
- **`koinViewModel()` для VM в Composable** — `koinInject()` для VM антипаттерн _(from `2026-09-06-koin-vm-viewmodelof-koinviewmodel`)_
- Navigation interaction tests (click-to-navigate) are out of scope for this smoke test — they require handling NavBackStackEntry lifecycle in `runDesktopComposeUiTest` _(from `2026-09-06-desktop-smoke-test-with-koin`)_
- **New file count:** 8 новых файлов (7 модулей + decision). _(from `2026-09-06-di-module-split`)_
- **NotificationHost** — финальный widget для всех экранов, заменяет ~64 строк ручного glue кода _(from `2026-09-05-ui-event-per-feature`)_
- On JVM, `ColorizedWriter` uses `\u001B` ANSI escapes. Older Windows terminals (pre-10) will print escape sequences literally. `NO_COLOR` env var is respected. _(from `2026-09-06-kermit-logging-setup`)_
- **@Preview и widget-тесты не затрагиваются** — все preview используют `*Content` helpers (stateless) _(from `2026-09-06-koin-vm-viewmodelof-koinviewmodel`)_
- **Raw `runBlocking` в модулях** — не допускается, `koinBridge` как единая точка входа _(from `2026-09-06-koin-bridge-audit`)_
- `RefineTaskTool.kt:34-38` has identical try and catch branches (copy-paste bug) — not fixed in this PR. _(from `2026-09-06-kermit-logging-setup`)_
- **Robolectric 4.17-beta-4** — `4.16` maxes at SDK 36; `compileSdk=37` requires the beta. The beta is already cached. _(from `2026-09-05-robolectric-widget-tests`)_
- **`Session.Anonymous()` requires `UserId`** — always pass `UserId.anonymous` or `UserId.fromString("...")`. _(from `2026-09-05-robolectric-widget-tests`)_
- **`singleOf` для репозиториев** — architectural limitation; сложные конструкторы не поддерживают constructor-reference форму _(from `2026-09-06-koin-bridge-audit`)_
- **`singularity-todo-vm-koin-scoping` skill** — создан как single source of truth _(from `2026-09-06-koin-vm-viewmodelof-koinviewmodel`)_
- Smoke test now passes: `./gradlew :desktopApp:test` → BUILD SUCCESSFUL _(from `2026-09-06-desktop-smoke-test-with-koin`)_
- `sourceSets { test { java.srcDirs("src/jvmTest") ... } }` added to `desktopApp/build.gradle.kts` to wire the `jvmTest` source set to the `test` task _(from `2026-09-06-desktop-smoke-test-with-koin`)_
- **`TaskEditorViewModel` special case** — `viewModel { (initialDueDate) -> ... }` + `koinViewModel { parametersOf(initialDueDate) }` _(from `2026-09-06-koin-vm-viewmodelof-koinviewmodel`)_
- **UiEvent marker** — `ShowDialog/ShowError/NavigateBack` больше не определены глобально _(from `2026-09-05-ui-event-per-feature`)_
- **Use `UserId` from `feature.tasks`** — it's defined in `Ids.kt` there, imported explicitly. _(from `2026-09-05-robolectric-widget-tests`)_
- **`viewModelOf(::VM)` для VM без nullable dep** — предпочтительный паттерн _(from `2026-09-06-koin-vm-viewmodelof-koinviewmodel`)_
- **`viewModel { Vm(get(), get(), ...) }`** — для VM с nullable dep + getOrNull() (TasksViewModel, ProjectsViewModel) _(from `2026-09-06-koin-vm-viewmodelof-koinviewmodel`)_
- **`waitForIdle()` is a method, not a function** — do NOT import it. Call `composeRule.waitForIdle()` directly. _(from `2026-09-05-robolectric-widget-tests`)_
- When adding a new AI tool, **always** bind its use case with **explicit `get<ConcreteTool>()`** if the use case's parameter is `SimpleTool<T>`: _(from `2026-09-05-koog-test-workarounds`)_
- When the script's grep is broken (a stray `runBlocking` appears), fix it immediately; the helper exists specifically so this is detectable. _(from `2026-09-05-koin-suspend-bridge`)_
- **Правило подтверждено:** `koinBridge` только для one-shot startup suspend reads _(from `2026-09-06-koin-bridge-audit`)_
- **Существующие тесты** использующие `TasksViewModel`, `NotesViewModel` и т.д. — `_events.emit(UiEvent.ShowDialog(...))` нужно обновить на `TasksUiEvent.AiResult(...)` _(from `2026-09-05-ui-event-per-feature`)_

### `koog`

- A passing `:androidApp:assembleDebug` is the cross-platform smoke test (it would have failed under the old stub). _(from `2026-09-05-koog-both-platforms`)_
- `JvmAiDiGraphTest` keeps its `LLModel` override as a safety belt — if someone reintroduces `OpenAIModels.*`, this test fails at graph-build time. _(from `2026-09-05-koog-test-workarounds`)_
- The old `JvmPromptExecutorPort` and `AndroidPromptExecutorPort` files are deleted. _(from `2026-09-05-koog-both-platforms`)_
- The unified `KoogPromptExecutorPort` lives in `commonMain` and exposes `val executor: PromptExecutor` publicly for the platform `single<PromptExecutor>` binding. _(from `2026-09-05-koog-both-platforms`)_
- When adding a new AI tool, **always** bind its use case with **explicit `get<ConcreteTool>()`** if the use case's parameter is `SimpleTool<T>`: _(from `2026-09-05-koog-test-workarounds`)_

### `logging`

- All new `catch` blocks in ViewModels, repositories, and use cases should inject `Logger` and call `log.e(e) { "..." }` or use `runCatchingLogged`. _(from `2026-09-06-kermit-logging-setup`)_
- `BuildConfig.DEBUG` requires `buildConfig = true` in `androidApp/build.gradle.kts`. No BuildConfig is available in `shared` jvm target. _(from `2026-09-06-kermit-logging-setup`)_
- Existing silent `catch (_: Exception)` (e.g., in `ToolFactories.kt` lines 58, 126, 181) remain unfixed — these require separate investigation (some appear to be copy-paste bugs, not intentional suppression). _(from `2026-09-06-kermit-logging-setup`)_
- Koin logs (`NoDefinitionFoundException`, etc.) now appear in Kermit's output via `KermitKoinLogger`. _(from `2026-09-06-kermit-logging-setup`)_
- On JVM, `ColorizedWriter` uses `\u001B` ANSI escapes. Older Windows terminals (pre-10) will print escape sequences literally. `NO_COLOR` env var is respected. _(from `2026-09-06-kermit-logging-setup`)_
- `RefineTaskTool.kt:34-38` has identical try and catch branches (copy-paste bug) — not fixed in this PR. _(from `2026-09-06-kermit-logging-setup`)_

### `navigation`

- `AppDestination` пополнился `Notes` (уже был), логика FAB его задействует. _(from `2026-09-07-fab-chrome-level`)_
- `AppShell` — minor change: добавлен `FabAction` parameter. _(from `2026-09-07-fab-chrome-level`)_
- **BottomBar taps** now have a single source of truth: `navigator.navigateTopLevel(dest)` — no `selectedIndex` to keep in sync. _(from `2026-09-05-android-bottom-nav`)_
- Desktop chrome is a 240 dp left rail, VSCode/JetBrains-style. Width is explicit, not derived from drawer measurements. _(from `2026-09-06-desktop-sidebar-replaces-permanent-drawer`)_
- **Desktop chrome** is unchanged from the user's perspective — the drawer still works exactly as before. _(from `2026-09-05-android-bottom-nav`)_
- Every `NavDestination` entry has an `icon` field. When adding a new entry, pick an icon from `androidx.compose.material.icons.Filled` or `Icons.AutoMirrored.Filled`. _(from `2026-09-06-desktop-sidebar-replaces-permanent-drawer`)_
- **Menu sheet visibility** is `rememberSaveable` state in `AndroidShell` — survives config changes, not part of the back stack. _(from `2026-09-05-android-bottom-nav`)_
- `ModalShell` + `DrawerStyle.Modal` remain in `AppShell.kt`. They are not wired to any platform but are preserved for future modal drawer needs. _(from `2026-09-06-desktop-sidebar-replaces-permanent-drawer`)_
- **`NavDestination` (drawer enum)** remains for the desktop drawer's grouping by `NavGroup` — not removed, just no longer wired to mobile. _(from `2026-09-05-android-bottom-nav`)_
- **Per-tab backstacks** work as expected: open TaskDetail on Today, switch to Plans, switch back to Today → TaskDetail is restored. _(from `2026-09-05-android-bottom-nav`)_
- `singularity-todo-shared-ui-components` skill governs decomposition: desktop-only chrome stays in `feature/nav/`, shared widgets go to `core/ui/components/`. _(from `2026-09-06-desktop-sidebar-replaces-permanent-drawer`)_
- `TagsScreen` больше не принимает callback — экран не подключён к навигации (menu destination `Tags` отсутствует в `AppDestination`). _(from `2026-09-07-fab-chrome-level`)_
- **`TasksScreen`** unchanged — it already takes `onNavigateToTask` / `onNavigateToCreateTask` callbacks; the per-tab sub-navigation state now lives in `TasksRoute` inside `AppNavHost` via `rememberSaveable`. _(from `2026-09-05-android-bottom-nav`)_

### `notes`

- `FakeNotesRepository` и `FakeNoteDao` обновлены同步. _(from `2026-09-07-note-editor-body-load`)_
- `NoteDao.updateContent` сигнатура изменилась: добавлен параметр `html: String`. _(from `2026-09-07-note-editor-body-load`)_
- `NotesRepository.createWithContent` и `updateContent` сигнатуры изменились: добавлен параметр `bodyHtml: String`. _(from `2026-09-07-note-editor-body-load`)_
- Все существующие тесты проходят — никаких изменений в тестовых вызовах не потребовалось (jvmTest зелёный). _(from `2026-09-07-note-editor-body-load`)_
- При первом открытии старой заметки (без `bodyHtml`) — форматирование может отличаться от исходного (round-trip через markdown). Это accepted trade-off для legacy data. _(from `2026-09-07-note-editor-body-load`)_

### `preview`

- `Clock.System.now()` must not appear in preview code — use _(from `2026-09-06-compose-previews`)_
- `@Preview` annotation is `@androidx.compose.ui.tooling.preview.Preview` — _(from `2026-09-06-compose-previews`)_
- Preview functions are `private` and placed at the end of the source file, _(from `2026-09-06-compose-previews`)_
- `PreviewParameterProvider` is avoided — individual preview functions used instead _(from `2026-09-06-compose-previews`)_
- `useSurface = false` when the preview root already contains a `Scaffold` _(from `2026-09-06-compose-previews`)_

### `rich-editor`

- `FakeNotesRepository` и `FakeNoteDao` обновлены同步. _(from `2026-09-07-note-editor-body-load`)_
- `NoteDao.updateContent` сигнатура изменилась: добавлен параметр `html: String`. _(from `2026-09-07-note-editor-body-load`)_
- `NotesRepository.createWithContent` и `updateContent` сигнатуры изменились: добавлен параметр `bodyHtml: String`. _(from `2026-09-07-note-editor-body-load`)_
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

### `room`

- `FakeNotesRepository` и `FakeNoteDao` обновлены同步. _(from `2026-09-07-note-editor-body-load`)_
- `NoteDao.updateContent` сигнатура изменилась: добавлен параметр `html: String`. _(from `2026-09-07-note-editor-body-load`)_
- `NotesRepository.createWithContent` и `updateContent` сигнатуры изменились: добавлен параметр `bodyHtml: String`. _(from `2026-09-07-note-editor-body-load`)_
- Все существующие тесты проходят — никаких изменений в тестовых вызовах не потребовалось (jvmTest зелёный). _(from `2026-09-07-note-editor-body-load`)_
- При первом открытии старой заметки (без `bodyHtml`) — форматирование может отличаться от исходного (round-trip через markdown). Это accepted trade-off для legacy data. _(from `2026-09-07-note-editor-body-load`)_

### `secure-storage`

- Adding a new secret (e.g. another provider's API key) **always** follows the same pattern: new `KEY_*` constant, new config object, migration on first DataStore access, no DataStore copy. _(from `2026-09-05-secret-storage-split`)_
- `AiApiKeyMigration` is wired through `koinBridge { ... }` inside the DataStore factory's `.also { ds -> ... }` block. See `koin-suspend-bridge` decision. _(from `2026-09-05-secret-storage-split`)_

### `security`

- Adding a new secret (e.g. another provider's API key) **always** follows the same pattern: new `KEY_*` constant, new config object, migration on first DataStore access, no DataStore copy. _(from `2026-09-05-secret-storage-split`)_
- `AiApiKeyMigration` is wired through `koinBridge { ... }` inside the DataStore factory's `.also { ds -> ... }` block. See `koin-suspend-bridge` decision. _(from `2026-09-05-secret-storage-split`)_

### `settings`

- Adding a new secret (e.g. another provider's API key) **always** follows the same pattern: new `KEY_*` constant, new config object, migration on first DataStore access, no DataStore copy. _(from `2026-09-05-secret-storage-split`)_
- `AiApiKeyMigration` is wired through `koinBridge { ... }` inside the DataStore factory's `.also { ds -> ... }` block. See `koin-suspend-bridge` decision. _(from `2026-09-05-secret-storage-split`)_
- `App.kt` инжектит `SettingsRepository` через Koin — это нормально, Koin доступен в Common startup. _(from `2026-09-07-settings-fixes`)_
- `SettingsNavRail` Column теперь содержит Box с CircleShape — Layout инлайн, не refactor. _(from `2026-09-07-settings-fixes`)_
- `SettingsViewModel.testConnection()` **always** short-circuits with `Error("API key not configured")` when no key, **without** calling `textGen`. Tests assert this with `FakeTextGen(trackGenerateCalls = true)` and `assertEquals(emptyList(), textGen.generateCalls)`. _(from `2026-09-05-llm-provider-settings`)_
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

### `testing`

- **`Clock` must be passed to `CreateTaskUseCase` / `UpdateTaskUseCase`** — use the singleton `Clock` from `core.platform`. _(from `2026-09-05-robolectric-widget-tests`)_
- `compose-ui-test:1.12.0` added to `libs.versions.toml` as `composeUiTest` _(from `2026-09-06-desktop-smoke-test-with-koin`)_
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

### `ui`

- **8 экранов мигрируют одновременно** — невозможно сделать постепенную миграцию из-за смены типа `_events` _(from `2026-09-05-ui-event-per-feature`)_
- `AppDestination` пополнился `Notes` (уже был), логика FAB его задействует. _(from `2026-09-07-fab-chrome-level`)_
- `App.kt` инжектит `SettingsRepository` через Koin — это нормально, Koin доступен в Common startup. _(from `2026-09-07-settings-fixes`)_
- `AppShell` — minor change: добавлен `FabAction` parameter. _(from `2026-09-07-fab-chrome-level`)_
- **BottomBar taps** now have a single source of truth: `navigator.navigateTopLevel(dest)` — no `selectedIndex` to keep in sync. _(from `2026-09-05-android-bottom-nav`)_
- **`Clock` must be passed to `CreateTaskUseCase` / `UpdateTaskUseCase`** — use the singleton `Clock` from `core.platform`. _(from `2026-09-05-robolectric-widget-tests`)_
- `Clock.System.now()` must not appear in preview code — use _(from `2026-09-06-compose-previews`)_
- **CollectEvents** в виджетах принимает `Flow<T : UiEvent>` — generic call site остаётся тем же _(from `2026-09-05-ui-event-per-feature`)_
- Desktop chrome is a 240 dp left rail, VSCode/JetBrains-style. Width is explicit, not derived from drawer measurements. _(from `2026-09-06-desktop-sidebar-replaces-permanent-drawer`)_
- **Desktop chrome** is unchanged from the user's perspective — the drawer still works exactly as before. _(from `2026-09-05-android-bottom-nav`)_
- Every `NavDestination` entry has an `icon` field. When adding a new entry, pick an icon from `androidx.compose.material.icons.Filled` or `Icons.AutoMirrored.Filled`. _(from `2026-09-06-desktop-sidebar-replaces-permanent-drawer`)_
- **Fake repo returns empty by default** — widget tests that check `LazyColumn` with `testTag` will fail when repo is empty (state = `Empty`). Test the `EmptyState` text instead, or seed data via `fakeNotesRepo.seed(note)`. _(from `2026-09-05-robolectric-widget-tests`)_
- **JVM args for JDK 21+** — add `--add-opens=java.base/jdk.internal.access=ALL-UNNAMED` to `gradle.properties` (`org.gradle.jvmargs`) AND to `shared/build.gradle.kts` via `afterEvaluate` + `tasks.withType<Test>()` for the test worker process. _(from `2026-09-05-robolectric-widget-tests`)_
- **Menu sheet visibility** is `rememberSaveable` state in `AndroidShell` — survives config changes, not part of the back stack. _(from `2026-09-05-android-bottom-nav`)_
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
- `singularity-todo-shared-ui-components` skill governs decomposition: desktop-only chrome stays in `feature/nav/`, shared widgets go to `core/ui/components/`. _(from `2026-09-06-desktop-sidebar-replaces-permanent-drawer`)_
- `TagsScreen` больше не принимает callback — экран не подключён к навигации (menu destination `Tags` отсутствует в `AppDestination`). _(from `2026-09-07-fab-chrome-level`)_
- **`TasksScreen`** unchanged — it already takes `onNavigateToTask` / `onNavigateToCreateTask` callbacks; the per-tab sub-navigation state now lives in `TasksRoute` inside `AppNavHost` via `rememberSaveable`. _(from `2026-09-05-android-bottom-nav`)_

### `ui-test`

- `compose-ui-test:1.12.0` added to `libs.versions.toml` as `composeUiTest` _(from `2026-09-06-desktop-smoke-test-with-koin`)_
- Navigation interaction tests (click-to-navigate) are out of scope for this smoke test — they require handling NavBackStackEntry lifecycle in `runDesktopComposeUiTest` _(from `2026-09-06-desktop-smoke-test-with-koin`)_
- Smoke test now passes: `./gradlew :desktopApp:test` → BUILD SUCCESSFUL _(from `2026-09-06-desktop-smoke-test-with-koin`)_
- `sourceSets { test { java.srcDirs("src/jvmTest") ... } }` added to `desktopApp/build.gradle.kts` to wire the `jvmTest` source set to the `test` task _(from `2026-09-06-desktop-smoke-test-with-koin`)_

### `ui`

- `TextGenPort.listModels` — добавлен в интерфейс, реализация в `KoogAgentService` и `FakeTextGen`. _(from `2026-09-07-settings-fixes`)_
- The Test connection "probe" prompt is hard-coded: `"Reply with the single word: pong."` — change together with the system prompt if needed. _(from `2026-09-05-llm-provider-settings`)_
- **UiEvent marker** — `ShowDialog/ShowError/NavigateBack` больше не определены глобально _(from `2026-09-05-ui-event-per-feature`)_
- `useSurface = false` when the preview root already contains a `Scaffold` _(from `2026-09-06-compose-previews`)_
- **Use `UserId` from `feature.tasks`** — it's defined in `Ids.kt` there, imported explicitly. _(from `2026-09-05-robolectric-widget-tests`)_
- **`waitForIdle()` is a method, not a function** — do NOT import it. Call `composeRule.waitForIdle()` directly. _(from `2026-09-05-robolectric-widget-tests`)_
- Все 6 sub-screens имеют `verticalScroll` — контент больше не обрезается. _(from `2026-09-07-settings-fixes`)_
- **Существующие тесты** использующие `TasksViewModel`, `NotesViewModel` и т.д. — `_events.emit(UiEvent.ShowDialog(...))` нужно обновить на `TasksUiEvent.AiResult(...)` _(from `2026-09-05-ui-event-per-feature`)_

### `_untagged_`

- **~14 изменённых файлов**: Screen.kt + testTag, VM constructors, DI module _(from `2026-09-05-ui-tests-ultron`)_
- **~25 новых файлов**: 4 порта, 7 Page Objects, test infrastructure, integration tests _(from `2026-09-05-ui-tests-ultron`)_
- 8 экранов мигрированы: Tasks, Notes, TaskDetail, TaskEditor, Projects, ProjectEditor, Chat, Archive _(from `2026-09-05-ui-decomposition`)_
- AGENTS.md remains unchanged — its inline `adb`/`sqlite3` commands are still valid escape hatches. _(from `2026-09-06-modular-justfile`)_
- `AppDestination.TaskEditor` serialisation is backward compatible (extra field _(from `2026-09-05-task-editor-refactor`)_
- Autosave вынесен из `delay()` в VM в отдельный port — теперь тестируем без `advanceTimeBy` _(from `2026-09-05-ui-decomposition`)_
- Bulk-операции fail-fast при отсутствующих ID _(from `2026-09-05-refactoring-summary`)_
- CI may later call `just tests::check` instead of `./check.sh` — the behavior is identical. _(from `2026-09-06-modular-justfile`)_
- **CI требует adb-устройство** для instrumentation — `SKIP_ADB=1` для пропуска _(from `2026-09-05-ui-tests-ultron`)_
- `ContentStateMapper` — добавлен object с двумя методами _(from `2026-09-05-refactoring-summary`)_
- DI-граф упрощён: 5 factory → 1 _(from `2026-09-05-refactoring-summary`)_
- `just` must be installed (`just 1.57.0` is present in this environment). _(from `2026-09-06-modular-justfile`)_
- **`koinInject()` в Screen** требует Koin контекст — widget тесты обходят это через Robolectric + `createComposeRule` без Koin _(from `2026-09-05-ui-tests-ultron`)_
- `NotificationHost` заменил ~64 строки ручного glue кода на 8 экранах _(from `2026-09-05-ui-decomposition`)_
- Per-feature events устранили конфликты имён (до: `ShowDialog` everywhere; после: `TasksUiEvent.AiResult`, `NotesUiEvent.SaveFailed`) _(from `2026-09-05-ui-decomposition`)_
- **`performTextClear`** не доступен в Robolectric — используется `performTextInput` напрямую _(from `2026-09-05-ui-tests-ultron`)_
- Recipe names with `::` sub-namespacing (e.g. `android::db::schema`) do not work in `just 1.57.0` — flat names are used instead (e.g. `android::db-schema`). _(from `2026-09-06-modular-justfile`)_
- Robolectric widget tests в `androidHostTest` также **удалены** — все 5 классов _(from `2026-09-05-uiautomator-compose-discovery`)_
- `scopeOverride` добавлен в `ProjectsViewModel` _(from `2026-09-05-ui-decomposition`)_
- `TaskDetailScreen` stays as a read-only viewer until a future PR consolidates _(from `2026-09-05-task-editor-refactor`)_
- `TaskEditorReducerTest` must add test cases for new intents. _(from `2026-09-05-task-editor-refactor`)_
- `TaskEditorViewModel` constructor signature unchanged; DI registration unchanged. _(from `2026-09-05-task-editor-refactor`)_
- `TaskEditorViewModelTest` and `TaskEditorIntegrationTest` must add edit-mode scenarios. _(from `2026-09-05-task-editor-refactor`)_
- `TaskMutationsUseCase` — новый класс, но он по сущиности — grouping, не новая логика _(from `2026-09-05-refactoring-summary`)_
- Two new top-level entries added: `justfile` and `.just/`. _(from `2026-09-06-modular-justfile`)_
- UI Automator тесты **удалены** (`UIAutomatorTest.kt`). _(from `2026-09-05-uiautomator-compose-discovery`)_
- Все fake-репозитории теперь имеют консистентное поведение seed()/add()/clear() _(from `2026-09-05-refactoring-summary`)_
- Все ViewModel'ы с `scopeOverride` — консистентны в тестах _(from `2026-09-05-refactoring-summary`)_
- Для UI-тестов на реальном устройстве: Kaspresso или `contentDescription` + `By.desc()`. _(from `2026-09-05-uiautomator-compose-discovery`)_
- Оставшиеся `androidHostTest`: только `AppNavigatorTest` (nav contract, без Espresso), _(from `2026-09-05-uiautomator-compose-discovery`)_

### `vm`

- **`koinInject()` для репозиториев/сервисов остаётся** — не VM _(from `2026-09-06-koin-vm-viewmodelof-koinviewmodel`)_
- **`koinViewModel()` для VM в Composable** — `koinInject()` для VM антипаттерн _(from `2026-09-06-koin-vm-viewmodelof-koinviewmodel`)_
- **@Preview и widget-тесты не затрагиваются** — все preview используют `*Content` helpers (stateless) _(from `2026-09-06-koin-vm-viewmodelof-koinviewmodel`)_
- **`singularity-todo-vm-koin-scoping` skill** — создан как single source of truth _(from `2026-09-06-koin-vm-viewmodelof-koinviewmodel`)_
- **`TaskEditorViewModel` special case** — `viewModel { (initialDueDate) -> ... }` + `koinViewModel { parametersOf(initialDueDate) }` _(from `2026-09-06-koin-vm-viewmodelof-koinviewmodel`)_
- **`viewModelOf(::VM)` для VM без nullable dep** — предпочтительный паттерн _(from `2026-09-06-koin-vm-viewmodelof-koinviewmodel`)_
- **`viewModel { Vm(get(), get(), ...) }`** — для VM с nullable dep + getOrNull() (TasksViewModel, ProjectsViewModel) _(from `2026-09-06-koin-vm-viewmodelof-koinviewmodel`)_


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
- `2026-09-07-fab-chrome-level` — ui  navigation  architecture
- `2026-09-07-note-editor-body-load` — notes  room  rich-editor  di-graph
- `2026-09-07-settings-fixes` — settings  ui  di-graph

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
- `2026-09-07-fab-chrome-level` — FAB at chrome level — single source of truth in shells
- `2026-09-07-note-editor-body-load` — NoteEditor body load — store HTML directly, fix RichTextState init
- `2026-09-07-settings-fixes` — Settings layout fixes, reactive dark theme, LLM providers
- `2026-09-07-task-detail-document-style` — _(no title)
