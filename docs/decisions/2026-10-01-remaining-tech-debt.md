---
title: "Remaining tech debt — post-v4 audit"
date: 2026-10-01
tags: [tech-debt, architecture, audit]
status: open
---

# Remaining tech debt — post-v4 audit

## Context

After completing the v4 cluster-driven refactoring plan (10 MRs), the following issues were identified that are worth documenting for future work.

---

## Critical / Blockers (fix immediately)

### 1. Detekt baseline drift between worktree and main

**Problem**: The worktree `baseline-shared.xml` had `LongMethod: ProjectDetailContent` suppressed; main's baseline did not. After merge, CI fails until suppressed.

**Fix**: Either (a) add `suppress` annotations to the function, or (b) split the component. Applied option (a) as immediate fix.

**Long-term**: When any new `LongMethod`/`CyclomaticComplexMethod` is found, suppress inline (not in baseline) — baselines should only contain pre-existing accepted debt.

---

## High Priority (fix in next sprint)

### 2. `kotlin.time.Instant` vs `kotlinx.datetime.Instant` mixing

**Problem**: 82 files in commonMain use `kotlin.time.Instant`/`kotlin.time.Clock` directly. The original R26 ADR estimated 30 files — actual count is 3× larger.

**Scope**:
- `core/attachments/Attachment.kt` — uses `kotlin.time.Instant` in domain model
- `core/observability/UsageRecorder.kt` — uses `kotlin.time.Instant`
- `core/backup/BackupExporter.kt`, `BackupImporter.kt` — use `kotlin.time.Clock`
- `feature/agenda/domain/model/SavedAgendaView.kt` — uses `kotlin.time.Instant`
- `feature/pomodoro/PomodoroDomain.kt` — uses `kotlin.time.Instant` for timers
- `feature/ai/tools/CreateTaskTool.kt` — uses `kotlin.time.Clock`
- Plus 75+ more files

**Why it matters**: Deprecation warnings flood the build log, making real warnings easy to miss. Mixing two `Instant` types is a maintenance hazard.

**Recommendation**: Mechanical find-replace migration — but needs 82 files updated. Best done as a dedicated sprint.

---

### 3. `ProjectDetailContent.kt` decomposition (146-line composable)

**Problem**: `ProjectDetailContent` at line 103 is 146 lines (limit: 80) and has `CyclomaticComplexMethod` violations. Already suppressed but not fixed.

**What to do**: Split into:
- `ProjectDetailHero` — the hero section (icon, name, description, progress bar)
- `ProjectDetailBody` — the task list + quick-add
- `ProjectDetailTopBar` — already extracted to `TaskDetailTopBar.kt`

This follows the same pattern as `TaskDetailViewScreen` → FeatureSlot decomposition.

---

### 4. `find-unwired-surfaces.py` — one finding on main

```
[screen] SyncConfigScreen has no call site
```

Fixed: `SyncConfigScreen` deleted. But the pattern is worth monitoring — run `find-unwired-surfaces.py --quiet` before every MR to catch new unwired surfaces.

---

## Medium Priority (fix in next phase)

### 5. `kotlin.time.Clock` direct usage in `AgendaDeps`

**Problem**: `AgendaDeps.kt`:
```kotlin
val clock: kotlin.time.Clock = kotlin.time.Clock.System
```

This is a **deprecated** stdlib type. All clocks should use `kotlinx.datetime.Clock` or the injected `Clock` from DI.

**Note**: `Clock` is injected into `AgendaDeps` by the caller. The default value uses `kotlin.time.Clock.System` as a fallback for when the caller doesn't provide one. This is a reasonable pattern but should use `kotlinx.datetime.Clock`.

---

### 6. Cluster 3: 31 empty `onClick = {}` lambdas

**Problem**: 31 instances of `onClick = {}` in shared UI code. These are no-op callbacks that do nothing.

**Examples**:
- `ActionButtons.kt:45-63` — 4 `AiActionButton(onClick = {})` and `DeleteActionButton(onClick = {})` stubs
- `EmptyState.kt:98,112` — placeholder buttons
- `SettingsSection.kt:263-286` — 6 settings rows with no action

**What to do**:
- If the button should do something: wire it up
- If the button is intentionally disabled: add `enabled = false` or a comment explaining why
- If the button is a placeholder: delete it

**Tool**: The `NoEmptyOnClickLambda` detekt rule was planned but not implemented (Cluster 3 was informational). Implementing the rule would catch future regressions.

---

### 7. Cluster 9: Repository package convention

9 files should be moved to `domain/port/` per the v4 plan, but deferred to MR-7 (informational):

| File | Current | Target |
|---|---|---|
| `notes/NotesRepository` | `feature/notes/` | `domain/port/` |
| `search/InternalLinkRepository` | `feature/search/` | `domain/port/` |
| `profile/ProfileRepository` | `feature/profile/` | `domain/port/` |
| `archive/ArchiveRepository` | `feature/archive/` | `domain/port/` |
| `reminders/ReminderRepository` | `feature/reminders/` | `domain/port/` |
| `checklist/ChecklistRepository` | `feature/checklist/` | `domain/port/` |
| `calendar_sync/domain/repository/` | `feature/calendar_sync/` | `domain/port/` |

**Why deferred**: These are mechanical moves that touch import statements everywhere. Best done after R26 (kotlin.time migration) to avoid merge conflicts.

---

### 8. `NoDirectClockSystemRule` exemptions list

**Problem**: The rule has a growing `isAllowedFile()` allowlist:
```kotlin
private fun isAllowedFile(file: String): Boolean =
    file.contains("core/platform/Clock.kt") ||
    file.contains("AndroidPomodoroTimer") ||
    file.contains("CalendarDiModule") ||
    file.contains("JvmPomodoroTimer") ||
    file.contains("AgendaDeps")  // Added MR-4
```

**Why bad**: Every new Clock.System usage needs a code change + rule update. The rule should use a more systematic approach (e.g., exempting `Clock.System` in default parameter values only).

**Fix**: Narrow the rule to only allow `Clock.System` in:
1. Default parameter values of DI factory functions
2. The `core/platform/Clock.kt` platform factory

---

## Low Priority / Informational

### 9. `SharedLogicDesktopTest.kt` — placeholder test

```kotlin
@Test
fun example() {
    assertEquals(3, 1 + 2)
}
```

This test does nothing. Should either be deleted or replaced with a real desktop-specific test.

---

### 10. Detekt baseline pre-existing failures

`CalendarDayFlowTest` — `calendar_day_2026_10_01` not displayed. Pre-existing failure, confirmed by running tests without changes.

---

### 11. `NoDirectDispatchersDefault` rule not implemented

Cluster 2 was to implement a detekt rule banning `Dispatchers.Default` in production. The rule was **not** created — only `AlarmReceiver` was manually fixed. A proper rule would catch future regressions.

**Note**: `Dispatchers.IO` is used in `AlarmReceiver` — this is actually correct for I/O-bound work (notifications, DB). The concern was `Dispatchers.Default` which is CPU-bound.

---

### 12. Gradle 9 `build-logic/convention/` wiring

`build-logic/convention/` has 4 reference plugin implementations but is not wired into the build. Gradle 9's included-build classpath isolation makes wiring non-trivial.

**Current state**: Plugin sources are in `build-logic/convention/src/main/kotlin/`, documented with migration steps in `build-logic/README.md`.

**When to wire**: When project reaches 10+ modules, or when Gradle natively supports convention plugin discovery.

---

## Summary table

| # | Item | Severity | Effort | Status |
|---|---|---|---|---|
| 1 | Baseline drift (ProjectDetailContent) | **Critical** | 5 min | ✅ Fixed |
| 2 | kotlin.time mixing (82 files) | High | Large | Open |
| 3 | ProjectDetailContent decomposition | High | Medium | Open |
| 4 | SyncConfigScreen unwired | High | Done | ✅ Fixed |
| 5 | AgendaDeps Clock | Medium | Small | Open |
| 6 | 31 empty onClick lambdas | Medium | Medium | Open |
| 7 | Repository package moves | Medium | Medium | Open |
| 8 | NoDirectClockSystem exemptions | Low | Small | Open |
| 9 | SharedLogicDesktopTest placeholder | Low | Small | Open |
| 10 | CalendarDayFlowTest pre-existing | Low | — | Open |
| 11 | NoDirectDispatchersDefault rule | Low | Medium | Open |
| 12 | build-logic wiring | Low | High | Open |

---

## Related

- `docs/decisions/2026-10-01-tech-debt-reconciled.md` — full cluster inventory
- `docs/decisions/2026-10-01-post-mr-9-findings.md` — MR-9 audit
- `build-logic/README.md` — convention plugin migration path
