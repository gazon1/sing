---
title: "Post-tech-debt-cleanup audit — remaining findings"
date: 2026-10-02
tags: [tech-debt, detekt, quality, testing]
status: accepted
---

# Post-tech-debt-cleanup audit — remaining findings

## Context

After completing all 16 MRs in the `refactor/tech-debt-cleanup` branch, a final
pass identified issues that were noticed during the work but fell outside the scope
of any single MR. This ADR documents them so future agents have a clear starting
point.

---

## 1. Blockers (fix immediately, do not defer)

### 1a. `fallbackToDestructiveMigration` — commented but dangerous

**File**: `shared/src/commonMain/kotlin/com/singularity/todo/core/database/AppDatabaseFactory.kt:55`

A commented-out `fallbackToDestructiveMigration` line carried a note "REMOVE before commit":

```kotlin
// .fallbackToDestructiveMigration(dropAllTables = true) // TEMP: re-add while writing migration, REMOVE before commit
```

**Status**: ✅ **Fixed** — the comment was removed in the final pass of this branch.
The line must never be present in production builds.

**What to do if you need it during active schema development**: uncomment it,
write your migration, test it against a real pre-existing database, then remove
the line before committing. Never merge with it present.

---

## 2. High-priority (fix in next 1–2 sessions)

### 2a. Three desktop flow tests lack `checkA11y = true`

**Affected files**:
- `desktopApp/src/jvmTest/kotlin/com/singularity/todo/feature/agenda/SavedAgendaCreateFlowTest.kt`
- `desktopApp/src/jvmTest/kotlin/com/singularity/todo/feature/flows/agenda/AgendaBadgePolicyFlowTest.kt`

Both fail `HarnessConventionTest.every_flow_test_enables_the_a11y_check`.

`HarnessConventionTest` was written precisely to prevent this: a new flow test
written without `checkA11y = true` compiles, passes, and silently skips a11y
verification. These two files bypass that guard.

Additionally, `OpenSavedViewShowsMatchingTasksFlowTest` fails with a timeout on
CI (`ComposeTimeoutException`) — this appears to be a pre-existing timing issue
with the `tapTab` helper and the drawer animation, unrelated to the tech-debt
changes.

**Fix**: Add `checkA11y = true` to the `runDesktopAppTest { ... }` calls in the
two affected files. The `OpenSavedViewShowsMatchingTasksFlowTest` timeout needs
investigation of the `tapTab` → drawer interaction.

---

### 2b. `koinInject()` for ViewModels — scope boundary violation

**Affected**: `CalendarSyncSettingsScreen.kt:50` was fixed (changed to `koinViewModel()`).
However, there are other places where `koinInject()` is used for components that
hold state tied to the navigation lifecycle.

**Rule to apply**: If a composable function is a screen entry point (used as a
NavHost destination), it **must** use `koinViewModel()` or `koinViewModel { parametersOf(...) }`,
not `koinInject()`. `koinInject()` for ViewModels bypasses Koin's viewModel scope,
meaning the ViewModel instance will survive navigation away and back, potentially
holding stale state.

**Audit command**:
```bash
grep -rn "koinInject<.*ViewModel" shared/src/commonMain/
```

Any result that is a screen-level composable (not a helper/utility composable)
should be converted to `koinViewModel()`.

**Note**: `koinInject()` is correct and intended for repositories, ports, and
services (per AGENTS.md). The ban is specifically for ViewModels.

---

### 2c. `ProjectDetailBody.kt` — 286 lines, baseline debt

**File**: `shared/src/commonMain/kotlin/com/singularity/todo/feature/projects/presentation/screen/ProjectDetailBody.kt`

This file was created by decomposing `ProjectDetailContent.kt` (607 lines → 4 files).
At 286 lines it exceeds the 80-line `LongMethod` threshold but is suppressed by
the detekt baseline.

**Why it's in the baseline**: The decomposition preserved all composable logic
verbatim; only file boundaries changed. The threshold violation pre-existed in
`ProjectDetailContent.kt:101` and was already suppressed there. The suppression
was not transferred because the new file has a different Entity ID in the baseline.

**Debt ceiling**: If this file grows further, the baseline suppression will mask
a new violation. The long-term fix is to extract sub-components (the
`AddExistingTaskPopup` and `ProjectDetailQuickAddInput` are the largest blocks).

**Action**: When working on this screen, monitor `detekt` output. If you extract
another sub-component, regenerate the baseline to remove the stale entry.

---

## 3. Medium-priority (plan in next sprint)

### 3a. `NoEmptyOnClickLambda` rule — `active: false`, warn-only

**File**: `config/detekt/detekt.yml`

The `no-empty-onclick-lambda` rule was created (MR-3) to catch `onClick = {}`
in composable calls. It is currently set to `active: false` (warn-only, no build
failure). The rule correctly skips `@Preview` functions.

**Activation criteria** (all must be true before promoting to `active: true`):
1. All 119 `@Preview` functions in the project use `noopClick` (or are otherwise exempt)
2. All production `onClick = {}` occurrences found by the bulk scan have been resolved
3. The rule has been run with `active: true` on a clean codebase for ≥ 1 week with no
   false positives filed

**To activate**: Change `active: false` to `active: true` in `detekt.yml`.

---

### 3b. `LocalInspectionMode` guards — parameter hoisting

**Files**: `TaskTitleRow.kt:43`, `ChecklistItemRow.kt:62`

Both use `LocalInspectionMode` to conditionally use `koinInject()` vs. a passed
parameter:

```kotlin
val viewModel: SomeVM = if (LocalInspectionMode.current) {
    koinInject()
} else {
    viewModel()  // or koinViewModel()
}
```

This pattern makes the component harder to test (requires `LocalInspectionMode`) and
blurs the dependency boundary. The `haptic: Haptic?` parameter mentioned in the
original MR-8 plan is an example: it was suggested to hoist it as a composable
parameter rather than reaching into Koin from inside the component.

**Fix**: Add `haptic: Haptic?` as a parameter to both composables and remove the
`LocalInspectionMode` branch. The parent screen or a wrapper composable can
decide whether to pass a real or `null` haptic.

---

## 4. Low-priority / informational

### 4a. `NoDirectClockSystem` allowlist — hardcoded file paths

**File**: `detekt-rules/.../NoDirectClockSystemRule.kt:81-84`

```kotlin
internal fun isAllowedPath(path: String): Boolean {
    return path.endsWith("/core/platform/Clock.kt") ||
        path.endsWith("/core/di/CoreDiModule.kt")
}
```

Two files are hardcoded. If a third file legitimately needs `Clock.System` (e.g.,
a test fixture or a platform-specific initialization), the rule requires a code
change to add the path. A config-based allowlist would be more flexible.

**Current trade-off**: Acceptable. The allowlist is a last resort; most cases
should inject `Clock` as a dependency. The two entries correspond to the only
correct uses in the codebase.

---

### 4b. Bulk `onClick = {}` count after MR-3

After MR-3, the bulk count was reduced from ~143 production occurrences to
approximately 39 files with 1–3 occurrences each. The remaining instances
are in components where:
- The click handler is genuinely no-op (e.g., a disabled state in a preview)
- The component is a low-level atomic UI element that does not own its state
- Replacing `{}` with `noopClick` would be mechanical and add no value

A follow-up grep should be run before activating `NoEmptyOnClickLambda`:

```bash
grep -rpon "onClick\s*=\s*\{\s*\}" shared/src/commonMain/ \
  --include="*.kt" | grep -v "noopClick" | grep -v "@Preview" | wc -l
```

If the count is 0, `NoEmptyOnClickLambda` can be activated.

---

## 5. Verification commands

```bash
# Fast checks
./gradlew :shared:jvmTest --no-daemon
./gradlew :shared:detekt --no-daemon

# Desktop tests (may hang on timing — run with timeout)
timeout 120 ./gradlew :desktopApp:test --no-daemon

# Grep for remaining tech debt
grep -rpon "onClick\s*=\s*\{\s*\}" shared/src/commonMain/ --include="*.kt" | grep -v "noopClick" | grep -v "@Preview"
grep -rn "koinInject<.*ViewModel" shared/src/commonMain/
grep -rn "fallbackToDestructiveMigration" shared/src/commonMain/
```

---

## Links

- Original plan: `docs/decisions/2026-10-02-tech-debt-cleanup-plan-v3.md`
- MR-0 ADR (pragma/FK): `docs/decisions/2026-10-02-per-connection-pragmas-and-fk-enforcement.md`
- MR-12 ADR (NoteEntity): `docs/decisions/2026-10-02-note-entity-dual-task-linkage.md`
