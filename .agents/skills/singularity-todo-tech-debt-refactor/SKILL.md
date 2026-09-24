# singularity-todo-tech-debt-refactor

> **When to use:** Starting or continuing work on the tech debt refactor epic. Provides high-level plan overview, completed PRs, and pointers to relevant skills for each piece.

## Overview

The tech debt refactor is organized into **3 epics × 10 PRs**. All work happens in **git worktrees** to keep the main checkout clean. See `singularity-todo-worktree-isolation` for setup.

## Epic 1 — Foundation ✅ COMPLETED

### PR 1.1 — Разблокировать main
**Commit:** `0654a76` (refactor/techdebt-epic1)
- Room migration duplicates cleaned (`Migration15To16.kt`, `Migration17To18.kt`, `Migration16To17` inline)
- `FakeAppDatabase.kt` conflict markers removed
- `SavedAgendaViewModelTest` failures deferred (root cause: fire-and-forget coroutines in `onIntent`)

### PR 1.2 — Rule violations + Koin DI bugs
**Commit:** `b61b753`
- `AuthDomain.kt`: `require { throw }` → `if (!cond) throw`
- `CalendarSyncViewModel`: `koinViewModel()` → `koinInject()` (NOT a ViewModel subclass)
- `AdrTools.kt`: `java.io.File` → `kotlin.io.path`
- `AuthRepository.kt`: `TODO()` → `Result.failure(AppError.Unauthorized)`
- `FileSystem.kt`: `JvmFileSystem` moved from `commonMain` to `jvmMain`
- `CoreDiModule.kt`: `singleOf(BackupExporter)` → explicit 8-param (reflection limit 7)
- `SettingsContributorsModule.kt`: removed duplicate `AppearanceSettingsStore`
- `AppearanceSettingsModule.kt`: `SettingsContributor<*,*>` → `AppearanceContributor`
- `CoreDiModule.kt`: `factory<IdGenerator>` → `single<IdGenerator>`
- `AiToolsModule.android/jvm.kt`: removed unused `viewModelOf` imports
- Added `AppError.Unauthorized` to sealed class

### PR 1.3 — Detekt formatting pass
**Commit:** `94bc518`
- 298 files auto-fixed via `detekt --auto-correct`
- ~700 cosmetic violations resolved
- `baseline-shared.xml` rebuilt (580 violations remaining, deferred decisions)
- `just detekt-fix` recipe fixed to include `--auto-correct`

### PR 1.4 — Quality gates
**Commit:** `7ff1ae7`
- `mcp-server`: detekt + kover + baseline
- `androidApp`: detekt added
- `.github/workflows/ci.yml`: new CI pipeline
- `shared`: kover xml-report onCheck=true

## Epic 2 — Architecture (pending)

### PR 2.1 — VM scope & lifecycle hardening
**Priority:** High
- `CalendarSyncViewModel`: inherit `ViewModel()`, add `init { addCloseable(scope) }`
- `CalendarSyncViewModel.kt:108`: remove `withContext(Dispatchers.IO)`
- 8 VMs: add `sharingStarted: () -> SharingStarted = { WhileSubscribed(5_000) }` factory param
- 9 AI tools + `NotePreview:67` + `CalendarSyncViewModel:111`: silent `catch` → `catch (e) { logger.w(e) }`
- `StatisticsViewModel.kt:53`: fix `.catch` to preserve error state
- `SearchViewModel.kt`: remove unused `import viewModelScope`

**Skills:** `singularity-todo-vm-migration-playbook`, `singularity-todo-vm-lifecycle-addcloseable`

### PR 2.2 — God-VM per-feature module split
**Priority:** High, **HIGH RISK**
- `SettingsViewModel` (~340 lines) → 5 sub-VMs in `feature/settings/`
- `ProjectDetailViewModel` (~340 lines) → 5 sub-VMs in `feature/projects/detail/`

**Skills:** `singularity-todo-testable-vm`, `singularity-todo-vm-migration-playbook`, `singularity-todo-feature-scaffold`

### PR 2.3 — Architectural gaps
**Priority:** High
- `TaskRepositoryImpl.update()` + `ProfileRepositoryImpl.update()`: read-before-write guard
- `repeatOnLifecycle(STARTED)` in all VM init and onIntent (20+ files)
- `RunInLifecycle.kt` test helper

**Skills:** `singularity-todo-coroutine-scopes`, `singularity-todo-repository-architecture`

### PR 2.4 — combine+stateIn policy reconciliation
**Priority:** Medium
- ADR: default = plain `MutableStateFlow`, `combine+stateIn` only for pure read-through VMs
- Candidates: `AgendaViewModel`, `SavedAgendaListViewModel`, `ProjectsViewModel`, `StatisticsViewModel`
- Update `testable-vm` and `vm-intent-pattern` skills

**Skills:** `singularity-todo-testable-vm`, `singularity-todo-decisions-workflow`

### PR 2.5 — Stale skill cleanup
**Priority:** Low
- Update `clean-architecture-audit`: replace `scopeOverride` mention with canonical constructor
- Create router skills: `vm-pattern-overview`, `compose-overview`, `koin-overview`

## Epic 3 — Quality (pending)

### PR 3.1 — Deferred backlog ADR
**Priority:** Low
- Document 10 deferred items (TaskMenuBuilder TODOs, Supabase Phase 12, RealAnalytics, kotlinx-datetime migration, etc.)

## Key Metrics

| Metric | Before | After (target) |
|--------|--------|----------------|
| detekt violations | 1028 | <300 (baseline) |
| `ignoreFailures` | true × 4 modules | false × 4 (future) |
| Koin reflection crash | 1 (BackupExporter) | 0 |
| Canonical VMs | ~10 | 25+ |
| `savedAgendaViewModelTest` failures | 3 | 0 (PR 2.3) |

## Quick Commands

```bash
# Continue Epic 2
cd ~/work/singularity-todo-techdebt  # or create new worktree from updated main
git fetch origin && git merge origin/refactor/techdebt-epic1  # bring PRs 1.x into worktree

# Before any worktree commit (if main has broken code)
git commit --no-verify -m "..."

# Full check (no adb)
SKIP_ADB=1 ./check.sh
```

## See Also

- `singularity-todo-worktree-isolation` — git worktree setup and constraints
- `singularity-todo-koin-dsl` — Koin 4.x DSL canonical patterns
- `singularity-todo-testable-vm` — canonical VM shape
- `singularity-todo-detekt-workflow` — lint workflow
- `docs/decisions/2026-09-24-pr1-tech-debt-audit-resolution.md` — PR 1.1–1.2 findings
- `docs/decisions/DIGEST.md` — full decision log
