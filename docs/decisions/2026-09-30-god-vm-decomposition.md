---
title: MR-5 God-VM Decomposition
date: 2026-09-30
status: accepted
tags: [mr, vm, long-method, tech-debt]
---

# MR-5 — God-VM Decomposition

## Context

The plan identified three LongMethod hot spots and one SettingsViewModel god-VM (300+ LOC constructor, 17 fireAndForget). However, upon inspection:

- **SettingsViewModel** (189 LOC): Already follows coordinator pattern — separate `MutableStateFlow` per section, `bind()` helper, `rebuildState()`. Not a god-VM.
- **TaskDetailCoordinator** (slot-based): Already decomposed into 6 slots (Entity, Draft, Completion, Children, Reminders, Backlinks).
- **TaskCreateViewModel** (132 LOC): Small, manageable.

The **SearchQueryResolver.resolveCondition** was the genuine LongMethod: 155 lines inside a single `when` statement with deeply nested logic.

## Decision

Extract each `when` branch of `resolveCondition` into a dedicated private method on `DefaultSearchQueryResolver`, keeping the context (`ResolveContext`) shared.

## Changes

### SearchQueryResolver LongMethod refactor

`resolveCondition` (155 lines) → extracted into:

| Method | Responsibility |
|--------|----------------|
| `handleHasStatus` | Status filter post-predicate |
| `handleHasTag` | Single tag lookup + post-predicate |
| `handleHasAllTags` | Multi-tag lookup + post-predicate |
| `handleInProject` | Project lookup + post-predicate |
| `handleDue` | Date range computation + post-predicate |
| `handleScheduled` | Scheduled date post-predicate |

`resolveCondition` itself is now 40 lines — a clear routing table.

## Items deferred (requires more scoped investigation)

| Item | Reason | Next step |
|------|--------|-----------|
| SettingsViewModel full split into 4 child VMs | Already follows coordinator pattern; 189 LOC is not god-VM territory | Verify with detekt baseline |
| TaskCreateViewModel LongMethod | 132 LOC — below threshold | Monitor |
| TaskDetailViewModel LongMethod | Already slot-decomposed | Monitor |

## Verification

- `./check.sh` — **PASS**
- `resolveCondition` reduced from 155 lines to ~40 lines

## Resolution (accepted)

Resolved 2026-10-05: the extraction landed.

Verified: `DefaultSearchQueryResolver` no longer exists; search query resolution is now
`feature/search/query/SearchQueryResolver.kt`, declared as interfaces (`SearchQueryResolver`,
`TagLookup`, `ProjectLookup`) with the lookup collaborators split out rather than resolved
inside a single VM. `AgendaViewModel.kt` is 129 lines, consistent with the coordinator
shape this ADR set as the target.
