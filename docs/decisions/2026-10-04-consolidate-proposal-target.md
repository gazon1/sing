---
title: Consolidate ProposalTarget — Remove Legacy taskId from AiProposal
status: draft
deciders:
  - Singularity Developer
created: 2026-10-04
---

## Context

`AiProposal` currently carries **both**:
- `targetKind: String` + `targetId: String` — new polymorphic target (v30→v31 migration)
- `taskId: TaskId?` — legacy column, kept for backward compatibility

This creates ambiguity:
- Code can use `proposal.taskId` even for note-bound proposals (where it's `null`)
- The legacy column is `NOT NULL DEFAULT 'TASK'` in the entity but nullable in the domain model
- During migration (v31), `taskId` is kept nullable in entity (`taskId: String? = null`) but not actually nullable in domain

## Decision

**Out-of-scope for Phase A.** Breaking change: requires:
1. All code using `proposal.taskId` → migrate to `proposal.targetId`
2. All code creating `AiProposal` → stop passing `taskId`
3. Database migration: drop `task_id` column after all data migrated to `target_id`

## Implementation Plan

### Phase 1: Stop using `taskId` in domain logic
Audit all uses of `AiProposal.taskId`:
```bash
grep -rn "\.taskId" shared/src/commonMain/kotlin/com/singularity/todo/feature/proposals/
```
Expected uses in `ProposalRepositoryImpl`, `ProposalMapper`, `TaskAiSlot`. Replace with `targetId`.

### Phase 2: Remove `taskId` from `AiProposal` data class
```kotlin
// Before
data class AiProposal(
    val id: ProposalId,
    val targetKind: String,
    val targetId: String,
    val taskId: TaskId?,  // ← remove
    ...
)

// After
data class AiProposal(
    val id: ProposalId,
    val targetKind: String,
    val targetId: String,
    // taskId removed — for task-bound proposals, targetKind = TARGET_KIND_TASK
    ...
)
```

### Phase 3: Migration
```kotlin
// v31→v32: drop task_id column
@DeleteColumn(tableName = "ai_proposal", fromColumnName = "task_id")
class Migration31To32 : AutoMigrationSpec
```

### Phase 4: Remove `@Deprecated` from `AiProposalEntity.taskId`
All reads from `taskId` replaced with `targetId` in mapper.

## Why Not Now

- MR-A3 and MR-A4 are blocked by other work
- `taskId` is currently used only for task-bound proposals where `taskId == targetId`
- The inconsistency is cosmetic, not a bug
- Fixing it requires a full database migration that needs thorough testing

## Consequences

- Simpler domain model (3 fields instead of 4)
- `targetId` is always non-null string
- `AiProposal(taskId = ...)` constructor calls → all updated to `targetId`
- `toDomain()` in mapper simplifies

## Links

- Migration v30→v31 ADR (why taskId was kept nullable)
- Phase A retrospective 2026-10-04
