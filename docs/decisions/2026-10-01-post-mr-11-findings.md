---
title: "Post-MR-11 findings — SavedAgendaResults screen + pre-existing desktop nav regression"
date: 2026-10-01
tags: [agenda, navigation, desktop, mr-11]
status: accepted
---

# Post-MR-11 audit findings

## MR-11: SavedAgendaResults screen

**Decision**: Added `AgendaStartRoute.SavedAgendaResults` route, `SavedAgendaScreenMode.View`, `AgendaNavigator.openSavedAgendaResults`, and wired the saved-view card tap to open the results screen showing matching tasks.

### What was done

#### Product code

- **`AgendaStartRoute.SavedAgendaResults`** — new route carrying `viewId: String`, used by `SavedAgendaScreenMode.View(viewId)` to display matching tasks via `AgendaScreen(definition)`.
- **`SavedAgendaScreenMode.View`** — new sealed mode; renders results directly without the section-editor chrome.
- **`SavedAgendaViewModel.initViewMode()`** — loads the saved view from `repo.observe(viewId)`, decodes `sectionsJson` via `StableJson`, produces `SavedAgendaViewState.Results(definition, viewName)`.
- **`SavedAgendaScreen`** — added `Results` branch: `BackTopAppBar` with view name + `AgendaScreen(definition)` below it.
- **`AgendaNavigator.openSavedAgendaResults(viewId: SavedAgendaViewId)`** — pushes `SavedAgendaResults` onto the agenda back stack.
- **`AgendaRouteMapping`** — maps `SavedAgendaResults` to `SavedAgendaScreen(mode = View(...), modeHint = "Saved view")`.
- **`AgendaNavGraph.android.kt` / `AgendaNavGraph.jvm.kt`** — registered `entry<AgendaStartRoute.SavedAgendaResults>` in both `navDisplayEntryProvider` and `agendaEntryProvider`.
- **`SavedAgendaListScreen.onViewSelected`** — switched from `navigator.openSavedAgendaEditor` to `navigator.openSavedAgendaResults`.
- **`SavedAgendaCard`** — applied `testTag(TestTags.savedAgendaCard(view.name))` (was declared but never wired).
- **`AppDestination.title`** — returns `"Saved view"` for `SavedAgendaResults`.
- **`PreviewAgendaHelpers`** — added `openSavedAgendaResults` no-op override.

#### Test artifacts

- **`04-saved-view-results.yaml`** — Maestro flow: seed task → save as named view → open saved views list → tap card → verify view name in top bar → verify task appears under matching section.
- **`OpenSavedViewShowsMatchingTasksFlowTest.kt`** — Desktop Compose UI test. **Blocked** (see §Deferred).

### Fixed

- `savedAgendaCard-testTag-never-applied` — `TestTags.savedAgendaCard(name)` existed in `TestTags.kt` but was never applied to any UI element.
- `openSavedAgendaResults-missing` — no navigator method existed to open the results screen from the list.

### Future (product decisions needed)

1. **Pre-existing desktop navigation regression** — after saving in `SavedAgendaScreen` and tapping back, the entire desktop app UI goes blank (0 semantics tags). Same pattern hits `CreateTaskFlowTest`. Investigation needed in `Nav3State.goBack()` / `DesktopShellNav3` / `NavDisplay` interaction. Tracked as `desktop-nav-goBack-blank-screen` in deferred-backlog.

### Deferred

| Issue | Reason |
|---|---|
| `desktop-nav-goBack-blank-screen` | Pre-existing regression; needs dedicated investigation |
| `AgendaScreen` FAB hidden state | MR-13 (FAB prefill + visibility by sub-route) |
| Badge contract / `AgendaBadgePolicy` | MR-14 |
| Full editor fields (project/tags/recurrence/startDate) | MR-12 |

### Verification

**Primary** (Maestro — not blocked by desktop nav regression):
```bash
# Run on Android device/emulator
maestro test Maestro/flows/agenda/04-saved-view-results.yaml
```

**Desktop** (blocked by pre-existing nav regression):
```bash
./gradlew :desktopApp:test -PtestIncludes="**/OpenSavedViewShowsMatchingTasksFlowTest"
# Expected: INTERRUPTED (pre-existing desktop nav bug, not MR-11 code)
```

**Regression gate** (must all be green):
```bash
./gradlew :shared:jvmTest                        # green
./gradlew :shared:detekt                          # green
./gradlew :shared:ktlintCheck                    # green
```

### Related

- `feature/agenda/presentation/screen/SavedAgendaScreen.kt` — Results branch added
- `feature/agenda/presentation/viewmodel/SavedAgendaViewModel.kt` — initViewMode() + Results state
- `feature/nav/AgendaNavigator.kt` — openSavedAgendaResults()
- `feature/nav/AgendaStartRoute.kt` — SavedAgendaResults route
- `feature/nav/AgendaRouteMapping.kt` — route → screen mapping
- `feature/agenda/presentation/screen/SavedAgendaListScreen.kt` — onViewSelected → openSavedAgendaResults
- `Maestro/flows/agenda/04-saved-view-results.yaml` — Maestro verification flow
- `deferred-backlog.md` — `desktop-nav-goBack-blank-screen` OPEN
