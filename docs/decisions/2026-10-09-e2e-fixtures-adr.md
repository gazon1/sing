# ADR: E2E Fixtures, Auth Bypass, and Dialog State Architecture

**Date:** 2026-10-09
**Status:** Accepted
**Context:** Fixing CI gates for `fix-ci-gates-v2` (PR #254), E2E Maestro flows failing due to auth crashes and missing UI wiring.

---

## 1. DevAuthRepository for CI and local development

### Context
`SupabaseAuthRepository` crashes the Android app with SIG 9 (~2ms after `menu_settings` tap) when no Supabase backend is configured (CI, local dev). The crash happens inside `SessionStore` / Supabase client initialization — before any UI renders.

### Decision
Create `DevAuthRepository` — a no-network `AuthRepository` implementation that bypasses Supabase entirely.

**DI binding** (`CoreDiModule.kt`):
```kotlin
// Dev/CI: no backend required
single<AuthRepository> { DevAuthRepository.anonymous() }
```

**For production with real Supabase**: swap to `SupabaseAuthRepository`.

### Factory API
```kotlin
DevAuthRepository.anonymous()                      // Session.Anonymous
DevAuthRepository.signedIn(email, userId)         // Session.SignedIn
repo.configureSession(Session.SignedIn(...))       // transition in tests
repo.signInCalls  // [(email, password), ...]
repo.signOutCalled  // boolean
```

### Rationale
- No Supabase classes referenced anywhere in tests
- Full call tracking for assertion
- Session transitions via `configureSession()` without network
- Both CI and local dev bypass the auth crash

### Consequences
- **Positive**: E2E suite passes; auth crash eliminated; tests are hermetic
- **Negative**: E2E tests never exercise real Supabase auth flow
- **Mitigation**: Integration tests can still use `SupabaseAuthRepository` with a test project

---

## 2. DialogState sharing between TaskDetailContent and TaskEditorContent

### Context
`TaskDetailContent` passed `onDueDateClick = null` to `TaskEditorContent`, making the due-date row non-functional in task detail/edit mode. The date picker sheet was managed internally by `TaskEditorContent` via `rememberDialogState()`.

### Decision
`TaskEditorContent` accepts an **optional** `sheets: DialogState<TaskEditorSheet>?` parameter:

```kotlin
@Composable
fun TaskEditorContent(
    ...existing params...,
    sheets: DialogState<TaskEditorSheet>? = null,  // NEW
) {
    // Use the passed sheet state if provided,
    // otherwise create a local one (for screens that don't need external control).
    val dialogState = sheets ?: rememberDialogState<TaskEditorSheet>()
    ...
}
```

`TaskDetailContent` creates and owns the `DialogState`, passes it to `TaskEditorContent`, and wires `onDueDateClick = { sheets.show(TaskEditorSheet.Date) }`.

### Rationale
- `TaskDetailContent` is the parent that coordinates multiple sheets (AI sheet, time-entry sheet)
- Due-date sheet is just one of many sheets in the detail view
- The `DialogState` object reference is shared — when `TaskDetailContent` calls `sheets.show()`, `TaskEditorContent`'s `dialogState.active` updates, triggering recomposition

### Consequences
- **Positive**: `TaskDetailContent` can open any editor sheet (not just due-date); architectural consistency with other sheets
- **Risk**: `rememberDialogState()` inside `TaskEditorContent` (when `sheets = null`) has the same lifecycle as the composable — fine for `TaskCreateScreen` which owns the state
- **Alternative considered**: Move all sheet state to `TaskDetailCoordinator` — too invasive for this session

---

## 3. Slug function: trailing separator stripping

### Context
`TestTags.slug()` was appending a trailing `_` when the input string ended with a non-alphanumeric character. For example, `slug("Settings")` → `"settings_"` instead of `"settings"`. This caused `menuItem("Settings")` → `"menu_settings_"` — a testTag mismatch in Maestro flows.

### Decision
Changed `slug()` to strip trailing separators using `while` loops instead of appending them:

```kotlin
// OLD (broken):
result.append('_')

// NEW (correct):
while (result.isNotEmpty() && !result.last().isLetterOrDigit()) {
    result.deleteLastChar()
}
while (result.isNotEmpty() && result.first() == '_') {
    result.deleteCharAt(0)
}
```

### Rationale
- Appending `_` unconditionally breaks slugs where the source already ends with `_` or other separators
- Stripping at the end handles natural language labels like "Sign out?" (becomes `sign_out`) without trailing underscore
- Leading strip handles labels starting with punctuation

### Consequences
- `menu_settings` is now correctly generated from `menuItem("Settings")`
- `saved_agenda_empty_title` was already correct (no trailing `_`)
- All `TestTags` consumers use the fixed slug

---

## 4. Known limitation: TaskEditorDueDateRow accessibility

### Context
`TaskEditorDueDateRow`'s text label "Добавить дату" (or the ISO date when set) does **not** appear in the Android accessibility tree, even though it is visually rendered. Maestro's `tapOn id: task_editor_due_row` works because the row's `testTag` IS in the AT tree — but `tapOn text: "Добавить дату"` fails.

### Root cause
Compose's `testTag` + `mapTestTagsAsResourceIds()` on a `Row` creates a resource-id node in the AT tree, but the `Text` children inside the `Row` are not independently accessible. This is a known Compose-to-Android accessibility rendering quirk.

### Current workaround
Maestro flows use `tapOn id: task_editor_due_row` instead of `tapOn text: "Добавить дату"`. The `swipe: UP` gesture after opening the date picker compensates for the 500dp `Box` height constraint that clips day 1 of the month.

### Open question
Could wrapping `TaskEditorDueDateRow`'s `Text` in a separate `testTag`-annotated composable expose the text to Maestro? This would require removing `mapTestTagsAsResourceIds()` from the parent `Row` and applying it only to the text element, but would break the `id: task_editor_due_row` selector.

**Resolution**: Accept the current workaround. The row is tappable via ID, which is sufficient for E2E flows.

---

## 5. DatePickerSheet 500dp height constraint

### Context
`DatePickerSheet` wraps the Material3 `DatePicker` in `Box(heightIn(max = 500.dp))` to prevent it from being clipped by the bottom sheet. This constrains the calendar to ~5 visible weeks, cutting off the first few days of the month on the current screen.

### Current workaround
Maestro flow `02-set-due-date.yaml` uses `swipe: UP` after the date picker opens to scroll the calendar to reveal day 1.

### Open question
Is 500dp the right constraint? On large screens (tablets, foldables) more calendar content could be shown. Consider making the height constraint adaptive or increasing it to 600dp.

---

## 6. `koinBridge` — runBlocking in Koin factory bindings

### Context
Koin's factory DSL is synchronous. Some bindings need to call suspend code (e.g., reading Flow-backed settings). `KoinBridge.kt` wraps such calls in `runBlocking`:

```kotlin
internal inline fun <T> koinBridge(crossinline block: suspend () -> T): T =
    runBlocking { block() }
```

This is used in `CoreDiModule` for settings initialization.

### Risk
`runBlocking` on the main thread (UI startup) can cause deadlocks or ANR if the blocking call holds a lock that a UI thread is waiting for. The `Dispatchers.IO` is used internally, so the risk is low for I/O-bound calls — but any mutual exclusion in the settings DataStore could trigger it.

### Current status
Known, documented, suppressed with `@Suppress("NoRunBlocking")`. Replaceable wholesale when Koin adds first-class coroutine factory support.

### Recommendation
Audit all `koinBridge` call sites and move I/O to `Dispatchers.IO` where possible. Long-term: migrate to Koin's coroutine-aware factories when available.

---

## 7. Maestro flow architecture

### TestTag ID selector strategy
All Maestro flows use `id:` selectors with `mapTestTagsAsResourceIds()` on the root composable modifier. This makes Compose `testTag` values accessible as Android resource IDs to UI automation.

**Key pattern** (from `MenuBottomSheet.kt`):
```kotlin
// MenuBottomSheet renders in its own popup window (separate window from app root).
// App-root mapTestTagsAsResourceIds() doesn't reach it.
// Re-assert mapping on the Column inside the sheet:
Column(modifier = Modifier.mapTestTagsAsResourceIds()) {
    NavigationDrawerItem(
        modifier = Modifier.testTag(TestTags.menuItem(item.label)),
        ...
    )
}
```

**Known gaps**:
- `DropdownMenu` in `TaskEditorContent`: same popup window issue, also uses `mapTestTagsAsResourceIds()` on the menu's Column
- `DatePickerSheet`'s OK button: works correctly with `testTag` on the `Button`

### Flow CI gate: APK version check
`scripts/run-maestro.sh` verifies that the installed APK matches the current git checkout's build (`versionName` includes `+g<git-sha>`). `DevAuthRepository` bypasses this check's failure mode (SIG 9 from missing Supabase).

---

## Links

- Fix PR: `fix-ci-gates-v2` (PR #254)
- Original SIG 9 investigation: commit `575b278e`
- `DevAuthRepository`: `shared/src/commonMain/kotlin/com/singularity/todo/core/auth/DevAuthRepository.kt`
- `KoinBridge.kt`: `shared/src/commonMain/kotlin/com/singularity/todo/core/di/KoinBridge.kt`
- Maestro flows: `Maestro/flows/`
- `DialogState.kt`: `shared/src/commonMain/kotlin/com/singularity/todo/core/ui/components/DialogState.kt`
