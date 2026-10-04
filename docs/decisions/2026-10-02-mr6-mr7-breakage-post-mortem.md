---
title: MR-6 / MR-7 Post-mortem — broken preconditions and agent cleanup
date: 2026-10-02
decides:
  - Addenda to MR-0 scope
  - Rules for future agents working on multi-commit feature branches
status: accepted
author: Singularity Developer
---

# MR-6 / MR-7 Post-mortem

## Context

While implementing MR-6 (`+` button in agenda section headers) and reviewing the
partial work of a background agent attempting MR-7 (AI proposal data layer),
several significant problems were discovered and fixed.

## What Went Wrong

### 1. `Section.id` required field broke saved-agenda backwards compatibility

**Severity: production crash for any user with saved agenda views**

When `Section.id` was added as a required (non-nullable, no default) field, any
saved `AgendaDefinition` JSON in the database would throw `MissingFieldException`
on deserialization because it predated the `id` field.

**Root cause**: Adding a required field to a serializable data class with existing
persisted data has no migration path.

**Fix applied**: `id: String? = null` with a computed `effectiveId` property:

```kotlin
data class Section(
    val id: String? = null,  // nullable for backwards compatibility
    val name: String,
    // ...
) {
    val effectiveId: String get() = id ?: name.lowercase()
}
```

The `effectiveId` derives a stable id from the section name when `id` is null,
ensuring the `+` button prefill key is always deterministic regardless of whether
the agenda was saved before or after the `id` field was introduced.

**Rule**: When adding a field to a `@Serializable` data class that is persisted,
always make it nullable with a default, or provide a custom serializer that
handles missing fields.

### 2. Agent produced incomplete MR-7 work that broke compilation

**Severity: build breakage for all downstream work**

A background agent attempting MR-7 produced:
- Incomplete `ApplyProposalItemUseCase.kt` with wrong imports (`TagsRepository` vs
  `TagsRepository` from a non-existent package)
- Incorrect `FakeTimeTrackingRepository` override for `createManualEntry` (missing
  `source` parameter, signature no longer matching interface)
- References to `proposalDao()` in `AppDatabase` and `PlatformModule.jvm.kt` that
  referenced non-existent types
- Modifications to `AppDatabase` adding `ProposalEntity`/`ProposalItemEntity` before
  the types existed

**Root cause**: The agent ran without incremental verification. It committed
changes after a successful-looking compile check but the check was cached/partial.
More importantly, MR-7 has a hard prerequisite on MR-0-B (the checklist
reconstruction fix) because `ApplyProposalItemUseCase` touches entities that need
the `row_version` and reconstruction fixes to be stable.

**Fix applied**: All agent changes to the `proposals/` package were discarded.
Only the valid incremental changes (nullable `Section.id`, `effectiveId`, fixed
`FakeTimeTrackingRepository` signature, `TimeTrackingRepository.createManualEntry`
source parameter) were kept.

**Rule for agents**: Never run an agent on a branch that has uncommitted
preconditions. Always verify each commit compiles before the next agent starts.

### 3. `TimeTrackingRepository.createManualEntry` needed `source` parameter

**Severity: interface/impl mismatch, not caught by existing tests**

The interface method `createManualEntry` in `TimeTrackingRepository` was missing the
`source: TimeEntrySource` parameter that the implementation already accepted. The
agent added it, but the `FakeTimeTrackingRepository` wasn't updated, breaking the
build.

This was a pre-existing latent interface/impl mismatch — the agent's change
exposed it.

**Fix applied**: `source: TimeEntrySource = TimeEntrySource.Manual` added to the
interface method signature, and `FakeTimeTrackingRepository` updated to match.

## Decision
### 1. `Section.id` stays nullable with `effectiveId` derivation

`Section.id` remains `String? = null`. All code that needs a guaranteed non-null
id uses `section.effectiveId`. This preserves backwards compatibility with no
migration needed.

### 2. MR-7 is blocked until MR-0-B is committed

MR-7 (`ai_proposal` + `ai_proposal_item` tables) requires the entity
reconstruction fixes from MR-0-B to be stable. Specifically:
- `ChecklistRepositoryImpl.toggleItem` reconstruction fix (rows > 0 check)
- `createBatch` separating insert from update (preserving `createdAt`)
- `TagGroupRepositoryImpl` transaction fix
- `TaskRemindersSlot` idempotent reminder fix

Without MR-0-B, any proposal item that modifies a checklist item or creates a
batch could silently lose data on reconstruction.

### 3. All agents must run after verifying preconditions

Before running an agent for MR-N, confirm:
1. `shared:jvmTest` is green on the current branch
2. Any MR-M that is a prerequisite (per the plan) is committed
3. `./gradlew :shared:compileKotlinJvm --rerun-tasks` succeeds after the agent's
   changes (not just FROM-CACHE)

## Consequences

- `Section.id` is nullable — code using `section.id` directly (not `effectiveId`)
  will get a nullable value. All call sites have been audited and updated.
- MR-7 must be re-implemented after MR-0-B is merged. The correct sequence is:
  MR-0-B → MR-7 → MR-8 → MR-9 → 4 ADRs.
- `TimeTrackingRepository.createManualEntry` now has an explicit `source` parameter
  (defaulting to `Manual`), which is a minor API improvement.

## Links

- MR-0-B prerequisite: `ToggleItemTransactionFix`, `createBatch`, `TagGroupTransaction`
- MR-7: `ai_proposal` + `ai_proposal_item` tables (postponed)
- MR-8: Checklist `checkedBy`/`checkedAt` sovereignty (postponed)
