---
title: "MR-1: Quick Wins — механический техдолг batch"
date: 2026-09-30
status: accepted
tags: [tech-debt, mr-1, quick-wins, detekt, ktlint, kotlin]
---

# MR-1: Quick Wins — механический техдолг batch

## Context

Проект накопил механический техдолг, обнаруженный при initial triage:
- 138 active violations `NoDirectClockSystem` (current regression в CI)
- 7 `!!` non-null assertions в production commonMain
- 12+ magic time constants без named constants
- 31 ktlint findings сверх baseline
- 4 unused imports
- 7 `System.err.println` в mcp-server
- 2 `runBlocking` без `@Suppress`
- 10-15 missing KDocs на public top-level classes
- 2 `throw` в `TaskMutations.kt` вместо `Result.failure()`
- `DIGEST.md` 2084 строки против бюджета 1500
- `check-tags.sh` misleading message
- `IdGenerator` registered as `factory` вместо `single` (stateless)

Всё это — механические фиксы, не требующие архитектурных решений.

## Decision

Делаем один MR (MR-1) со всеми механическими фиксами, чтобы:
1. Очистить active regression в CI
2. Снизить baseline-pressure для следующих MR
3. Попрактиковаться с `just lint` / `just detekt-fix` / `./check.sh` cycle

## Changes

### 1. Fix 138 NoDirectClockSystem violations
**Files:** `shared/src/commonMain/.../test/fakes/FakeRepositories.kt` (15+), slot fixtures

Все `Clock.System.now()` в test fakes заменить на инъектированный `Clock`:
```kotlin
class FakeTaskRepository(
    private val clock: Clock = Clock.System,
) : TaskRepository {
    // вместо Clock.System.now()
}
```

### 2. Create TimeConstants.kt
**File:** `shared/src/commonMain/kotlin/com/singularity/todo/core/platform/TimeConstants.kt`

```kotlin
@JvmInline
value class TimeConstants private constructor() {
    companion object {
        const val MILLIS_PER_SECOND = 1_000L
        const val MILLIS_PER_DAY = 86_400_000L
        const val SECONDS_PER_DAY = 86_400
        val BackupRefreshIntervalMs = 5_000.milliseconds
        val OneSecond = 1_000.milliseconds
        val AutoSaveDebounceMs = 1_500.milliseconds
    }
}
```

Заменить ~12 magic literals в:
- `BackupRepository.kt:59`
- `AndroidPomodoroTimer.kt:116`
- `JvmPomodoroTimer.kt:84`
- `NotePreviewScreen.kt:443-444`
- `StatisticsDomain.kt:26,33`
- `StatisticsViewModel.kt:49`
- `TaskRepositoryImpl.kt:105`
- `RoomUsageRecorder.kt:49,64`
- `NoteEditorScreen.kt:97`

### 3. Remove !! (7 sites)
**Files:** `LoginScreen.kt:94`, `StatisticsViewModel.kt:46,49`, `SavedAgendaViewModel.kt:239`, `SavedAgendaScreen.kt:92`, `SyncConfigScreen.kt:111,217`

Заменить на `?.let { } ?: default` или sealed states.

### 4. IdGenerator: single not factory
**File:** `shared/src/commonMain/.../core/di/CoreDiModule.kt:210`

```kotlin
// Before
factory<IdGenerator> { UlidIdGenerator }

// After
single<IdGenerator> { UlidIdGenerator }
```

### 5. ktlintFormat pass
31 ktlint finding: `ArgumentListWrapping × 6`, `FunctionSignature × 1`, `ImportOrdering × 2`, `Indentation × 3`, `MultiLineIfElse × 4`, `NoMultipleSpaces × 2`, `SpacingBetweenDeclarationsWithComments × 4`, `TrailingCommaOnCallSite × 4`, `Filename × 1`, `NoUnusedImports × 4`.

### 6. System.err.println → kermit Logger
**File:** `mcp-server/src/main/kotlin/.../mcp/Main.kt`

7 sites. Добавить doc-comment почему stderr (bootstrap before kermit configured).

### 7. runBlocking in FileLogWriter.kt with @Suppress
**File:** `shared/src/commonMain/.../core/log/FileLogWriter.kt:57,65`

Добавить `@Suppress("NoRunBlocking")` на оба сайта.

### 8. Missing KDocs
**Files:** `KoogPromptExecutorPort.kt`, `MenuNodesBuilder.kt`, `Debouncer.kt`, `DialogState.kt`, `EventBus.kt`, `OverlayState.kt`, `DraftState.kt`, `CurrentUser.kt`, etc.

Добавить class-level KDoc (2-3 строки, "why not what").

### 9. TaskMutations.kt throws → Result.failure
**File:** `shared/src/commonMain/.../feature/tasks/domain/usecase/TaskMutations.kt:18,25`

```kotlin
// Before
throw IllegalArgumentException(...)

// After
return Result.failure(IllegalArgumentException(...))
```

### 10. Trim DIGEST.md (2084 → ≤1500)
Удалить дублирующие per-tag секции. Объединить 57 секций в Active / Retired / Superseded.

### 11. Fix check-tags.sh misleading message
**File:** `scripts/check-tags.sh`

LEGACY_RAW / ALLOW_PATTERNS → "add to TestTags.kt or the skip-list".

## Consequences

- CI regression устранена: `NoDirectClockSystem = 0`
- `just docs-audit` → green
- `just lint` → green
- `./check.sh` → green
- 0 active violations сверх baseline

## Links

- Initial scan: `docs/decisions/2026-09-28-roadmap-status.md`
- DIGEST.md budget: `docs/doc-maintenance.md`
- `singularity-todo-quality-tools` skill
