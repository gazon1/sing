---
date: 2026-10-03
end-date: 2026-10-03
adr: mvi-refactor-residuals-mr-a
deciders:
  - Singularity Developer
status: accepted
title: MVI Refactor Residuals — After MR-A
---

# MVI Refactor Residuals — After MR-A

## Baseline (worktree before MR-A changes)

| Gate | Result | Notes |
|------|--------|-------|
| `shared:jvmTest` | ✅ GREEN | |
| `shared:detekt` | ✅ GREEN | |
| `desktopApp:compileKotlin` | ✅ GREEN | |
| `androidApp:compileDebugKotlin` | 🔴 RED | Pre-existing `WrappingDriver.android.kt:13` — `SQLiteDriver` unresolved. Exists in main checkout too. Not caused by this MR. |

## MR-A Changes

- `Nav3State.getTopLevelRoutesInUse()`: restored Google multiplestacks recipe (max 2 routes)
- `Nav3StateFactory.android.kt`: one `SavedStateConfiguration` per top-level route (inside `associateWith`)
- `DesktopShellNav3.kt`: added `testTag(navTab(...))` to all `NavigationDrawerItem`

## Findings

### Critical Fixed in MR-A

1. **getTopLevelRoutesInUse() returned all non-empty stacks** — confirmed bug, not intentional divergence. Fix applied: exact Google recipe. Detekt required `@Suppress("FunctionExpressionBody")` for style interaction with `MultiLineIfElse`.

2. **Nav3StateFactory shared SavedStateConfiguration** — one instance on 13 routes outside `remember`. Fix applied: moved `navSavedStateConfig()` call inside `associateWith` lambda.

### Deferred to MR-D

3. **isViewModelClass gate** (`MviViewModelRulesProvider.kt`): `endsWith("ViewModel")` misses `NoteEditor` and `TaskDetailCoordinator` — invisible to all 5 MVI rules. Plan: fix to check `extends MviViewModel/DraftMviViewModel`.

4. **ShadowedState rule disabled** — not in `mvi-viewmodel:` block in `detekt.yml`, defaults to `active: false`. Plan: enable in MR-D.

### Deferred (Scroll Reset — UX Enhancement)

5. **Scroll reset on tab reselect** — the `_reselectEvents` + scroll reset mechanism from Google's recipe was not implemented. This requires each scrollable screen to observe a central reselect event, which is a significant architectural addition. Not a blocker — the nav bug fix (item 1 above) is the P0. Scroll reset is a UX enhancement. **Trigger**: implement when a shared scroll-reset infrastructure is added to Nav3State or when a screen needs it.

### Not An Issue

6. **JvmNavEntries vs AndroidNavEntries TasksGraph divergence** — JVM creates its own `NavBackStack<TasksRoute>` via `rememberInMemoryNavBackStack`; Android uses the passed parameter and ignores it. Analyzed: intentional — Android and JVM use different stack persistence mechanisms (`SavedStateConfig` vs in-memory). The JVM code comment (`// Seed with the REQUESTED route, not a hardcoded one`) is accurate. **No action.**

7. **TaskDetailViewScreen** — plan claimed it was "unused dead code". Actually used in `TasksNavGraph.android.kt:66,75` and `TasksNavGraph.jvm.kt:58,67`. Must NOT be deleted. **No action.**

## Consequences

- Scroll reset (item 5) is deferred until a shared reselect-event mechanism exists in Nav3State or a screen needs it.
- Android compilation (WrappingDriver.android.kt) is a pre-existing issue tracked separately.
- All other items are captured in subsequent MRs (B–E).

---

## MR-C Retrospective (2026-10-03)

### Critical Fixed in MR-C

1. **AwaitState was a broken spin-loop** — `while (!predicate()) { advanceUntilIdle() }` hangs indefinitely when the predicate depends on non-time-based state (repository response, in-process computation), because `advanceUntilIdle()` returns immediately when no time-based coroutines are pending, without advancing the virtual clock. Zero callers in the codebase, so this bug was latent. Rewrote to `state.first { predicate(it) }` which suspends until matching state arrives and advances virtual clock automatically. Deprecated in favor of `testVm { ... }.assertIs<T>()`.

2. **coroutine-scopes skill contradiction** — the skill's "Test pattern" section recommended `backgroundScope` for VM scope in `jvmTest`, which is the **direct opposite** of the `test-helpers` canon (`testScope(this)` is correct, `backgroundScope` is wrong). A student following both skills would get non-deterministic test failures. Fixed `coroutine-scopes/SKILL.md` to recommend `testScope(this)` with explicit explanation of why `backgroundScope` is wrong.

3. **RecordingNavigator missing** — navigation test doubles did not exist in the project. Created `RecordingNavigator` (full call recording) and `SimpleRecordingNavigator` (back-only) to support navigation testing in VM tests.

### Systemic Issue (not a one-off)

4. **detekt FunctionExpressionBody vs FunctionSignature conflict** — `FunctionExpressionBody` enforces `= expression` body style, but `FunctionSignature` (when it sees multi-line parameter list) requires body on a new line. These rules are mutually exclusive for generic extension functions with complex parameter types. Resolved with `@Suppress("FunctionExpressionBody", "FunctionSignature")` on affected functions. This is a project-level rule conflict that should be resolved in a separate cleanup pass (consider disabling `FunctionExpressionBody` or adding a `FunctionSignature` exclusion for functions with multi-line generic signatures).

### Pre-existing Issues Noted

5. **DIGEST 1 line over budget** (1551/1550) — caused by MR-A retrospective ADR entry. Not introduced by MR-C. **Trigger**: trim DIGEST or split oversized skill when next ADR is added.

6. **Dead reference** `UsageRecordingTextGen.kt` → `2026-10-02-usage-recording-textgen-architecture.md` — pre-existing from main. Not introduced by MR-C. Baselined dead ref count: 38 (37 baselined + 1 historical).

## Consequences

- Items 1–3 (AwaitState, skill contradiction, RecordingNavigator) are resolved in MR-C.
- Item 4 (detekt rule conflict) is deferred: needs a project-wide rule cleanup pass.
- Items 5–6 are pre-existing, not caused by MR-C.

---

## MR-D Retrospective (2026-10-03)

### Critical Fixed in MR-D

1. **isViewModelClass gate missed DraftMviViewModel subclasses** — `NoteEditor` (extends `DraftMviViewModel`) and `TaskDetailCoordinator` (extends `MviViewModel`) were invisible to all 5 MVI rules because the gate only checked `endsWith("ViewModel")`. `NoteEditor` doesn't end in `ViewModel`. Fixed: gate now checks both name suffix AND supertype (`MviViewModel`/`DraftMviViewModel`). `ShadowedState` and `VmCloseable` rules also had the same supertype check written as `entry.text` instead of `entry.typeReference?.text`, making it always-fail silently.

2. **6 redundant addCloseable(scope) calls** — `TaskDetailCoordinator`, `TagGroupsViewModel`, `AiUsageViewModel`, `BackupViewModel`, `StatisticsViewModel`, `ArchiveViewModel` all had `addCloseable(scope)` as the first line of their `init` block. The `MviViewModel` base class already calls `addCloseable(scope)` in its own init — these were duplicate registrations. Deleted all 6.

3. **detekt-rules-authoring SKILL.md stale table** — listed `MviViewModelRulesProvider` with only 3 rules (`VmScopePosition`, `VmCloseable`, `ShadowedState`) but it registers 5 (`MviViewModelExt`, `IntentMethodName` were missing). Fixed table.

### Deferred / Pre-existing

4. **RemoteConfigRepositoryImpl.defaultConfig is unwired** — `RemoteConfigRepositoryImpl` (core/sync) is a full Room-backed repository registered in DI, but `defaultConfig` (the `Flow<RemoteConfigEntity?>` property) is never collected anywhere in production. The active runtime config uses `RemoteConfigPort` + `RemoteConfigCacheRepositoryImpl` (core/config). `RemoteConfigRepositoryImpl` is likely dead code or a planned-but-never-completed migration artifact. **Trigger**: investigate whether `RemoteConfigRepository` / `RemoteConfigEntity` can be deleted, or confirm the intended migration path.

5. **DIGEST still 1 line over budget** (1551/1550) — not introduced by MR-D. Trigger unchanged.

## Consequences

- Items 1–3 are resolved in MR-D.
- Item 4 (RemoteConfig unwired) requires investigation — either delete the dead code or wire it up.
- Item 5 is pre-existing.

---

## MR-E Retrospective (2026-10-03)

### Critical Fixed in MR-E

1. **IntentActions deleted** — generic `@JvmInline value class IntentActions<I>(private val dispatch: (I) -> Unit)` had 0 production references. Every feature uses its own per-feature `*Actions` value class (e.g. `ProjectDetailActions` with 22 named helpers, `internal val Empty` for previews). Deleted `IntentActions.kt` + `IntentActionsTest.kt`. The generic type cannot offer named helpers, `invoke`-only dispatch, or internal `Empty` sentinel — strictly inferior.

2. **DialogState deleted, OverlayState is the single primitive** — `DialogState` was a strict subset of `OverlayState` (only `active`, no overflow/snackbar). Migrated all 4 call sites to `OverlayState`: `.active` → `.sheet`, `.dismiss()` → `.dismissSheet()`. Deleted `DialogState.kt` + `DialogStateTest.kt`. This unifies the sheet/dialog primitive across the codebase.

3. **collectAsState → collectAsStateWithLifecycle** — 4 screens were using raw `collectAsState()` on VM state flows: `LoginScreen`, `ProfileSwitcherScreen`, `SearchScreen`, `SettingsScreen`. This bypasses lifecycle-aware cancellation and can cause updates after the screen is gone. Migrated to `collectAsStateWithLifecycle()`.

### Deferred / Skipped in MR-E

4. **Routing-when refactor** (`ProjectDetailContent.kt:49-77`) — the `remember { ProjectDetailActions { intent -> when (intent) { ... } } }` block is a valid target but requires careful migration: the `when` handles `Routing` intents (opening sheets) by calling `sheets.show()`, and delegates `Domain` intents to `viewModel.onIntent()`. Moving this to a top-level factory requires ensuring `sheets` and `nav` are captured correctly. Deferred to a follow-up PR — the current structure works, this is pure refactoring without correctness impact.

5. **CurrentProjectContent nullable lambdas** (`ProjectDetailSheetsHost.kt:112-133`) — 10 nullable callback fields that are always passed non-null but require `?.invoke()` guards. The plan proposed replacing with `ProjectDetailActions`-typed field, but this requires the routing-when refactor (item 4) first to be meaningful. Deferred.

6. **@Immutable on hot classes** — mass annotation of 16 hot UI classes (`TaskDetailUi`, `ProjectDetailUiState.Content`, `SettingsUiState.Content`, etc.) is a large diff with no logic change. Deferred to a dedicated annotation pass.

7. **13 non-keyed `items(` calls** — the plan listed 14, but 3 (`AgendaContent.kt:218`, `SavedSearchesRow.kt:78`, `DependencyPickerSheet.kt:64`) are actually keyed (multi-line `key =` on following line). Remaining 13 include `SearchScreen.kt:178-213` (4 result section lists), `ParentPickerSheet`, `ChildProjectsSheet`, `AiUsageScreen` (3 lists), etc. These are real reorder risk but fixing them requires careful key-selection per item. Deferred.

## Consequences

- Items 1–3 are resolved in MR-E.
- Items 4–7 were deferred here and closed in `2026-10-03-mvi-deferred-followup`: the
  routing-`when` hoisting and `CurrentProjectContent` simplification turned out to fix
  real defects (an unkeyed `remember` capturing a stale navigator, and a `data class` whose
  generated `equals` could never be true), not just style.
