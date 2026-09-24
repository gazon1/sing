---
title: "PR 1.1–1.2 Tech Debt Audit Resolution"
status: accepted
date: 2026-09-24
tags: [tech-debt, koin, di, detekt]
---

# PR 1.1–1.2 Tech Debt Audit Resolution

## Context

Tech debt audit (Epic 1) surfaced several issues ranging from actual runtime bugs (Koin DI) to cosmetic violations (detekt rules). Some findings required investigation to determine whether they were genuine bugs or pre-existing design decisions.

## Decisions

### ✅ Migration duplicates NOT duplicates

**Finding:** `Migration15To16.kt` and `Migration17To18.kt` appeared as duplicate files in Android Studio, suggesting they were moved/copied incorrectly during a previous refactor.

**Resolution:** These are separate canonical files — `Migration16To17` is defined inline in `Migrations.kt:125`. The comment at line 127 confirms this. `FakeAppDatabase.kt` had conflict markers (`<<<<<<< HEAD`) from a previous merge that were cleaned in commit `952db1c`.

### ✅ SavedAgendaViewModelTest failures are pre-existing

**Finding:** 2 tests in `SavedAgendaViewModelTest` (`sectionsReorderedSetsIsDirty`, `createModeSaveCreatesNewView`) fail with `UncompletedCoroutinesError`.

**Root cause:** `vm.onIntent` calls `scope.launch { emitEditingState() }` as fire-and-forget. `advanceUntilIdle()` completes before the launched coroutine finishes, leaving orphaned coroutines.

**Resolution:** Deferred to PR 2.3 — the proper fix is `repeatOnLifecycle` migration for all VMs with fire-and-forget coroutines in `onIntent`.

**NOT a regression:** Tests passed before migration to canonical pattern (which introduced `scope.launch` in `emitEditingState`).

### ✅ CalendarSyncViewModel is NOT a ViewModel subclass

**Finding:** `CalendarSyncViewModel` does not inherit from `ViewModel`. It is a plain class with 6 constructor parameters (5 Koin-resolved dependencies + 1 `CoroutineScope`).

**Resolution:** This is an intentional design (pre-dates canonical pattern). DI registration uses `factory { }` NOT `viewModelOf` or `viewModel { }` because:
- It's not a `ViewModel`, so Koin's `viewModel { }` DSL doesn't apply
- It IS navigation-lifecycle bound in practice (injected via `koinInject()` in `CalendarSyncSettingsScreen`)
- `factory { }` creates a new instance per injection — acceptable for this screen

**DI registration:**
```kotlin
factory<CalendarSyncViewModel> {
    CalendarSyncViewModel(get(), get(), get(), get(), get(), createBackgroundScope())
}
```

**Screen injection:**
```kotlin
val viewModel: CalendarSyncViewModel = koinInject()
```

### ✅ BackupExporter crashes at runtime (Koin reflection limit = 7)

**Finding:** `CoreDiModule.kt:174` used `singleOf(::BackupExporter)` — Koin's reflection-based instantiation for a class with **8 constructor parameters**.

**Resolution:** Replaced with explicit 8-parameter registration:
```kotlin
single { BackupExporter(get(), get(), get(), get(), get(), get(), get(), get()) }
```

### ✅ AppearanceSettingsStore registered twice

**Finding:** `AppearanceSettingsStore` was registered as a `single` in both:
- `AppearanceSettingsModule.kt:21`
- `SettingsContributorsModule.kt:29`

**Resolution:** Removed duplicate from `SettingsContributorsModule.kt:29`. The module in `core/appearance/` owns the store registration; `SettingsContributorsModule` should only register `SettingsContributor` interfaces.

### ✅ AuthRepository.migrateAnonymousTo used `TODO()`

**Finding:** `AuthRepository.kt:90` had `TODO("Implement migration...")`.

**Resolution:** Replaced with:
```kotlin
throw AppError.Unauthorized("Anonymous-to-user migration not yet implemented")
```

Also added `AppError.Unauthorized` to the sealed class (previously missing).

### ✅ IdGenerator registered as `factory` instead of `single`

**Finding:** `CoreDiModule.kt:150` had `factory<IdGenerator> { UlidIdGenerator }`.

**Resolution:** Changed to `single<IdGenerator> { UlidIdGenerator }` — ID generation is stateless, a singleton is appropriate.

### ✅ JvmFileSystem in commonMain

**Finding:** `JvmFileSystem` was defined in `commonMain` (where it uses `java.io.File` directly), but it's JVM-specific platform code.

**Resolution:** Moved `JvmFileSystem` class body to `jvmMain/kotlin/com/singularity/todo/core/files/JvmFileSystem.kt`. `commonMain/FileSystem.kt` now only contains the `FileSystem` interface, `FileStat` data class, and `MapFileSystem` (test fake).

### ✅ Detekt formatting pass (PR 1.3)

**Findings:** ~1028 violations (cosmetic). Auto-fixed via `detekt --auto-correct`:
- 298 files modified
- `baseline-shared.xml` rebuilt capturing 580 remaining violations

**Notable:** `just detekt-fix` recipe was missing `--auto-correct` flag despite ktlint `auto_correct=true` in config. Fixed in PR 1.4.

## Deferred to Epic 2

| Finding | Priority | PR |
|---------|----------|-----|
| `repeatOnLifecycle` in VM init + onIntent | High | 2.3 |
| SavedAgendaViewModelTest `UncompletedCoroutinesError` | High | 2.3 |
| `CalendarSyncViewModel` addCloseable(scope) | High | 2.1 |
| 8 VMs with hardcoded `WhileSubscribed(5000)` | High | 2.1 |
| Silent `catch (_: Exception)` blocks | High | 2.1 |
| God-VM split (SettingsViewModel, ProjectDetailViewModel) | High | 2.2 |

## Links

- Plan: `docs/decisions/DIGEST.md`
- CI setup: `.github/workflows/ci.yml` (PR 1.4)
