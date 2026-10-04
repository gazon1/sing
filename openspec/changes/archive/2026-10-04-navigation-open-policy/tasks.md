# tasks: navigation-open-policy

## B0 — Inventory & green baseline

- [x] `docs/ — B0: create docs/navigation.md` — таблица «экран → откуда открывается →
      контейнер (top-level destination / nested graph / inner route)» по обоим
      платформенным entry-файлам. Baseline-прогон: `Nav3StateReselectTest`,
      `NavSavedStateConfigTest`, `NavKeyRegistrationTest`, desktop `NavigationFlowTest`,
      `PlatformParityTest` — фиксируем зелёное состояние до изменений.

## B1 — Policy + shell facade

- [x] `shared/ — B1: NavigationPolicy (pure commonMain)` — `resolve(from, to): OpenAction`
      (`SwitchTab | Push | ExitAndOpen`), exhaustive `when` без `else`;
      тест: `NavigationPolicyTest` (shared jvmTest/commonTest — таблица
      (from, to) → action для top-level/same-family/cross-family пар;
      prior art `Nav3StateReselectTest`).
- [x] `shared/ — B1: policy rejects structurally invalid targets` — bare start-route как
      app-level цель → descriptive error (source + target), стек не меняется;
      тест: `NavigationPolicyTest` (error case).
- [x] `shared/ — B1: shell Navigator.open/close facade consumes the policy` —
      `open(target)` вычисляет текущий контекст и резолвит действие; `NavCallbacks.navigate`
      делегирует в `open`; тест: `Nav3StateReselectTest` (tab-семантика) +
      `NavigationPolicyTest` + desktop `NavigationFlowTest` (поведение).
- [x] `shared/ — B1: platform entry providers drop the duplicated when(dest) allow-lists` —
      `AndroidNavEntries.kt` / `JvmNavEntries.kt`: единый
      `if (dest == null) close() else open(dest)` колбэк во всех графах;
      тест: desktop `NavigationFlowTest`, `PlatformParityTest`,
      `OpenTaskFromAgendaFlowTest` (кросс-фитюрные переходы не изменились).
- [x] `shared/ — B1: cross-feature opens no longer fall through to back` — ранее
      молча сворачивавшиеся открытия (project → task из Plans, task → linked note)
      теперь открываются (REQ-2); тест: desktop `NavigationFlowTest` (новый сценарий
      project → task из Plans-таба).
- [x] `shared/ — B1: fix task-id → task create misrouting in the note navigator` —
      `NotesNavigator.openTask` открывает деталь задачи, не создание;
      тест: `NotesNavigatorTest` (commonTest, fake stack — asserted target
      `TasksGraph(Detail(taskId))`).

- [x] `shared/ — B1 companion: hoist JVM top-level graph stacks` — entry-local
      `remember { NavBackStack }` died whenever the entry left NavDisplay's top
      (tab switch / outer push — ADR `2026-10-01-desktop-nav-followup` Bug 3), so
      REQ-NAV-002 "Back returns to the project" failed on desktop; the six top-level
      graph stacks are now created in `createJvmEntryProvider` (shell composition) and
      passed via the graphs' `backStack` param; tests: desktop
      `NavigationFlowTest.project_detail_task_opens_from_plans_instead_of_falling_back`
      + `plans_detail_survives_tab_roundtrip`.

## B2 — Typed ids + typed stack factory

- [x] `shared/ — B2: typed entity ids on the five route properties` —
      `TasksStartRoute.Detail(taskId)`, `TasksByProject(projectId)`,
      `NotesStartRoute.Preview(noteId)`, `NotesStartRoute.EditorForTask(taskId)`,
      `ProjectsStartRoute.Editor(projectId?)`; границы распаковки
      (`fromString`) уходят в конвертеры (boundary: `shared/androidMain/App.kt`
      собирает `TaskId(deeplinkTaskId)` — именно туда входит raw String из intent,
      `MainActivity` остаётся String-слоем); тест:
      `NavSavedStateConfigTest` (round-trip inventory расширен) +
      `NavKeyRegistrationTest`.
- [x] `androidApp/ — B2: deep-link boundary converts raw id` — deep-link вход
      (`singularity://task/{id}`) конвертирует String → `TaskId` на границе;
      тест: `androidApp/ assembleDebug` (compile) + Maestro smoke в B5.
- [x] `shared/ — B2: rememberNavBackStackTyped expect/actual factory` —
      инкапсулирует единственный unchecked cast (android) / in-memory fallback (jvm);
      7 graph-файлов теряют `@Suppress("UNCHECKED_CAST")`-пары;
      тест: `NavSavedStateConfigTest` (конфиг собирается) + desktop
      `NavigationFlowTest` + `androidApp/ assembleDebug` (process-death restore —
      Maestro smoke в B5).

## B3 — familyOf + validation

- [x] `shared/ — B3: exhaustive familyOf classification` — `familyOf(key): ScreenFamily`
      (компилятор требует классифицировать новые маршруты, без mutable-реестра);
      тест: `ScreenFamilyTest` (каждый объявленный ключ классифицирован;
      паттерн `NavKeyRegistrationTest`).
- [x] `shared/ — B3: policy pairs driven by family classification` — same-family → `Push`,
      cross-family → `ExitAndOpen`; каждая цель навигаторов объявлена в политике;
      тест: `NavigationPolicyTest` (expand).

## B4 — Scoped cleanup

- [x] `shared/ — B4: remove 7 deprecated AppDestination members after live-ref grep` —
      `Inbox/Today/Upcoming`, `TasksStartRoute.Inbox/Upcoming`, `TaskDetail`,
      `TaskDetailCreate`; удалить ветки production-маппинга
      (`toTasksRoute`), icon-when и фикстурные использования в тестах;
      тест: `NavKeyRegistrationTest` + `NavSavedStateConfigTest` зелёные;
      после удаления: `scripts/find-unwired-surfaces.py`.
- [x] `shared/ — B4: typed ids in legacy onNavigateTo* callbacks` — note link-handler,
      projects screen переводятся на навигаторы с типизированными id;
      тест: desktop `NavigationFlowTest`.
- [x] `docs/ — B4: update cross-feature-navigation skill (Nav2 → Nav3) and stale ADR
      javaagent docs` — доки приводятся к фактическому API; тест: не требуется
      (doc-only), проверка `scripts/check-doc-dead-refs.py`.

## B5 — Verification

- [x] `shared/ + desktopApp/ — B5: full verification` — `./check.sh` (тесты + Android),
      desktop `NavigationFlowTest`, `OpenTaskFromAgendaFlowTest`, `PlatformParityTest`,
      `just lint` (detekt). Выполнено 2026-10-04: 190 классов / 1519 тестов в
      `shared:jvmTest`, 27/77 в `desktopApp:test`, detekt и assembleDebug зелёные,
      все гейты (unwired surfaces, doc sizes, dead refs, test-run floors, openspec)
      проходят. **Maestro smoke выполнить не удалось**: `DeviceServerDiedException`
      на `deviceInfo` — падает и для модифицированных, и для контрольного
      нетронутого потока `04-delete.yaml`, то есть это дефект окружения
      (эмулятор/драйвер), а не кода. Задокументировано в
      `docs/decisions/deferred-backlog.md` и ADR
      `2026-09-28-emulator-gfxstream-colorbuffer-segv`.
- [x] `docs/ — B5: finalize ADR navigation-policy + PROGRESS.md entry` — Consequences
      дополняются фактическим результатом; digest пересобирается
      (`./scripts/refresh-decisions-digest.sh`).
