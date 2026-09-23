---
title: "PR 1.1 resolution — Tech Debt Audit findings"
date: 2026-09-24
tags: [tech-debt, audit, pr-1]
status: accepted
---

## Context

Investigation of the 8 items listed in `2026-09-23-tech-debt-audit.md` PR #1 stack.

---

## Findings

### Migration files (items 1, 8)

**Status: No action needed.**

The merge conflicts described in `e37bbdb` were already resolved in commits
`36bdebb` (Merge refactor/cleanup-alarms) and `952db1c` (feat/ota).

| File | Verified |
|---|---|
| `Migration15To16.kt` | Exists as separate file; registered in `AppDatabase.autoMigrations` |
| `Migration17To18.kt` | Exists as separate file; registered in `AppDatabase.autoMigrations` |
| `Migrations.kt` | `Migration16To17` defined inline exactly once (line 125) |
| `Migration15To16.kt` duplicate | Not a duplicate — it IS the canonical definition; `Migrations.kt` line 127 comment "Migration17To18 is defined in Migration17To18.kt" confirms the split |
| `FakeAppDatabase.kt` | No conflict markers (`<<<<<<< HEAD`) |

**Verify:** `./gradlew :shared:compileKotlinJvm` — BUILD SUCCESSFUL. No KSP errors.

---

### FakeAppDatabase conflict markers (item 1)

**Status: No action needed.**

`FakeAppDatabase.kt` contains no conflict markers in the current HEAD (`952db1c`).

---

### SavedAgendaViewModelTest failures (item 2)

**Status: Deferred to PR 2.3.**

2 tests fail with `UncompletedCoroutinesError`:

```
sectionsReorderedSetsIsDirty[jvm]     — UncompletedCoroutinesError
createModeSaveCreatesNewView[jvm]    — UncompletedCoroutinesError
```

**Root cause:** `SavedAgendaViewModel.init { scope.launch { when(mode) { ... } } }`
creates a coroutine that outlives the test body. The VM's `scope` is a child of
`TestScope.backgroundScope` via `testScope()`. When `advanceUntilIdle()` completes,
the init coroutine may still be suspended in `initEditMode` (waiting on
`repo.observe(id).first()`) or in `emitEditingState()` (fire-and-forget `scope.launch`).

This is a **symptom of the `repeatOnLifecycle` gap** documented in item 5 of the
audit (ADR `2026-09-23-tech-debt-audit.md` §5). The proper fix is:

1. Replace `scope.launch { }` with `scope.launch { repeatOnLifecycle(STARTED) { ... } }`
   in all VM init blocks (PR 2.3).
2. OR make `onIntent`-triggered state emissions synchronous by using
   `scope.launch { emitEditingState() }` + `advanceUntilIdle()` in tests.

The `UncompletedCoroutinesError` disappears when tests run in isolation
(`--tests "SingleTest"`) but appears when the full test class runs — confirming
the child-job-leak between tests pattern.

**Action:** No test changes in PR 1.1. Documented here as rationale for PR 2.3 item 5.

---

## Resolution

- Items 1, 8: **Closed** — already resolved in `36bdebb` / `952db1c`.
- Item 2: **Deferred** — root cause is coroutine scope management, fix in PR 2.3.
- Items 3–7: **Moved to PR 2.x and PR 3.x** per the epic breakdown in the plan.
