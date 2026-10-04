---
title: Phase A Retrospective — 2026-10-05
date: 2026-10-05
status: accepted
deciders: Singularity Developer
deciders: Singularity Developer
description: Phase A retrospective — what was done, what was fixed, remaining risks and deferred items.
issuesRelated: 
---

# Phase A Retrospective — 2026-10-05

## What was delivered

| MR | Scope | Status |
|----|-------|--------|
| MR-A1 | ProposalRepository observe contract | ✅ Complete |
| MR-A2 | ProposalTarget polymorphism + migration v30→v31 | ✅ Complete |
| MR-A3 | Gate notes-AI with proposals | ✅ Complete |
| MR-A4 | Gate destructive chat tools with proposals | ✅ Complete |
| MR-A5 | Statistics consolidation + TimeTrackingRepository port move | ✅ Complete |

## MR-A1 — ProposalRepository observe contract

**Problem**: `watchProposalsForTask(taskId, userId)` and `watchProposalsByStatus(userId, status)` violated the `GenericUserScopedRepository` contract — callers passed `userId` explicitly, which snapshots at call-time rather than reacting to profile switches.

**Fix**: `userId` removed from both method signatures. Implementation uses `flatMapLatest(currentUser.scopedUserId) { ... }` internally. A Konsist rule (`ProhibitUserIdInObserveRule`) now enforces that observe methods of repository interfaces must not accept `userId`/`scopedUserId` as parameters.

**Verification**: 148 occurrences of `scopedUserId.value` in the codebase were audited; all legitimate (write-time snapshots). No behavioral regressions.

## MR-A2 — ProposalTarget polymorphism + migration v30→v31

**Problem**: `ai_proposal.task_id` was NOT NULL in Room but 4 proposal item kinds had no task owner (`DeleteNote`, `DeleteProject`, `DeleteTag`, `ExtractActions`). The schema was physically incapable of representing note-bound proposals.

**Fix**: `task_id → target_kind TEXT NOT NULL DEFAULT 'TASK' + target_id TEXT`. `AutoMigrationSpec` + `onPostMigrate` backfills `target_id = task_id` for existing rows. Domain: `AiProposal.targetKind` / `targetId` (polymorphic) + legacy nullable `taskId`. `ProposalItemKind` remains the single dispatch point.

**Remaining risk**: Legacy `taskId` field is still written and read in some paths. After MR-A3 and MR-A4 confirm stable, it should be dropped in a follow-up migration.

## MR-A3 — Gate notes-AI with proposals

**Problem**: `NoteAiController` applied AI results directly without user confirmation.

**Fix**: `NoteEditor.runAiThroughProposal()` creates `ProposalItem` with `ProposalItemKind.SetNoteField` and persists via `ProposalRepository.save()`. The note editor shows a proposals card for pending items. Dead code (`NoteAiSlot`, `NoteProposalsCollector`) removed.

**Architecture note**: `NoteEditor` now depends on `ProposalRepository` directly. A collector/slot pattern (like `TaskAiSlot`) was considered but rejected — direct repository access is simpler for the single-surface use case. See ADR [2026-10-04-note-proposal-ui-path].

## MR-A4 — Gate destructive chat tools with proposals

**Problem**: `DeleteTaskTool`, `DeleteNoteTool`, `DeleteProjectTool`, `DeleteTagTool` called repository `softDelete`/`delete` directly. Any agent prompt could archive content without user confirmation.

**Fix**: All four tools now create a single-item `AiProposal` via `ProposalRepository.save()` and return `proposalCreated: true` in their JSON output. Deletion is deferred to `ApplyProposalItemUseCase.confirm()` which is called when the user approves the proposal. `DeleteTaskTool` uses `ProposalSource.Agent` to distinguish from the task detail surface.

**Additional constants added**: `AiProposal.TARGET_KIND_PROJECT = "PROJECT"` and `TARGET_KIND_TAG = "TAG"` in `AiProposal.Companion`.

**Test changes**: `WriteToolsTest` `DeleteTaskTool` tests rewritten to verify proposal creation and confirm deletion does NOT directly archive the task.

**Note**: The `fingerprint` field is intentionally left as empty string `""` for delete variants — `fingerprintTarget` for `DeleteTask/DeleteNote/DeleteProject/DeleteTag` already returns `""` in `ProposalItemKind.fingerprintTarget`, so the fingerprint digest is stable and correct. No change needed to `ProposalFingerprint`.

## MR-A5 — Statistics consolidation + TimeTrackingRepository port move

**Problem 1**: `StatisticsScreen` used two separate ViewModels (`StatisticsViewModel` + `InsightsViewModel`) with separate DI bindings, two separate `koinViewModel()` calls, and two separate coroutine scopes.

**Fix**: Merged into single `StatisticsViewModel` with combined `StatisticsUiState` that holds both `snapshot: StatisticsSnapshot?` (task stats) and `insights: InsightsData` (time-tracking). `SetRange(7|30|90)` intent added. `InsightsTabContent` now accepts state via function parameters rather than its own `koinViewModel()`. `InsightsViewModel` deleted.

**Problem 2**: `TimeTrackingRepository` was the only repository not in `domain/port/`.

**Fix**: Moved to `feature/timetracking/domain/port/TimeTrackingRepository.kt`. 10 files updated with new import path.

## Known false positive: detekt `FunctionSignature`/`ClassSignature` in StatisticsScreen

The StatisticsScreen file requires `@file:Suppress("FunctionSignature")` because ktlint's `FunctionSignature` rule (max-params=5, expression-body style) contradicts `ArgumentListWrapping` for functions with a parameter list that breaks across lines. The standard multi-line style `fun foo(\n    param1,\n): R` triggers `FunctionSignature: No whitespace expected between ( and first param` — this is a known ktlint/detekt interaction bug, not a real style violation. The same suppress appears in test files.

**Action item**: Consider disabling `FunctionSignature` in detekt config or adding to project-wide suppress for this specific pattern.

## Deferred items (ADR needed or future work)

1. **Drop legacy `AiProposal.taskId` column** — after delete-proposal flows are confirmed stable, remove nullable `taskId` from `AiProposal` and update `ProposalMapper`. See ADR [2026-10-04-consolidate-proposal-target].

2. **`ProposalBuilder.forTask()` integration** — `TaskAiSlot` builds `AiProposal` manually; could use `ProposalBuilder.forTask()` for consistency. Currently `TaskAiSlot` uses a custom `newItem` builder internally. Low priority — works correctly.

3. **`ApplyProposalItemUseCase` TooManyFunctions** — the class has 7 functions (limit=6). Refactor into `ProposalPlanner` (plans from item kind) + `ProposalDispatch` (executes plan). See ADR [2026-10-04-apply-proposal-refactor].

4. **`overdueCount` in `StatisticsSnapshot`** — `computeStatistics` always sets `overdueCount=0` in buckets. The aggregate `totalOverdue` is computed but not surfaced in the bar chart. Future work: show overdue as a separate series.

5. **`NoteAiController` still applies directly for some actions** — `NoteAiAction.Summarize`, `NoteAiAction.Translate`, etc. still call `runAiThroughEvent` → `ai.run()` → `controller.applyResult()` directly. Only `Improve` and `Rewrite*` use the proposal path. A follow-up should gate remaining actions.
