---
title: "Android Bottom Navigation — known issues and refactoring backlog"
date: 2026-09-05
tags: [navigation, followups, refactoring, bugs]
---

Status of items in the original followups doc (after refactor pass on
2026-09-05):

## 🔴 Bugs — RESOLVED

### FAB always opens TaskEditor (regardless of current tab)
- **Status:** ✅ Fixed
- **Fix:** `AndroidShell.fabActionFor(current, navigator)` returns a
  per-tab `FabAction`. Today/Inbox → "Add task" → TaskEditor.
  Plans → "Add project" → ProjectEditor. Habits/Calendar hide the FAB
  entirely (`return@Scaffold`).
- **Side effect:** Local FAB in `TasksScreen` and `ProjectsScreen`
  removed. The chrome-level FAB is the single source of truth.

### `ProjectsScreen.onNavigateToProject` was a TODO
- **Status:** ✅ Fixed
- **Fix:** Added `AppDestination.ProjectDetail(projectId)` route +
  `ProjectDetailScreen` + `ProjectDetailViewModel` (minimal: project
  header + loading/not-found/content states).
- **Wired:** `ProjectsScreen.onNavigateToProject = { id -> navigator.navigate(ProjectDetail(id)) }`.

### `ProjectsRoute` used local state instead of sub-route
- **Status:** ✅ Fixed
- **Fix:** `AppNavHost` for `AppDestination.Plans` now passes
  `onNavigateToCreateProject = { navigator.navigate(ProjectEditor()) }`
  — ProjectEditor pushes as a sub-route, no local state.
- **Note:** Same change for `NotesRoute` (sub-route, not local state).

## 🟡 Refactoring — RESOLVED

### `BottomBarEntry` enum duplicates `AppDestination` role
- **Status:** ✅ Removed
- **Fix:** `AppDestination` now carries `val title` + `val icon`
  (extension property). `BottomBarEntry` and `MenuEntry` enums deleted.
  Less code, single source of truth.

### `MenuBottomSheet` hard-codes Account/Search placeholder sections
- **Status:** ✅ Fixed
- **Fix:** Declarative `MenuSections` list at the bottom of the file.
  Adding a section = one entry. Adding a destination = append to
  `DestinationKind.menuEntries`.

### `destinationFromRoute()` hard-codes subclass names
- **Status:** ⚠️ Left as-is
- **Reason:** Reflection is overkill for 16 entries. KDoc warning
  documents the fragility.

### `NavDestination` enum became overkill
- **Status:** ✅ Fixed
- **Fix:** Reduced to enum of 9 entries with a single `title` field.
  `NavGroup` and `grouped` map deleted. `AppShell` now shows a flat
  drawer list.

### `TasksScreenEntry` lived in feature, used by shell
- **Status:** ✅ Moved to its own file
- **Fix:** `TasksScreenEntry` now in `feature/tasks/TasksScreenEntry.kt`
  (separate from `TasksScreen.kt`). Still lives in feature because
  it's a feature-specific contract — `AppNavHost` imports from feature.

### `AndroidShell.content` parameter is a test-only API
- **Status:** ⚠️ Left as-is
- **Reason:** Internal can't cross modules in KMP; would require
  splitting shell into public/test-only files. Cost > benefit.

## 🟢 Cosmetic

### `Modifier.padding(padding)` boilerplate
- **Status:** ⏸️ Skipped

### `Modifier.windowInsetsPadding` on NavigationBar
- **Status:** ⏸️ Skipped (works correctly in tests on real device)

## Net result

- 6 files added
- 5 files changed
- 2 files removed (`Navigation.kt`, `NavigationLabels.kt`)
- 0 test regressions — `:shared:jvmTest` and `:shared:testAndroidHostTest`
  pass with 357 + tests including 6 `AppNavigatorTest` widget tests
  and 5 `AndroidShellFlowTest` widget tests.
- UI verified on real device (Realme RMX3938, Android 15): bottom bar,
  per-tab FAB, project detail screen, per-tab backstack, MenuBottomSheet
  all working as designed.
